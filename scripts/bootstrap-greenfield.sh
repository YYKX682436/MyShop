#!/usr/bin/env bash
set -Eeuo pipefail

# First installation only. A failed or interrupted attempt is deliberately not
# resumed: its volume and containers must be inspected by an operator.
die() { printf '[myshop-bootstrap] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 1 )) || die 'usage: bootstrap-greenfield.sh <builder-release-manifest.json>'
manifest=$1
umask 077
script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
runtime=${MYSHOP_RUNTIME_ROOT:-/opt/myshop/runtime}
env_file=${MYSHOP_ENV_FILE:-/opt/myshop/.env}
state_file=${MYSHOP_DEPLOY_STATE:-$runtime/deploy-state.json}
lock_file=${HOST_DEPLOY_LOCK_FILE:-/run/myshop-deploy.lock}
mountpoint=${DATA_MOUNTPOINT:-/data}
wrapper=${MYSHOP_COMPOSE_WRAPPER:-$script_dir/myshop-compose.sh}
backup=${MYSHOP_BACKUP_SCRIPT:-$script_dir/create-predeploy-backup.sh}
schema=${MYSHOP_SCHEMA_SCRIPT:-$script_dir/check-production-schema.sh}
state_writer=${MYSHOP_STATE_WRITER:-$script_dir/write-deploy-state.sh}
storage=${HOST_STORAGE_HEALTH_SCRIPT:-/opt/host-ops/check-storage-health.sh}
registry=${MYSHOP_APPROVED_REGISTRY:-}

[[ "$registry" == ghcr.io/yykx682436 ]] || die 'approved registry must be ghcr.io/yykx682436'
if [[ "$runtime" == /opt/myshop/runtime ]]; then
  [[ "$state_file" == "$runtime/deploy-state.json" && "$lock_file" == /run/myshop-deploy.lock && "$mountpoint" == /data ]] \
    || die 'production state, lock and mount paths must not be overridden'
  [[ "$(id -u)" == 0 ]] || die 'production bootstrap must run as root'
fi
[[ -f "$manifest" && -f "$env_file" && ! -L "$env_file" && -d "$runtime" ]] || die 'manifest, environment or runtime directory is missing or unsafe'
[[ -x "$wrapper" && -x "$backup" && -x "$schema" && -x "$state_writer" && -x "$storage" ]] || die 'a required helper is missing or not executable'
python3 - "$env_file" <<'PY'
import os, stat, sys
info = os.stat(sys.argv[1])
if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) & 0o077:
    raise SystemExit('environment file must be owned by the operator and private (0600 or stricter)')
PY

exec 9>"$lock_file"
flock -n 9 || die 'another deployment or storage lifecycle operation is active'
[[ ! -e "$state_file" && ! -L "$state_file" ]] || die 'deployment state exists; use deploy-production.sh or inspect the damaged state'
[[ "$(findmnt -n -o TARGET -M "$mountpoint")" == "$mountpoint" ]] || die 'data filesystem is not mounted'
for unit in storage-lifecycle.timer docker-maintenance.timer storage-lifecycle.service docker-maintenance.service; do
  status=0
  systemctl is-active --quiet "$unit" || status=$?
  case "$status" in
    0) die "$unit must be stopped before bootstrap" ;;
    3|4) ;; # inactive or not installed
    *) die "could not determine the status of $unit" ;;
  esac
done
systemctl is-active --quiet containerd.service || die 'containerd is not active'
systemctl is-active --quiet docker.service || die 'Docker is not active'
[[ "$(docker info --format '{{.DockerRootDir}}')" == /data/docker ]] || die 'Docker root is not /data/docker'
containerd_config=$(containerd config dump)
grep -Eq "^root = ['\"]/data/containerd['\"]$" <<< "$containerd_config" || die 'containerd root is not /data/containerd'
"$storage" || die 'storage gate did not accept the data filesystem'

manifest_json=$(python3 "$script_dir/validate-release-manifest.py" "$manifest" "$registry" myshop)
read_manifest() { printf '%s' "$manifest_json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["source_sha"] if len(sys.argv)==1 else d["images"][sys.argv[1]][sys.argv[2]])' "$@"; }
candidate_sha=$(read_manifest)
api_ref=$(read_manifest api ref)
web_ref=$(read_manifest web ref)
api_digest=$(read_manifest api digest)
web_digest=$(read_manifest web digest)
public_url=$(python3 - "$env_file" <<'PY'
import sys
from urllib.parse import urlsplit

values = {}
for line in open(sys.argv[1], encoding="utf-8"):
    if line.startswith(("PUBLIC_SITE_URL=", "SITE_DOMAIN=")):
        key, value = line.rstrip("\n").split("=", 1)
        if key in values:
            raise SystemExit(f"duplicate {key}")
        values[key] = value.strip().strip("\"'")
url = values.get("PUBLIC_SITE_URL", "")
parsed = urlsplit(url)
if (parsed.scheme != "https" or not parsed.hostname or parsed.netloc != parsed.hostname
        or parsed.path or parsed.query or parsed.fragment or parsed.hostname != values.get("SITE_DOMAIN")):
    raise SystemExit("SITE_DOMAIN and PUBLIC_SITE_URL must describe the same HTTPS origin")
print(url)
PY
) || die 'invalid public origin'

run_compose() {
  MYSHOP_RUNTIME_ROOT="$runtime" MYSHOP_ENV_FILE="$env_file" MYSHOP_APPROVED_REGISTRY="$registry" \
    MYSHOP_API_IMAGE="$api_ref" MYSHOP_WEB_IMAGE="$web_ref" "$wrapper" --env-file "$env_file" "$@"
}

# Reject even stopped containers and existing project volumes. This also makes
# an interrupted bootstrap fail closed instead of resetting its database.
containers=$(docker ps -aq --filter 'label=com.docker.compose.project=myshop')
[[ -z "$containers" ]] || die 'existing MyShop containers need inspection before first installation'
volumes=$(docker volume ls -q)
project_volumes=$(docker volume ls -q --filter 'label=com.docker.compose.project=myshop')
[[ -z "$project_volumes" ]] || die 'existing MyShop volumes need inspection before first installation'
for volume in myshop_postgres_data myshop_caddy_data myshop_caddy_config; do
  if printf '%s\n' "$volumes" | grep -Fxq "$volume"; then die "existing volume needs inspection: $volume"; fi
done
networks=$(docker network ls --format '{{.Name}}')
project_networks=$(docker network ls --filter 'label=com.docker.compose.project=myshop' --format '{{.Name}}')
[[ -z "$project_networks" ]] || die 'existing MyShop networks need inspection before first installation'
if printf '%s\n' "$networks" | grep -Fxq myshop_internal; then die 'existing MyShop network needs inspection'; fi

tmp_dir=$(mktemp -d "$runtime/.bootstrap.XXXXXX")
phase=preflight
resources_created=0
committed=0
watchdog_pid=
on_exit() {
  local result=$?
  trap - EXIT
  if [[ -n "$watchdog_pid" ]]; then
    kill "$watchdog_pid" 2>/dev/null || true
    wait "$watchdog_pid" 2>/dev/null || true
  fi
  # A signal can arrive just after the atomic link but before the shell records
  # the commit. The linked inode identifies our already accepted state.
  if (( committed == 0 )) && [[ -e "$tmp_dir/initial-state.json" && "$state_file" -ef "$tmp_dir/initial-state.json" ]]; then
    committed=1
  fi
  if (( result != 0 && committed == 0 )); then
    if (( resources_created )); then
      run_compose stop caddy web api node-exporter || printf '[myshop-bootstrap] could not stop every candidate service\n' >&2
      mkdir -p "$runtime/candidates"
      python3 - "$runtime/candidates/$candidate_sha.greenfield-failed.json" "$candidate_sha" "$phase" <<'PY' || true
import json, os, sys
from datetime import datetime, timezone
path, sha, phase = sys.argv[1:]
fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, 'w', encoding='utf-8') as f:
    json.dump({'status': 'FAILED', 'source_sha': sha, 'phase': phase,
               'failed_at': datetime.now(timezone.utc).isoformat()}, f)
    f.write('\n')
PY
    fi
    printf '[myshop-bootstrap] NOT ACCEPTED phase=%s; database, backup and logs retained\n' "$phase" >&2
  elif (( result != 0 )); then
    printf '[myshop-bootstrap] state committed; post-commit restart policy needs attention\n' >&2
  fi
  if (( result == 0 )) && [[ -f "$runtime/candidates/$candidate_sha.greenfield-watchdog.log" && ! -s "$runtime/candidates/$candidate_sha.greenfield-watchdog.log" ]]; then
    rm -- "$runtime/candidates/$candidate_sha.greenfield-watchdog.log"
  fi
  rm -r -- "$tmp_dir"
  exit "$result"
}
trap on_exit EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 131' QUIT
trap 'exit 143' TERM

phase=compose-config
run_compose config --format json > "$tmp_dir/compose.json"
python3 - "$tmp_dir/compose.json" <<'PY'
import json, sys
c = json.load(open(sys.argv[1], encoding='utf-8'))
if c.get('name') != 'myshop':
    raise SystemExit('unexpected Compose project')
volumes = c['services']['db'].get('volumes', [])
if not any(v.get('type') == 'volume' and v.get('source') == 'postgres_data'
           and v.get('target') == '/var/lib/postgresql/data' for v in volumes):
    raise SystemExit('unexpected database volume')
if c.get('volumes', {}).get('postgres_data', {}).get('name') != 'myshop_postgres_data':
    raise SystemExit('unexpected database volume name')
PY

phase=pull
docker pull "$api_ref"
docker pull "$web_ref"
verify_digest() {
  local ref=$1 digest=$2
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$ref" | grep -Fx "${ref%:*}@$digest" >/dev/null
}
verify_digest "$api_ref" "$api_digest" || die 'API digest does not match the manifest'
verify_digest "$web_ref" "$web_digest" || die 'Web digest does not match the manifest'
run_compose pull db node-exporter caddy

container_id() {
  local ids
  ids=$(docker ps -aq --filter 'label=com.docker.compose.project=myshop' --filter "label=com.docker.compose.service=$1")
  [[ -n "$ids" && "$(printf '%s\n' "$ids" | wc -l | tr -d ' ')" == 1 ]] || return 1
  printf '%s\n' "$ids"
}
create_without_restart() {
  local service=$1 id
  # Compose creates dependencies without starting them; --no-recreate keeps
  # already prepared containers and their restart=no policy intact.
  run_compose create --no-build --no-recreate "$service"
  id=$(container_id "$service")
  docker update --restart=no "$id" >/dev/null
  [[ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$id")" == no ]]
}
wait_healthy() {
  local service=$1 id status attempt
  id=$(container_id "$service")
  for ((attempt=1; attempt<=90; attempt++)); do
    status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$id")
    [[ "$status" == healthy || ( "$service" == node-exporter && "$status" == running ) ]] && return 0
    [[ "$status" != unhealthy && "$status" != exited && "$status" != dead ]] || return 1
    sleep 2
  done
  return 1
}

phase=database-start
resources_created=1
# A separate guard handles loss of the operator process even on SIGKILL. It
# closes the inherited deployment lock, waits for the parent to disappear,
# then takes the lock and stops only unaccepted application/public services.
mkdir -p "$runtime/candidates"
bootstrap_pid=$$
(
  trap - EXIT HUP INT QUIT TERM
  exec 9>&-
  while kill -0 "$bootstrap_pid" 2>/dev/null; do sleep 2; done
  exec 9>"$lock_file"
  flock -w 30 9 || { printf '[myshop-bootstrap] watchdog could not obtain the deployment lock\n' >&2; exit 1; }
  if [[ ! -e "$state_file" && ! -L "$state_file" ]]; then
    printf '[myshop-bootstrap] operator process disappeared before acceptance\n'
    run_compose stop caddy web api node-exporter || exit 1
  fi
  [[ ! -d "$tmp_dir" ]] || rm -r -- "$tmp_dir"
) >>"$runtime/candidates/$candidate_sha.greenfield-watchdog.log" 2>&1 </dev/null &
watchdog_pid=$!
create_without_restart db
run_compose start db
wait_healthy db || die 'new PostgreSQL did not become healthy'
db_id=$(container_id db)
phase=database-empty-check
empty=$(docker exec -i "$db_id" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atq' <<'SQL'
SELECT current_database() = 'myshop'
 AND NOT EXISTS (SELECT 1 FROM pg_database WHERE datallowconn AND datname NOT IN ('postgres','template1','myshop'))
 AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname NOT IN ('public','information_schema') AND nspname NOT LIKE 'pg\_%' ESCAPE '\')
 AND NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public')
 AND NOT EXISTS (SELECT 1 FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE n.nspname='public')
 AND NOT EXISTS (SELECT 1 FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace WHERE n.nspname='public' AND t.typtype IN ('e','d','c','r','m'));
SQL
)
[[ "$empty" == t ]] || die 'database is not a verified empty new-store database'

phase=database-backup
backup_output=$("$backup" bootstrap "$manifest")
backup_manifest=$(printf '%s\n' "$backup_output" | sed -n 's/^MYSHOP_BACKUP_MANIFEST=//p')
[[ -n "$backup_manifest" && -f "$backup_manifest" ]] || die 'backup manifest is missing'
backup_file=$(python3 - "$backup_manifest" "$candidate_sha" <<'PY'
import hashlib, json, os, sys
from pathlib import Path
meta = json.load(open(sys.argv[1], encoding='utf-8'))
path = Path(meta['filename'])
if (meta.get('project') != 'myshop' or meta.get('source_active_sha') != 'bootstrap'
        or meta.get('candidate_sha') != sys.argv[2] or meta.get('validation', {}).get('pg_restore_list') != 'passed'
        or not path.is_file() or path.is_symlink() or path.stat().st_size <= 0
        or path.stat().st_size != meta.get('size') or hashlib.sha256(path.read_bytes()).hexdigest() != meta.get('sha256')
        or os.stat(path).st_mode & 0o077 or os.stat(sys.argv[1]).st_mode & 0o077):
    raise SystemExit('backup manifest, size, hash or permissions do not match')
print(path)
PY
)
docker exec -i "$db_id" pg_restore -l < "$backup_file" >/dev/null
printf 'GREENFIELD_BACKUP_VERIFIED manifest=%s\n' "$backup_manifest"

phase=application-start
for service in node-exporter api web; do
  create_without_restart "$service"
  run_compose start "$service"
  wait_healthy "$service" || die "$service did not become healthy"
done
phase=schema
"$schema"
phase=caddy-start
kill -0 "$watchdog_pid" || die 'bootstrap watchdog is not running'
create_without_restart caddy
run_compose start caddy
wait_healthy caddy || die 'Caddy did not become healthy'

phase=https-smoke
for path in / /api/site/config /robots.txt /sitemap.xml; do
  case "$path" in /) name=home ;; /api/site/config) name=config ;; /robots.txt) name=robots ;; *) name=sitemap ;; esac
  curl --disable --fail --silent --show-error --connect-timeout 10 --max-time 30 \
    --retry 10 --retry-delay 6 --retry-all-errors "$public_url$path" > "$tmp_dir/$name"
done
python3 - "$public_url" "$tmp_dir" <<'PY'
import json, sys, xml.etree.ElementTree as ET
from html.parser import HTMLParser
from pathlib import Path

origin, directory = sys.argv[1], Path(sys.argv[2])
json.loads((directory / 'config').read_text(encoding='utf-8'))
if f'{origin}/sitemap.xml' not in (directory / 'robots').read_text(encoding='utf-8'):
    raise SystemExit('robots sitemap URL does not match the public origin')
locations = [e.text for e in ET.parse(directory / 'sitemap').getroot().iter() if e.tag.endswith('}loc') or e.tag == 'loc']
if not any((x or '').rstrip('/') == origin for x in locations):
    raise SystemExit('sitemap does not contain the public origin')
class Canonical(HTMLParser):
    def __init__(self): super().__init__(); self.urls = []
    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == 'link' and 'canonical' in a.get('rel', '').lower().split(): self.urls.append(a.get('href', ''))
parser = Canonical(); parser.feed((directory / 'home').read_text(encoding='utf-8'))
if len(parser.urls) != 1 or parser.urls[0].rstrip('/') != origin:
    raise SystemExit('home canonical does not match the public origin')
PY

phase=identity-and-storage
for service in api web; do
  id=$(container_id "$service")
  if [[ "$service" == api ]]; then ref=$api_ref; digest=$api_digest; else ref=$web_ref; digest=$web_digest; fi
  [[ "$(docker inspect --format '{{.Config.Image}}' "$id")" == "$ref" ]]
  [[ "$(docker inspect --format '{{.Image}}' "$id")" == "$(docker image inspect --format '{{.Id}}' "$ref")" ]]
  verify_digest "$ref" "$digest" || die "$service digest changed"
done
"$storage" || die 'post-start storage gate failed'
[[ ! -e "$state_file" && ! -L "$state_file" ]] || die 'deployment state appeared during bootstrap'

phase=commit
kill -0 "$watchdog_pid" || die 'bootstrap watchdog is not running'
"$state_writer" "$tmp_dir/initial-state.json" "$manifest"
python3 - "$tmp_dir/initial-state.json" "$state_file" <<'PY'
import os, sys
source, target = sys.argv[1:]
# The candidate is on the runtime filesystem. link() atomically refuses an
# existing state rather than replacing it if another operator created one.
os.link(source, target)
try:
    directory = os.open(os.path.dirname(target), os.O_RDONLY)
    try: os.fsync(directory)
    finally: os.close(directory)
except BaseException:
    if os.path.samestat(os.stat(source), os.stat(target)):
        os.unlink(target)
    raise
PY
committed=1
# No candidate restarts on daemon/host restart before the acceptance point.
# A failure here leaves the accepted service running and is reported separately.
phase=restart-policy
for service in db node-exporter api web caddy; do
  id=$(container_id "$service")
  docker update --restart=unless-stopped "$id" >/dev/null
  [[ "$(docker inspect --format '{{.HostConfig.RestartPolicy.Name}}' "$id")" == unless-stopped ]]
done
printf 'GREENFIELD_ACCEPTED source=%s state=%s backup=%s\n' "$candidate_sha" "$state_file" "$backup_manifest"

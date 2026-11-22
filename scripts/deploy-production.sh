#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-deploy] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 1 )) || die 'usage: deploy-production.sh <builder-release-manifest.json>'
manifest=$1
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ENV_FILE=${MYSHOP_ENV_FILE:-/opt/myshop/.env}
read_env_setting() {
  local key=$1 value
  [[ -f "$ENV_FILE" ]] || return 0
  value=$(awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$ENV_FILE")
  value=${value#\"}; value=${value%\"}; value=${value#\'}; value=${value%\'}
  printf '%s' "$value"
}
PROJECT_ROOT=${MYSHOP_RUNTIME_ROOT:-$(read_env_setting MYSHOP_RUNTIME_ROOT)}
PROJECT_ROOT=${PROJECT_ROOT:-/opt/myshop/runtime}
COMPOSE_PROJECT=${MYSHOP_COMPOSE_PROJECT_NAME:-$(read_env_setting MYSHOP_COMPOSE_PROJECT_NAME)}
COMPOSE_PROJECT=${COMPOSE_PROJECT:-myshop}
COMPOSE_FILE=${MYSHOP_COMPOSE_FILE:-$(read_env_setting MYSHOP_COMPOSE_FILE)}
STATE_FILE=${MYSHOP_DEPLOY_STATE:-$PROJECT_ROOT/deploy-state.json}
COMPOSE_WRAPPER=${MYSHOP_COMPOSE_WRAPPER:-$SCRIPT_DIR/myshop-compose.sh}
STATE_WRITER=${MYSHOP_STATE_WRITER:-$SCRIPT_DIR/write-deploy-state.sh}
SCHEMA_SCRIPT=${MYSHOP_SCHEMA_SCRIPT:-$SCRIPT_DIR/check-production-schema.sh}
BACKUP_SCRIPT=${MYSHOP_BACKUP_SCRIPT:-$SCRIPT_DIR/create-predeploy-backup.sh}
RETENTION=${MYSHOP_RETENTION_SCRIPT:-$SCRIPT_DIR/cleanup-docker-retention.sh}
HEALTH_SCRIPT=${HOST_STORAGE_HEALTH_SCRIPT:-/opt/host-ops/check-storage-health.sh}
HOST_MAINTENANCE=${HOST_MAINTENANCE_SCRIPT:-/opt/host-ops/docker-maintenance.sh}
HOST_LOCK=${HOST_DEPLOY_LOCK_FILE:-/run/myshop-deploy.lock}
shared_server_setting=${MYSHOP_SHARED_SERVER:-$(read_env_setting MYSHOP_SHARED_SERVER)}
case "$shared_server_setting" in
  1|true|TRUE|yes|YES) shared_server_mode=1 ;;
  ''|0|false|FALSE|no|NO) shared_server_mode=0 ;;
  *) die 'MYSHOP_SHARED_SERVER must be true or false' ;;
esac
if (( shared_server_mode )); then
  [[ "$COMPOSE_PROJECT" == myshop-friend-* ]] || die 'shared-server deployment requires a dedicated myshop-friend-* Compose project'
  [[ "$COMPOSE_FILE" == */docker-compose.shared-server.yml ]] || die 'MYSHOP_SHARED_SERVER requires docker-compose.shared-server.yml'
  proxy_source_confirmed=${MYSHOP_BAOTA_PROXY_SOURCE_CONFIRMED:-$(read_env_setting MYSHOP_BAOTA_PROXY_SOURCE_CONFIRMED)}
  proxy_source_cidr=${MYSHOP_BAOTA_NGINX_PROXY_CIDR:-$(read_env_setting MYSHOP_BAOTA_NGINX_PROXY_CIDR)}
  [[ "$proxy_source_confirmed" == 1 ]] || die 'Baota-to-Caddy source address has not been explicitly confirmed by a server-side audit'
  python3 - "$proxy_source_cidr" <<'PY'
import ipaddress
import sys
try:
    network = ipaddress.ip_network(sys.argv[1], strict=True)
except ValueError:
    raise SystemExit("MYSHOP_BAOTA_NGINX_PROXY_CIDR must be a confirmed /32 or /128 host prefix")
if network.prefixlen != network.max_prefixlen:
    raise SystemExit("MYSHOP_BAOTA_NGINX_PROXY_CIDR must trust exactly one confirmed host address")
PY
fi
offline_images=${MYSHOP_OFFLINE_IMAGES:-$(read_env_setting MYSHOP_OFFLINE_IMAGES)}
offline_images=${offline_images:-0}
[[ "$offline_images" == 0 || "$offline_images" == 1 ]] || die 'MYSHOP_OFFLINE_IMAGES must be 0 or 1'
offline_bundle=${MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE:-$(read_env_setting MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE)}
OFFLINE_IMAGE_VERIFIER=${MYSHOP_OFFLINE_IMAGE_VERIFIER:-$SCRIPT_DIR/verify-offline-image-bundle.py}
if [[ "$offline_images" == 1 ]]; then
  (( shared_server_mode )) || die 'offline image imports are supported only for the isolated shared-server project'
  [[ -n "$offline_bundle" && -f "$offline_bundle" && ! -L "$offline_bundle" ]] || die 'offline image bundle file is missing or unsafe'
  [[ -x "$OFFLINE_IMAGE_VERIFIER" ]] || die 'offline image verifier is missing or not executable'
fi
run_host_maintenance_default=1
(( shared_server_mode )) && run_host_maintenance_default=0
RUN_HOST_MAINTENANCE=${MYSHOP_RUN_HOST_MAINTENANCE:-$(read_env_setting MYSHOP_RUN_HOST_MAINTENANCE)}
RUN_HOST_MAINTENANCE=${RUN_HOST_MAINTENANCE:-$run_host_maintenance_default}
DATA_ROOT=${MYSHOP_DATA_ROOT:-$(read_env_setting MYSHOP_DATA_ROOT)}
BACKUP_DIR=${MYSHOP_BACKUP_DIR:-$(read_env_setting MYSHOP_BACKUP_DIR)}
STORAGE_MOUNTPOINT=${MYSHOP_STORAGE_MOUNTPOINT:-$(read_env_setting MYSHOP_STORAGE_MOUNTPOINT)}
if (( shared_server_mode )); then
  DATA_ROOT=${DATA_ROOT:-/srv/myshop-friend}
  BACKUP_DIR=${BACKUP_DIR:-$DATA_ROOT/backups/database}
  STORAGE_MOUNTPOINT=${STORAGE_MOUNTPOINT:-$DATA_ROOT}
  [[ "$RUN_HOST_MAINTENANCE" == 0 ]] || die 'shared-server deployment forbids host-wide Docker maintenance'
else
  STORAGE_MOUNTPOINT=${STORAGE_MOUNTPOINT:-/data}
fi
COMPOSE_FILE=${COMPOSE_FILE:-$PROJECT_ROOT/docker-compose.prod.yml}
RUNTIME_CANDIDATES="$PROJECT_ROOT/candidates"
MAINTENANCE_DIR=${MYSHOP_MAINTENANCE_DIR:-$PROJECT_ROOT/maintenance}

[[ -f "$manifest" ]] || die "release manifest is missing: $manifest"
[[ -f "$ENV_FILE" ]] || die "production environment file is missing: $ENV_FILE"
[[ -x "$COMPOSE_WRAPPER" && -x "$STATE_WRITER" && -x "$SCHEMA_SCRIPT" && -x "$BACKUP_SCRIPT" && -x "$RETENTION" ]] || die 'deployment helper is missing or not executable'
[[ -x "$HEALTH_SCRIPT" ]] || die "storage health gate is missing: $HEALTH_SCRIPT"
command -v python3 >/dev/null || die 'python3 is required'
[[ "$COMPOSE_PROJECT" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die 'MYSHOP_COMPOSE_PROJECT_NAME is invalid'
[[ "$RUN_HOST_MAINTENANCE" == 0 || "$RUN_HOST_MAINTENANCE" == 1 ]] || die 'MYSHOP_RUN_HOST_MAINTENANCE must be 0 or 1'

approved_registry=${MYSHOP_APPROVED_REGISTRY:-}
[[ -n "$approved_registry" ]] || die 'MYSHOP_APPROVED_REGISTRY is not configured'
manifest_json=$(python3 "$SCRIPT_DIR/validate-release-manifest.py" "$manifest" "$approved_registry" myshop) || exit $?
candidate_sha=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["source_sha"])')
api_ref=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["api"]["ref"])')
api_digest=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["api"]["digest"])')
web_ref=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["web"]["ref"])')
web_digest=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["web"]["digest"])')

tmp_dir=$(mktemp -d)
state_backup="$tmp_dir/state.json"
state_existed=0
candidate_started=0
committed=0
shared_bootstrap=0
inspection_id=
cleanup_tmp() {
  [[ -z "$inspection_id" ]] || docker rm "$inspection_id" >/dev/null 2>&1 || true
  rm -rf -- "$tmp_dir"
}
trap cleanup_tmp EXIT
exec 9>"$HOST_LOCK"
flock -n 9 || die 'another deployment or storage lifecycle operation is active'
if [[ -f "$STATE_FILE" ]]; then
  cp -p -- "$STATE_FILE" "$state_backup"
  state_existed=1
fi
declare -A before_ref before_digest before_image_id baseline_container_ref candidate_ref candidate_digest candidate_image_id
candidate_ref[api]=$api_ref
candidate_ref[web]=$web_ref
candidate_digest[api]=$api_digest
candidate_digest[web]=$web_digest

read_state_active() {
  [[ -f "$STATE_FILE" ]] || return 1
  python3 - "$STATE_FILE" <<'PY'
import json, re, sys
state = json.load(open(sys.argv[1], encoding='utf-8'))
if state.get('version') != 1 or not isinstance(state.get('active'), dict):
    raise SystemExit('malformed accepted deployment state')
active = state['active']
if not re.fullmatch(r'[0-9a-f]{40}', str(active.get('source_sha', ''))):
    raise SystemExit('malformed active source SHA')
images = active.get('images')
if not isinstance(images, dict) or set(images) != {'api', 'web'}:
    raise SystemExit('malformed active image set')
for service in ('api', 'web'):
    image = images[service]
    if not isinstance(image, dict) or not image.get('ref') or not re.fullmatch(r'sha256:[0-9a-f]{64}', str(image.get('digest', ''))):
        raise SystemExit(f'malformed active image: {service}')
    image_id = image.get('image_id', '')
    if image_id and not re.fullmatch(r'sha256:[0-9a-f]{64}', str(image_id)):
        raise SystemExit(f'malformed active image_id: {service}')
    print(f'{service}\t{image["ref"]}\t{image["digest"]}\t{image_id}')
print(f'active_sha\t{active["source_sha"]}')
PY
}

active_sha=bootstrap
if (( state_existed )); then
  while IFS=$'\t' read -r key value digest image_id; do
    case "$key" in
      api|web) before_ref[$key]=$value; before_digest[$key]=$digest; before_image_id[$key]=$image_id ;;
      active_sha) active_sha=$value ;;
    esac
  done < <(read_state_active) || die 'accepted deployment state is malformed'
elif (( shared_server_mode )); then
  # The first shared-host candidate has no accepted application baseline.
  # Never infer one from running candidate containers left by an earlier try.
  shared_bootstrap=1
else
  # Preserve the legacy bootstrap path for the old production installation,
  # whose pre-state full-SHA containers are an existing accepted baseline.
  for service in api web; do
    container_id=$(docker ps --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" --filter "label=com.docker.compose.service=$service" -q | head -n 1)
    [[ -n "$container_id" ]] || die "bootstrap baseline container is missing: $service"
    baseline_container_ref[$service]=$(docker inspect --format '{{.Config.Image}}' "$container_id")
    before_ref[$service]=${baseline_container_ref[$service]}
    before_digest[$service]=$(docker inspect --format '{{.Id}}' "${before_ref[$service]}" 2>/dev/null || true)
    [[ -n "${before_digest[$service]}" ]] || before_digest[$service]=$(docker inspect --format '{{.Image}}' "$container_id")
    [[ "${before_ref[$service]}" != *:latest && "${before_ref[$service]}" != *:local ]] || die "bootstrap baseline is mutable: ${before_ref[$service]}"
    if [[ "${before_ref[$service]}" == *:* ]]; then
      tag=${before_ref[$service]##*:}
      if [[ "$tag" =~ ^[0-9a-f]{40}$ ]]; then active_sha=$tag; fi
    fi
  done
fi
if (( state_existed )); then
  for service in api web; do
    baseline_container_ref[$service]=${before_ref[$service]}
  done
fi
if (( ! shared_bootstrap )); then
  [[ -n "${before_ref[api]:-}" && -n "${before_ref[web]:-}" ]] || die 'accepted baseline image refs are incomplete'
fi

# Check the accepted version while holding the deployment lock. A delayed
# release may only move forward along the verified Git history.
if (( shared_server_mode && state_existed )); then
  release_repository=${MYSHOP_RELEASE_REPOSITORY:-/opt/myshop-friend/release.git}
  [[ -d "$release_repository" ]] || die 'release Git repository is missing'
  [[ "$(git --git-dir="$release_repository" rev-parse --is-shallow-repository)" == false ]] \
    || die 'release Git history must not be shallow'
  git --git-dir="$release_repository" cat-file -e "$candidate_sha^{commit}" \
    && git --git-dir="$release_repository" cat-file -e "$active_sha^{commit}" \
    || die 'candidate or accepted commit is missing from release Git history'
  [[ "$candidate_sha" != "$active_sha" ]] || die 'candidate is already the accepted release'
  git --git-dir="$release_repository" merge-base --is-ancestor "$active_sha" "$candidate_sha" \
    || die 'candidate does not descend from the accepted release'
fi

verify_baseline() {
  for service in api web; do
    container_id=$(docker ps --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" --filter "label=com.docker.compose.service=$service" -q | head -n 1)
    [[ -n "$container_id" ]] || return 1
    [[ "$(docker inspect --format '{{.Config.Image}}' "$container_id")" == "${baseline_container_ref[$service]}" ]] || return 1
    image_id=$(docker image inspect --format '{{.Id}}' "${before_ref[$service]}" 2>/dev/null) || return 1
    [[ -n "${before_image_id[$service]:-}" && "$image_id" == "${before_image_id[$service]}" ]] && continue
    [[ "$image_id" == "${before_digest[$service]}" ]] && continue
    docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "${before_ref[$service]}" 2>/dev/null \
      | grep -F "@${before_digest[$service]}" >/dev/null || return 1
  done
}
forward_recovery=0
recovery_failed_sha=${MYSHOP_FORWARD_RECOVERY_FAILED_SHA:-}
if [[ -n "$recovery_failed_sha" ]]; then
  (( shared_server_mode && state_existed )) || die 'forward recovery requires an accepted shared-server deployment'
  [[ "$offline_images" == 1 && "$recovery_failed_sha" =~ ^[0-9a-f]{40}$ && "$recovery_failed_sha" != "$candidate_sha" && "$candidate_sha" != "$active_sha" ]] \
    || die 'forward recovery requires a new candidate and a verified failed offline release'
  [[ -x "$SCRIPT_DIR/preflight-shared-server.sh" ]] || die 'forward recovery preflight is unavailable'
  MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_ENV_FILE="$ENV_FILE" MYSHOP_COMPOSE_FILE="$COMPOSE_FILE" \
    MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$recovery_failed_sha" \
    MYSHOP_API_IMAGE="$approved_registry/myshop-api:$recovery_failed_sha" \
    MYSHOP_WEB_IMAGE="$approved_registry/myshop-web:$recovery_failed_sha" \
    "$SCRIPT_DIR/preflight-shared-server.sh" --mode forward-recovery \
    || die 'forward recovery preflight failed'
  forward_recovery=1
  printf 'FORWARD_RECOVERY_VERIFIED failed=%s candidate=%s\n' "$recovery_failed_sha" "$candidate_sha"
elif (( ! shared_bootstrap )); then
  verify_baseline || die 'running production does not match the accepted baseline image refs/digests'
fi

storage_gate() {
  set +e
  "$HEALTH_SCRIPT"
  local status=$?
  set -e
  return "$status"
}

pre_status=0
storage_gate || pre_status=$?
case "$pre_status" in
  0|1) ;;
  2|3) die 'DATA_DISK_HIGH_WATERMARK' ;;
  4)
    [[ "$RUN_HOST_MAINTENANCE" == 1 ]] || die 'DATA_DISK_HIGH_WATERMARK; host-wide maintenance is disabled for this deployment'
    HOST_DEPLOY_LOCK_HELD=1 "$HOST_MAINTENANCE" || die 'predeploy host maintenance failed'
    storage_gate || pre_status=$?
    (( pre_status < 4 )) || die 'predeploy maintenance did not lower data disk below 70%'
    ;;
  *) die "predeploy storage gate failed: $pre_status" ;;
esac

verify_remote_digest() {
  local ref=$1 expected=$2 image_id
  image_id=$(docker image inspect --format '{{.Id}}' "$ref" 2>/dev/null) || return 1
  [[ "$image_id" == "$expected" ]] && return 0
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$ref" 2>/dev/null \
    | grep -F "@$expected" >/dev/null
}

pull_candidate() {
  if [[ "$offline_images" == 1 ]]; then
    python3 "$OFFLINE_IMAGE_VERIFIER" "$offline_bundle" --manifest "$manifest" --verify-local >/dev/null \
      || die 'preloaded image bundle does not match the release manifest or local image IDs'
    for service in api web; do
      candidate_image_id[$service]=$(python3 "$OFFLINE_IMAGE_VERIFIER" "$offline_bundle" --get-loaded-image-id "$service") \
        || die "could not read preloaded ${service} image identity"
    done
    return 0
  fi
  docker pull "$api_ref"
  docker pull "$web_ref"
  verify_remote_digest "$api_ref" "$api_digest" || die 'API RepoDigest does not match release manifest'
  verify_remote_digest "$web_ref" "$web_digest" || die 'Web RepoDigest does not match release manifest'
}

run_compose() {
  MYSHOP_OFFLINE_IMAGES="$offline_images" MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE="$offline_bundle" \
    MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" \
    MYSHOP_COMPOSE_FILE="$COMPOSE_FILE" MYSHOP_SHARED_SERVER="$shared_server_mode" \
    MYSHOP_ENV_FILE="$ENV_FILE" MYSHOP_APPROVED_REGISTRY="$approved_registry" \
    MYSHOP_API_IMAGE="${candidate_ref[api]}" MYSHOP_WEB_IMAGE="${candidate_ref[web]}" \
    "$COMPOSE_WRAPPER" --env-file "$ENV_FILE" "$@"
}

run_before_compose() {
  MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" \
    MYSHOP_COMPOSE_FILE="$COMPOSE_FILE" MYSHOP_SHARED_SERVER="$shared_server_mode" \
    MYSHOP_ENV_FILE="$ENV_FILE" MYSHOP_APPROVED_REGISTRY="$approved_registry" \
    MYSHOP_API_IMAGE="${before_ref[api]}" MYSHOP_WEB_IMAGE="${before_ref[web]}" \
    "$COMPOSE_WRAPPER" --env-file "$ENV_FILE" "$@"
}

prepare_bootstrap_rollback_refs() {
  (( state_existed || shared_server_mode )) && return 0
  # The pre-state production stack predates the immutable registry model and
  # uses local full-SHA tags. If a candidate has already started, rollback
  # must still pass the production wrapper's approved-namespace check. Make
  # a temporary local alias in that namespace without changing image content;
  # it is never written to accepted state and scoped retention removes it
  # after a successful bootstrap when no container still references it.
  for service in api web; do
    baseline=${before_ref[$service]}
    tag=${baseline##*:}
    [[ "$baseline" == *:* && "$tag" =~ ^[0-9a-f]{40}$ ]] || return 1
    alias="$approved_registry/myshop-$service:$tag"
    docker tag "$baseline" "$alias" || return 1
    alias_id=$(docker image inspect --format '{{.Id}}' "$alias" 2>/dev/null) || return 1
    [[ "$alias_id" == "${before_digest[$service]}" ]] || return 1
    before_ref[$service]=$alias
  done
}

verify_candidate_identity() {
  for service in api web; do
    container_id=$(docker ps --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" --filter "label=com.docker.compose.service=$service" -q | head -n 1)
    [[ -n "$container_id" ]] || return 1
    [[ "$(docker inspect --format '{{.Config.Image}}' "$container_id")" == "${candidate_ref[$service]}" ]] || return 1
    image_id=$(docker inspect --format '{{.Image}}' "$container_id")
    expected_id=$(docker image inspect --format '{{.Id}}' "${candidate_ref[$service]}") || return 1
    [[ "$image_id" == "$expected_id" ]] || return 1
    if [[ "$offline_images" == 1 ]]; then
      [[ "$image_id" == "${candidate_image_id[$service]}" ]] || return 1
    else
      docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "${candidate_ref[$service]}" \
        | grep -F "@${candidate_digest[$service]}" >/dev/null || return 1
    fi
  done
}

# A pre-commit rollback may only start the old API against the same Flyway
# history it was using before the candidate started. Do not infer schema
# compatibility from health checks or automatically restore a live database.
database_schema_fingerprint() {
  local db_id fingerprint
  db_id=$(docker ps --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \
    --filter 'label=com.docker.compose.service=db' -q \
    | awk 'NF {count++; value=$0} END {if (count == 1) print value; else exit 1}') || return 1
  fingerprint=$(docker exec -i "$db_id" sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atq' <<'SQL'
BEGIN;
SET LOCAL statement_timeout = '10s';
LOCK TABLE public.flyway_schema_history IN EXCLUSIVE MODE;
SELECT md5(COALESCE(string_agg(row_to_json(h)::text, ',' ORDER BY installed_rank), ''))
FROM public.flyway_schema_history h;
COMMIT;
SQL
  ) || return 1
  [[ "$fingerprint" =~ ^[0-9a-f]{32}$ ]] || return 1
  printf '%s\n' "$fingerprint"
}

verify_recovery_contracts() {
  local service_ref destination db_id actual expected
  for service_ref in "$approved_registry/myshop-api:$recovery_failed_sha" "${candidate_ref[api]}"; do
    destination="$tmp_dir/contract-${service_ref##*:}.json"
    # Create an isolated, never-started container solely to read the immutable
    # image's contract; no production volume or network is attached.
    inspection_id=$(docker create --network none --entrypoint /bin/false "$service_ref") || return 1
    docker cp "$inspection_id:/app/production-schema-contract.json" "$destination" >/dev/null || return 1
    docker rm "$inspection_id" >/dev/null || return 1
    inspection_id=
  done
  expected=$(python3 - "$tmp_dir/contract-$recovery_failed_sha.json" "$tmp_dir/contract-$candidate_sha.json" <<'PY'
import json, re, sys
old, new = (json.load(open(path, encoding='utf-8')) for path in sys.argv[1:])
if old != new or new.get('version') != 1:
    raise SystemExit('candidate schema contract differs from the failed migrated release')
count, latest = new.get('migration_count'), str(new.get('latest_migration_version', ''))
if not isinstance(count, int) or count < 1 or not re.fullmatch(r'[0-9]+', latest):
    raise SystemExit('candidate schema migration contract is invalid')
print(f'{count}|{latest}|0')
PY
  ) || return 1
  db_id=$(docker ps --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \
    --filter 'label=com.docker.compose.service=db' -q \
    | awk 'NF {count++; value=$0} END {if (count == 1) print value; else exit 1}') || return 1
  actual=$(docker exec -i "$db_id" sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atq' <<'SQL'
SELECT COUNT(*) FILTER (WHERE type = 'SQL'),
       COALESCE(MAX(version::bigint) FILTER (WHERE type = 'SQL'), 0),
       COUNT(*) FILTER (WHERE NOT success)
FROM flyway_schema_history;
SQL
  ) || return 1
  [[ "$actual" == "$expected" ]] || return 1
}

prepare_prospective_state() {
  prospective_root="$tmp_dir/prospective"
  prospective_state="$prospective_root/deploy-state.json"
  mkdir -p -- "$prospective_root"
  if (( state_existed )); then
    cp -p -- "$state_backup" "$prospective_state"
  fi
  MYSHOP_OFFLINE_IMAGES="$offline_images" MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE="$offline_bundle" \
    "$STATE_WRITER" "$prospective_state" "$manifest" >/dev/null
}

wait_health() {
  local project=$1
  for attempt in $(seq 1 30); do
    local healthy=1
    for service in db api web caddy; do
      container_id=$(docker ps --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=$service" -q | head -n 1)
      [[ -n "$container_id" ]] || { healthy=0; break; }
      status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")
      [[ "$status" == healthy || ( "$service" == db && "$status" == running ) ]] || { healthy=0; break; }
    done
    (( healthy == 1 )) && return 0
    (( attempt == 30 )) && return 1
    sleep 2
  done
}

wait_database() {
  local project=$1 container_id status
  for attempt in $(seq 1 60); do
    container_id=$(docker ps --filter "label=com.docker.compose.project=$project" --filter 'label=com.docker.compose.service=db' -q | head -n 1)
    if [[ -n "$container_id" ]]; then
      status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")
      [[ "$status" == healthy ]] && return 0
      [[ "$status" != unhealthy && "$status" != exited && "$status" != dead ]] || return 1
    fi
    (( attempt == 60 )) && return 1
    sleep 2
  done
}

smoke() {
  [[ "${DEPLOY_TEST_FAIL:-}" != smoke ]] || return 1
  public_url=$(awk -F= '$1 == "PUBLIC_SITE_URL" {sub(/^[^=]*=/, ""); print; exit}' "$ENV_FILE")
  [[ -n "$public_url" ]] || return 1
  smoke_url=${MYSHOP_SMOKE_BASE_URL:-$public_url}
  if (( shared_server_mode )); then
    [[ -n "${MYSHOP_SMOKE_HOST_HEADER:-}" ]] || return 1
    shared_port=$(python3 - "$smoke_url" "$public_url" "$MYSHOP_SMOKE_HOST_HEADER" <<'PY'
import sys
from urllib.parse import urlsplit

smoke = urlsplit(sys.argv[1])
public = urlsplit(sys.argv[2])
if (smoke.scheme != "http" or smoke.hostname != "127.0.0.1" or smoke.port is None
        or smoke.username or smoke.password or smoke.path not in ("", "/")
        or smoke.query or smoke.fragment):
    raise SystemExit("shared-host smoke URL must be plain HTTP to 127.0.0.1:<port>")
if (public.scheme != "https" or public.hostname not in {"mc.mc520.top", "myshop-candidate.mc520.top"}
        or public.netloc != public.hostname or public.path or public.query or public.fragment):
    raise SystemExit("shared-host PUBLIC_SITE_URL must be a canonical HTTPS origin")
if sys.argv[3] != public.hostname:
    raise SystemExit("shared-host smoke Host must match PUBLIC_SITE_URL")
print(smoke.port)
PY
) || return 1
    caddy_id=$(docker ps --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \
      --filter 'label=com.docker.compose.service=caddy' -q \
      | awk 'NF {count++; value=$0} END {if (count == 1) print value; else exit 1}') || return 1
    published=$(docker port "$caddy_id" 80/tcp 2>/dev/null) || return 1
    [[ "$published" == "127.0.0.1:$shared_port" ]] || return 1
  fi
  curl_args=(--fail --silent --show-error --connect-timeout 10 --max-time 30 --output /dev/null)
  if [[ -n "${MYSHOP_SMOKE_HOST_HEADER:-}" ]]; then
    curl_args+=(--header "Host: $MYSHOP_SMOKE_HOST_HEADER")
  fi
  curl "${curl_args[@]}" "$smoke_url/"
  curl "${curl_args[@]}" "$smoke_url/api/site/config"
}

rollback_state() {
  python3 - "$state_backup" "$STATE_FILE" "$state_existed" <<'PY'
import os
import shutil
import stat
import sys
import tempfile
from pathlib import Path


def fsync_dir(path: Path) -> None:
    if os.name == "nt":
        return
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def restore(source: Path, target: Path, existed: bool) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    if existed:
        fd, temporary = tempfile.mkstemp(prefix=f".{target.name}.restore.", dir=target.parent)
        try:
            with os.fdopen(fd, "wb") as handle:
                with source.open("rb") as source_handle:
                    shutil.copyfileobj(source_handle, handle)
                handle.flush()
                os.fsync(handle.fileno())
            os.chmod(temporary, stat.S_IMODE(source.stat().st_mode))
            os.replace(temporary, target)
            fsync_dir(target.parent)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)
    elif target.exists():
        target.unlink()
        fsync_dir(target.parent)


restore(Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3] == "1")
PY
}

mark_failed() {
  mkdir -p -- "$RUNTIME_CANDIDATES"
  python3 - "$RUNTIME_CANDIDATES/$candidate_sha.failed.json" "$candidate_sha" "${1:-unknown}" <<'PY'
import json, os, sys, tempfile
from datetime import datetime, timezone
from pathlib import Path
path = Path(sys.argv[1])
payload = {"version": 1, "status": "FAILED", "source_sha": sys.argv[2], "reason": sys.argv[3], "failed_at": datetime.now(timezone.utc).isoformat()}
fd, tmp = tempfile.mkstemp(prefix='.candidate-failed.', dir=path.parent)
try:
    with os.fdopen(fd, 'w', encoding='utf-8') as handle:
        json.dump(payload, handle, indent=2, sort_keys=True); handle.write('\n'); handle.flush(); os.fsync(handle.fileno())
    os.replace(tmp, path)
finally:
    if os.path.exists(tmp): os.unlink(tmp)
PY
}

write_maintenance_failure() {
  local phase=$1 reason=${2:-$phase}
  mkdir -p -- "$MAINTENANCE_DIR"
  python3 - "$MAINTENANCE_DIR/$candidate_sha.failed.json" "$candidate_sha" "$phase" "$reason" <<'PY'
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

path = Path(sys.argv[1])
payload = {
    "version": 1,
    "source_sha": sys.argv[2],
    "accepted": True,
    "maintenance_status": "FAILED",
    "phase": sys.argv[3],
    "failed_at": datetime.now(timezone.utc).isoformat(),
    "reason": sys.argv[4],
}
path.parent.mkdir(parents=True, exist_ok=True)
fd, temporary = tempfile.mkstemp(prefix=".maintenance-failure.", dir=path.parent)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)
    directory_fd = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
PY
}

restore_old() {
  local current_schema
  rollback_state || return 1
  if (( forward_recovery )); then
    # The accepted older API was never validated against this migrated schema.
    # A failed forward recovery must leave all app-facing services stopped.
    run_compose stop api web caddy || return 1
    return 2
  fi
  if (( shared_bootstrap )); then
    # There is no accepted application version to restore. Stop only this
    # candidate's app-facing services; preserve the imported DB and its backup.
    run_compose stop api web caddy || return 1
    return 0
  fi
  if (( candidate_started )); then
    # The candidate may still be migrating or serving writes. Stop it before
    # checking history; otherwise the database can change after the check.
    run_compose stop api web caddy || return 1
    current_schema=$(database_schema_fingerprint) || return 2
    [[ -n "${baseline_schema_fingerprint:-}" && "$current_schema" == "$baseline_schema_fingerprint" ]] || return 2
  fi
  if (( ! state_existed && ! candidate_started )); then
    verify_baseline || return 1
    unset DEPLOY_TEST_FAIL
    smoke || return 1
    return 0
  fi
  run_before_compose config --quiet || return 1
  run_before_compose up -d || return 1
  wait_health "$COMPOSE_PROJECT" || return 1
  unset DEPLOY_TEST_FAIL
  smoke || return 1
}

pre_commit_fail() {
  local reason=$1 restore_status=0
  if (( committed )); then
    post_commit_fail "$reason"
  fi
  mark_failed "$reason"
  if (( shared_bootstrap )); then
    if restore_old; then
      printf 'BOOTSTRAP FAILED source=%s reason=%s accepted_state=absent database_and_backups=preserved dns=unchanged\n' \
        "$candidate_sha" "$reason" >&2
      exit 1
    fi
    printf 'BOOTSTRAP FAILED source=%s reason=%s accepted_state=absent app_stop=failed database_and_backups=preserved dns=unchanged\n' \
      "$candidate_sha" "$reason" >&2
    exit 2
  fi
  restore_old || restore_status=$?
  if (( restore_status == 0 )); then
    printf 'DEPLOYMENT FAILED / ROLLED BACK source=%s reason=%s\n' "$candidate_sha" "$reason" >&2
    exit 1
  fi
  if (( restore_status == 2 )); then
    printf 'CRITICAL_SCHEMA_ROLLBACK_BLOCKED source=%s reason=%s; database and recovery point preserved, application services stopped\n' \
      "$candidate_sha" "$reason" >&2
    exit 2
  fi
  printf 'CRITICAL_ROLLBACK_FAILED source=%s reason=%s\n' "$candidate_sha" "$reason" >&2
  exit 2
}

post_commit_fail() {
  local phase=$1
  write_maintenance_failure "$phase" "$phase"
  printf 'DEPLOYMENT ACCEPTED source=%s maintenance_status=FAILED phase=%s active_state=%s\n' \
    "$candidate_sha" "$phase" "$STATE_FILE" >&2
  exit 1
}

create_predeploy_backup() {
  MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" \
    MYSHOP_BACKUP_DIR="${BACKUP_DIR:-${DATA_ROOT:-/data/myshop}/backups/database}" \
    "$BACKUP_SCRIPT" "$active_sha" "$manifest" >/dev/null
}

# A first shared-host attempt has no previously accepted friend-host version.
# Snapshot the restored database before any registry failure can end the run.
if (( shared_bootstrap )); then
  # The shared first install owns a fresh, initially empty PostgreSQL volume.
  # Start only that isolated service so the pre-migration recovery dump can be
  # made before API schema changes or any application-facing service starts.
  run_compose up -d db || pre_commit_fail database-start
  wait_database "$COMPOSE_PROJECT" || pre_commit_fail database-health
  create_predeploy_backup || pre_commit_fail backup
fi
[[ "${DEPLOY_TEST_FAIL:-}" != pull ]] || pre_commit_fail pull
pull_candidate || pre_commit_fail pull
if (( forward_recovery )); then
  verify_recovery_contracts || pre_commit_fail schema-compatibility
fi
# Later releases already have an accepted running version, so pull and digest
# verification first to avoid backup churn for an unavailable candidate.
if (( ! shared_bootstrap )); then
  create_predeploy_backup || pre_commit_fail backup
  baseline_schema_fingerprint=$(database_schema_fingerprint) || pre_commit_fail baseline-schema
fi
[[ "${DEPLOY_TEST_FAIL:-}" != health ]] || pre_commit_fail health
run_compose config --quiet || pre_commit_fail compose
prepare_bootstrap_rollback_refs || pre_commit_fail bootstrap-rollback-prepare
[[ "${DEPLOY_TEST_FAIL:-}" != start ]] || pre_commit_fail start
candidate_started=1
run_compose up -d || pre_commit_fail start
wait_health "$COMPOSE_PROJECT" || pre_commit_fail health
[[ "${DEPLOY_TEST_FAIL:-}" != health-after-start ]] || pre_commit_fail health-after-start
[[ "${DEPLOY_TEST_FAIL:-}" != schema ]] || pre_commit_fail schema
MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" "$SCHEMA_SCRIPT" || pre_commit_fail schema
smoke || pre_commit_fail smoke
post_candidate_status=0
storage_gate || post_candidate_status=$?
(( post_candidate_status < 2 )) || pre_commit_fail candidate-storage

# Running identity is part of the transactional phase. Promotion is not
# allowed to precede this check because retention must never be asked to
# protect an image that the running Compose service did not actually start.
verify_candidate_identity || pre_commit_fail final-identity

# Validate the post-commit accepted set before changing the production state.
# The prospective state is written only below tmp_dir; the production state
# and the accepted deployment state remain untouched until the real commit point.
prepare_prospective_state || pre_commit_fail retention-preflight
HOST_DEPLOY_LOCK_HELD=1 HOST_DEPLOY_LOCK_FILE="$HOST_LOCK" \
  MYSHOP_RUNTIME_ROOT="$prospective_root" MYSHOP_DEPLOY_STATE="$prospective_state" \
  MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" \
  MYSHOP_SHARED_SERVER="$shared_server_mode" \
  MYSHOP_STORAGE_MOUNTPOINT="$STORAGE_MOUNTPOINT" \
  "$RETENTION" --dry-run --state-file "$prospective_state" --prospective \
  || pre_commit_fail retention-preflight

# COMMIT POINT: write-deploy-state performs fsync plus atomic rename for the
# sole accepted deployment state. Once it returns
# successfully, application rollback is forbidden; only post-commit
# maintenance may fail.
MYSHOP_OFFLINE_IMAGES="$offline_images" MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE="$offline_bundle" \
  "$STATE_WRITER" "$STATE_FILE" "$manifest" >/dev/null || pre_commit_fail promote
committed=1
printf 'DEPLOYMENT TRANSACTION COMMITTED source=%s active_state=%s\n' "$candidate_sha" "$STATE_FILE"

# From this point onward the candidate is accepted. Maintenance failure is
# observable and retryable, but must never restore an older application state.
HOST_DEPLOY_LOCK_HELD=1 HOST_DEPLOY_LOCK_FILE="$HOST_LOCK" MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" \
  MYSHOP_DEPLOY_STATE="$STATE_FILE" MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" \
  MYSHOP_SHARED_SERVER="$shared_server_mode" \
  MYSHOP_STORAGE_MOUNTPOINT="$STORAGE_MOUNTPOINT" \
  MYSHOP_RETENTION_SCRIPT="$RETENTION" "$RETENTION" || post_commit_fail retention
post_status=0
storage_gate || post_status=$?
(( post_status < 2 )) || post_commit_fail post-storage
printf 'DEPLOYMENT ACCEPTED source=%s active_state=%s post_storage_status=%s\n' "$candidate_sha" "$STATE_FILE" "$post_status"

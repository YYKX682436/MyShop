#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-deploy] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 1 )) || die 'usage: deploy-production.sh <builder-release-manifest.json>'
manifest=$1
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
PROJECT_ROOT=${MYSHOP_RUNTIME_ROOT:-/opt/myshop/runtime}
STATE_FILE=${MYSHOP_DEPLOY_STATE:-$PROJECT_ROOT/deploy-state.json}
ENV_FILE=${MYSHOP_ENV_FILE:-/opt/myshop/.env}
COMPOSE_WRAPPER=${MYSHOP_COMPOSE_WRAPPER:-$SCRIPT_DIR/myshop-compose.sh}
STATE_WRITER=${MYSHOP_STATE_WRITER:-$SCRIPT_DIR/write-deploy-state.sh}
SCHEMA_SCRIPT=${MYSHOP_SCHEMA_SCRIPT:-$SCRIPT_DIR/check-production-schema.sh}
BACKUP_SCRIPT=${MYSHOP_BACKUP_SCRIPT:-$SCRIPT_DIR/create-predeploy-backup.sh}
RETENTION=${MYSHOP_RETENTION_SCRIPT:-$SCRIPT_DIR/cleanup-docker-retention.sh}
HEALTH_SCRIPT=${HOST_STORAGE_HEALTH_SCRIPT:-/opt/host-ops/check-storage-health.sh}
HOST_MAINTENANCE=${HOST_MAINTENANCE_SCRIPT:-/opt/host-ops/docker-maintenance.sh}
HOST_LOCK=${HOST_DEPLOY_LOCK_FILE:-/run/myshop-xianyu-deploy.lock}
RUNTIME_CANDIDATES="$PROJECT_ROOT/candidates"
RUNTIME_CONFIG_STATE=${MYSHOP_RUNTIME_CONFIG_STATE:-$PROJECT_ROOT/runtime-config-state.json}
RUNTIME_CONFIG_TOOL=${MYSHOP_RUNTIME_CONFIG_TOOL:-$SCRIPT_DIR/runtime_config_state.py}
MAINTENANCE_DIR=${MYSHOP_MAINTENANCE_DIR:-$PROJECT_ROOT/maintenance}

[[ -f "$manifest" ]] || die "release manifest is missing: $manifest"
[[ -f "$ENV_FILE" ]] || die "production environment file is missing: $ENV_FILE"
[[ -x "$COMPOSE_WRAPPER" && -x "$STATE_WRITER" && -x "$SCHEMA_SCRIPT" && -x "$BACKUP_SCRIPT" && -x "$RETENTION" ]] || die 'deployment helper is missing or not executable'
[[ -x "$HEALTH_SCRIPT" ]] || die "storage health gate is missing: $HEALTH_SCRIPT"
command -v python3 >/dev/null || die 'python3 is required'

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
cleanup_tmp() { rm -rf -- "$tmp_dir"; }
trap cleanup_tmp EXIT
exec 9>"$HOST_LOCK"
flock -n 9 || die 'another deployment or storage lifecycle operation is active'
if [[ -f "$STATE_FILE" ]]; then
  cp -p -- "$STATE_FILE" "$state_backup"
  state_existed=1
fi
declare -A before_ref before_digest baseline_container_ref candidate_ref candidate_digest
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
    print(f'{service}\t{image["ref"]}\t{image["digest"]}')
print(f'active_sha\t{active["source_sha"]}')
PY
}

active_sha=bootstrap
if (( state_existed )); then
  while IFS=$'\t' read -r key value digest; do
    case "$key" in
      api|web) before_ref[$key]=$value; before_digest[$key]=$digest ;;
      active_sha) active_sha=$value ;;
    esac
  done < <(read_state_active) || die 'accepted deployment state is malformed'
else
  for service in api web; do
    container_id=$(docker ps --filter 'label=com.docker.compose.project=myshop' --filter "label=com.docker.compose.service=$service" -q | head -n 1)
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
[[ -n "${before_ref[api]:-}" && -n "${before_ref[web]:-}" ]] || die 'accepted baseline image refs are incomplete'

verify_baseline() {
  for service in api web; do
    container_id=$(docker ps --filter 'label=com.docker.compose.project=myshop' --filter "label=com.docker.compose.service=$service" -q | head -n 1)
    [[ -n "$container_id" ]] || return 1
    [[ "$(docker inspect --format '{{.Config.Image}}' "$container_id")" == "${baseline_container_ref[$service]}" ]] || return 1
    image_id=$(docker image inspect --format '{{.Id}}' "${before_ref[$service]}" 2>/dev/null) || return 1
    [[ "$image_id" == "${before_digest[$service]}" ]] && continue
    docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "${before_ref[$service]}" 2>/dev/null \
      | grep -F "@${before_digest[$service]}" >/dev/null || return 1
  done
}
verify_baseline || die 'running production does not match the accepted baseline image refs/digests'
if [[ -f "$RUNTIME_CONFIG_STATE" ]]; then
  runtime_config_container=$(docker ps --filter 'label=com.docker.compose.project=myshop' \
    --filter 'label=com.docker.compose.service=api' -q | awk 'NF {count++; value=$0} END {if (count == 1) print value; else exit 1}') \
    || die 'RUNTIME_CONFIG_DRIFT'
  python3 "$RUNTIME_CONFIG_TOOL" verify-baseline --runtime-state "$RUNTIME_CONFIG_STATE" \
    --env-file "$ENV_FILE" --container-id "$runtime_config_container" || die 'RUNTIME_CONFIG_DRIFT'
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
  docker pull "$api_ref"
  docker pull "$web_ref"
  verify_remote_digest "$api_ref" "$api_digest" || die 'API RepoDigest does not match release manifest'
  verify_remote_digest "$web_ref" "$web_digest" || die 'Web RepoDigest does not match release manifest'
}

run_compose() {
  MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_ENV_FILE="$ENV_FILE" MYSHOP_APPROVED_REGISTRY="$approved_registry" \
    MYSHOP_API_IMAGE="${candidate_ref[api]}" MYSHOP_WEB_IMAGE="${candidate_ref[web]}" \
    "$COMPOSE_WRAPPER" --env-file "$ENV_FILE" "$@"
}

run_before_compose() {
  MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_ENV_FILE="$ENV_FILE" MYSHOP_APPROVED_REGISTRY="$approved_registry" \
    MYSHOP_API_IMAGE="${before_ref[api]}" MYSHOP_WEB_IMAGE="${before_ref[web]}" \
    "$COMPOSE_WRAPPER" --env-file "$ENV_FILE" "$@"
}

prepare_bootstrap_rollback_refs() {
  (( state_existed )) && return 0
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
    container_id=$(docker ps --filter 'label=com.docker.compose.project=myshop' --filter "label=com.docker.compose.service=$service" -q | head -n 1)
    [[ -n "$container_id" ]] || return 1
    [[ "$(docker inspect --format '{{.Config.Image}}' "$container_id")" == "${candidate_ref[$service]}" ]] || return 1
    image_id=$(docker inspect --format '{{.Image}}' "$container_id")
    expected_id=$(docker image inspect --format '{{.Id}}' "${candidate_ref[$service]}") || return 1
    [[ "$image_id" == "$expected_id" ]] || return 1
    docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "${candidate_ref[$service]}" \
      | grep -F "@${candidate_digest[$service]}" >/dev/null || return 1
  done
}

prepare_prospective_state() {
  prospective_root="$tmp_dir/prospective"
  prospective_state="$prospective_root/deploy-state.json"
  mkdir -p -- "$prospective_root"
  if (( state_existed )); then
    cp -p -- "$state_backup" "$prospective_state"
  fi
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

smoke() {
  [[ "${DEPLOY_TEST_FAIL:-}" != smoke ]] || return 1
  public_url=$(awk -F= '$1 == "PUBLIC_SITE_URL" {sub(/^[^=]*=/, ""); print; exit}' "$ENV_FILE")
  [[ -n "$public_url" ]] || return 1
  curl --fail --silent --show-error --output /dev/null "$public_url/"
  curl --fail --silent --show-error --output /dev/null "$public_url/api/site/config"
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
  rollback_state
  if (( ! state_existed && ! candidate_started )); then
    verify_baseline || return 1
    unset DEPLOY_TEST_FAIL
    smoke || return 1
    return 0
  fi
  run_before_compose config --quiet || return 1
  run_before_compose up -d || return 1
  wait_health myshop || return 1
  unset DEPLOY_TEST_FAIL
  smoke || return 1
}

pre_commit_fail() {
  local reason=$1
  if (( committed )); then
    post_commit_fail "$reason"
  fi
  mark_failed "$reason"
  if restore_old; then
    printf 'DEPLOYMENT FAILED / ROLLED BACK source=%s reason=%s\n' "$candidate_sha" "$reason" >&2
    exit 1
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

[[ "${DEPLOY_TEST_FAIL:-}" != pull ]] || pre_commit_fail pull
pull_candidate || pre_commit_fail pull
# A fresh verified backup is part of this transaction, not an operator-supplied
# path from an earlier deployment.  Pull and digest verification happen first,
# so a registry failure cannot create backup churn without a candidate that can
# actually be started.
"$BACKUP_SCRIPT" "$active_sha" "$manifest" >/dev/null || pre_commit_fail backup
[[ "${DEPLOY_TEST_FAIL:-}" != health ]] || pre_commit_fail health
run_compose config --quiet || pre_commit_fail compose
run_compose pull || pre_commit_fail pull
prepare_bootstrap_rollback_refs || pre_commit_fail bootstrap-rollback-prepare
[[ "${DEPLOY_TEST_FAIL:-}" != start ]] || pre_commit_fail start
candidate_started=1
run_compose up -d || pre_commit_fail start
wait_health myshop || pre_commit_fail health
[[ "${DEPLOY_TEST_FAIL:-}" != health-after-start ]] || pre_commit_fail health-after-start
[[ "${DEPLOY_TEST_FAIL:-}" != schema ]] || pre_commit_fail schema
"$SCHEMA_SCRIPT" || pre_commit_fail schema
smoke || pre_commit_fail smoke
post_candidate_status=0
storage_gate || post_candidate_status=$?
(( post_candidate_status < 4 )) || pre_commit_fail candidate-storage

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
  "$RETENTION" --dry-run --state-file "$prospective_state" --prospective \
  || pre_commit_fail retention-preflight

# COMMIT POINT: write-deploy-state performs fsync plus atomic rename for the
# sole accepted deployment state. Once it returns
# successfully, application rollback is forbidden; only post-commit
# maintenance may fail.
"$STATE_WRITER" "$STATE_FILE" "$manifest" >/dev/null || pre_commit_fail promote
committed=1
printf 'DEPLOYMENT TRANSACTION COMMITTED source=%s active_state=%s\n' "$candidate_sha" "$STATE_FILE"

# From this point onward the candidate is accepted. Maintenance failure is
# observable and retryable, but must never restore an older application state.
HOST_DEPLOY_LOCK_HELD=1 HOST_DEPLOY_LOCK_FILE="$HOST_LOCK" MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_DEPLOY_STATE="$STATE_FILE" MYSHOP_RETENTION_SCRIPT="$RETENTION" "$RETENTION" || post_commit_fail retention
post_status=0
storage_gate || post_status=$?
(( post_status < 4 )) || post_commit_fail post-storage
printf 'DEPLOYMENT ACCEPTED source=%s active_state=%s post_storage_status=%s\n' "$candidate_sha" "$STATE_FILE" "$post_status"

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
recovery_bundle=${MYSHOP_FORWARD_RECOVERY_BUNDLE_FILE:-$(read_env_setting MYSHOP_FORWARD_RECOVERY_BUNDLE_FILE)}
recovery_manifest=${MYSHOP_FORWARD_RECOVERY_MANIFEST_FILE:-$(read_env_setting MYSHOP_FORWARD_RECOVERY_MANIFEST_FILE)}
OFFLINE_IMAGE_VERIFIER=${MYSHOP_OFFLINE_IMAGE_VERIFIER:-$SCRIPT_DIR/verify-offline-image-bundle.py}
if [[ "$offline_images" == 1 ]]; then
  (( shared_server_mode )) || die 'offline image imports are supported only for the isolated shared-server project'
  [[ -n "$offline_bundle" && -f "$offline_bundle" && ! -L "$offline_bundle" ]] || die 'offline image bundle file is missing or unsafe'
  [[ -f "$OFFLINE_IMAGE_VERIFIER" && ! -L "$OFFLINE_IMAGE_VERIFIER" ]] \
    || die 'offline image verifier is missing or unsafe'
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
command -v timeout >/dev/null || die 'timeout is required for bounded registry pulls'
[[ "$COMPOSE_PROJECT" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die 'MYSHOP_COMPOSE_PROJECT_NAME is invalid'
[[ "$RUN_HOST_MAINTENANCE" == 0 || "$RUN_HOST_MAINTENANCE" == 1 ]] || die 'MYSHOP_RUN_HOST_MAINTENANCE must be 0 or 1'

approved_registry=${MYSHOP_APPROVED_REGISTRY:-}
[[ -n "$approved_registry" ]] || die 'MYSHOP_APPROVED_REGISTRY is not configured'
manifest_json=$(python3 "$SCRIPT_DIR/validate-release-manifest.py" "$manifest" "$approved_registry" myshop) || exit $?
candidate_sha=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["source_sha"])')
api_ref=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["api"]["ref"])')
api_digest=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["api"]["digest"])')
api_source_sha=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["api"]["source_sha"])')
web_ref=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["web"]["ref"])')
web_digest=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["web"]["digest"])')
web_source_sha=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["images"]["web"]["source_sha"])')
database_migration=$(printf '%s' "$manifest_json" | python3 -c 'import json,sys; print("1" if json.load(sys.stdin)["database_migration"] else "0")')

tmp_dir=$(mktemp -d)
state_backup="$tmp_dir/state.json"
state_existed=0
candidate_started=0
api_candidate_started=0
web_candidate_started=0
api_stop_attempted=0
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
declare -A before_ref before_digest before_image_id baseline_container_ref candidate_ref candidate_runtime_ref candidate_tag_ref candidate_source_sha candidate_digest candidate_image_id
candidate_ref[api]=$api_ref
candidate_ref[web]=$web_ref
candidate_runtime_ref[api]=$api_ref
candidate_runtime_ref[web]=$web_ref
candidate_tag_ref[api]="$approved_registry/myshop-api:$api_source_sha"
candidate_tag_ref[web]="$approved_registry/myshop-web:$web_source_sha"
candidate_source_sha[api]=$api_source_sha
candidate_source_sha[web]=$web_source_sha
candidate_digest[api]=$api_digest
candidate_digest[web]=$web_digest
api_changed=0
web_changed=0
api_digest_changed=0
web_digest_changed=0

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
    image_sha = image.get('source_sha', active['source_sha'])
    repo = f'ghcr.io/yykx682436/myshop-{service}'
    canonical_ref = f'{repo}@{image["digest"]}'
    tag_ref = f'{repo}:{image_sha}'
    if (not re.fullmatch(r'[0-9a-f]{40}', str(image_sha))
            or image['ref'] not in {canonical_ref, tag_ref}
            or image.get('tag_ref', tag_ref) != tag_ref):
        raise SystemExit(f'malformed active image provenance: {service}')
    runtime_ref = image.get('runtime_ref', image['ref'])
    if runtime_ref not in {image['ref'], tag_ref}:
        raise SystemExit(f'malformed active runtime ref: {service}')
    print(f'{service}|{image["ref"]}|{image["digest"]}|{image_id}|{runtime_ref}')
print(f'active_sha|{active["source_sha"]}')
PY
}

active_sha=bootstrap
if (( state_existed )); then
  while IFS='|' read -r key value digest image_id runtime_ref; do
    case "$key" in
      api|web)
        before_ref[$key]=$runtime_ref
        before_digest[$key]=$digest
        before_image_id[$key]=$image_id
        ;;
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
  for service in api web; do
    image_sha=${candidate_source_sha[$service]}
    git --git-dir="$release_repository" cat-file -e "$image_sha^{commit}" \
      && git --git-dir="$release_repository" merge-base --is-ancestor "$image_sha" "$candidate_sha" \
      || die "${service} image provenance is missing from or not ancestral to the release history"
  done

  # CI compares migrations with its latest successful release, which may be
  # newer than production. Recheck the accepted-to-candidate range so an
  # unaccepted CI migration cannot be hidden by a later manifest's false bit.
  if (( database_migration == 0 )); then
    migration_diff_status=0
    git --git-dir="$release_repository" diff --quiet --no-renames \
      "$active_sha" "$candidate_sha" -- apps/api/src/main/resources/db/ || migration_diff_status=$?
    case "$migration_diff_status" in
      0) ;;
      1)
        database_migration=1
        printf 'DATABASE_MIGRATION_INFERRED_FROM_ACCEPTED_RANGE accepted=%s candidate=%s\n' \
          "$active_sha" "$candidate_sha"
        ;;
      *) die 'could not compare production-accepted and candidate migration files' ;;
    esac
  fi
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
  [[ -n "$recovery_bundle" && -f "$recovery_bundle" && ! -L "$recovery_bundle" ]] \
    || die 'forward recovery requires the failed release offline bundle separately from the candidate bundle'
  [[ -n "$recovery_manifest" && -f "$recovery_manifest" && ! -L "$recovery_manifest" ]] \
    || die 'forward recovery requires the failed release manifest separately from the candidate manifest'
  python3 "$OFFLINE_IMAGE_VERIFIER" "$recovery_bundle" --manifest "$recovery_manifest" \
    --verify-local --expected-source-sha "$recovery_failed_sha" >/dev/null \
    || die 'failed release recovery bundle is invalid or not loaded locally'
  [[ -x "$SCRIPT_DIR/preflight-shared-server.sh" ]] || die 'forward recovery preflight is unavailable'
  MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_ENV_FILE="$ENV_FILE" MYSHOP_COMPOSE_FILE="$COMPOSE_FILE" \
    MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$recovery_failed_sha" \
    MYSHOP_RELEASE_MANIFEST_FILE="$manifest" \
    MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE="$offline_bundle" \
    MYSHOP_FORWARD_RECOVERY_MANIFEST_FILE="$recovery_manifest" \
    MYSHOP_FORWARD_RECOVERY_BUNDLE_FILE="$recovery_bundle" \
    MYSHOP_API_IMAGE="$(python3 "$OFFLINE_IMAGE_VERIFIER" "$recovery_bundle" --get-runtime-ref api)" \
    MYSHOP_WEB_IMAGE="$(python3 "$OFFLINE_IMAGE_VERIFIER" "$recovery_bundle" --get-runtime-ref web)" \
    "$SCRIPT_DIR/preflight-shared-server.sh" --mode forward-recovery \
    || die 'forward recovery preflight failed'
  forward_recovery=1
  printf 'FORWARD_RECOVERY_VERIFIED failed=%s candidate=%s\n' "$recovery_failed_sha" "$candidate_sha"
elif (( ! shared_bootstrap )); then
  verify_baseline || die 'running production does not match the accepted baseline image refs/digests'
fi

if (( shared_bootstrap )); then
  api_changed=1
  web_changed=1
  api_digest_changed=1
  web_digest_changed=1
else
  if [[ "${candidate_digest[api]}" != "${before_digest[api]}" ]]; then
    api_changed=1
    api_digest_changed=1
  fi
  if [[ "${candidate_digest[web]}" != "${before_digest[web]}" ]]; then
    web_changed=1
    web_digest_changed=1
  fi
  if (( api_changed == 0 )); then
    candidate_runtime_ref[api]=${before_ref[api]}
    candidate_image_id[api]=${before_image_id[api]:-}
  fi
  if (( web_changed == 0 )); then
    candidate_runtime_ref[web]=${before_ref[web]}
    candidate_image_id[web]=${before_image_id[web]:-}
  fi
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
  local service bundle_image_id bundle_runtime_ref attempt started elapsed status pull_log
  if [[ "$offline_images" == 1 ]]; then
    python3 "$OFFLINE_IMAGE_VERIFIER" "$offline_bundle" --manifest "$manifest" --verify-local >/dev/null \
      || die 'preloaded image bundle does not match the release manifest or local image IDs'
    for service in api web; do
      bundle_image_id=$(python3 "$OFFLINE_IMAGE_VERIFIER" "$offline_bundle" --get-loaded-image-id "$service") \
        || die "could not read preloaded ${service} image identity"
      bundle_runtime_ref=$(python3 "$OFFLINE_IMAGE_VERIFIER" "$offline_bundle" --get-runtime-ref "$service") \
        || die "could not read preloaded ${service} runtime ref"
      if [[ "$service" == api && "$api_changed" == 1 || "$service" == web && "$web_changed" == 1 ]]; then
        candidate_image_id[$service]=$bundle_image_id
        candidate_runtime_ref[$service]=$bundle_runtime_ref
      else
        candidate_image_id[$service]=$bundle_image_id
        candidate_runtime_ref[$service]=${before_ref[$service]}
      fi
    done
    return 0
  fi
  for service in api web; do
    [[ "$service" == api && "$api_digest_changed" == 1 || "$service" == web && "$web_digest_changed" == 1 ]] || continue
    pull_log="$tmp_dir/pull-${service}.log"
    for attempt in 1 2 3; do
      started=$SECONDS
      printf '[myshop-deploy] pulling %s attempt %s/3 ref=%s\n' "$service" "$attempt" "${candidate_ref[$service]}"
      if timeout --signal=TERM --kill-after=5s 600s docker pull "${candidate_ref[$service]}" >"$pull_log" 2>&1; then
        elapsed=$((SECONDS - started))
        cat "$pull_log"
        printf '[myshop-deploy] %s pull succeeded attempt=%s/3 elapsed=%ss\n' "$service" "$attempt" "$elapsed"
        break
      else
        status=$?
      fi
      elapsed=$((SECONDS - started))
      printf '[myshop-deploy] %s pull failed attempt=%s/3 elapsed=%ss exit=%s\n' "$service" "$attempt" "$elapsed" "$status" >&2
      if (( status == 124 )); then
        printf '[myshop-deploy] %s pull exceeded the 600s attempt limit\n' "$service" >&2
      fi
      tail -n 5 "$pull_log" >&2
      if (( attempt == 3 )); then
        printf '[myshop-deploy] %s pull exhausted three attempts; production remains on the accepted release\n' "$service" >&2
        return 1
      fi
      sleep $((attempt * 3))
    done
    verify_remote_digest "${candidate_ref[$service]}" "${candidate_digest[$service]}" \
      || die "${service} RepoDigest does not match release manifest"
    candidate_image_id[$service]=$(docker image inspect --format '{{.Id}}' "${candidate_ref[$service]}") \
      || die "could not inspect pulled ${service} image"
    docker tag "${candidate_ref[$service]}" "${candidate_tag_ref[$service]}" \
      || die "could not create the local ${service} retention tag"
    [[ "$(docker image inspect --format '{{.Id}}' "${candidate_tag_ref[$service]}")" == "${candidate_image_id[$service]}" ]] \
      || die "${service} retention tag changed image identity"
  done
}

run_compose() {
  MYSHOP_OFFLINE_IMAGES="$offline_images" MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE="$offline_bundle" \
    MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" \
    MYSHOP_COMPOSE_FILE="$COMPOSE_FILE" MYSHOP_SHARED_SERVER="$shared_server_mode" \
    MYSHOP_ENV_FILE="$ENV_FILE" MYSHOP_APPROVED_REGISTRY="$approved_registry" \
    MYSHOP_API_IMAGE="${candidate_runtime_ref[api]}" MYSHOP_WEB_IMAGE="${candidate_runtime_ref[web]}" \
    "$COMPOSE_WRAPPER" --env-file "$ENV_FILE" "$@"
}

compose_service_hash() {
  local service=$1 line hash found=
  while IFS= read -r line; do
    read -r name hash _ <<<"$line"
    if [[ "$name" == "$service" && "$hash" =~ ^[0-9a-f]{64}$ ]]; then
      found=$hash
      break
    fi
  done < <(run_compose config --hash "$service")
  [[ -n "$found" ]] || die "Docker Compose did not return a valid $service service config hash"
  printf '%s' "$found"
}

service_config_matches_running() {
  local service=$1 desired_hash container_id running_hash
  desired_hash=$(compose_service_hash "$service") || die "could not calculate the desired $service service config hash"
  container_id=$(docker ps --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \
    --filter "label=com.docker.compose.service=$service" -q \
    | awk 'NF {count++; value=$0} END {if (count == 1) print value; else exit 1}') \
    || die "could not identify the running $service service container"
  running_hash=$(docker inspect --format '{{index .Config.Labels "com.docker.compose.config-hash"}}' "$container_id") \
    || die "could not inspect the running $service service config hash"
  [[ -n "$running_hash" ]] || die "the running $service service has no Compose config hash"
  [[ "$desired_hash" == "$running_hash" ]]
}

if (( ! shared_bootstrap )); then
  if ! service_config_matches_running api; then api_changed=1; fi
  if ! service_config_matches_running web; then web_changed=1; fi
fi
if (( database_migration && api_digest_changed == 0 )); then
  die 'manifest declares a database migration without changing the API image digest'
fi

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
    [[ "$(docker inspect --format '{{.Config.Image}}' "$container_id")" == "${candidate_runtime_ref[$service]}" ]] || return 1
    image_id=$(docker inspect --format '{{.Image}}' "$container_id")
    expected_id=$(docker image inspect --format '{{.Id}}' "${candidate_runtime_ref[$service]}") || return 1
    [[ "$image_id" == "$expected_id" ]] || return 1
    if [[ "$offline_images" == 1 ]]; then
      [[ "$image_id" == "${candidate_image_id[$service]}" ]] || return 1
    else
      docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "${candidate_runtime_ref[$service]}" \
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
  local failed_api_ref image_ref service_ref destination db_id actual expected staged_history
  failed_api_ref=$(python3 "$OFFLINE_IMAGE_VERIFIER" "$recovery_bundle" --get-runtime-ref api) || return 1
  for service_ref in failed candidate; do
    if [[ "$service_ref" == failed ]]; then
      image_ref=$failed_api_ref
    else
      image_ref=${candidate_runtime_ref[api]}
    fi
    destination="$tmp_dir/contract-${service_ref}-api.json"
    # Create an isolated, never-started container solely to read the immutable
    # image's contract; no production volume or network is attached.
    inspection_id=$(docker create --network none --entrypoint /bin/false "$image_ref") || return 1
    docker cp "$inspection_id:/app/production-schema-contract.json" "$destination" >/dev/null || return 1
    docker rm "$inspection_id" >/dev/null || return 1
    inspection_id=
  done
  expected=$(python3 - "$tmp_dir/contract-failed-api.json" "$tmp_dir/contract-candidate-api.json" <<'PY'
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
  [[ "$actual" == "$expected" ]] && return 0

  # V63 is deliberately committed before the idempotent filesystem index is
  # built. If indexing or V64 fails, the old API must stay stopped, but a new
  # release with exactly the same final contract must be able to finish that
  # staged cutover. Do not generalize this exception to other migrations.
  [[ "$expected" == '64|64|0' && "$actual" == '63|63|0' ]] || return 1
  python3 - "$tmp_dir/contract-candidate-api.json" <<'PY' || return 1
import json, sys
contract = json.load(open(sys.argv[1], encoding='utf-8'))
tables = contract.get('tables', {})
if (not {'id', 'storage_path', 'processing_status', 'deleted_at'}.issubset(tables.get('media_assets', []))
        or not {'asset_id', 'owner_type', 'owner_id', 'field'}.issubset(tables.get('media_references', []))):
    raise SystemExit('V64 media contract is incomplete')
PY
  staged_history=$(docker exec -i "$db_id" sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -Atq' <<'SQL'
SELECT CASE WHEN
    COUNT(*) FILTER (WHERE type='SQL') = 63
    AND COUNT(DISTINCT version) FILTER (WHERE type='SQL') = 63
    AND COUNT(*) FILTER (WHERE type='SQL' AND version ~ '^([1-9]|[1-5][0-9]|6[0-3])$') = 63
    AND COUNT(*) FILTER (WHERE NOT success) = 0
    AND COUNT(*) FILTER (WHERE type='SQL' AND version='63'
        AND script='V63__media_assets_and_references.sql' AND success) = 1
    AND to_regclass('public.media_assets') IS NOT NULL
    AND to_regclass('public.media_references') IS NOT NULL
    AND to_regclass('public.media_deleted_files') IS NOT NULL
    AND to_regprocedure('public.myshop_media_guard_write()') IS NOT NULL
  THEN '1' ELSE '0' END
FROM public.flyway_schema_history;
SQL
  ) || return 1
  [[ "$staged_history" == '1' ]] || return 1
  printf 'FORWARD_RECOVERY_MEDIA_INDEX version=63 next=64\n'
}

prepare_prospective_state() {
  prospective_root="$tmp_dir/prospective"
  prospective_state="$prospective_root/deploy-state.json"
  mkdir -p -- "$prospective_root"
  if (( state_existed )); then
    cp -p -- "$state_backup" "$prospective_state"
  fi
  MYSHOP_OFFLINE_IMAGES="$offline_images" MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE="$offline_bundle" \
    MYSHOP_API_RUNTIME_REF="${candidate_runtime_ref[api]}" MYSHOP_WEB_RUNTIME_REF="${candidate_runtime_ref[web]}" \
    MYSHOP_API_IMAGE_ID="${candidate_image_id[api]:-}" MYSHOP_WEB_IMAGE_ID="${candidate_image_id[web]:-}" \
    "$STATE_WRITER" "$prospective_state" "$manifest" >/dev/null
}

wait_health() {
  local project=$1 attempts=${2:-30} attempt
  for ((attempt = 1; attempt <= attempts; attempt++)); do
    local healthy=1
    for service in db api web caddy; do
      container_id=$(docker ps --filter "label=com.docker.compose.project=$project" --filter "label=com.docker.compose.service=$service" -q | head -n 1)
      [[ -n "$container_id" ]] || { healthy=0; break; }
      status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id")
      [[ "$status" == healthy || ( "$service" == db && "$status" == running ) ]] || { healthy=0; break; }
    done
    (( healthy == 1 )) && return 0
    (( attempt == attempts )) && return 1
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
  if (( ! candidate_started && ! api_stop_attempted )); then
    verify_baseline || return 1
    unset DEPLOY_TEST_FAIL
    smoke || return 1
    return 0
  fi

  if (( database_migration && (candidate_started || api_stop_attempted) )); then
    # A migration rollback is safe only with unchanged Flyway history. Keep
    # the existing fail-closed behavior for all app-facing services here.
    run_compose stop api web caddy || return 1
    current_schema=$(database_schema_fingerprint) || return 2
    [[ -n "${baseline_schema_fingerprint:-}" && "$current_schema" == "$baseline_schema_fingerprint" ]] || return 2
    run_before_compose config --quiet || return 1
    run_before_compose up -d --pull never --no-deps api || return 1
    run_before_compose up -d --pull never --no-deps web || return 1
    run_before_compose up -d --pull never --no-deps caddy || return 1
  else
    if (( api_changed && (api_candidate_started || api_stop_attempted) )); then
      run_compose stop api || return 1
      current_schema=$(database_schema_fingerprint) || return 2
      [[ -n "${baseline_schema_fingerprint:-}" && "$current_schema" == "$baseline_schema_fingerprint" ]] || return 2
      run_before_compose config --quiet || return 1
      run_before_compose up -d --pull never --no-deps api || return 1
    fi
    if (( web_changed && web_candidate_started )); then
      run_before_compose config --quiet || return 1
      run_before_compose up -d --pull never --no-deps web || return 1
    fi
  fi
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
# The database is untouched by a pure Web release. API changes retain their
# recovery point; migration releases still stop the API before startup.
if (( ! shared_bootstrap && api_changed )); then
  create_predeploy_backup || pre_commit_fail backup
  baseline_schema_fingerprint=$(database_schema_fingerprint) || pre_commit_fail baseline-schema
fi
[[ "${DEPLOY_TEST_FAIL:-}" != health ]] || pre_commit_fail health
run_compose config --quiet || pre_commit_fail compose
prepare_bootstrap_rollback_refs || pre_commit_fail bootstrap-rollback-prepare
[[ "${DEPLOY_TEST_FAIL:-}" != start ]] || pre_commit_fail start

if (( shared_bootstrap )); then
  candidate_started=1
  run_compose up -d --pull never || pre_commit_fail start
  wait_health "$COMPOSE_PROJECT" 180 || pre_commit_fail health
  [[ "${DEPLOY_TEST_FAIL:-}" != schema ]] || pre_commit_fail schema
  MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" "$SCHEMA_SCRIPT" || pre_commit_fail schema
else
  # Migration startup must run with every previous API process stopped so no
  # old writer can race the new Flyway migration or media index.
  if (( api_changed && database_migration )); then
  api_stop_attempted=1
    run_compose stop api || pre_commit_fail stop-before-migration
    running_apis=$(docker ps -q \
      --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" \
      --filter 'label=com.docker.compose.service=api') || pre_commit_fail verify-api-stopped
    [[ -z "$running_apis" ]] || pre_commit_fail verify-api-stopped
  fi
  if (( api_changed )); then
    candidate_started=1
    api_candidate_started=1
    run_compose up -d --pull never --no-deps api || pre_commit_fail start
    # First media indexing runs during API startup and may outlast the ordinary
    # health budget on a CPU-constrained host.
    wait_health "$COMPOSE_PROJECT" 180 || pre_commit_fail health
    [[ "${DEPLOY_TEST_FAIL:-}" != health-after-start ]] || pre_commit_fail health-after-start
    [[ "${DEPLOY_TEST_FAIL:-}" != schema ]] || pre_commit_fail schema
    MYSHOP_COMPOSE_PROJECT_NAME="$COMPOSE_PROJECT" "$SCHEMA_SCRIPT" || pre_commit_fail schema
  fi
  if (( web_changed )); then
    candidate_started=1
    web_candidate_started=1
    run_compose up -d --pull never --no-deps web || pre_commit_fail start
    wait_health "$COMPOSE_PROJECT" 180 || pre_commit_fail health
  fi
  if (( ! api_changed && ! web_changed )); then
    wait_health "$COMPOSE_PROJECT" || pre_commit_fail health
  fi
fi
[[ "${DEPLOY_TEST_FAIL:-}" != health-after-start ]] || pre_commit_fail health-after-start
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
  MYSHOP_API_RUNTIME_REF="${candidate_runtime_ref[api]}" MYSHOP_WEB_RUNTIME_REF="${candidate_runtime_ref[web]}" \
  MYSHOP_API_IMAGE_ID="${candidate_image_id[api]:-}" MYSHOP_WEB_IMAGE_ID="${candidate_image_id[web]:-}" \
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

#!/usr/bin/env bash
set -Eeuo pipefail

# Keep the active release and the two most recent rollback candidates, then
# remove only Docker data that is not needed by those releases or by a running
# container. This script deliberately never prunes Docker volumes.

readonly PROJECT_ROOT="/opt/myshop"
readonly RELEASE_ROOT="${PROJECT_ROOT}/releases"
readonly RETAIN_RELEASES=3
readonly LOCK_FILE="${MYSHOP_LOCK_FILE:-${PROJECT_ROOT}/.docker-retention.lock}"

dry_run=0
case "${1:-}" in
  "") ;;
  --dry-run) dry_run=1 ;;
  -h|--help)
    printf 'Usage: %s [--dry-run]\n' "$0"
    exit 0
    ;;
  *)
    printf 'unknown option: %s\n' "$1" >&2
    exit 2
    ;;
esac

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '[myshop-retention] %s\n' "$*"
}

warn() {
  printf '[myshop-retention] WARNING: %s\n' "$*" >&2
}

run_cleanup_command() {
  log "+ $*"
  if ((dry_run == 0)) && ! "$@"; then
    warn "command failed: $*"
    cleanup_failures=$((cleanup_failures + 1))
  fi
}

(( RETAIN_RELEASES == 3 )) || die "retention policy must retain exactly three releases"
[[ -d "$PROJECT_ROOT" ]] || die "missing project root: $PROJECT_ROOT"
[[ -d "$RELEASE_ROOT" ]] || die "missing release root: $RELEASE_ROOT"
[[ -f "$PROJECT_ROOT/DEPLOYED_SHA" ]] || die "missing DEPLOYED_SHA"

for command_name in docker find sort awk realpath readlink tr flock grep stat; do
  command -v "$command_name" >/dev/null 2>&1 || die "required command is unavailable: $command_name"
done

mkdir -p "$(dirname "$LOCK_FILE")"
exec 9>"$LOCK_FILE" || die "cannot open lock file: $LOCK_FILE"
flock -n 9 || {
  log "another retention run is active; leaving this run unchanged"
  exit 0
}

cleanup_failures=0
current_sha="$(tr -d '[:space:]' < "$PROJECT_ROOT/DEPLOYED_SHA")"
[[ "$current_sha" =~ ^[0-9a-f]{40}$ ]] || die "DEPLOYED_SHA is not a full lowercase SHA"

current_release="${RELEASE_ROOT}/${current_sha}"
[[ -d "$current_release" ]] || die "current release directory is missing: $current_release"
[[ -L "$PROJECT_ROOT/current" ]] || die "current must be a symlink"
current_release_real="$(realpath -e "$PROJECT_ROOT/current")"
expected_release_real="$(realpath -e "$current_release")"
[[ "$current_release_real" == "$expected_release_real" ]] || die "current symlink does not match DEPLOYED_SHA"

api_ref="myshop-api:${current_sha}"
web_ref="myshop-web:${current_sha}"
running_images="$(docker ps --format '{{.Image}}')"
grep -Fqx "$api_ref" <<<"$running_images" || die "running API does not use $api_ref"
grep -Fqx "$web_ref" <<<"$running_images" || die "running Web does not use $web_ref"
docker image inspect "$api_ref" >/dev/null 2>&1 || die "current API image is missing: $api_ref"
docker image inspect "$web_ref" >/dev/null 2>&1 || die "current Web image is missing: $web_ref"

candidate_sha=""
if [[ -f "$PROJECT_ROOT/DEPLOYED_SHA.candidate" ]]; then
  candidate_sha="$(tr -d '[:space:]' < "$PROJECT_ROOT/DEPLOYED_SHA.candidate")"
  if [[ ! "$candidate_sha" =~ ^[0-9a-f]{40}$ ]]; then
    warn "ignoring malformed DEPLOYED_SHA.candidate"
    candidate_sha=""
  elif (( $(stat -c '%Y' "$PROJECT_ROOT/DEPLOYED_SHA.candidate") > $(stat -c '%Y' "$PROJECT_ROOT/DEPLOYED_SHA") )); then
    die "a newer DEPLOYED_SHA.candidate indicates a deployment is in progress"
  fi
fi

mapfile -t release_shas < <(
  find "$RELEASE_ROOT" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %f\n' |
    sort -nr |
    awk '$2 ~ /^[0-9a-f]{40}$/ {print $2}'
)

declare -a keep_shas=()
declare -A keep_seen=()
add_keep_sha() {
  local sha="$1"
  [[ -n "${keep_seen[$sha]+present}" ]] && return 0
  (( ${#keep_shas[@]} < RETAIN_RELEASES )) || return 0
  keep_seen["$sha"]=1
  keep_shas+=("$sha")
}

# The active release is always first. A stale candidate marker is ignored; a
# newer candidate marker aborts above so a deployment cannot race this cleanup.
add_keep_sha "$current_sha"
for sha in "${release_shas[@]}"; do
  add_keep_sha "$sha"
done
(( ${#keep_shas[@]} == RETAIN_RELEASES )) || die "fewer than ${RETAIN_RELEASES} safe release directories are available"

log "active release: $current_sha"
log "retaining releases: ${keep_shas[*]}"
if ((dry_run == 1)); then
  log "dry-run: no Docker or release-directory deletion will be performed"
fi

declare -A protected_image_ids=()
declare -A keep_image_refs=()

# Never remove an image referenced by any container, including stopped
# containers. This also protects an image if a deployment is between steps.
while IFS= read -r container_id; do
  [[ -n "$container_id" ]] || continue
  image_id="$(docker inspect --format '{{.Image}}' "$container_id" 2>/dev/null || true)"
  [[ -n "$image_id" ]] && protected_image_ids["$image_id"]=1
done < <(docker ps -aq)

for sha in "${keep_shas[@]}"; do
  for repository in myshop-api myshop-web; do
    reference="${repository}:${sha}"
    if image_id="$(docker image inspect --format '{{.Id}}' "$reference" 2>/dev/null)"; then
      keep_image_refs["$reference"]=1
      protected_image_ids["$image_id"]=1
    else
      warn "retained release has no image: $reference"
    fi
  done
done

while IFS=$'\t' read -r repository tag image_id; do
  [[ -n "$repository" && -n "$tag" && -n "$image_id" ]] || continue
  [[ "$repository" == '<none>' || "$tag" == '<none>' ]] && continue
  reference="${repository}:${tag}"
  [[ -n "${keep_image_refs[$reference]+present}" ]] && continue
  [[ -n "${protected_image_ids[$image_id]+present}" ]] && continue
  run_cleanup_command docker image rm "$reference"
done < <(docker image ls -a --no-trunc --format '{{.Repository}}\t{{.Tag}}\t{{.ID}}')

# Dangling tags are never rollback candidates. Builder cache is intentionally
# fully pruned after a successful deployment; this prevents rapid iterations
# from filling the data disk again.
run_cleanup_command docker image prune --force
run_cleanup_command docker builder prune --all --force

release_root_real="$(realpath -e "$RELEASE_ROOT")"
for sha in "${release_shas[@]}"; do
  [[ -n "${keep_seen[$sha]+present}" ]] && continue
  release_dir="${RELEASE_ROOT}/${sha}"
  [[ -d "$release_dir" ]] || continue
  release_dir_real="$(realpath -e "$release_dir" 2>/dev/null || true)"
  [[ -n "$release_dir_real" ]] || {
    warn "release disappeared before validation: $release_dir"
    continue
  }
  [[ "$release_dir_real" == "$release_root_real/${sha}" ]] || die "refusing unsafe release path: $release_dir"
  [[ "$(basename "$release_dir_real")" =~ ^[0-9a-f]{40}$ ]] || die "refusing non-SHA release path: $release_dir"
  log "remove old release directory: $release_dir"
  if ((dry_run == 0)); then
    rm -rf -- "$release_dir" || {
      warn "failed to remove release directory: $release_dir"
      cleanup_failures=$((cleanup_failures + 1))
    }
  fi
done

printf '\n'
docker system df 2>&1 || cleanup_failures=$((cleanup_failures + 1))
df -h "$PROJECT_ROOT" 2>&1 || cleanup_failures=$((cleanup_failures + 1))
df -h /data 2>&1 || cleanup_failures=$((cleanup_failures + 1))

if ((cleanup_failures > 0)); then
  die "retention cleanup completed with ${cleanup_failures} failure(s)"
fi
log "retention cleanup completed; Docker volumes were not touched"

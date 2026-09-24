#!/usr/bin/env bash
set -Eeuo pipefail

# Host-wide storage gate.  This is intentionally read-only: it reports the
# filesystem and Docker/containerd dimensions but never removes an object.
DATA_MOUNTPOINT=${DATA_MOUNTPOINT:-/data}
CONTAINERD_ROOT=${CONTAINERD_ROOT:-/data/containerd}
DOCKER_ROOT=${DOCKER_ROOT:-/data/docker}
MYSHOP_ROOT=${MYSHOP_ROOT:-/opt/myshop/runtime}
AGENT_ROOT=${AGENT_ROOT:-/opt/xianyu-agent/runtime}

die() { printf '%s\n' "$*" >&2; exit 1; }
command -v findmnt >/dev/null 2>&1 || die 'findmnt is required'
command -v df >/dev/null 2>&1 || die 'df is required'

mount_source=$(findmnt -n -o SOURCE -M "$DATA_MOUNTPOINT" 2>/dev/null || true)
mount_target=$(findmnt -n -o TARGET -M "$DATA_MOUNTPOINT" 2>/dev/null || true)
[[ "$mount_target" == "$DATA_MOUNTPOINT" ]] || die "DATA_DISK_NOT_MOUNTED: $DATA_MOUNTPOINT"

read -r used_percent _ < <(df -P "$DATA_MOUNTPOINT" | awk 'NR==2 {gsub(/%/,"",$5); print $5, $4}')
[[ "$used_percent" =~ ^[0-9]+$ ]] || die 'could not read data disk usage'

printf 'storage_mount source=%s target=%s\n' "$mount_source" "$mount_target"
df -hT "$DATA_MOUNTPOINT"

docker_available=0
if command -v docker >/dev/null 2>&1; then
  docker_available=1
  config_validator="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/validate-docker-config.sh"
  if [[ -x "$config_validator" && -x "$(command -v dockerd 2>/dev/null || true)" ]]; then
    "$config_validator"
  fi
  docker info --format 'docker_root={{.DockerRootDir}} storage_driver={{.Driver}} server={{.ServerVersion}}' || true
  docker system df -v || true
fi

printf 'containerd_usage='
if [[ -d "$CONTAINERD_ROOT" ]]; then
  du -shx "$CONTAINERD_ROOT" | awk '{print $1}'
else
  printf 'missing\n'
fi
printf 'docker_usage='
if [[ -d "$DOCKER_ROOT" ]]; then
  du -shx "$DOCKER_ROOT" | awk '{print $1}'
else
  printf 'missing\n'
fi
printf 'myshop_accepted_count='; python3 - "$MYSHOP_ROOT/deploy-state.json" <<'PY'
import json, sys
try:
    state = json.load(open(sys.argv[1], encoding='utf-8'))
    print(1 + len(state.get('rollback', [])) if state.get('version') == 1 else 'invalid')
except Exception:
    print('missing')
PY
printf 'agent_accepted_count='; python3 - "$AGENT_ROOT/deploy-state.json" <<'PY'
import json, sys
try:
    state = json.load(open(sys.argv[1], encoding='utf-8'))
    print(1 + len(state.get('rollback', [])) if state.get('version') == 1 else 'invalid')
except Exception:
    print('missing')
PY
printf 'source_release_directories=disabled\n'

if (( docker_available )); then
  # Production images are immutable registry-qualified refs (for example
  # ghcr.io/<namespace>/myshop-api:<sha>), so matching only the short local
  # repository name under-counts every production MyShop image.
  printf 'myshop_app_image_count='; docker image ls --format '{{.Repository}}' | awk '$0 ~ /(^|\/)myshop-(api|web)$/' | wc -l
  printf 'agent_app_image_count='; docker image ls --format '{{.Repository}}' | awk '$0 ~ /(^|\/)xianyu-agent-(backend-web|websocket|scheduler|frontend)$/' | wc -l
  printf 'build_cache='; docker system df --format '{{.Type}}|{{.Size}}|{{.Reclaimable}}' | awk -F'|' '$1=="Build Cache" {print $2 " (reclaimable " $3 ")"; found=1} END {if (!found) print "unknown"}'
else
  printf 'myshop_app_image_count=unknown\nagent_app_image_count=unknown\nbuild_cache=unknown\n'
fi

if (( used_percent >= 90 )); then
  printf 'CRITICAL: DATA_DISK_HIGH_WATERMARK used=%s%%\n' "$used_percent" >&2
  exit 2
elif (( used_percent >= 80 )); then
  printf 'HARD_FAIL: DATA_DISK_HIGH_WATERMARK used=%s%%\n' "$used_percent" >&2
  exit 3
elif (( used_percent >= 70 )); then
  printf 'MAINTENANCE_REQUIRED used=%s%%\n' "$used_percent" >&2
  exit 4
elif (( used_percent >= 60 )); then
  printf 'WARNING used=%s%%\n' "$used_percent"
else
  printf 'OK used=%s%%\n' "$used_percent"
fi

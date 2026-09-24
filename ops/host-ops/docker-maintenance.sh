#!/usr/bin/env bash
set -Eeuo pipefail

LOCK_FILE=${HOST_DEPLOY_LOCK_FILE:-/run/myshop-xianyu-deploy.lock}
DATA_MOUNTPOINT=${DATA_MOUNTPOINT:-/data}
if [[ "${HOST_DEPLOY_LOCK_HELD:-0}" != 1 ]]; then
  mkdir -p -- "$(dirname -- "$LOCK_FILE")"
  exec 9>"$LOCK_FILE"
  flock -n 9 || { printf 'HOST_MAINTENANCE_ALREADY_RUNNING\n' >&2; exit 0; }
fi

findmnt -n -o TARGET -M "$DATA_MOUNTPOINT" | grep -Fx "$DATA_MOUNTPOINT" >/dev/null || {
  printf 'DATA_DISK_NOT_MOUNTED: %s\n' "$DATA_MOUNTPOINT" >&2
  exit 1
}

if docker ps --format '{{.ID}} {{.Names}}' | grep -Eq '(^| )buildkit|buildx' \
  || ps -eo args= | grep -Eq '[b]uildkitd|[d]ocker build(x)? build'; then
  printf 'BUILD_IN_PROGRESS\n' >&2
  exit 1
fi

docker builder prune --all --force
docker image prune --force
docker system df -v
df -hT "$DATA_MOUNTPOINT"

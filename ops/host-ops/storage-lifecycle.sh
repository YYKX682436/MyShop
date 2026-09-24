#!/usr/bin/env bash
set -Eeuo pipefail

LOCK_FILE=${HOST_DEPLOY_LOCK_FILE:-/run/myshop-xianyu-deploy.lock}
DATA_MOUNTPOINT=${DATA_MOUNTPOINT:-/data}
MYSHOP_RETENTION=${MYSHOP_RETENTION_SCRIPT:-/opt/myshop/scripts/cleanup-docker-retention.sh}
AGENT_RETENTION=${AGENT_RETENTION_SCRIPT:-/opt/xianyu-agent/runtime/cleanup-docker-retention.sh}
STORAGE_HEALTH=${HOST_STORAGE_HEALTH_SCRIPT:-/opt/host-ops/check-storage-health.sh}
HOST_MAINTENANCE=${HOST_MAINTENANCE_SCRIPT:-/opt/host-ops/docker-maintenance.sh}

die() { printf '[storage-lifecycle] ERROR: %s\n' "$*" >&2; exit 1; }
mkdir -p -- "$(dirname -- "$LOCK_FILE")"
exec 9>"$LOCK_FILE"
flock -n 9 || die 'deployment/maintenance lock is held'
findmnt -n -o TARGET -M "$DATA_MOUNTPOINT" | grep -Fx "$DATA_MOUNTPOINT" >/dev/null || die "DATA_DISK_NOT_MOUNTED: $DATA_MOUNTPOINT"

pre_status=0
set +e
"$STORAGE_HEALTH"
pre_status=$?
set -e
case "$pre_status" in
  0|1|4) ;;
  2|3) die "precheck hard-failed: $pre_status" ;;
  *) die "precheck returned unexpected status: $pre_status" ;;
esac

# Both application retainers are mandatory once their accepted state exists.
# Missing or malformed state is a service failure, never a successful no-op.
MYSHOP_RUNTIME_ROOT=${MYSHOP_RUNTIME_ROOT:-/opt/myshop/runtime} \
  MYSHOP_DEPLOY_STATE=${MYSHOP_DEPLOY_STATE:-/opt/myshop/runtime/deploy-state.json} \
  HOST_DEPLOY_LOCK_FILE="$LOCK_FILE" "$MYSHOP_RETENTION"
AGENT_RUNTIME_ROOT=${AGENT_RUNTIME_ROOT:-/opt/xianyu-agent/runtime} \
  AGENT_DEPLOY_STATE=${AGENT_DEPLOY_STATE:-/opt/xianyu-agent/runtime/deploy-state.json} \
  HOST_DEPLOY_LOCK_FILE="$LOCK_FILE" "$AGENT_RETENTION"

HOST_DEPLOY_LOCK_HELD=1 HOST_DEPLOY_LOCK_FILE="$LOCK_FILE" "$HOST_MAINTENANCE"

post_status=0
set +e
"$STORAGE_HEALTH"
post_status=$?
set -e
(( post_status < 4 )) || die "postcheck is at or above 70 percent: $post_status"
printf '[storage-lifecycle] SUCCESS pre_status=%s post_status=%s\n' "$pre_status" "$post_status"

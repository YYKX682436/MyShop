#!/usr/bin/env bash
set -Eeuo pipefail

[[ "$(id -u)" == 0 ]] || { printf 'must run as root\n' >&2; exit 1; }
CONFIG_SOURCE=${CONFIG_SOURCE:-/tmp/daemon.json.builder-gc.example}
CONFIG_TARGET=${CONFIG_TARGET:-/etc/docker/daemon.json}
LOCK_FILE=${HOST_DEPLOY_LOCK_FILE:-/run/myshop-xianyu-deploy.lock}
[[ -r "$CONFIG_SOURCE" ]] || { printf 'missing candidate Docker config: %s\n' "$CONFIG_SOURCE" >&2; exit 1; }

exec 9>"$LOCK_FILE"
flock -n 9 || { printf 'deployment/maintenance lock is held\n' >&2; exit 1; }
if ps -eo args= | grep -Eq '[b]uildkitd|[d]ocker build(x)? build'; then
  printf 'BUILD_IN_PROGRESS\n' >&2
  exit 1
fi

stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="${CONFIG_TARGET}.storage-lifecycle-${stamp}"
cp -a -- "$CONFIG_TARGET" "$backup"
install -o root -g root -m 0644 -- "$CONFIG_SOURCE" "$CONFIG_TARGET"

restore() {
  install -o root -g root -m 0644 -- "$backup" "$CONFIG_TARGET"
}
if ! dockerd --validate --config-file="$CONFIG_TARGET" >/dev/null; then
  restore
  exit 1
fi
if ! systemctl restart docker; then
  restore
  systemctl restart docker || true
  exit 1
fi
systemctl is-active --quiet docker || { restore; systemctl restart docker; exit 1; }
printf 'daemon_config_backup=%s\n' "$backup"

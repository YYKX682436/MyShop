#!/usr/bin/env bash
set -Eeuo pipefail

DOCKER_CONFIG=${DOCKER_CONFIG:-/etc/docker/daemon.json}
CONTAINERD_CONFIG=${CONTAINERD_CONFIG:-/etc/containerd/config.toml}

[[ -r "$DOCKER_CONFIG" ]] || { printf 'missing Docker config: %s\n' "$DOCKER_CONFIG" >&2; exit 1; }
[[ -r "$CONTAINERD_CONFIG" ]] || { printf 'missing containerd config: %s\n' "$CONTAINERD_CONFIG" >&2; exit 1; }
command -v dockerd >/dev/null 2>&1 || { printf 'dockerd is unavailable\n' >&2; exit 1; }

dockerd --validate --config-file="$DOCKER_CONFIG"
python3 - "$DOCKER_CONFIG" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    config = json.load(handle)
if config.get("data-root") != "/data/docker":
    raise SystemExit("Docker data-root is not /data/docker")
gc = config.get("builder", {}).get("gc", {})
if gc and gc.get("defaultKeepStorage") not in ("2GB", "2048MB"):
    raise SystemExit("unexpected Docker builder defaultKeepStorage")
print("docker_config_roots_and_gc=OK")
PY

grep -Eq '^[[:space:]]*root[[:space:]]*=[[:space:]]*"/data/containerd"' "$CONTAINERD_CONFIG" || {
  printf 'containerd root is not /data/containerd\n' >&2
  exit 1
}
printf 'containerd_config_root=/data/containerd\n'

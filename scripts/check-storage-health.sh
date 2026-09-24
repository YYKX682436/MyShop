#!/usr/bin/env bash
set -Eeuo pipefail

readonly REPO_ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
exec "$REPO_ROOT/ops/host-ops/check-storage-health.sh" "$@"

#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only per-deployment storage gate. It inspects the filesystem containing
# MyShop's data path and never inspects or removes global Docker objects.
DATA_PATH=${MYSHOP_STORAGE_MOUNTPOINT:-/srv/myshop-friend}

die() { printf '[myshop-shared-storage] ERROR: %s\n' "$*" >&2; exit 1; }
command -v df >/dev/null 2>&1 || die 'df is required'
[[ -d "$DATA_PATH" ]] || die "MyShop data path is missing: $DATA_PATH"

mount_target=$(findmnt -n -o TARGET -T "$DATA_PATH" 2>/dev/null || true)
read -r used_percent available_kib < <(df -P "$DATA_PATH" | awk 'NR==2 {gsub(/%/, "", $5); print $5, $4}')
[[ "$used_percent" =~ ^[0-9]+$ && "$available_kib" =~ ^[0-9]+$ ]] || die 'could not read filesystem usage'

printf 'shared_storage_path=%s mount=%s used=%s%% available_kib=%s\n' \
  "$DATA_PATH" "${mount_target:-unknown}" "$used_percent" "$available_kib"
df -hT "$DATA_PATH"

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

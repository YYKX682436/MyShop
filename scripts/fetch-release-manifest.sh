#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-manifest-fetch] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 2 )) || die 'usage: fetch-release-manifest.sh <source_sha> <output-path>'
source_sha=$1
output=$2
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || die 'source SHA must be exactly 40 lowercase hexadecimal characters'
[[ -n "$output" ]] || die 'output path is empty'
docker_command=${MYSHOP_DOCKER_COMMAND:-docker}
command -v "$docker_command" >/dev/null 2>&1 || die 'docker is unavailable'
command -v python3 >/dev/null 2>&1 || die 'python3 is required'

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
registry=ghcr.io/yykx682436
source_url=https://github.com/YYKX682436/MyShop
carrier_ref="$registry/myshop-release-manifest:$source_sha"
output_dir=$(dirname -- "$output")
mkdir -p -- "$output_dir"
[[ ! -d "$output" ]] || die 'output path is a directory'
tmp_dir=$(mktemp -d)
container_id=
cleanup() {
  if [[ -n "$container_id" ]]; then "$docker_command" rm -f "$container_id" >/dev/null 2>&1 || true; fi
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT

"$docker_command" pull "$carrier_ref" >/dev/null || die "manifest carrier could not be pulled for source $source_sha"
revision=$("$docker_command" image inspect --format '{{if .Config.Labels}}{{index .Config.Labels "org.opencontainers.image.revision"}}{{end}}' "$carrier_ref") || die 'cannot inspect manifest carrier revision label'
[[ "$revision" == "$source_sha" ]] || die 'manifest carrier revision label does not match the requested source SHA'
source_label=$("$docker_command" image inspect --format '{{if .Config.Labels}}{{index .Config.Labels "org.opencontainers.image.source"}}{{end}}' "$carrier_ref") || die 'cannot inspect manifest carrier source label'
[[ "$source_label" == "$source_url" ]] || die 'manifest carrier source label does not match the MyShop repository'
container_id=$("$docker_command" create "$carrier_ref") || die 'cannot create manifest carrier extraction container'
[[ -n "$container_id" ]] || die 'Docker returned an empty manifest carrier container id'
"$docker_command" cp "$container_id:/release-manifest.json" "$tmp_dir/release-manifest.json" >/dev/null || die 'manifest carrier does not contain /release-manifest.json'

python3 "$script_dir/validate-release-manifest.py" "$tmp_dir/release-manifest.json" "$registry" myshop >"$tmp_dir/validated.json" || die 'release manifest validation failed'
python3 - "$tmp_dir/validated.json" "$source_sha" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1], encoding="utf-8"))
if manifest["source_sha"] != sys.argv[2]:
    raise SystemExit("manifest source SHA does not match the requested carrier SHA")
PY

python3 - "$tmp_dir/release-manifest.json" "$output" <<'PY'
import os, stat, sys, tempfile
from pathlib import Path

source, destination = Path(sys.argv[1]), Path(sys.argv[2])
destination.parent.mkdir(parents=True, exist_ok=True)
fd, temporary = tempfile.mkstemp(prefix=f".{destination.name}.", dir=destination.parent)
try:
    with source.open("rb") as input_file, os.fdopen(fd, "wb") as output_file:
        fd = -1
        while chunk := input_file.read(1024 * 1024):
            output_file.write(chunk)
        output_file.flush()
        os.fsync(output_file.fileno())
    os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary, destination)
    dir_fd = os.open(destination.parent, os.O_RDONLY)
    try:
        os.fsync(dir_fd)
    finally:
        os.close(dir_fd)
finally:
    if fd >= 0:
        os.close(fd)
    if os.path.exists(temporary):
        os.unlink(temporary)
PY
printf 'RELEASE_MANIFEST_FETCHED source_sha=%s path=%s\n' "$source_sha" "$output"

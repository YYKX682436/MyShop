#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-offline-images] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 3 )) || die 'usage: import-offline-image-bundle.sh <release-manifest.json> <bundle.json> <archive-directory>'
manifest=$1
bundle=$2
archive_dir=$3
script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
verifier="$script_dir/verify-offline-image-bundle.py"
[[ -f "$manifest" && ! -L "$manifest" ]] || die 'release manifest is missing or unsafe'
[[ -f "$bundle" && ! -L "$bundle" ]] || die 'image bundle is missing or unsafe'
[[ -d "$archive_dir" && ! -L "$archive_dir" ]] || die 'archive directory is missing or unsafe'
[[ $(id -u) == 0 ]] || die 'run as root so loaded-image identity can be checked'
command -v docker >/dev/null 2>&1 || die 'docker is unavailable'

python3 "$verifier" "$bundle" --manifest "$manifest" --verify-archives "$archive_dir" >/dev/null \
  || die 'offline image archive verification failed'
load_image() {
  local service=$1 image_archive
  image_archive=$(python3 - "$bundle" "$service" <<'PY'
import json, sys
bundle = json.load(open(sys.argv[1], encoding="utf-8"))
print(bundle["images"][sys.argv[2]]["archive"])
PY
)
  docker load --input "$archive_dir/$image_archive" >/dev/null || die "Docker could not import $service"
}
for service in api web; do load_image "$service"; done
install -o root -g root -m 0600 "$bundle" "$bundle.tmp"
mv -f -- "$bundle.tmp" "$bundle"
# The shared host already runs the three pinned infrastructure images. Record
# their local identities only after verifying the exact RepoDigests; the
# emergency artifact carries app images only and never replaces infrastructure.
python3 "$verifier" "$bundle" --record-loaded-image-ids >/dev/null \
  || die 'app imports or preinstalled pinned infrastructure images failed identity checks'
python3 "$verifier" "$bundle" --manifest "$manifest" --verify-local >/dev/null \
  || die 'loaded image IDs or platforms do not match the verified transfer bundle'
printf 'OFFLINE_IMAGES_IMPORTED source_sha=%s platform=linux/amd64 app_images=2 infra_images=verified_preinstalled\n' \
  "$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["source_sha"])' "$bundle")"

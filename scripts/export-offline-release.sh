#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-offline-export] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 2 )) || die 'usage: export-offline-release.sh <release-manifest.json> <output-directory>'
manifest=$1
output=$2
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
[[ -f "$manifest" && ! -L "$manifest" ]] || die 'release manifest is missing or unsafe'
[[ ! -e "$output" && ! -L "$output" ]] || die 'output directory already exists or is unsafe'
command -v docker >/dev/null 2>&1 || die 'docker is unavailable'
mkdir -m 0700 -- "$output"

normalized="$output/release-manifest.json"
python3 "$SCRIPT_DIR/validate-release-manifest.py" "$manifest" ghcr.io/yykx682436 myshop >"$normalized" \
  || die 'release manifest validation failed'

pull_and_save() {
  local service=$1 ref=$2 digest=$3 archive_ref=$4 repo
  repo=${ref%%@*}
  docker pull "$ref"
  docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$ref" \
    | grep -Fx "$repo@$digest" >/dev/null \
    || die "$service local RepoDigest differs from the immutable release reference"
  docker tag "$ref" "$archive_ref"
  docker save --output "$output/$service.tar" "$archive_ref"
}

while IFS=$'\t' read -r service ref digest image_sha archive_ref; do
  [[ -n "$service" ]] || continue
  pull_and_save "$service" "$ref" "$digest" "$archive_ref"
done < <(python3 - "$normalized" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1], encoding="utf-8"))
for service in ("api", "web"):
    item = manifest["images"][service]
    archive_ref = f"ghcr.io/yykx682436/myshop-{service}:{item['source_sha']}"
    print("\t".join((service, item["ref"], item["digest"], item["source_sha"], archive_ref)))
PY
)

python3 "$SCRIPT_DIR/create-offline-image-bundle.py" "$normalized" "$output" "$output/bundle.json"
python3 "$SCRIPT_DIR/verify-offline-image-bundle.py" "$output/bundle.json" \
  --manifest "$normalized" --verify-archives "$output"
(cd "$output" && sha256sum api.tar web.tar release-manifest.json bundle.json > SHA256SUMS)
printf 'OFFLINE_RELEASE_EXPORTED source_sha=%s path=%s\n' \
  "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["source_sha"])' "$normalized")" "$output"

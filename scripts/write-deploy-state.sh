#!/usr/bin/env bash
set -Eeuo pipefail

# Write accepted deployment state only from an explicit operator/deployment
# decision. This script intentionally does not inspect release mtimes.

usage() {
  printf 'Usage: %s <state-file> <active-sha> <rollback-sha-1> <rollback-sha-2> <api-ref> <api-digest> <web-ref> <web-digest>\n' "$0" >&2
  exit 2
}

(( $# == 8 )) || usage
state_file=$1
active_sha=$2
rollback_one=$3
rollback_two=$4
api_ref=$5
api_digest=$6
web_ref=$7
web_digest=$8

for sha in "$active_sha" "$rollback_one" "$rollback_two"; do
  [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || { printf 'invalid full SHA: %s\n' "$sha" >&2; exit 1; }
done
[[ "$active_sha" != "$rollback_one" && "$active_sha" != "$rollback_two" && "$rollback_one" != "$rollback_two" ]] || {
  printf 'accepted deployment state must contain three unique SHAs\n' >&2
  exit 1
}
[[ "$api_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || { printf 'invalid API digest\n' >&2; exit 1; }
[[ "$web_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || { printf 'invalid Web digest\n' >&2; exit 1; }
[[ "$api_ref" != *:latest && "$api_ref" != *:local && "$web_ref" != *:latest && "$web_ref" != *:local ]] || {
  printf 'mutable image tag is forbidden\n' >&2
  exit 1
}
[[ "$api_ref" =~ (^|/)myshop-api:${active_sha}$ ]] || { printf 'API image must be the active MyShop SHA\n' >&2; exit 1; }
[[ "$web_ref" =~ (^|/)myshop-web:${active_sha}$ ]] || { printf 'Web image must be the active MyShop SHA\n' >&2; exit 1; }

state_dir=$(dirname -- "$state_file")
mkdir -p -- "$state_dir"
tmp_file=$(mktemp "$state_dir/.deploy-state.XXXXXX")
cleanup() { rm -f -- "$tmp_file"; }
trap cleanup EXIT

python3 - "$tmp_file" "$active_sha" "$rollback_one" "$rollback_two" "$api_ref" "$api_digest" "$web_ref" "$web_digest" <<'PY'
import json
import sys
from datetime import datetime, timezone

path, active, rollback_one, rollback_two, api_ref, api_digest, web_ref, web_digest = sys.argv[1:]
state = {
    "active": active,
    "rollback": [rollback_one, rollback_two],
    "accepted": [active, rollback_one, rollback_two],
    "updated_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "images": {
        "api": {"ref": api_ref, "digest": api_digest},
        "web": {"ref": web_ref, "digest": web_digest},
    },
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(state, handle, indent=2, sort_keys=True)
    handle.write("\n")
PY

chmod 0640 -- "$tmp_file"
mv -f -- "$tmp_file" "$state_file"
trap - EXIT
printf 'accepted deployment state atomically written: %s\n' "$state_file"

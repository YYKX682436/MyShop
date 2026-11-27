#!/usr/bin/env bash
# One manual entry point from the trusted local checkout after committing/pushing main.
set -Eeuo pipefail
die() { printf '[myshop-release] ERROR: %s\n' "$*" >&2; exit 1; }
root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$root"
command -v gh >/dev/null && command -v ssh >/dev/null && command -v python3 >/dev/null || die 'gh, ssh, and python3 are required'
rsync_bin=${MYSHOP_RSYNC_BIN:-/opt/homebrew/bin/rsync}
[[ -x "$rsync_bin" ]] || die "rsync 3 is required at $rsync_bin"
[[ $(git branch --show-current) == main ]] || die 'release requires local main'
[[ -z $(git diff --name-only) && -z $(git diff --cached --name-only) ]] || die 'commit tracked changes before release'
sha=$(git rev-parse HEAD)
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || die 'invalid local SHA'
git fetch origin main --quiet
[[ $(git rev-parse origin/main) == "$sha" ]] || die 'local main differs from remote main'
state_json=$(ssh myshop-friend-prod 'cat /opt/myshop-friend/runtime/deploy-state.json')
accepted=$(printf '%s' "$state_json" | python3 -c 'import json,sys;print(json.load(sys.stdin)["active"]["source_sha"])')
[[ "$accepted" =~ ^[0-9a-f]{40}$ ]] || die 'invalid production accepted SHA'
[[ "$accepted" != "$sha" ]] || { printf 'ALREADY_ACCEPTED sha=%s\n' "$sha"; exit 0; }
git merge-base --is-ancestor "$accepted" "$sha" || die 'production accepted SHA is not an ancestor of main'
if ! git diff --name-only "$accepted" "$sha" | python3 -c '
import importlib.util,sys
spec=importlib.util.spec_from_file_location("scope","scripts/ci-release-scope.py")
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
raise SystemExit(0 if m.classify(sys.stdin.read().splitlines())["runtime"] else 1)
'; then
  printf 'NO_RUNTIME_RELEASE accepted=%s main=%s\n' "$accepted" "$sha"
  exit 0
fi
ssh myshop-friend-prod 'flock -n /run/myshop-friend-deploy.lock true' || die 'a production deployment is active'
previous_run=$(gh run list --repo YYKX682436/MyShop --workflow cicd.yml --commit "$sha" --limit 20 --json databaseId --jq '[.[].databaseId] | max // 0')
started=$SECONDS
gh workflow run cicd.yml --repo YYKX682436/MyShop --ref main -f "accepted_sha=$accepted"
run_id=
for _ in {1..30}; do
  run_id=$(gh run list --repo YYKX682436/MyShop --workflow cicd.yml --commit "$sha" --limit 20 --json databaseId --jq "[.[].databaseId | select(. > $previous_run)] | max // empty")
  [[ -n "$run_id" ]] && break
  sleep 2
done
[[ -n "$run_id" ]] || die 'workflow dispatch did not appear within 60 seconds'
printf 'CI_STARTED run=%s sha=%s accepted=%s\n' "$run_id" "$sha" "$accepted"
gh run watch "$run_id" --repo YYKX682436/MyShop --exit-status || die "CI failed; run=$run_id; production unchanged"
printf 'CI_PASSED run=%s elapsed=%ss\n' "$run_id" "$((SECONDS-started))"

temp=$(mktemp -d -t myshop-release.XXXXXX)
trap 'rm -rf -- "$temp"' EXIT
bash scripts/fetch-release-manifest.sh "$sha" "$temp/release-manifest.json"
python3 - "$temp/release-manifest.json" "$accepted" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]))
if m.get('version') != 3 or m.get('accepted_sha') != sys.argv[2]:
    raise SystemExit('CI manifest was not built against the observed production SHA')
PY
server_sha=$(ssh myshop-friend-prod "python3 -c 'import json;print(json.load(open(\"/opt/myshop-friend/runtime/deploy-state.json\"))[\"active\"][\"source_sha\"])'")
[[ "$server_sha" == "$accepted" ]] || die 'production accepted SHA changed while CI ran'
ssh myshop-friend-prod "install -d -m 0700 /srv/myshop-friend/releases/$sha /srv/myshop-upload/staging/$sha"
"$rsync_bin" -a "$temp/release-manifest.json" "myshop-friend-prod:/srv/myshop-friend/releases/$sha/release-manifest.json"
git bundle create "$temp/release.bundle" main "^$accepted"
"$rsync_bin" -a "$temp/release.bundle" "myshop-friend-prod:/srv/myshop-friend/releases/$sha/release.bundle"
ssh myshop-friend-prod "git --git-dir=/opt/myshop-friend/release.git fetch '/srv/myshop-friend/releases/$sha/release.bundle' '$sha' >/dev/null"
for helper in create-offline-image-bundle.py verify-offline-image-bundle.py import-offline-image-bundle.sh validate-release-manifest.py write-deploy-state.sh deploy-production.sh finalize-transferred-release.sh; do
  "$rsync_bin" -a "scripts/$helper" "myshop-friend-prod:/opt/myshop-friend/scripts/$helper"
done
ssh myshop-friend-prod 'chmod 0755 /opt/myshop-friend/scripts/{import-offline-image-bundle,write-deploy-state,deploy-production,finalize-transferred-release}.sh'

for service in api web; do
  changed=$(python3 - "$temp/release-manifest.json" "$service" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]));s=sys.argv[2]
print('1' if s in m['built_services'] else '0')
PY
)
  [[ "$changed" == 1 ]] || { printf 'REUSE_ACCEPTED service=%s\n' "$service"; continue; }
  if ssh myshop-friend-prod bash -s -- "$sha" "$service" < <(cat <<'REMOTE'
set -Eeuo pipefail
sha=$1 service=$2
python3 - "$sha" "$service" <<'PY'
import hashlib,json,sys
from pathlib import Path
sha,service=sys.argv[1:]
root=Path('/srv/myshop-upload/staging')/sha
record=json.loads((root/f'{service}.record.json').read_text())
if record['source_sha'] != sha or record['service'] != service: raise SystemExit(1)
digest=hashlib.file_digest((root/f'{service}.tar').open('rb'),'sha256').hexdigest()
if digest != record['tar_sha256']: raise SystemExit(1)
PY
REMOTE
); then
    printf 'DIRECT_TRANSFER_REUSED service=%s\n' "$service"
    continue
  fi
  mkdir -p "$temp/$service"
  gh run download "$run_id" --repo YYKX682436/MyShop --name "myshop-transfer-$service-$sha" --dir "$temp/$service" \
    || die "CI artifact download failed for $service; production unchanged"
  [[ -f "$temp/$service/$service.tar" && -f "$temp/$service/$service.record.json" ]] || die "incomplete $service artifact"
  printf 'MAC_RSYNC_START service=%s archive_bytes=%s\n' "$service" "$(stat -f %z "$temp/$service/$service.tar")"
  transfer_start=$SECONDS
  "$rsync_bin" -avc --mkpath --partial-dir=.rsync-partial --copy-dest=/srv/myshop-upload/basis --stats \
    "$temp/$service/$service.tar" "$temp/$service/$service.record.json" \
    "myshop-friend-prod:/srv/myshop-upload/staging/$sha/" \
    || die "Mac rsync failed for $service; staged data retained for resumption"
  printf 'MAC_RSYNC_DONE service=%s elapsed=%ss\n' "$service" "$((SECONDS-transfer_start))"
done
ssh myshop-friend-prod "bash /opt/myshop-friend/scripts/finalize-transferred-release.sh '$sha' '$accepted'" \
  || die "production validation or deployment failed for $sha; inspect server release and transaction logs"
printf 'RELEASE_COMPLETE sha=%s run=%s elapsed=%ss\n' "$sha" "$run_id" "$((SECONDS-started))"

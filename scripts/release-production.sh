#!/usr/bin/env bash
# One manual entry point; the server release lock covers the entire invocation.
set -Eeuo pipefail
die() { printf '[myshop-release] ERROR: %s\n' "$*" >&2; exit 1; }
root=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$root"
if [[ "${MYSHOP_RELEASE_LOCK_HELD:-0}" != 1 ]]; then
  exec python3 scripts/with-release-lock.py
fi
command -v gh >/dev/null && command -v ssh >/dev/null && command -v python3 >/dev/null || die 'gh, ssh, and python3 are required'
rsync_bin=${MYSHOP_RSYNC_BIN:-/opt/homebrew/bin/rsync}
[[ -x "$rsync_bin" ]] || die "rsync 3 is required at $rsync_bin"
[[ $(git branch --show-current) == main ]] || die 'release requires local main'
[[ -z $(git diff --name-only) && -z $(git diff --cached --name-only) ]] || die 'commit tracked changes before release'
sha=$(git rev-parse HEAD)
[[ "$sha" =~ ^[0-9a-f]{40}$ ]] || die 'invalid local SHA'
git fetch origin main --quiet
[[ $(git rev-parse origin/main) == "$sha" ]] || die 'local main differs from remote main'
started=$SECONDS
ssh myshop-friend-prod 'flock -n /run/myshop-friend-deploy.lock true' \
  || die 'a deployment or storage operation is already active'

# Finish a committed release's basis/environment update before considering a
# subsequent release, including when main contains only documentation.
ssh myshop-friend-prod bash -s <<'REMOTE'
set -Eeuo pipefail
state=/opt/myshop-friend/runtime/deploy-state.json
sha=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["active"]["source_sha"])' "$state")
release=/srv/myshop-friend/releases/$sha
if [[ -f "$release/candidate/scripts/finalize-transferred-release.sh" && ! -f "$release/release-complete" ]]; then
  base=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1])).get("accepted_sha",""))' "$release/release-manifest.json")
  [[ "$base" =~ ^[0-9a-f]{40}$ ]] || { echo 'accepted release has no recoverable manifest baseline' >&2; exit 1; }
  bash "$release/candidate/scripts/finalize-transferred-release.sh" "$sha" "$base"
fi
REMOTE
snapshot=$(ssh myshop-friend-prod python3 - <<'PY'
import json
from pathlib import Path
state=json.load(open("/opt/myshop-friend/runtime/deploy-state.json"))
sha=state["active"]["source_sha"]
path=Path("/srv/myshop-friend/releases")/sha/"release-manifest.json"
manifest=path.read_text() if path.is_file() and not path.is_symlink() else ""
print(json.dumps({"state":state,"manifest":manifest}))
PY
)
accepted=$(printf '%s' "$snapshot" | python3 -c 'import json,sys;print(json.load(sys.stdin)["state"]["active"]["source_sha"])')
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

git_dir=$(git rev-parse --absolute-git-dir)
cache="$git_dir/myshop-release/$sha"
umask 077
mkdir -p "$cache"
chmod 0700 "$cache"
printf '%s' "$snapshot" | python3 -c '
import json,sys
from pathlib import Path
x=json.load(sys.stdin); root=Path(sys.argv[1])
(root/"accepted-state.json").write_text(json.dumps(x["state"]))
(root/"accepted-manifest.json").write_text(x["manifest"])
' "$cache"
baseline=
if baseline=$(python3 scripts/release-baseline.py "$cache/accepted-manifest.json" "$cache/accepted-state.json" 2>/dev/null); then
  printf 'ACCEPTED_MANIFEST_REUSED sha=%s\n' "$accepted"
else
  baseline=
  printf 'ACCEPTED_MANIFEST_UNAVAILABLE full validation and builds required\n' >&2
fi

selection=$(python3 scripts/select-release-run.py "$sha") || die 'could not safely select an existing CI run'
if [[ "$selection" == new ]]; then
  previous_run=$(gh run list --repo YYKX682436/MyShop --workflow cicd.yml --commit "$sha" --limit 100 --json databaseId --jq '[.[].databaseId] | max // 0')
  gh workflow run cicd.yml --repo YYKX682436/MyShop --ref main -f "accepted_sha=$accepted" -f "accepted_manifest=$baseline"
  run_id=
  for _ in {1..30}; do
    run_id=$(gh run list --repo YYKX682436/MyShop --workflow cicd.yml --commit "$sha" --limit 100 --json databaseId --jq "[.[].databaseId | select(. > $previous_run)] | max // empty")
    [[ -n "$run_id" ]] && break
    sleep 2
  done
  [[ -n "$run_id" ]] || die 'workflow dispatch did not appear within 60 seconds'
  printf 'CI_STARTED run=%s sha=%s accepted=%s\n' "$run_id" "$sha" "$accepted"
  run_status=running
else
  run_id=${selection%%|*}
  run_status=${selection##*|}
  printf 'CI_REUSED run=%s status=%s sha=%s\n' "$run_id" "$run_status" "$sha"
fi
[[ "$run_id" =~ ^[0-9]+$ ]] || die 'invalid selected CI run'
if [[ "$run_status" != completed ]]; then
  gh run watch "$run_id" --repo YYKX682436/MyShop --exit-status || die "CI failed; run=$run_id; staged data retained"
fi
printf 'CI_PASSED run=%s elapsed=%ss\n' "$run_id" "$((SECONDS-started))"
manifest="$cache/release-manifest-$run_id.json"
if [[ ! -f "$manifest" ]]; then
  if ! bash scripts/fetch-release-manifest.sh "$sha" "$manifest" "$run_id"; then
    if ssh myshop-friend-prod python3 - "/srv/myshop-friend/releases/$sha" "$sha" "$run_id" read-manifest \
        < scripts/release-server-proof.py > "$manifest.partial"; then
      mv -f -- "$manifest.partial" "$manifest"
      printf 'SERVER_CI_MANIFEST_REUSED run=%s\n' "$run_id"
    else
      rm -f -- "$manifest.partial"
      die "successful CI manifest is unavailable or expired for run $run_id; do not rebuild this SHA; use a new reviewed commit"
    fi
  fi
fi
python3 scripts/validate-release-manifest.py "$manifest" ghcr.io/yykx682436 myshop >/dev/null
python3 scripts/validate-release-ancestry.py "$manifest" "$sha"
python3 - "$manifest" "$cache/accepted-state.json" "$accepted" "$sha" <<'PY'
import json,sys
m=json.load(open(sys.argv[1])); state=json.load(open(sys.argv[2]))['active']; accepted,sha=sys.argv[3:]
if m.get('version') != 3 or m.get('source_sha') != sha or m.get('accepted_sha') != accepted:
    raise SystemExit('successful CI manifest has a different production baseline; preserve its immutable images and use a new reviewed commit')
for service in ('api','web'):
    old=state['images'][service]; image=m['images'][service]
    changed=image['digest'] != old['digest']
    if changed and service not in m['built_services']:
        raise SystemExit(f'{service} digest changed without a CI build')
    if not changed and (image['source_sha'] != old.get('source_sha', accepted) or image['ref'] != old['ref']):
        raise SystemExit(f'{service} reused image identity differs from accepted state')
PY
server_sha=$(ssh myshop-friend-prod "python3 -c 'import json;print(json.load(open(\"/opt/myshop-friend/runtime/deploy-state.json\"))[\"active\"][\"source_sha\"])'")
[[ "$server_sha" == "$accepted" ]] || die 'production accepted SHA changed while CI ran; this immutable candidate cannot be rebased'

release=/srv/myshop-friend/releases/$sha
stage=/srv/myshop-upload/staging/$sha
ssh myshop-friend-prod "install -d -o root -g root -m 0700 '$release' '$release/candidate'"
ssh myshop-friend-prod python3 - "$sha" <<'PY'
import os,re,sys
from pathlib import Path
sha=sys.argv[1]
if not re.fullmatch(r'[0-9a-f]{40}',sha): raise SystemExit('invalid candidate SHA')
parent=Path('/srv/myshop-upload/staging')
if parent.is_symlink() or not parent.is_dir(): raise SystemExit('unsafe staging root')
stage=parent/sha
if stage.is_symlink() or (stage.exists() and not stage.is_dir()): raise SystemExit('unsafe candidate staging directory')
stage.mkdir(mode=0o700,exist_ok=True)
os.chown(stage,0,0);os.chmod(stage,0o700)
for name in ('.rsync-partial','api.tar','api.record.json','web.tar','web.record.json'):
    if (stage/name).is_symlink(): raise SystemExit('unsafe symlink in candidate staging')
partial=stage/'.rsync-partial'
if partial.exists():
    if not partial.is_dir(): raise SystemExit('unsafe partial directory')
    for service in ('api','web'):
        if (partial/(service+'.tar')).is_symlink(): raise SystemExit('unsafe partial archive symlink')
PY
"$rsync_bin" -a --no-owner --no-group "$manifest" "myshop-friend-prod:$release/release-manifest.json"
ssh myshop-friend-prod "chown root:root '$release/release-manifest.json'; chmod 0600 '$release/release-manifest.json'"
ssh myshop-friend-prod python3 - "$release" "$sha" "$run_id" write-manifest < scripts/release-server-proof.py
git bundle create "$cache/release.bundle" main "^$accepted"
"$rsync_bin" -a --no-owner --no-group "$cache/release.bundle" "myshop-friend-prod:$release/release.bundle"
# This allowlist is the complete production runtime surface. Never send env,
# uploads, or unrelated files. Every staged file comes from the candidate SHA.
files=(
  docker-compose.shared-server.yml Caddyfile.shared-server ops/shared-server/check-storage-health.sh
  scripts/create-offline-image-bundle.py scripts/verify-offline-image-bundle.py
  scripts/import-offline-image-bundle.sh scripts/validate-release-manifest.py
  scripts/verify-transfer-record.py scripts/write-deploy-state.sh scripts/deploy-production.sh
  scripts/finalize-transferred-release.sh scripts/myshop-compose.sh scripts/preflight-shared-server.sh
  scripts/check-production-schema.sh scripts/create-predeploy-backup.sh scripts/cleanup-docker-retention.sh
)
mkdir -p "$cache/candidate"
git archive "$sha" "${files[@]}" | tar -xf - -C "$cache/candidate"
"$rsync_bin" -a --no-owner --no-group "$cache/candidate/" "myshop-friend-prod:$release/candidate/"

for service in api web; do
  changed=$(python3 - "$manifest" "$cache/accepted-state.json" "$service" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]));s=json.load(open(sys.argv[2]))['active'];service=sys.argv[3]
print('1' if m['images'][service]['digest'] != s['images'][service]['digest'] else '0')
PY
)
  [[ "$changed" == 1 ]] || { printf 'REUSE_ACCEPTED service=%s\n' "$service"; continue; }
  trusted="$cache/$run_id/trusted-$service.record.json"
  component="$cache/$run_id/$service"
  mkdir -p "$cache/$run_id"
  if [[ ! -f "$trusted" ]]; then
    record_dir="$cache/$run_id/record-$service"
    mkdir -p "$record_dir"
    if ssh myshop-friend-prod python3 - "$release" "$sha" "$run_id" read-record "$service" \
        < scripts/release-server-proof.py > "$trusted.partial" 2>/dev/null; then
      mv -f -- "$trusted.partial" "$trusted"
      printf 'SERVER_CI_RECORD_REUSED service=%s run=%s\n' "$service" "$run_id"
    elif gh run download "$run_id" --repo YYKX682436/MyShop --name "myshop-transfer-record-$service-$sha" --dir "$record_dir"; then
      rm -f -- "$trusted.partial"
      cp "$record_dir/$service.record.json" "$trusted"
    else
      rm -f -- "$trusted.partial"
      # Older successful runs carried the record only inside the full artifact.
      mkdir -p "$component"
      gh run download "$run_id" --repo YYKX682436/MyShop --name "myshop-transfer-$service-$sha" --dir "$component" \
        || die "trusted $service CI record and artifact are unavailable or expired for run $run_id; preserve this SHA and use a new reviewed commit"
      cp "$component/$service.record.json" "$trusted"
    fi
  fi
  chmod 0600 "$trusted"
  python3 scripts/verify-transfer-record.py "$manifest" "$service" "$trusted" >/dev/null \
    || die "$service trusted CI record does not match the release manifest"
  ssh myshop-friend-prod "umask 077; cat > '$release/.trusted-$service.tmp'; chown root:root '$release/.trusted-$service.tmp'; chmod 0600 '$release/.trusted-$service.tmp'; mv -f '$release/.trusted-$service.tmp' '$release/trusted-$service.record.json'" < "$trusted"
  ssh myshop-friend-prod python3 - "$release" "$sha" "$run_id" write-record "$service" < scripts/release-server-proof.py
  if ssh myshop-friend-prod "python3 '$release/candidate/scripts/verify-transfer-record.py' '$release/release-manifest.json' '$service' '$release/trusted-$service.record.json' --archive '$stage/$service.tar' --match-record '$stage/$service.record.json'" >/dev/null 2>&1; then
    printf 'STAGED_TRANSFER_REUSED service=%s\n' "$service"
    continue
  fi
  if ! python3 scripts/verify-transfer-record.py "$manifest" "$service" "$trusted" --archive "$component/$service.tar" --match-record "$component/$service.record.json" >/dev/null 2>&1; then
    mkdir -p "$component"
    rm -f -- "$component/$service.tar" "$component/$service.record.json"
    gh run download "$run_id" --repo YYKX682436/MyShop --name "myshop-transfer-$service-$sha" --dir "$component" \
      || die "$service CI artifact download failed or expired; retained data can be retried"
    python3 scripts/verify-transfer-record.py "$manifest" "$service" "$trusted" --archive "$component/$service.tar" --match-record "$component/$service.record.json" \
      || die "$service downloaded artifact does not match the trusted CI record"
  else
    printf 'LOCAL_ARTIFACT_REUSED service=%s\n' "$service"
  fi
  transfer_start=$SECONDS
  "$rsync_bin" -avc --mkpath --partial-dir=.rsync-partial --copy-dest=/srv/myshop-upload/basis --stats \
    "$component/$service.tar" "$component/$service.record.json" "myshop-friend-prod:$stage/" \
    || die "$service upload failed; local artifact and server partial retained for retry"
  printf 'MAC_RSYNC_DONE service=%s elapsed=%ss\n' "$service" "$((SECONDS-transfer_start))"
done
ssh myshop-friend-prod "bash '$release/candidate/scripts/finalize-transferred-release.sh' '$sha' '$accepted'" \
  || die "production finalization failed for $sha; retained release can be resumed"
python3 - "$cache" <<'PY'
import shutil,sys
shutil.rmtree(sys.argv[1])
PY
printf 'RELEASE_COMPLETE sha=%s run=%s elapsed=%ss\n' "$sha" "$run_id" "$((SECONDS-started))"

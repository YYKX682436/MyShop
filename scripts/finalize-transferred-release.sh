#!/usr/bin/env bash
# The parent release lock is held over SSH; this lock also excludes legacy deploys.
set -Eeuo pipefail
die() { printf '[myshop-transfer] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 2 )) || die 'usage: finalize-transferred-release.sh <full-sha> <accepted-sha>'
sha=$1 accepted=$2
[[ "$sha" =~ ^[0-9a-f]{40}$ && "$accepted" =~ ^[0-9a-f]{40}$ ]] || die 'invalid SHA'
[[ $(id -u) == 0 ]] || die 'run as root on the MyShop host'
root=/opt/myshop-friend
runtime=$root/runtime
release=/srv/myshop-friend/releases/$sha
stage=/srv/myshop-upload/staging/$sha
state=$runtime/deploy-state.json
env_file=/etc/myshop-friend/myshop.env
manifest=$release/release-manifest.json
bundle=$release/image-bundle.json
candidate=$release/candidate
previous=$release/previous
inputs=$release/verified-inputs
HOST_DEPLOY_LOCK_FILE=/run/myshop-friend-deploy.lock
exec 9>"$HOST_DEPLOY_LOCK_FILE"
flock -n 9 || die 'another deployment or storage lifecycle operation is active'
[[ -f "$manifest" && -f "$state" ]] || die 'manifest or accepted state is missing'
active=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["active"]["source_sha"])' "$state")
[[ "$active" == "$accepted" || "$active" == "$sha" ]] || die 'production accepted SHA changed during transfer'

scripts=(
  create-offline-image-bundle.py verify-offline-image-bundle.py import-offline-image-bundle.sh
  validate-release-manifest.py verify-transfer-record.py write-deploy-state.sh deploy-production.sh
  finalize-transferred-release.sh myshop-compose.sh preflight-shared-server.sh check-production-schema.sh
  create-predeploy-backup.sh cleanup-docker-retention.sh
)
installed=0
restore_unaccepted_files() {
  local status=$? current file
  if (( status != 0 && installed )); then
    current=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["active"]["source_sha"])' "$state" 2>/dev/null || true)
    if [[ "$current" == "$accepted" ]]; then
      for file in "${scripts[@]}"; do
        if [[ -f "$previous/scripts/$file" ]]; then
          install -o root -g root -m 0755 "$previous/scripts/$file" "$root/scripts/$file"
        else
          rm -f -- "$root/scripts/$file"
        fi
      done
      install -o root -g root -m 0644 "$previous/docker-compose.shared-server.yml" "$runtime/docker-compose.shared-server.yml"
      install -o root -g root -m 0644 "$previous/Caddyfile.shared-server" "$runtime/Caddyfile.shared-server"
      install -o root -g root -m 0755 "$previous/check-storage-health.sh" "$root/ops/shared-server/check-storage-health.sh"
      printf 'UNACCEPTED_RUNTIME_FILES_RESTORED\n' >&2
    fi
  fi
}
trap restore_unaccepted_files EXIT

if [[ "$active" == "$accepted" ]]; then
  [[ -d "$stage" && -d "$candidate/scripts" && -f "$release/release.bundle" ]] || die 'candidate staging is missing'
  git --git-dir="$root/release.git" fetch "$release/release.bundle" "$sha" >/dev/null
  git --git-dir="$root/release.git" merge-base --is-ancestor "$accepted" "$sha" || die 'candidate is not an accepted descendant'
  install -d -o root -g root -m 0700 "$inputs"
  for service in api web; do
    changed=$(python3 - "$manifest" "$state" "$service" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]));s=json.load(open(sys.argv[2]))['active'];key=sys.argv[3]
print('1' if m['images'][key]['digest'] != s['images'][key]['digest'] else '0')
PY
)
    [[ "$changed" == 1 ]] || continue
    trusted="$release/trusted-$service.record.json"
    verifier="$candidate/scripts/verify-transfer-record.py"
    python3 "$verifier" "$manifest" "$service" "$trusted" >/dev/null
    if ! python3 "$verifier" "$manifest" "$service" "$trusted" --archive "$inputs/$service.tar" >/dev/null 2>&1; then
      python3 "$verifier" "$manifest" "$service" "$trusted" --archive "$stage/$service.tar" --match-record "$stage/$service.record.json" >/dev/null \
        || die "$service staged transfer differs from the independent CI record"
      cp -- "$stage/$service.tar" "$inputs/.$service.tar.tmp"
      chmod 0600 "$inputs/.$service.tar.tmp"
      mv -f -- "$inputs/.$service.tar.tmp" "$inputs/$service.tar"
      python3 "$verifier" "$manifest" "$service" "$trusted" --archive "$inputs/$service.tar" >/dev/null \
        || die "$service private transfer snapshot failed verification"
    fi
    install -o root -g root -m 0600 "$trusted" "$inputs/$service.record.json"
  done

  if [[ ! -d "$previous" ]]; then
    previous_tmp="$release/.previous.tmp"
    if [[ -d "$previous_tmp" && ! -L "$previous_tmp" ]]; then
      python3 -c 'import shutil,sys;shutil.rmtree(sys.argv[1])' "$previous_tmp"
    fi
    [[ ! -e "$previous_tmp" ]] || die 'unsafe previous configuration snapshot path'
    install -d -o root -g root -m 0700 "$previous_tmp/scripts"
    for file in "${scripts[@]}"; do
      [[ ! -f "$root/scripts/$file" ]] || cp -p -- "$root/scripts/$file" "$previous_tmp/scripts/$file"
    done
    cp -p -- "$runtime/docker-compose.shared-server.yml" "$previous_tmp/docker-compose.shared-server.yml"
    cp -p -- "$runtime/Caddyfile.shared-server" "$previous_tmp/Caddyfile.shared-server"
    cp -p -- "$root/ops/shared-server/check-storage-health.sh" "$previous_tmp/check-storage-health.sh"
    mv -- "$previous_tmp" "$previous"
  fi
  caddy_changed=0
  cmp -s "$candidate/Caddyfile.shared-server" "$runtime/Caddyfile.shared-server" || caddy_changed=1
  installed=1
  for file in "${scripts[@]}"; do
    [[ -f "$candidate/scripts/$file" && ! -L "$candidate/scripts/$file" ]] || die "candidate helper missing: $file"
    install -o root -g root -m 0755 "$candidate/scripts/$file" "$root/scripts/$file"
  done
  for file in docker-compose.shared-server.yml Caddyfile.shared-server; do
    [[ -f "$candidate/$file" && ! -L "$candidate/$file" ]] || die "candidate runtime configuration missing: $file"
    cmp -s "$candidate/$file" "$runtime/$file" || install -o root -g root -m 0644 "$candidate/$file" "$runtime/$file"
  done
  install -o root -g root -m 0755 "$candidate/ops/shared-server/check-storage-health.sh" "$root/ops/shared-server/check-storage-health.sh"
  python3 "$root/scripts/create-offline-image-bundle.py" "$manifest" "$inputs" "$bundle" "$state"
  bash "$root/scripts/import-offline-image-bundle.sh" "$manifest" "$bundle" "$inputs"

  export MYSHOP_RUNTIME_ROOT=$runtime MYSHOP_ENV_FILE=$env_file
  export MYSHOP_COMPOSE_FILE=$runtime/docker-compose.shared-server.yml MYSHOP_COMPOSE_PROJECT_NAME=myshop-friend-prod
  export MYSHOP_SHARED_SERVER=1 MYSHOP_BACKUP_DIR=/srv/myshop-friend/backups/database
  export MYSHOP_STORAGE_MOUNTPOINT=/srv/myshop-friend
  export HOST_STORAGE_HEALTH_SCRIPT=$root/ops/shared-server/check-storage-health.sh
  export HOST_DEPLOY_LOCK_FILE MYSHOP_RUN_HOST_MAINTENANCE=0 MYSHOP_INHERITED_DEPLOY_LOCK=1
  export MYSHOP_RELEASE_REPOSITORY=$root/release.git MYSHOP_APPROVED_REGISTRY=ghcr.io/yykx682436
  export MYSHOP_OFFLINE_IMAGES=1 MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE=$bundle MYSHOP_RELEASE_MANIFEST_FILE=$manifest
  export MYSHOP_SMOKE_BASE_URL=http://127.0.0.1:8082 MYSHOP_SMOKE_HOST_HEADER=mc.mc520.top
  export MYSHOP_CHECK_CADDY_CONFIG=1 MYSHOP_CADDY_CONFIG_CHANGED=$caddy_changed MYSHOP_PREVIOUS_CONFIG_DIR=$previous
  bash "$root/scripts/preflight-shared-server.sh" --mode upgrade
  bash "$root/scripts/deploy-production.sh" "$manifest"
else
  printf 'RECOVER_ACCEPTED_FINALIZATION sha=%s\n' "$sha"
  python3 "$root/scripts/verify-offline-image-bundle.py" "$bundle" --manifest "$manifest" --verify-local >/dev/null \
    || die 'accepted bundle or local image identity cannot be verified'
fi
[[ $(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["active"]["source_sha"])' "$state") == "$sha" ]] || die 'deployment did not accept candidate'

# Resume these operations after an accepted deployment without running deploy again.
for service in api web; do
  trusted="$release/trusted-$service.record.json"
  [[ -f "$trusted" ]] || continue
  verifier="$root/scripts/verify-transfer-record.py"
  if python3 "$verifier" "$manifest" "$service" "$trusted" --archive "/srv/myshop-upload/basis/$service.tar" >/dev/null 2>&1; then
    continue
  fi
  python3 "$verifier" "$manifest" "$service" "$trusted" --archive "$inputs/$service.tar" >/dev/null \
    || die "$service accepted transfer cannot update the rsync basis"
  install -o root -g myshop-upload -m 0640 "$inputs/$service.tar" "/srv/myshop-upload/basis/.$service.tar.$sha"
  mv -f -- "/srv/myshop-upload/basis/.$service.tar.$sha" "/srv/myshop-upload/basis/$service.tar"
done
python3 - "$env_file" "$manifest" "$bundle" <<'PY'
import json, os, sys, tempfile
from pathlib import Path
path, manifest_path, bundle_path = map(Path, sys.argv[1:])
bundle = json.loads(bundle_path.read_text())
updates = {'MYSHOP_API_IMAGE': bundle['images']['api']['archive_ref'],
           'MYSHOP_WEB_IMAGE': bundle['images']['web']['archive_ref'],
           'MYSHOP_RELEASE_MANIFEST_FILE': str(manifest_path), 'MYSHOP_OFFLINE_IMAGES': '0'}
lines = [line for line in path.read_text().splitlines(keepends=True)
         if line.split('=', 1)[0] != 'MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE']
seen = set()
for index, line in enumerate(lines):
    key = line.split('=', 1)[0]
    if key in updates:
        lines[index] = f'{key}={updates[key]}\n'
        seen.add(key)
lines += [f'{key}={value}\n' for key, value in updates.items() if key not in seen]
fd, name = tempfile.mkstemp(prefix='.myshop.env.', dir=path.parent)
try:
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as file:
        file.writelines(lines); file.flush(); os.fsync(file.fileno())
    os.replace(name, path)
finally:
    if os.path.exists(name): os.unlink(name)
PY
printf '%s\n' "$sha" > "$release/.release-complete.tmp"
chmod 0600 "$release/.release-complete.tmp"
mv -f -- "$release/.release-complete.tmp" "$release/release-complete"
python3 - "$stage" "$inputs" "$release/release.bundle" <<'PY'
import shutil, sys
from pathlib import Path
for text in sys.argv[1:3]:
    path = Path(text)
    if path.is_dir() and not path.is_symlink():
        shutil.rmtree(path)
bundle = Path(sys.argv[3])
if bundle.is_file() and not bundle.is_symlink():
    bundle.unlink()
PY
printf 'TRANSFER_RELEASE_ACCEPTED sha=%s\n' "$sha"

#!/usr/bin/env bash
# Run on the MyShop production host after the exact CI archives reach staging.
set -Eeuo pipefail
die() { printf '[myshop-transfer] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 2 )) || die 'usage: finalize-transferred-release.sh <full-sha> <accepted-sha>'
sha=$1 accepted=$2
[[ "$sha" =~ ^[0-9a-f]{40}$ && "$accepted" =~ ^[0-9a-f]{40}$ ]] || die 'invalid SHA'
[[ $(id -u) == 0 ]] || die 'run as root on the MyShop host'
root=/opt/myshop-friend
runtime=$root/runtime
release=/srv/myshop-friend/releases/$sha
staging=/srv/myshop-upload/staging/$sha
state=$runtime/deploy-state.json
env_file=/etc/myshop-friend/myshop.env
manifest=$release/release-manifest.json
bundle=$release/image-bundle.json
[[ -f "$manifest" && -d "$staging" && -f "$state" ]] || die 'manifest, staging, or accepted state is missing'
[[ $(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["active"]["source_sha"])' "$state") == "$accepted" ]] || die 'accepted state changed during transfer'
flock -n /run/myshop-friend-deploy.lock true || die 'another deployment is active'
python3 "$root/scripts/create-offline-image-bundle.py" "$manifest" "$staging" "$bundle" "$state"
bash "$root/scripts/import-offline-image-bundle.sh" "$manifest" "$bundle" "$staging"

export MYSHOP_RUNTIME_ROOT=$runtime MYSHOP_ENV_FILE=$env_file
export MYSHOP_COMPOSE_FILE=$runtime/docker-compose.shared-server.yml MYSHOP_COMPOSE_PROJECT_NAME=myshop-friend-prod
export MYSHOP_SHARED_SERVER=1 MYSHOP_BACKUP_DIR=/srv/myshop-friend/backups/database
export MYSHOP_STORAGE_MOUNTPOINT=/srv/myshop-friend
export HOST_STORAGE_HEALTH_SCRIPT=$root/ops/shared-server/check-storage-health.sh
export HOST_DEPLOY_LOCK_FILE=/run/myshop-friend-deploy.lock MYSHOP_RUN_HOST_MAINTENANCE=0
export MYSHOP_RELEASE_REPOSITORY=$root/release.git MYSHOP_APPROVED_REGISTRY=ghcr.io/yykx682436
export MYSHOP_OFFLINE_IMAGES=1 MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE=$bundle
export MYSHOP_RELEASE_MANIFEST_FILE=$manifest
export MYSHOP_SMOKE_BASE_URL=http://127.0.0.1:8082 MYSHOP_SMOKE_HOST_HEADER=mc.mc520.top
bash "$root/scripts/preflight-shared-server.sh" --mode upgrade
bash "$root/scripts/deploy-production.sh" "$manifest"
[[ $(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["active"]["source_sha"])' "$state") == "$sha" ]] || die 'deployment did not accept the candidate SHA'

# The previous basis stays intact until the candidate is accepted. Replace only
# the changed component, with an atomic rename, for the next rsync copy-dest.
for service in api web; do
  if [[ -f "$staging/$service.tar" ]]; then
    install -o root -g myshop-upload -m 0640 "$staging/$service.tar" "/srv/myshop-upload/basis/.$service.tar.$sha"
    mv -f -- "/srv/myshop-upload/basis/.$service.tar.$sha" "/srv/myshop-upload/basis/$service.tar"
  fi
done
python3 - "$env_file" "$manifest" "$bundle" <<'PY'
import json, os, sys, tempfile
from pathlib import Path
path, manifest_path, bundle_path = map(Path, sys.argv[1:])
bundle = json.loads(bundle_path.read_text())
updates = {
    'MYSHOP_API_IMAGE': bundle['images']['api']['archive_ref'],
    'MYSHOP_WEB_IMAGE': bundle['images']['web']['archive_ref'],
    'MYSHOP_RELEASE_MANIFEST_FILE': str(manifest_path),
    'MYSHOP_OFFLINE_IMAGES': '0',
}
lines = path.read_text().splitlines(keepends=True)
seen = set()
lines = [line for line in lines if line.split('=', 1)[0] != 'MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE']
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
        file.writelines(lines)
        file.flush()
        os.fsync(file.fileno())
    os.replace(name, path)
finally:
    if os.path.exists(name): os.unlink(name)
PY
printf 'TRANSFER_RELEASE_ACCEPTED sha=%s\n' "$sha"

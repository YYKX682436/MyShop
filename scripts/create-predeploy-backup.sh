#!/usr/bin/env bash
set -Eeuo pipefail

(( $# == 2 )) || { printf 'usage: %s <active-source-sha-or-bootstrap> <candidate-manifest>\n' "$0" >&2; exit 2; }
active_sha=$1
manifest=$2
project_name=${MYSHOP_COMPOSE_PROJECT_NAME:-myshop}
backup_dir=${MYSHOP_BACKUP_DIR:-${MYSHOP_DATA_ROOT:-/data/myshop}/backups/database}
[[ "$project_name" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || { printf 'invalid MYSHOP_COMPOSE_PROJECT_NAME\n' >&2; exit 2; }
mkdir -p -- "$backup_dir"
umask 077
stamp=$(date -u +%Y%m%dT%H%M%SZ)
candidate_sha=$(python3 - "$manifest" <<'PY'
import json, sys
print(json.load(open(sys.argv[1], encoding='utf-8'))['source_sha'])
PY
)
final_file="$backup_dir/${project_name}-predeploy-${stamp}-${candidate_sha}.dump"
temporary="${final_file}.partial"
db_id=$(docker ps --filter "label=com.docker.compose.project=$project_name" --filter 'label=com.docker.compose.service=db' -q \
  | awk 'NF {count++; value=$0} END {if (count == 1) print value; else exit 1}') \
  || { printf 'expected exactly one database container for Compose project %s\n' "$project_name" >&2; exit 1; }
rm -f -- "$temporary"
trap 'rm -f -- "$temporary"' EXIT
docker exec -i "$db_id" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' >"$temporary"
[[ -s "$temporary" ]] || { printf 'PostgreSQL backup is empty\n' >&2; exit 1; }
docker exec -i "$db_id" pg_restore -l <"$temporary" >/dev/null
mv -f -- "$temporary" "$final_file"
trap - EXIT
size=$(stat -c '%s' "$final_file")
sha256=$(sha256sum "$final_file" | awk '{print $1}')
database=$(docker exec "$db_id" sh -c 'printf "%s@%s/%s" "$POSTGRES_USER" "${POSTGRES_HOSTNAME:-db}" "$POSTGRES_DB"')
manifest_file="${final_file}.manifest.json"
python3 - "$manifest_file" "$project_name" "$active_sha" "$candidate_sha" "$database" "$final_file" "$size" "$sha256" <<'PY'
import json, os, sys, tempfile
from datetime import datetime, timezone
from pathlib import Path

out, project, active, candidate, database, filename, size, sha256 = sys.argv[1:]
payload = {
    "version": 1,
    "project": project,
    "created_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace('+00:00', 'Z'),
    "source_active_sha": active,
    "candidate_sha": candidate,
    "database_identity": database,
    "filename": filename,
    "size": int(size),
    "sha256": sha256,
    "validation": {"pg_restore_list": "passed"},
}
path = Path(out)
fd, tmp = tempfile.mkstemp(prefix='.backup-manifest.', dir=path.parent)
try:
    with os.fdopen(fd, 'w', encoding='utf-8') as handle:
        json.dump(payload, handle, indent=2, sort_keys=True)
        handle.write('\n')
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(tmp, path)
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)
finally:
    if os.path.exists(tmp):
        os.unlink(tmp)
PY
chmod 0600 -- "$final_file" "$manifest_file"
printf 'MYSHOP_BACKUP_MANIFEST=%s\n' "$manifest_file"

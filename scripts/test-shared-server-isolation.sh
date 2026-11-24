#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
project=myshop-friend-prod
sha=dddddddddddddddddddddddddddddddddddddddd
registry=ghcr.io/yykx682436

fake_bin="$tmp_dir/bin"
mkdir -p "$fake_bin" "$tmp_dir/runtime"
cat >"$fake_bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
mode=${ISOLATION_DOCKER_MODE:-helpers}
project_name=myshop
service=
for arg in "$@"; do
  [[ "$arg" == label=com.docker.compose.project=* ]] && project_name=${arg##*=}
  [[ "$arg" == label=com.docker.compose.service=* ]] && service=${arg##*=}
done
case "${1:-}" in
  compose)
    if [[ "${2:-}" == version ]]; then echo 'Docker Compose version v2.24.0'; exit 0; fi
    printf 'compose-project=%s\n' "${COMPOSE_PROJECT_NAME:-$project_name}" >>"${ISOLATION_LOG:?}"
    printf 'compose-file=%s\n' "${MYSHOP_COMPOSE_FILE:-}" >>"${ISOLATION_LOG:?}"
    exit 0
    ;;
  ps)
    printf 'container-query-project=%s service=%s\n' "$project_name" "${service:-all}" >>"${ISOLATION_LOG:?}"
    if [[ "$mode" == retention && " $* " == *' -aq '* ]]; then printf 'friend-api\nfriend-web\nfriend-db\n'; exit 0; fi
    if [[ " $* " == *' -q '* ]]; then printf '%s-db\n' "$project_name"; exit 0; fi
    printf '%s-%s|%s/myshop-%s:%s\n' "$project_name" "$service" "$REGISTRY" "$service" "$SHA"
    ;;
  exec)
    target=
    for arg in "$@"; do
      case "$arg" in *-db|*-api) target=$arg ;; esac
    done
    printf 'container-exec=%s project=%s\n' "$target" "$project_name" >>"${ISOLATION_LOG:?}"
    args=" $* "
    if [[ "$args" == *'pg_dump '* ]]; then printf 'fixture-postgres-dump\n'; exit 0; fi
    if [[ "$args" == *'pg_restore -l '* ]]; then cat >/dev/null; exit 0; fi
    if [[ "$args" == *'printf "%s@%s/%s"'* ]]; then printf 'myshop@db/myshop\n'; exit 0; fi
    if [[ "$args" == *production-schema-contract.json* ]]; then
      printf '{"version":1,"migration_count":0,"latest_migration_version":0,"tables":{"products":["id"]},"indexes":["idx_products"]}\n'
      exit 0
    fi
    if [[ "$args" == *psql* ]]; then
      cat >/dev/null
      printf 'flyway_failures=0\nflyway_count=0\nflyway_latest=0\ntable=products\ncolumn=products.id\nindex=idx_products\n'
      exit 0
    fi
    exit 0
    ;;
  port)
    printf '127.0.0.1:8082\n'
    ;;
  image)
    case "${2:-}" in
      inspect)
        fmt=${4:-}; ref=${5:-}; tag=${ref##*:}
        if [[ "$tag" == "$SHA" ]]; then
          if [[ "$fmt" == '{{.Id}}' ]]; then printf 'sha256:%s\n' "$(printf '1%.0s' {1..64})"; else printf '%s@sha256:%s\n' "${ref%:*}" "$(printf '1%.0s' {1..64})"; fi
        else
          if [[ "$fmt" == '{{.Id}}' ]]; then printf 'sha256:%s\n' "$(printf '9%.0s' {1..64})"; else printf '%s@sha256:%s\n' "${ref%:*}" "$(printf '9%.0s' {1..64})"; fi
        fi
        ;;
      ls)
        if [[ "$mode" == retention ]]; then
          for svc in api web; do
            printf '%s/myshop-%s|%s|sha256:%s|sha256:accepted\n' "$REGISTRY" "$svc" "$SHA" "$(printf '1%.0s' {1..64})"
            printf '%s/myshop-%s|oldlegacy|sha256:%s|sha256:obsolete\n' "$REGISTRY" "$svc" "$(printf '9%.0s' {1..64})"
          done
          printf 'myshop-api|friend-custom|sha256:%s\n' "$(printf '8%.0s' {1..64})"
          printf 'friend-marketplace/api|latest|sha256:%s\n' "$(printf '7%.0s' {1..64})"
        fi
        ;;
      rm) printf '%s\n' "${3:-}" >>"${ISOLATION_REMOVALS:?}" ;;
      *) exit 99 ;;
    esac
    ;;
  inspect)
    fmt=${3:-}; target=${4:-}
    if [[ "$fmt" == '{{.Image}}' ]]; then printf 'sha256:running\n'; fi
    ;;
  system) echo 'fake-docker-system-df' ;;
  *) exit 99 ;;
esac
DOCKER
cat >"$fake_bin/stat" <<'STAT'
#!/usr/bin/env bash
set -Eeuo pipefail
python3 - "$3" <<'PY'
import pathlib, sys
print(pathlib.Path(sys.argv[1]).stat().st_size)
PY
STAT
cat >"$fake_bin/sha256sum" <<'HASH'
#!/usr/bin/env bash
set -Eeuo pipefail
python3 - "$1" <<'PY'
import hashlib, pathlib, sys
p=pathlib.Path(sys.argv[1])
print(hashlib.sha256(p.read_bytes()).hexdigest(), p)
PY
HASH
cat >"$fake_bin/chmod" <<'CHMOD'
#!/usr/bin/env bash
set -Eeuo pipefail
exit 0
CHMOD
chmod +x "$fake_bin/docker" "$fake_bin/stat" "$fake_bin/sha256sum" "$fake_bin/chmod"

env_file="$tmp_dir/runtime/.env"
compose_file="$tmp_dir/runtime/docker-compose.shared-server.yml"
printf 'name: myshop-friend-prod\n' >"$compose_file"
cat >"$env_file" <<EOF
MYSHOP_COMPOSE_PROJECT_NAME=$project
MYSHOP_COMPOSE_FILE=$compose_file
MYSHOP_API_IMAGE=$registry/myshop-api:$sha
MYSHOP_WEB_IMAGE=$registry/myshop-web:$sha
EOF

PATH="$fake_bin:$PATH" MYSHOP_RUNTIME_ROOT="$tmp_dir/runtime" MYSHOP_ENV_FILE="$env_file" \
  MYSHOP_APPROVED_REGISTRY="$registry" MYSHOP_COMPOSE_FILE="$compose_file" \
  MYSHOP_COMPOSE_PROJECT_NAME="$project" COMPOSE_PROJECT_NAME="$project" \
  ISOLATION_LOG="$tmp_dir/log" REGISTRY="$registry" SHA="$sha" \
  bash "$repo_dir/scripts/myshop-compose.sh" --env-file "$env_file" ps
grep -Fxq "compose-project=$project" "$tmp_dir/log" || fail 'Compose wrapper did not select the dedicated project'
grep -Fxq "compose-file=$compose_file" "$tmp_dir/log" || fail 'Compose wrapper did not select the dedicated Compose file'

manifest="$tmp_dir/manifest.json"
python3 - "$manifest" "$sha" <<'PY'
import json, sys
json.dump({'source_sha':sys.argv[2]}, open(sys.argv[1], 'w'))
PY
backup_dir="$tmp_dir/backups"
PATH="$fake_bin:$PATH" MYSHOP_COMPOSE_PROJECT_NAME="$project" MYSHOP_BACKUP_DIR="$backup_dir" \
  ISOLATION_LOG="$tmp_dir/log" REGISTRY="$registry" SHA="$sha" \
  bash "$repo_dir/scripts/create-predeploy-backup.sh" bootstrap "$manifest" >"$tmp_dir/backup-output"
backup_manifest=$(sed -n 's/^MYSHOP_BACKUP_MANIFEST=//p' "$tmp_dir/backup-output")
python3 - "$backup_manifest" "$project" <<'PY'
import json, sys
record=json.load(open(sys.argv[1], encoding='utf-8'))
assert record['project'] == sys.argv[2]
assert record['validation']['pg_restore_list'] == 'passed'
PY
grep -Fq "container-query-project=$project service=db" "$tmp_dir/log" || fail 'database backup located a container outside the shared project'

PATH="$fake_bin:$PATH" MYSHOP_COMPOSE_PROJECT_NAME="$project" \
  ISOLATION_LOG="$tmp_dir/log" REGISTRY="$registry" SHA="$sha" \
  bash "$repo_dir/scripts/check-production-schema.sh" >/dev/null
grep -Fq "container-query-project=$project service=api" "$tmp_dir/log" || fail 'schema check located API outside the shared project'
grep -Fq "container-query-project=$project service=db" "$tmp_dir/log" || fail 'schema check located database outside the shared project'

state="$tmp_dir/runtime/deploy-state.json"
python3 - "$state" "$sha" "$registry" <<'PY'
import json, sys
sha, registry=sys.argv[2:]
digest='sha256:'+'1'*64
record={'source_sha':sha,'accepted_at':'2026-11-18T00:00:00Z','images':{svc:{'ref':f'{registry}/myshop-{svc}:{sha}','digest':digest} for svc in ('api','web')}}
json.dump({'version':1,'active':record,'rollback':[],'history':[record]},open(sys.argv[1],'w'))
PY
PATH="$fake_bin:$PATH" ISOLATION_DOCKER_MODE=retention \
  MYSHOP_COMPOSE_PROJECT_NAME="$project" MYSHOP_RUNTIME_ROOT="$tmp_dir/runtime" \
  MYSHOP_DEPLOY_STATE="$state" MYSHOP_STORAGE_MOUNTPOINT=/tmp \
  HOST_DEPLOY_LOCK_FILE="$tmp_dir/retention.lock" HOST_DEPLOY_LOCK_HELD=1 \
  ISOLATION_LOG="$tmp_dir/log" ISOLATION_REMOVALS="$tmp_dir/removals" \
  REGISTRY="$registry" SHA="$sha" bash "$repo_dir/scripts/cleanup-docker-retention.sh" >/dev/null
grep -Fxq "$registry/myshop-api:oldlegacy" "$tmp_dir/removals" || fail 'shared retention did not remove obsolete MyShop API image'
if grep -Eq '^(myshop-api:friend-custom|friend-marketplace/api:latest)$' "$tmp_dir/removals"; then fail 'shared retention removed a friend image'; fi

printf 'Compose, database backup, schema lookup, and image retention project-isolation checks passed\n'

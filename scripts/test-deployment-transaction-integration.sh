#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
deploy_script="$repo_dir/scripts/deploy-production.sh"
state_writer="$repo_dir/scripts/write-deploy-state.sh"
retention_script="$repo_dir/scripts/cleanup-docker-retention.sh"
tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }

old_sha=$(printf 'a%.0s' {1..40})
rollback1_sha=$(printf 'b%.0s' {1..40})
rollback2_sha=$(printf 'c%.0s' {1..40})
candidate_sha=$(printf 'd%.0s' {1..40})
registry=ghcr.io/yykx682436
candidate_api_ref="$registry/myshop-api@sha256:$(printf '4%.0s' {1..64})"

base_state="$tmp_dir/base-state.json"
manifest="$tmp_dir/candidate-manifest.json"
python3 - "$base_state" "$manifest" "$old_sha" "$rollback1_sha" "$rollback2_sha" "$candidate_sha" "$registry" <<'PY'
import json
import sys
from pathlib import Path

state_path, manifest_path, old, rollback1, rollback2, candidate, registry = sys.argv[1:]
digests = {
    old: "sha256:" + "1" * 64,
    rollback1: "sha256:" + "2" * 64,
    rollback2: "sha256:" + "3" * 64,
    candidate: "sha256:" + "4" * 64,
}

def record(sha: str, accepted_at: str) -> dict:
    return {
        "source_sha": sha,
        "accepted_at": accepted_at,
        "images": {
            service: {
                "ref": f"{registry}/myshop-{service}:{sha}",
                "digest": digests[sha],
            }
            for service in ("api", "web")
        },
    }

state = {
    "version": 1,
    "active": record(old, "2026-09-24T00:00:00Z"),
    "rollback": [record(rollback1, "2026-09-23T00:00:00Z"), record(rollback2, "2026-09-22T00:00:00Z")],
    "history": [record(old, "2026-09-24T00:00:00Z"), record(rollback1, "2026-09-23T00:00:00Z"), record(rollback2, "2026-09-22T00:00:00Z")],
}
Path(state_path).write_text(json.dumps(state, indent=2, sort_keys=True) + "\n", encoding="utf-8")
manifest = {
    "version": 2,
    "project": "myshop",
    "source_sha": candidate,
    "created_at": "2026-09-24T00:00:00Z",
    "built_services": ["api", "web"],
    "database_migration": True,
    "images": {
        service: {"ref": f"{registry}/myshop-{service}@{digests[candidate]}",
                  "digest": digests[candidate], "source_sha": candidate}
        for service in ("api", "web")
    },
}
Path(manifest_path).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

base_candidate_manifest="$tmp_dir/base-candidate-manifest.json"
cp "$manifest" "$base_candidate_manifest"
offline_bundle="$tmp_dir/offline-image-bundle.json"
python3 - "$manifest" "$offline_bundle" "$registry" <<'PY'
import json, sys
manifest = json.load(open(sys.argv[1], encoding='utf-8'))
registry = sys.argv[3]
images = {}
for service in ('api', 'web'):
    item = manifest['images'][service]
    images[service] = {
        'source_ref': item['ref'],
        'source_digest': item['digest'],
        'source_sha': item['source_sha'],
        'archive_ref': f"{registry}/myshop-{service}:{item['source_sha']}",
        'config_digest': 'sha256:' + ('7' if service == 'api' else '8') * 64,
        'runtime_config_digest': 'sha256:' + ('9' if service == 'api' else 'a') * 64,
        'rootfs_diff_ids': ['sha256:' + ('b' if service == 'api' else 'c') * 64],
        'loaded_image_id': 'sha256:' + ('5' if service == 'api' else '6') * 64,
        'archive': service + '.tar',
        'archive_sha256': '0' * 64,
    }
for name in ('postgres', 'caddy', 'node-exporter'):
    images[name] = {'source_ref': 'unused', 'source_digest': 'unused', 'source_sha': '', 'archive_ref': 'unused', 'config_digest': 'unused', 'runtime_config_digest': 'unused', 'rootfs_diff_ids': [], 'loaded_image_id': '', 'archive': name + '.tar', 'archive_sha256': '0' * 64}
json.dump({'version':4,'source_sha':manifest['source_sha'],'platform':'linux/amd64','images':images}, open(sys.argv[2], 'w'))
PY
cat >"$tmp_dir/offline-verifier.py" <<'OFFLINE_VERIFY'
#!/usr/bin/env python3
import os, sys
if '--get-loaded-image-id' in sys.argv:
    service = sys.argv[sys.argv.index('--get-loaded-image-id') + 1]
    print('sha256:' + ('5' if service == 'api' else '6') * 64)
elif '--get-runtime-ref' in sys.argv:
    service = sys.argv[sys.argv.index('--get-runtime-ref') + 1]
    sha = os.environ.get('ROLLBACK1_SHA') if sys.argv[1] == os.environ.get('FAKE_RECOVERY_BUNDLE') else os.environ.get('CANDIDATE_SHA')
    print(f"{os.environ['FAKE_REGISTRY']}/myshop-{service}:{sha}")
else:
    sys.exit(0)
OFFLINE_VERIFY
[[ ! -x "$tmp_dir/offline-verifier.py" ]] || fail 'Python offline verifier fixture should not require executable mode'
recovery_bundle="$tmp_dir/failed-recovery-bundle.json"
recovery_manifest="$tmp_dir/failed-recovery-manifest.json"
printf '{}\n' >"$recovery_bundle"
printf '{}\n' >"$recovery_manifest"

fake_bin="$tmp_dir/fake-bin"
mkdir -p "$fake_bin"
mkdir -p "$tmp_dir/release.git"

cat >"$fake_bin/git" <<'GIT'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ "${2:-}" == rev-parse ]]; then printf '%s\n' "${FAKE_SHALLOW:-false}"; exit 0; fi
if [[ "${2:-}" == cat-file ]]; then exit "${FAKE_MISSING_COMMIT:-0}"; fi
if [[ "${2:-}" == merge-base ]]; then exit "${FAKE_NOT_ANCESTOR:-0}"; fi
if [[ "${2:-}" == diff ]]; then
  [[ "${FAKE_SOURCE_MIGRATION:-0}" == 1 ]] && exit 1
  exit 0
fi
exit 1
GIT
chmod +x "$fake_bin/git"

cat >"$fake_bin/flock" <<'FLOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
exit 0
FLOCK

cat >"$fake_bin/sleep" <<'SLEEP'
#!/usr/bin/env bash
set -Eeuo pipefail
count_file="${FAKE_ROOT:?}/simulated-sleep-count"
count=$(cat "$count_file" 2>/dev/null || printf 0)
printf '%s\n' "$((count + 1))" >"$count_file"
SLEEP

cat >"$fake_bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail

root=${FAKE_ROOT:?}
old_sha=${OLD_SHA:?}
rollback1_sha=${ROLLBACK1_SHA:?}
rollback2_sha=${ROLLBACK2_SHA:?}
candidate_sha=${CANDIDATE_SHA:?}
registry=${FAKE_REGISTRY:?}
mode=${FAKE_MODE:-deploy}
stage=$(cat "$root/running" 2>/dev/null || printf old)
stage_for_service() {
  local service=$1
  if [[ "$service" == api || "$service" == web ]]; then
    cat "$root/$service-stage" 2>/dev/null || printf '%s' "$stage"
  else
    printf '%s' "$stage"
  fi
}
if [[ "${1:-}" == ps && -n "${FAKE_DOCKER_PROJECT_LOG:-}" ]]; then
  for arg in "$@"; do
    [[ "$arg" == label=com.docker.compose.project=* ]] && printf '%s\n' "${arg##*=}" >>"$FAKE_DOCKER_PROJECT_LOG"
  done
fi

digest_for_sha() {
  case "$1" in
    "$old_sha") printf 'sha256:%s' "$(printf '1%.0s' {1..64})" ;;
    "$rollback1_sha") printf 'sha256:%s' "$(printf '2%.0s' {1..64})" ;;
    "$rollback2_sha") printf 'sha256:%s' "$(printf '3%.0s' {1..64})" ;;
    "$candidate_sha") printf 'sha256:%s' "$(printf '4%.0s' {1..64})" ;;
    *) printf 'sha256:%s' "$(printf '9%.0s' {1..64})" ;;
  esac
}

offline_id_for() {
  case "$1" in
    *myshop-api:*) printf 'sha256:%s' "$(printf '5%.0s' {1..64})" ;;
    *myshop-web:*) printf 'sha256:%s' "$(printf '6%.0s' {1..64})" ;;
    *) digest_for_sha "${1##*:}" ;;
  esac
}

ref_for_service() {
  local service=$1 selected_sha=$old_sha service_stage
  service_stage=$(stage_for_service "$service")
  if [[ "$service_stage" == candidate ]]; then
    selected_sha=$candidate_sha
    if [[ "${FAKE_OFFLINE_MODE:-0}" == 1 ]]; then
      printf '%s/myshop-%s:%s' "$registry" "$service" "$selected_sha"
    else
      printf '%s/myshop-%s@%s' "$registry" "$service" "$(digest_for_sha "$selected_sha")"
    fi
  else
    printf '%s/myshop-%s:%s' "$registry" "$service" "$selected_sha"
  fi
}

source_sha_for_ref() {
  local ref=$1 repo_part tag digest
  repo_part=${ref%%@*}
  if [[ "$repo_part" != "$ref" ]]; then
    digest=${ref##*@}
    for sha in "$candidate_sha" "$old_sha" "$rollback1_sha" "$rollback2_sha"; do
      [[ "$(digest_for_sha "$sha")" == "$digest" ]] && { printf '%s' "$sha"; return; }
    done
    printf '%s' unknown
    return
  fi
  tag=${ref##*:}
  printf '%s' "$tag"
}

service_from_args() {
  local arg
  for arg in "$@"; do
    [[ "$arg" == label=com.docker.compose.service=* ]] && { printf '%s' "${arg##*=}"; return; }
  done
}

container_id() {
  printf 'myshop-%s-%s' "$1" "$(stage_for_service "$1")"
}

if [[ "$mode" == retention ]]; then
  case "${1:-}" in
    ps)
      if [[ " $* " == *" -aq "* || "${2:-}" == -aq ]]; then
        printf 'myshop-api-d\nmyshop-web-d\nmyshop-db\nmyshop-caddy\n'
      else
        service=$(service_from_args "$@")
        [[ -n "$service" ]] || exit 0
        if [[ " $* " == *" -q "* ]]; then
          printf 'myshop-%s-d\n' "$service"
        else
          # Docker 29 may render the IMAGE column as a short local ID for
          # digest-pinned containers. Retention must inspect Config.Image.
          printf 'myshop-%s-d|%s\n' "$service" "$(digest_for_sha "$candidate_sha")"
        fi
      fi
      ;;
    inspect)
      format=${3:-}
      target=${4:-}
      case "$format" in
        '{{.Config.Image}}')
          service=${target#myshop-}
          service=${service%-d}
          ref_for_service "$service"
          ;;
        '{{.Image}}')
          case "$target" in
            myshop-api-d|myshop-web-d) digest_for_sha "$candidate_sha" ;;
            *) printf 'sha256:infra\n' ;;
          esac
          ;;
        *) exit 0 ;;
      esac
      ;;
    image)
      [[ "${2:-}" == inspect || "${2:-}" == ls || "${2:-}" == rm ]] || exit 99
      if [[ "${2:-}" == inspect ]]; then
        format=${4:-}
        target=${5:-}
        source_sha=$(source_sha_for_ref "$target")
        repo=${target%%@*}
        digest=${target#*@}
        [[ "$repo" != "$target" ]] || digest=$(digest_for_sha "$source_sha")
        if [[ -e "$root/removed-${repo##*/}-$source_sha" ]]; then exit 1; fi
        if [[ "$format" == '{{.Id}}' ]]; then
          printf '%s\n' "$(digest_for_sha "$source_sha")"
        else
          printf '%s@%s\n' "$repo" "$digest"
        fi
      elif [[ "${2:-}" == ls ]]; then
        for service in api web; do
          for sha in "$candidate_sha" "$old_sha" "$rollback1_sha" "$rollback2_sha"; do
            printf '%s/myshop-%s|%s|%s|%s\n' "$registry" "$service" "$sha" "$(digest_for_sha "$sha")" "$(digest_for_sha "$sha")"
          done
        done
        if [[ "${MYSHOP_SHARED_SERVER:-0}" == 1 ]]; then
          printf 'myshop-api|friend-custom|sha256:%s|sha256:%s\n' "$(printf '7%.0s' {1..64})" "$(printf '7%.0s' {1..64})"
        fi
      else
        ref=${3:-}
        source_sha=$(source_sha_for_ref "$ref")
        repo=${ref%%@*}
        [[ "$repo" != "$ref" ]] || repo=${ref%:*}
        if [[ "$ref" == "$repo:$rollback2_sha" ]]; then
          # A tag removal also drops the digest alias in this Docker fixture.
          touch "$root/removed-${repo##*/}-$source_sha"
        elif [[ "$ref" == "$repo@$(digest_for_sha "$rollback2_sha")" && -e "$root/removed-${repo##*/}-$source_sha" ]]; then
          exit 1
        fi
        printf '%s\n' "$ref" >>"${FAKE_REMOVAL_LOG:?}"
      fi
      ;;
    system)
      printf 'fake docker system df\n'
      ;;
    *) exit 99 ;;
  esac
  exit 0
fi

case "${1:-}" in
  create)
    ref=${!#}
    printf 'inspection-%s\n' "${ref##*:}"
    ;;
  cp)
    source=${2:-}; destination=${3:-}
    count=55
    latest=55
    tables='{}'
    if [[ "${FAKE_RECOVERY_MEDIA_STAGE:-0}" == 1 ]]; then
      count=64
      latest=64
      tables='{"media_assets":["id","storage_path","processing_status","deleted_at"],"media_references":["asset_id","owner_type","owner_id","field"]}'
    fi
    [[ "${FAKE_RECOVERY_CONTRACT_MISMATCH:-0}" != 1 || "$source" != *"$candidate_sha"* ]] || count=54
    printf '{"version":1,"migration_count":%s,"latest_migration_version":"%s","tables":%s,"indexes":[]}\n' "$count" "$latest" "$tables" >"$destination"
    ;;
  rm) ;;
  tag) ;;
  pull)
    [[ "${DEPLOY_TEST_FAIL:-}" != pull ]] || exit 1
    printf '%s\n' "${2:-}" >>"${FAKE_PULL_LOG:?}"
    if [[ -n "${FAKE_PULL_FAILURES:-}" ]]; then
      pull_count=$(( $(wc -l <"${FAKE_PULL_LOG:?}") ))
      if (( pull_count <= FAKE_PULL_FAILURES )); then
        printf 'simulated temporary registry error on attempt %s\n' "$pull_count" >&2
        exit 1
      fi
    fi
    ;;
  ps)
    if [[ " $* " == *" -aq "* || "${2:-}" == -aq ]]; then
      printf 'myshop-db-%s\nmyshop-api-%s\nmyshop-web-%s\nmyshop-caddy-%s\n' "$stage" "$(stage_for_service api)" "$(stage_for_service web)" "$stage"
    else
      service=$(service_from_args "$@")
      [[ -n "$service" ]] || exit 0
      if [[ "$service" == api && "$(stage_for_service api)" == stopped && "${FAKE_LINGERING_API:-0}" != 1 ]]; then exit 0; fi
      if [[ "$service" == web && "$(stage_for_service web)" == stopped ]]; then exit 0; fi
      if [[ " $* " == *" -q "* ]]; then
        printf '%s\n' "$(container_id "$service")"
      else
        printf '%s|%s\n' "$(container_id "$service")" "$(ref_for_service "$service")"
      fi
    fi
    ;;
  port)
    [[ "${2:-}" == myshop-caddy-* && "${3:-}" == 80/tcp ]] || exit 1
    printf '127.0.0.1:8082\n'
    ;;
  inspect)
    format=${3:-}
    target=${4:-}
    service=${target#myshop-}
    service=${service%-old}
    service=${service%-candidate}
    service_stage=$(stage_for_service "$service")
    case "$format" in
      '{{.Id}}') digest_for_sha "${target##*:}"; printf '\n' ;;
      '{{.Config.Image}}')
        ref=$(ref_for_service "$service")
        if [[ "$service_stage" == candidate && "${DEPLOY_TEST_FAIL:-}" == final-identity ]]; then
          printf '%s-wrong\n' "$ref"
        else
          printf '%s\n' "$ref"
        fi
        ;;
      '{{index .Config.Labels "com.docker.compose.config-hash"}}')
        if [[ "$service" == api ]]; then printf '%s\n' "$(printf 'a%.0s' {1..64})"
        elif [[ "$service" == web ]]; then printf '%s\n' "$(printf 'b%.0s' {1..64})"
        else exit 1; fi
        ;;
      '{{.Image}}')
        sha=$old_sha
        [[ "$service_stage" == candidate ]] && sha=$candidate_sha
        if [[ "$service_stage" == candidate && "${DEPLOY_TEST_FAIL:-}" == final-identity ]]; then
          printf 'sha256:%s\n' "$(printf '8%.0s' {1..64})"
        elif [[ "${FAKE_OFFLINE_MODE:-0}" == 1 ]]; then
          offline_id_for "${registry}/myshop-${service}:${sha}"
          printf '\n'
        else
          digest_for_sha "$sha"
          printf '\n'
        fi
        ;;
      '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}')
        if [[ "$service_stage" == candidate && "$service" == api && -n "${FAKE_CANDIDATE_HEALTH_AFTER:-}" ]]; then
          count=$(cat "$root/candidate-health-checks" 2>/dev/null || printf 0)
          count=$((count + 1))
          printf '%s\n' "$count" >"$root/candidate-health-checks"
          if (( count < FAKE_CANDIDATE_HEALTH_AFTER )); then printf 'starting\n'; else printf 'healthy\n'; fi
        else printf 'healthy\n'; fi
        ;;
      *) printf 'healthy\n' ;;
    esac
    ;;
  image)
    if [[ "${2:-}" == inspect ]]; then
      format=${4:-}
      target=${5:-}
      source_sha=$(source_sha_for_ref "$target")
      repo=${target%%@*}
      digest=${target#*@}
      [[ "$repo" != "$target" ]] || digest=$(digest_for_sha "$source_sha")
      if [[ "$format" == '{{.Id}}' ]]; then
        if [[ "${FAKE_OFFLINE_MODE:-0}" == 1 ]]; then
          offline_id_for "$target"
          printf '\n'
        else
          printf '%s\n' "$(digest_for_sha "$source_sha")"
        fi
      else
        printf '%s@%s\n' "$repo" "$digest"
      fi
    elif [[ "${2:-}" == ls ]]; then
      printf '%s/myshop-api|%s|%s\n' "$registry" "$old_sha" "$(digest_for_sha "$old_sha")"
      printf '%s/myshop-api|%s|%s\n' "$registry" "$candidate_sha" "$(digest_for_sha "$candidate_sha")"
      printf '%s/myshop-web|%s|%s\n' "$registry" "$old_sha" "$(digest_for_sha "$old_sha")"
      printf '%s/myshop-web|%s|%s\n' "$registry" "$candidate_sha" "$(digest_for_sha "$candidate_sha")"
    elif [[ "${2:-}" == rm ]]; then
      printf '%s\n' "${3:-}" >>"${FAKE_REMOVAL_LOG:?}"
    else
      exit 99
    fi
    ;;
  exec)
    [[ "${2:-}" == -i ]] || exit 99
    sql=$(cat)
    if [[ "$sql" == *'V63__media_assets_and_references.sql'* ]]; then
      [[ "${FAKE_RECOVERY_STAGE_UNREADABLE:-0}" != 1 ]] || exit 1
      if [[ "${FAKE_RECOVERY_STAGE_INVALID:-0}" == 1 ]]; then printf '0\n'; else printf '1\n'; fi
      exit 0
    fi
    if [[ "$sql" == *'COUNT(*) FILTER'* ]]; then
      if [[ "${FAKE_RECOVERY_MEDIA_STAGE:-0}" == 1 ]]; then
        if [[ "${FAKE_RECOVERY_DB_MISMATCH:-0}" == 1 ]]; then printf '62|62|0\n'; else printf '63|63|0\n'; fi
      elif [[ "${FAKE_RECOVERY_DB_MISMATCH:-0}" == 1 ]]; then printf '54|54|0\n'; else printf '55|55|0\n'; fi
      exit 0
    fi
    if [[ "${FAKE_SCHEMA_MODE:-}" == unreadable && "$stage" == stopped ]]; then exit 1; fi
    if [[ "${FAKE_SCHEMA_MODE:-}" == changed && "$stage" == stopped ]]; then
      printf '%032d\n' 1
    else
      printf '%032d\n' 0
    fi
    ;;
  system) printf 'fake docker system df\n' ;;
  *) exit 99 ;;
esac
DOCKER

cat >"$tmp_dir/compose.sh" <<'COMPOSE'
#!/usr/bin/env bash
set -Eeuo pipefail
root=${FAKE_ROOT:?}
action=
for arg in "$@"; do
  case "$arg" in config|pull|up|stop) action=$arg ;; esac
done
printf 'compose-project=%s\n' "${MYSHOP_COMPOSE_PROJECT_NAME:-myshop}" >>"${FAKE_PROJECT_LOG:?}"
if [[ "$action" == up && " $* " == *" db "* ]]; then action=db-up; fi
printf '%s\n' "$action" >>"${FAKE_COMPOSE_LOG:?}"
printf '%s|%s|%s|%s\n' "$action" "$*" "${MYSHOP_API_IMAGE:-}" "${MYSHOP_WEB_IMAGE:-}" >>"${FAKE_COMPOSE_DETAIL_LOG:?}"
candidate_api=0
candidate_web=0
[[ "${MYSHOP_API_IMAGE:-}" == *":${CANDIDATE_SHA}" || "${MYSHOP_API_IMAGE:-}" == "$FAKE_REGISTRY/myshop-api@sha256:$(printf '4%.0s' {1..64})" ]] && candidate_api=1
[[ "${MYSHOP_WEB_IMAGE:-}" == *":${CANDIDATE_SHA}" || "${MYSHOP_WEB_IMAGE:-}" == "$FAKE_REGISTRY/myshop-web@sha256:$(printf '4%.0s' {1..64})" ]] && candidate_web=1
candidate=0
(( candidate_api || candidate_web )) && candidate=1 || true
if [[ "$action" == config && " $* " == *" --hash "* ]]; then
  service=${!#}
  selected=0
  if [[ "$service" == api && ( "$candidate_api" == 1 || "${FAKE_CONFIG_API_CHANGED:-0}" == 1 ) ]]; then selected=1; fi
  if [[ "$service" == web && ( "$candidate_web" == 1 || "${FAKE_CONFIG_WEB_CHANGED:-0}" == 1 ) ]]; then selected=1; fi
  if [[ "$service" == api ]]; then base_hash=$(printf 'a%.0s' {1..64}); else base_hash=$(printf 'b%.0s' {1..64}); fi
  if (( selected )); then config_hash=$(printf 'd%.0s' {1..64}); else config_hash=$base_hash; fi
  printf '%s %s\n' "$service" "$config_hash"
  exit 0
fi
write_running() {
  local api web
  api=$(cat "$root/api-stage" 2>/dev/null || printf old)
  web=$(cat "$root/web-stage" 2>/dev/null || printf old)
  if [[ "$api" == stopped && "$web" == stopped ]]; then printf 'stopped\n' >"$root/running"
  elif [[ "$api" == candidate && "$web" == candidate ]]; then printf 'candidate\n' >"$root/running"
  elif [[ "$api" == old && "$web" == old ]]; then printf 'old\n' >"$root/running"
  else printf 'mixed\n' >"$root/running"; fi
}
case "$action" in
  config)
    [[ ! ( "$candidate" == 1 && "${DEPLOY_TEST_FAIL:-}" == compose ) ]] || exit 1
    ;;
  pull) ;;
  db-up)
    [[ "${DEPLOY_TEST_FAIL:-}" != database-start ]]
    ;;
  up)
    [[ ! ( "$candidate" == 1 && "${DEPLOY_TEST_FAIL:-}" == start ) ]] || exit 1
    [[ ! ( "$candidate" == 0 && "${DEPLOY_TEST_FAIL:-}" == rollback-start ) ]] || exit 1
    targets=()
    for arg in "$@"; do [[ "$arg" != api && "$arg" != web ]] || targets+=("$arg"); done
    if (( ${#targets[@]} == 0 )) && [[ "$action" == up ]]; then targets=(api web); fi
    for service in "${targets[@]}"; do
      if [[ "$service" == api ]]; then selected=$candidate_api; else selected=$candidate_web; fi
      if (( selected )); then printf 'candidate\n' >"$root/$service-stage"; else printf 'old\n' >"$root/$service-stage"; fi
    done
    write_running
    ;;
  stop)
    [[ "$candidate" == 1 ]] || exit 1
    if [[ " $* " == *" api web caddy "* ]]; then
      [[ "${FAKE_STOP_FAIL:-0}" != 1 ]] || exit 1
    else
      if [[ "${FAKE_PRE_STOP_PARTIAL_FAIL:-0}" == 1 ]]; then printf 'stopped\n' >"$root/api-stage"; write_running; exit 1; fi
      [[ "${FAKE_PRE_STOP_FAIL:-0}" != 1 ]] || exit 1
    fi
    for service in api web; do
      if [[ " $* " == *" $service "* ]]; then printf 'stopped\n' >"$root/$service-stage"; fi
    done
    write_running
    ;;
  *) exit 99 ;;
esac
COMPOSE

cat >"$tmp_dir/backup.sh" <<'BACKUP'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'backup\n' >>"${FAKE_BACKUP_LOG:?}"
printf 'backup-project=%s\n' "${MYSHOP_COMPOSE_PROJECT_NAME:-myshop}" >>"${FAKE_PROJECT_LOG:?}"
[[ -e "$FAKE_ROOT/recovery-point.dump" ]] || printf 'imported-data-recovery-point\n' >"$FAKE_ROOT/recovery-point.dump"
[[ "${DEPLOY_TEST_FAIL:-}" != backup ]]
BACKUP

cat >"$tmp_dir/schema.sh" <<'SCHEMA'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'schema\n' >>"${FAKE_SCHEMA_LOG:?}"
printf 'schema-project=%s\n' "${MYSHOP_COMPOSE_PROJECT_NAME:-myshop}" >>"${FAKE_PROJECT_LOG:?}"
[[ "${DEPLOY_TEST_FAIL:-}" != schema && "${DEPLOY_TEST_FAIL:-}" != rollback-start ]]
SCHEMA

cat >"$tmp_dir/health.sh" <<'HEALTH'
#!/usr/bin/env bash
set -Eeuo pipefail
count_file="$FAKE_ROOT/health-count"
count=$(cat "$count_file" 2>/dev/null || printf 0)
count=$((count + 1))
printf '%s\n' "$count" >"$count_file"
printf 'health-%s\n' "$count" >>"${FAKE_HEALTH_LOG:?}"
if [[ "${DEPLOY_TEST_FAIL:-}" == candidate-storage && "$count" -eq 2 ]]; then exit 4; fi
if [[ "${DEPLOY_TEST_FAIL:-}" == post-storage && "$count" -eq 3 ]]; then exit 4; fi
exit 0
HEALTH

cat >"$tmp_dir/retention.sh" <<'RETENTION'
#!/usr/bin/env bash
set -Eeuo pipefail
if [[ " $* " == *" --dry-run "* ]]; then dry=yes; else dry=no; fi
printf 'retention held=%s dry=%s args=%s\n' "${HOST_DEPLOY_LOCK_HELD:-}" "$dry" "$*" >>"${FAKE_RETENTION_LOG:?}"
printf 'retention-project=%s\n' "${MYSHOP_COMPOSE_PROJECT_NAME:-myshop}" >>"${FAKE_PROJECT_LOG:?}"
if [[ "${DEPLOY_TEST_FAIL:-}" == retention-preflight && "$dry" == yes ]]; then exit 1; fi
if [[ "${DEPLOY_TEST_FAIL:-}" == retention && "$dry" == no ]]; then exit 1; fi
exit 0
RETENTION

cat >"$fake_bin/curl" <<'CURL'
#!/usr/bin/env bash
set -Eeuo pipefail
url=${!#}
printf '%s\n' "$url" >>"${FAKE_CURL_LOG:?}"
if [[ "$url" == https://mc.mc520.top/* ]]; then exit 0; fi
if [[ "${FAKE_CANDIDATE_DOWN:-0}" == 1 && "$url" == http://127.0.0.1:* ]]; then exit 22; fi
count_file="$FAKE_ROOT/curl-count"
count=$(cat "$count_file" 2>/dev/null || printf 0)
if [[ "${DEPLOY_TEST_FAIL:-}" == smoke && "$count" -eq 0 ]]; then
  printf '1\n' >"$count_file"
  exit 1
fi
printf '%s\n' "$((count + 1))" >"$count_file"
exit 0
CURL

chmod +x "$fake_bin/docker" "$fake_bin/flock" "$fake_bin/sleep" "$tmp_dir/compose.sh" "$tmp_dir/backup.sh" "$tmp_dir/schema.sh" "$tmp_dir/health.sh" "$tmp_dir/retention.sh" "$fake_bin/curl"

case_root="$tmp_dir/case"
reset_case() {
  rm -rf "$case_root"
  mkdir -p "$case_root/candidates" "$case_root/maintenance"
  cp "$base_state" "$case_root/deploy-state.json"
  rm -f "$case_root/accepted-images.env"
  printf 'old\n' >"$case_root/running"
  printf 'old\n' >"$case_root/api-stage"
  printf 'old\n' >"$case_root/web-stage"
  printf 'PUBLIC_SITE_URL=http://myshop.test\n' >"$case_root/.env"
  : >"$case_root/pull.log"
  : >"$case_root/removal.log"
  : >"$case_root/backup.log"
  : >"$case_root/schema.log"
  : >"$case_root/health.log"
  : >"$case_root/retention.log"
  : >"$case_root/docker-projects.log"
  : >"$case_root/deploy-curl.log"
  : >"$case_root/helper-projects.log"
  : >"$case_root/compose.log"
  : >"$case_root/compose-detail.log"
  rm -f "$case_root/health-count" "$case_root/curl-count" "$case_root/recovery-point.dump"
}

run_deploy() {
  PATH="$fake_bin:$PATH" \
    FAKE_ROOT="$case_root" \
    OLD_SHA="$old_sha" \
    ROLLBACK1_SHA="$rollback1_sha" \
    ROLLBACK2_SHA="$rollback2_sha" \
    CANDIDATE_SHA="$candidate_sha" \
    FAKE_REGISTRY="$registry" \
    FAKE_PULL_LOG="$case_root/pull.log" \
    FAKE_COMPOSE_LOG="$case_root/compose.log" \
    FAKE_COMPOSE_DETAIL_LOG="$case_root/compose-detail.log" \
    FAKE_REMOVAL_LOG="$case_root/removal.log" \
    FAKE_BACKUP_LOG="$case_root/backup.log" \
    FAKE_SCHEMA_LOG="$case_root/schema.log" \
    FAKE_HEALTH_LOG="$case_root/health.log" \
    FAKE_RETENTION_LOG="$case_root/retention.log" \
    FAKE_DOCKER_PROJECT_LOG="$case_root/docker-projects.log" \
    FAKE_PROJECT_LOG="$case_root/helper-projects.log" \
    FAKE_CURL_LOG="$case_root/deploy-curl.log" \
    FAKE_CANDIDATE_DOWN="${FAKE_CANDIDATE_DOWN:-0}" \
    FAKE_OFFLINE_MODE="${FAKE_OFFLINE_TEST:-0}" \
    FAKE_RECOVERY_BUNDLE="$recovery_bundle" \
    MYSHOP_RUNTIME_ROOT="$case_root" \
    MYSHOP_DEPLOY_STATE="$case_root/deploy-state.json" \
    MYSHOP_ENV_FILE="$case_root/.env" \
    MYSHOP_COMPOSE_PROJECT_NAME="${FAKE_SHARED_TEST:+myshop-friend-prod}" \
    MYSHOP_COMPOSE_FILE="${FAKE_SHARED_TEST:+$case_root/docker-compose.shared-server.yml}" \
    MYSHOP_SHARED_SERVER="${FAKE_SHARED_TEST:-0}" \
    MYSHOP_RELEASE_REPOSITORY="$tmp_dir/release.git" \
    MYSHOP_RUN_HOST_MAINTENANCE="${FAKE_SHARED_TEST:+0}" \
    MYSHOP_DATA_ROOT="${FAKE_SHARED_TEST:+/srv/myshop-friend}" \
    MYSHOP_BACKUP_DIR="${FAKE_SHARED_TEST:+$case_root/database-backups}" \
    MYSHOP_BAOTA_PROXY_SOURCE_CONFIRMED="${FAKE_BAOTA_PROXY_CONFIRMED-${FAKE_SHARED_TEST:+1}}" \
    MYSHOP_BAOTA_NGINX_PROXY_CIDR="${FAKE_BAOTA_PROXY_CIDR-${FAKE_SHARED_TEST:+172.29.0.1/32}}" \
    MYSHOP_SMOKE_BASE_URL="${FAKE_SHARED_TEST:+http://127.0.0.1:8082}" \
    MYSHOP_SMOKE_HOST_HEADER="${FAKE_SHARED_TEST:+${FAKE_CANDIDATE_HOST:-mc.mc520.top}}" \
    MYSHOP_COMPOSE_WRAPPER="$tmp_dir/compose.sh" \
    MYSHOP_STATE_WRITER="$state_writer" \
    MYSHOP_SCHEMA_SCRIPT="$tmp_dir/schema.sh" \
    MYSHOP_BACKUP_SCRIPT="$tmp_dir/backup.sh" \
    MYSHOP_RETENTION_SCRIPT="$tmp_dir/retention.sh" \
    HOST_STORAGE_HEALTH_SCRIPT="$tmp_dir/health.sh" \
    HOST_DEPLOY_LOCK_FILE="$case_root/deploy.lock" \
    MYSHOP_APPROVED_REGISTRY="$registry" \
    MYSHOP_OFFLINE_IMAGES="${FAKE_OFFLINE_TEST:-0}" \
    MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE="${FAKE_OFFLINE_TEST:+$offline_bundle}" \
    MYSHOP_OFFLINE_IMAGE_VERIFIER="${FAKE_OFFLINE_TEST:+$tmp_dir/offline-verifier.py}" \
    MYSHOP_FORWARD_RECOVERY_BUNDLE_FILE="${FAKE_OFFLINE_TEST:+$recovery_bundle}" \
    MYSHOP_FORWARD_RECOVERY_MANIFEST_FILE="${FAKE_OFFLINE_TEST:+$recovery_manifest}" \
    MYSHOP_MAINTENANCE_DIR="$case_root/maintenance" \
    bash "$deploy_script" "$manifest"
}

write_scope_manifest() {
  local scope=$1 path=$2
  python3 - "$path" "$scope" "$candidate_sha" "$old_sha" "$registry" <<'PY'
import json, sys
path, scope, release, old, registry = sys.argv[1:]
digests = {"old": "sha256:" + "1" * 64, "candidate": "sha256:" + "4" * 64}
built = [] if scope == "noop" else [scope]
images = {}
for service in ("api", "web"):
    changed = service in built
    image_sha = release if changed else old
    digest = digests["candidate" if changed else "old"]
    images[service] = {"ref": f"{registry}/myshop-{service}@{digest}",
                       "digest": digest, "source_sha": image_sha}
value = {"version": 2, "project": "myshop", "source_sha": release,
         "created_at": "2026-09-25T00:00:00Z", "built_services": built,
         "database_migration": False, "images": images}
open(path, "w", encoding="utf-8").write(json.dumps(value, sort_keys=True) + "\n")
PY
}

write_scope_bundle() {
  local path=$1
  python3 - "$manifest" "$offline_bundle" "$registry" <<'PY'
import json, sys
manifest_path, bundle_path, registry = sys.argv[1:]
manifest = json.load(open(manifest_path, encoding="utf-8"))
bundle = json.load(open(bundle_path, encoding="utf-8"))
bundle["source_sha"] = manifest["source_sha"]
for service in ("api", "web"):
    release = manifest["images"][service]
    bundle["images"][service].update({
        "source_ref": release["ref"], "source_digest": release["digest"],
        "source_sha": release["source_sha"],
        "archive_ref": f"{registry}/myshop-{service}:{release['source_sha']}",
    })
with open(bundle_path, "w", encoding="utf-8") as handle:
    json.dump(bundle, handle)
PY
}

assert_pre_commit_failure() {
  local hook=$1 output status
  reset_case
  set +e
  output=$(DEPLOY_TEST_FAIL="$hook" run_deploy 2>&1)
  status=$?
  set -e
  (( status != 0 )) || fail "$hook unexpectedly succeeded"
  if ! grep -Fq 'DEPLOYMENT FAILED / ROLLED BACK' <<<"$output"; then
    printf '%s\n' "$output" >&2
    fail "$hook did not roll back"
  fi
  cmp -s "$base_state" "$case_root/deploy-state.json" || fail "$hook changed accepted state"
  [[ ! -e "$case_root/accepted-images.env" ]] || fail "$hook recreated a second accepted image state"
  [[ "$(<"$case_root/running")" == old ]] || fail "$hook did not restore old stack"
  [[ -f "$case_root/candidates/$candidate_sha.failed.json" ]] || fail "$hook did not mark candidate FAILED"
  ! grep -Ev 'dry=yes' "$case_root/retention.log" | grep -q . || fail "$hook executed destructive retention"
  [[ ! -e "$case_root/maintenance/$candidate_sha.failed.json" ]] || fail "$hook wrote a post-commit failure record"
  printf 'PRE-COMMIT %-22s status=%s state=unchanged running=old retention=non-destructive\n' "$hook" "$status"
}

for unsafe_history in FAKE_SHALLOW FAKE_MISSING_COMMIT FAKE_NOT_ANCESTOR; do
  reset_case
  printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
  set +e
  case "$unsafe_history" in
    FAKE_SHALLOW) output=$(FAKE_SHARED_TEST=1 FAKE_SHALLOW=true run_deploy 2>&1) ;;
    FAKE_MISSING_COMMIT) output=$(FAKE_SHARED_TEST=1 FAKE_MISSING_COMMIT=1 run_deploy 2>&1) ;;
    FAKE_NOT_ANCESTOR) output=$(FAKE_SHARED_TEST=1 FAKE_NOT_ANCESTOR=1 run_deploy 2>&1) ;;
  esac
  status=$?
  set -e
  (( status != 0 )) || fail "$unsafe_history unexpectedly allowed deployment"
  cmp -s "$base_state" "$case_root/deploy-state.json" || fail "$unsafe_history changed accepted state"
  [[ ! -s "$case_root/backup.log" && ! -s "$case_root/compose.log" ]] || fail "$unsafe_history changed production"
done
pass 'shallow, missing, and non-ancestor histories cannot deploy'

printf 'PRE-COMMIT FAILURE REGRESSION\n'
for hook in pull backup compose start health-after-start schema smoke candidate-storage final-identity retention-preflight; do
  assert_pre_commit_failure "$hook"
done
pass 'all ten pre-commit failure paths restore old state and stack without destructive retention'

for stop_failure in FAKE_PRE_STOP_FAIL FAKE_LINGERING_API; do
  reset_case
  if [[ "$stop_failure" == FAKE_PRE_STOP_FAIL ]]; then
    if output=$(FAKE_PRE_STOP_FAIL=1 run_deploy 2>&1); then status=0; else status=$?; fi
  else
    if output=$(FAKE_LINGERING_API=1 run_deploy 2>&1); then status=0; else status=$?; fi
  fi
  (( status != 0 )) || fail "$stop_failure unexpectedly started the candidate"
  [[ "$(<"$case_root/running")" == old ]] || fail "$stop_failure did not restore the old API"
  cmp -s "$base_state" "$case_root/deploy-state.json" || fail "$stop_failure changed accepted state"
  if grep -F "up|" "$case_root/compose-detail.log" | grep -Fq "$candidate_api_ref"; then
    fail "$stop_failure started the candidate API"
  fi
  (( $(grep -c '^up$' "$case_root/compose.log") >= 1 )) || fail "$stop_failure did not restore the previous stack"
done
pass 'candidate migration waits for all old API instances to stop'

# The CI baseline may include a migration that production has not accepted.
# The manifest's CI-relative bit is false here; deployment must infer the
# accepted-to-candidate migration and stop the old API before starting it.
reset_case
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
write_scope_manifest api "$manifest"
if output=$(FAKE_SHARED_TEST=1 FAKE_SOURCE_MIGRATION=1 run_deploy 2>&1); then status=0; else status=$?; fi
(( status == 0 )) || { printf '%s\n' "$output" >&2; fail 'accepted-to-candidate migration was rejected'; }
grep -Fq 'DATABASE_MIGRATION_INFERRED_FROM_ACCEPTED_RANGE' <<<"$output" || fail 'deployment did not infer the migration missed by the CI baseline'
[[ -s "$case_root/backup.log" ]] || fail 'inferred migration skipped the required database backup'
stop_line=$(grep -n '^stop|' "$case_root/compose-detail.log" | head -n 1 | cut -d: -f1)
api_up_line=$(grep -n '^up|' "$case_root/compose-detail.log" | grep -F -- '--no-deps api' | head -n 1 | cut -d: -f1)
[[ "$stop_line" =~ ^[0-9]+$ && "$api_up_line" =~ ^[0-9]+$ && "$stop_line" -lt "$api_up_line" ]] \
  || fail 'inferred migration did not stop API before candidate startup'
! grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps web' || fail 'API migration update recreated Web'
pass 'production accepted baseline detects a migration hidden by the newer successful CI baseline'
cp "$base_candidate_manifest" "$manifest"

reset_case
if output=$(FAKE_CANDIDATE_HEALTH_AFTER=35 run_deploy 2>&1); then status=0; else status=$?; fi
(( status == 0 )) || { printf '%s\n' "$output" >&2; fail 'slow healthy candidate was rejected'; }
(( $(<"$case_root/candidate-health-checks") >= 35 )) || fail "candidate health did not wait beyond the old limit: observed $(cat "$case_root/candidate-health-checks" 2>/dev/null || printf missing)"
[[ "$(<"$case_root/simulated-sleep-count")" == 34 ]] || fail 'slow candidate did not pass the 60-second point'
grep -Fq 'DEPLOYMENT ACCEPTED' <<<"$output" || fail 'slow candidate was not accepted after becoming healthy'
pass 'candidate health can succeed after more than 60 seconds without resetting the deployment'

reset_case
if output=$(FAKE_CANDIDATE_HEALTH_AFTER=181 run_deploy 2>&1); then status=0; else status=$?; fi
(( status == 1 )) || { printf '%s\n' "$output" >&2; fail 'candidate health timeout was not rejected'; }
[[ "$(<"$case_root/candidate-health-checks")" == 180 ]] || fail 'candidate health timeout did not use its full bound'
[[ "$(<"$case_root/simulated-sleep-count")" == 179 ]] || fail 'candidate health timeout wait was incorrect'
grep -Fq 'DEPLOYMENT FAILED / ROLLED BACK' <<<"$output" || fail 'health timeout did not use the existing rollback path'
cmp -s "$base_state" "$case_root/deploy-state.json" || fail 'health timeout changed accepted state'
[[ "$(<"$case_root/running")" == old ]] || fail 'health timeout did not restore the old API'
pass 'candidate health remains bounded and rolls back when the unchanged schema is safe'

reset_case
if output=$(FAKE_CANDIDATE_HEALTH_AFTER=181 FAKE_SCHEMA_MODE=changed run_deploy 2>&1); then status=0; else status=$?; fi
(( status == 2 )) || { printf '%s\n' "$output" >&2; fail 'changed-schema health timeout did not block rollback'; }
grep -Fq 'CRITICAL_SCHEMA_ROLLBACK_BLOCKED' <<<"$output" || fail 'changed-schema timeout was not reported'
cmp -s "$base_state" "$case_root/deploy-state.json" || fail 'changed-schema timeout changed accepted state'
[[ "$(<"$case_root/running")" == stopped && "$(grep -c '^up$' "$case_root/compose.log")" == 1 ]] || fail 'changed-schema timeout restarted the old API'
pass 'a health timeout after migration preserves the database and keeps the old API stopped'

for prior_state in accepted legacy; do
  reset_case
  [[ "$prior_state" == accepted ]] || rm -f "$case_root/deploy-state.json"
  if output=$(FAKE_PRE_STOP_PARTIAL_FAIL=1 run_deploy 2>&1); then status=0; else status=$?; fi
  (( status == 1 )) || { printf '%s\n' "$output" >&2; fail "$prior_state partial pre-stop did not recover"; }
  grep -Fq 'DEPLOYMENT FAILED / ROLLED BACK' <<<"$output" || { printf '%s\n' "$output" >&2; fail "$prior_state partial pre-stop was not reported"; }
  [[ "$(<"$case_root/running")" == old ]] || fail "$prior_state partial pre-stop left the old API down"
  (( $(grep -c '^up$' "$case_root/compose.log") >= 1 )) || fail "$prior_state partial pre-stop did not restart the baseline"
  if grep -F "up|" "$case_root/compose-detail.log" | grep -Fq "$candidate_api_ref"; then
    fail "$prior_state partial pre-stop started the candidate API"
  fi
  if [[ "$prior_state" == accepted ]]; then cmp -s "$base_state" "$case_root/deploy-state.json" || fail 'accepted state changed after partial stop'
  else [[ ! -e "$case_root/deploy-state.json" ]] || fail 'legacy pre-stop failure created accepted state'; fi
done
pass 'a partial pre-stop restores both accepted and legacy baselines before candidate startup'

for schema_mode in changed unreadable; do
  reset_case
  printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
  set +e
  blocked_output=$(FAKE_SHARED_TEST=1 FAKE_SCHEMA_MODE="$schema_mode" DEPLOY_TEST_FAIL=health-after-start run_deploy 2>&1)
  blocked_status=$?
  set -e
  (( blocked_status == 2 )) || fail "$schema_mode schema did not block rollback"
  grep -Fq 'CRITICAL_SCHEMA_ROLLBACK_BLOCKED' <<<"$blocked_output" || fail "$schema_mode schema was not reported"
  cmp -s "$base_state" "$case_root/deploy-state.json" || fail "$schema_mode schema changed accepted state"
  [[ "$(<"$case_root/running")" == stopped ]] || fail "$schema_mode schema did not stop app-facing services"
  [[ -f "$case_root/recovery-point.dump" ]] || fail "$schema_mode schema lost the recovery point"
  [[ "$(grep -c '^up$' "$case_root/compose.log")" == 1 ]] || fail "$schema_mode schema restarted the old API"
  ! grep -Ev 'dry=yes' "$case_root/retention.log" | grep -q . || fail "$schema_mode schema executed destructive retention"
done
pass 'changed or unreadable Flyway history blocks old API rollback and preserves recovery data'

# Exercise the production deployment transaction with a separately tested
# preflight stub: forward recovery may only proceed after that gate succeeds,
# and it must never start the older API if the new candidate fails.
recovery_scripts="$tmp_dir/recovery-scripts"
mkdir -p "$recovery_scripts"
cp "$deploy_script" "$recovery_scripts/deploy-production.sh"
cp "$repo_dir/scripts/validate-release-manifest.py" "$recovery_scripts/validate-release-manifest.py"
cat >"$recovery_scripts/preflight-shared-server.sh" <<'RECOVERY_PREFLIGHT'
#!/usr/bin/env bash
set -Eeuo pipefail
[[ "$*" == '--mode forward-recovery' ]]
[[ "${MYSHOP_FORWARD_RECOVERY_FAILED_SHA:-}" == "$ROLLBACK1_SHA" ]]
[[ "${MYSHOP_API_IMAGE:-}" == "$FAKE_REGISTRY/myshop-api:$ROLLBACK1_SHA" ]]
[[ "${MYSHOP_WEB_IMAGE:-}" == "$FAKE_REGISTRY/myshop-web:$ROLLBACK1_SHA" ]]
printf 'recovery-preflight\n' >>"$FAKE_ROOT/recovery-preflight.log"
[[ "${FAKE_RECOVERY_PREFLIGHT_FAIL:-0}" != 1 ]]
RECOVERY_PREFLIGHT
chmod +x "$recovery_scripts/preflight-shared-server.sh"
normal_deploy_script=$deploy_script
deploy_script="$recovery_scripts/deploy-production.sh"

reset_case
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
printf 'stopped\n' >"$case_root/running"
if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi
(( recovery_status == 0 )) || { printf '%s\n' "$recovery_output" >&2; fail 'forward recovery candidate failed'; }
grep -Fq 'FORWARD_RECOVERY_VERIFIED' <<<"$recovery_output" || fail 'forward recovery gate was not recorded'
grep -Fxq 'recovery-preflight' "$case_root/recovery-preflight.log" || fail 'forward recovery skipped preflight'
[[ "$(<"$case_root/running")" == candidate && -f "$case_root/recovery-point.dump" ]] || fail 'forward recovery did not back up and start the candidate'
python3 - "$case_root/deploy-state.json" "$candidate_sha" <<'PY'
import json, sys
assert json.load(open(sys.argv[1]))['active']['source_sha'] == sys.argv[2]
PY
pass 'forward recovery verifies its gate and promotes only the healthy new candidate'

reset_case
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
printf 'stopped\n' >"$case_root/running"
if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_RECOVERY_MEDIA_STAGE=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi
(( recovery_status == 0 )) || { printf '%s\n' "$recovery_output" >&2; fail 'V63 staged media recovery failed'; }
grep -Fq 'FORWARD_RECOVERY_MEDIA_INDEX version=63 next=64' <<<"$recovery_output" || fail 'V63 staged recovery was not recorded'
[[ "$(<"$case_root/running")" == candidate && -f "$case_root/recovery-point.dump" ]] || fail 'V63 recovery did not start the new candidate with a recovery point'
pass 'a matching V64 release can resume the verified V63 media index and complete forward recovery'

for invalid_stage in FAKE_RECOVERY_STAGE_INVALID FAKE_RECOVERY_STAGE_UNREADABLE FAKE_RECOVERY_DB_MISMATCH FAKE_RECOVERY_CONTRACT_MISMATCH; do
  reset_case
  printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
  printf 'stopped\n' >"$case_root/running"
  case "$invalid_stage" in
    FAKE_RECOVERY_STAGE_INVALID) if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_RECOVERY_MEDIA_STAGE=1 FAKE_RECOVERY_STAGE_INVALID=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi ;;
    FAKE_RECOVERY_STAGE_UNREADABLE) if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_RECOVERY_MEDIA_STAGE=1 FAKE_RECOVERY_STAGE_UNREADABLE=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi ;;
    FAKE_RECOVERY_DB_MISMATCH) if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_RECOVERY_MEDIA_STAGE=1 FAKE_RECOVERY_DB_MISMATCH=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi ;;
    FAKE_RECOVERY_CONTRACT_MISMATCH) if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_RECOVERY_MEDIA_STAGE=1 FAKE_RECOVERY_CONTRACT_MISMATCH=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi ;;
  esac
  (( recovery_status == 2 )) || fail "V63 recovery accepted $invalid_stage"
  grep -Fq 'reason=schema-compatibility' <<<"$recovery_output" || fail "V63 recovery did not report $invalid_stage"
  ! grep -Fxq up "$case_root/compose.log" || fail "V63 $invalid_stage started an API"
  cmp -s "$base_state" "$case_root/deploy-state.json" || fail "V63 $invalid_stage changed accepted state"
done
pass 'V63 recovery rejects invalid or unreadable history, other versions, and mismatched image contracts'

reset_case
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
printf 'stopped\n' >"$case_root/running"
if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_RECOVERY_MEDIA_STAGE=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" DEPLOY_TEST_FAIL=health-after-start run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi
(( recovery_status == 2 )) || fail 'failed V63 forward recovery did not block rollback'
grep -Fq 'CRITICAL_SCHEMA_ROLLBACK_BLOCKED' <<<"$recovery_output" || fail 'failed V63 recovery was not reported'
[[ "$(<"$case_root/running")" == stopped && "$(grep -c '^up$' "$case_root/compose.log")" == 1 ]] || fail 'failed V63 recovery restarted the older API'
cmp -s "$base_state" "$case_root/deploy-state.json" || fail 'failed V63 recovery changed accepted state'
pass 'failed V63 forward recovery leaves the old API stopped and accepted state unchanged'

reset_case
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
printf 'stopped\n' >"$case_root/running"
if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" DEPLOY_TEST_FAIL=health-after-start run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi
(( recovery_status == 2 )) || fail 'failed forward recovery did not block rollback'
grep -Fq 'CRITICAL_SCHEMA_ROLLBACK_BLOCKED' <<<"$recovery_output" || fail 'failed forward recovery was not reported'
cmp -s "$base_state" "$case_root/deploy-state.json" || fail 'failed forward recovery changed accepted state'
[[ "$(<"$case_root/running")" == stopped && "$(grep -c '^up$' "$case_root/compose.log")" == 1 ]] || fail 'failed forward recovery restarted the older API'
[[ -f "$case_root/recovery-point.dump" ]] || fail 'failed forward recovery lost its recovery point'
pass 'failed forward recovery stops the candidate and never starts the older API'

reset_case
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
printf 'stopped\n' >"$case_root/running"
if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_RECOVERY_PREFLIGHT_FAIL=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi
(( recovery_status != 0 )) || fail 'failed recovery preflight was ignored'
grep -Fq 'forward recovery preflight failed' <<<"$recovery_output" || fail 'failed recovery preflight was not reported'
[[ ! -s "$case_root/backup.log" && ! -s "$case_root/compose.log" ]] || fail 'failed recovery preflight reached mutation'
cmp -s "$base_state" "$case_root/deploy-state.json" || fail 'failed recovery preflight changed accepted state'
pass 'failed forward recovery preflight blocks before mutation'

reset_case
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
printf 'stopped\n' >"$case_root/running"
saved_manifest=$manifest
manifest="$tmp_dir/old-recovery-manifest.json"
python3 - "$saved_manifest" "$manifest" "$old_sha" <<'PY'
import json, sys
data = json.load(open(sys.argv[1])); old = sys.argv[3]
data['source_sha'] = old
for service in ('api', 'web'):
    data['images'][service]['ref'] = f'ghcr.io/yykx682436/myshop-{service}:{old}'
json.dump(data, open(sys.argv[2], 'w'))
PY
if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi
manifest=$saved_manifest
(( recovery_status != 0 )) || fail 'forward recovery accepted the older baseline API'
[[ ! -s "$case_root/backup.log" && ! -s "$case_root/compose.log" ]] || fail 'older baseline API reached mutation during recovery'
pass 'forward recovery rejects the older accepted release'

for mismatch in contract database; do
  reset_case
  printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
  printf 'stopped\n' >"$case_root/running"
  if [[ "$mismatch" == contract ]]; then
    if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_RECOVERY_CONTRACT_MISMATCH=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi
  else
    if recovery_output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_RECOVERY_DB_MISMATCH=1 MYSHOP_FORWARD_RECOVERY_FAILED_SHA="$rollback1_sha" run_deploy 2>&1); then recovery_status=0; else recovery_status=$?; fi
  fi
  (( recovery_status == 2 )) || fail "forward recovery accepted mismatched $mismatch schema"
  grep -Fq 'reason=schema-compatibility' <<<"$recovery_output" || fail "forward recovery did not report mismatched $mismatch schema"
  ! grep -Fxq up "$case_root/compose.log" || fail "mismatched $mismatch schema started an API"
  cmp -s "$base_state" "$case_root/deploy-state.json" || fail "mismatched $mismatch schema changed accepted state"
done
pass 'forward recovery refuses mismatched image contracts and database migration history'
deploy_script=$normal_deploy_script

printf 'INDEPENDENT APP UPDATE REGRESSION\n'
saved_manifest=$manifest
manifest="$tmp_dir/web-only-manifest.json"
write_scope_manifest web "$manifest"
reset_case
if output=$(run_deploy 2>&1); then update_status=0; else update_status=$?; fi
(( update_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'Web-only release failed'; }
[[ "$(<"$case_root/api-stage")" == old && "$(<"$case_root/web-stage")" == candidate ]] || fail 'Web-only release changed API runtime state'
[[ ! -s "$case_root/backup.log" && ! -s "$case_root/schema.log" ]] || fail 'Web-only release touched database backup or schema checks'
[[ "$(wc -l <"$case_root/pull.log" | tr -d ' ')" == 1 ]] || fail 'Web-only release pulled more than one image'
grep -Fq "$registry/myshop-web@sha256:$(printf '4%.0s' {1..64})" "$case_root/pull.log" || fail 'Web-only release did not pull its immutable Web digest'
! grep -Fq "$registry/myshop-api@" "$case_root/pull.log" || fail 'Web-only release pulled an API image'
[[ "$(grep -c '^up|' "$case_root/compose-detail.log")" == 1 ]] || fail 'Web-only release started more than one app service'
grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps web' || fail 'Web-only release did not target only Web'
! grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps api' || fail 'Web-only release restarted API'
pass 'pure Web update pulls and starts Web only; API and database remain untouched'

reset_case
if output=$(FAKE_PULL_FAILURES=1 run_deploy 2>&1); then update_status=0; else update_status=$?; fi
(( update_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'temporary GHCR failure did not recover'; }
[[ "$(wc -l <"$case_root/pull.log" | tr -d ' ')" == 2 ]] || fail 'temporary GHCR failure did not retry exactly once'
grep -Fq 'web pull failed attempt=1/3' <<<"$output" || fail 'temporary GHCR failure was not reported'
grep -Fq 'web pull succeeded attempt=2/3' <<<"$output" || fail 'successful GHCR retry was not reported'
pass 'temporary GHCR failure retries the same immutable Web digest and deploys'

reset_case
if output=$(FAKE_PULL_FAILURES=3 run_deploy 2>&1); then update_status=0; else update_status=$?; fi
(( update_status != 0 )) || fail 'three failed GHCR pulls unexpectedly deployed'
[[ "$(wc -l <"$case_root/pull.log" | tr -d ' ')" == 3 ]] || fail 'GHCR failure did not stop after three attempts'
grep -Fq 'simulated temporary registry error on attempt 3' <<<"$output" || { printf '%s\n' "$output" >&2; fail 'third GHCR failure reason was not reported'; }
cmp -s "$base_state" "$case_root/deploy-state.json" || fail 'GHCR pull failure changed accepted state'
[[ "$(<"$case_root/running")" == old ]] || fail 'GHCR pull failure stopped production'
! grep -E '^(up|stop)$' "$case_root/compose.log" | grep -q . || fail 'GHCR pull failure changed running services'
pass 'three failed GHCR pulls leave accepted state and running services intact'

write_scope_bundle "$manifest"
reset_case
cat >"$case_root/.env" <<EOF
PUBLIC_SITE_URL=https://mc.mc520.top
MYSHOP_OFFLINE_IMAGES=1
MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE=$offline_bundle
EOF
if output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 run_deploy 2>&1); then update_status=0; else update_status=$?; fi
(( update_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'offline Web-only release failed'; }
[[ "$(<"$case_root/api-stage")" == old && "$(<"$case_root/web-stage")" == candidate ]] || fail 'offline Web-only release changed API runtime state'
[[ ! -s "$case_root/pull.log" && ! -s "$case_root/backup.log" && ! -s "$case_root/schema.log" ]] || fail 'offline Web-only release pulled images or touched database state'
[[ "$(grep -c '^up|' "$case_root/compose-detail.log")" == 1 ]] || fail 'offline Web-only release started more than one app service'
grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps web' || fail 'offline Web-only release did not target only Web'
! grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps api' || fail 'offline Web-only release restarted API'
pass 'offline Web-only update keeps the accepted API reference and does not recreate API'

manifest="$tmp_dir/api-only-manifest.json"
write_scope_manifest api "$manifest"
reset_case
if output=$(run_deploy 2>&1); then update_status=0; else update_status=$?; fi
(( update_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'API-only release failed'; }
[[ "$(<"$case_root/api-stage")" == candidate && "$(<"$case_root/web-stage")" == old ]] || fail 'API-only release changed Web runtime state'
[[ -s "$case_root/backup.log" && -s "$case_root/schema.log" ]] || fail 'API-only release skipped required backup or schema checks'
[[ "$(wc -l <"$case_root/pull.log" | tr -d ' ')" == 1 ]] || fail 'API-only release pulled more than one image'
grep -Fq "$registry/myshop-api@sha256:$(printf '4%.0s' {1..64})" "$case_root/pull.log" || fail 'API-only release did not pull its immutable API digest'
! grep -Fq "$registry/myshop-web@" "$case_root/pull.log" || fail 'API-only release pulled a Web image'
[[ "$(grep -c '^up|' "$case_root/compose-detail.log")" == 1 ]] || fail 'API-only release started more than one app service'
grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps api' || fail 'API-only release did not target only API'
! grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps web' || fail 'API-only release restarted Web'
! grep -Fxq stop "$case_root/compose.log" || fail 'non-migration API-only release unnecessarily stopped API before update'
pass 'pure API update pulls and starts API only while retaining the required database recovery gates'

manifest="$tmp_dir/no-op-manifest.json"
write_scope_manifest noop "$manifest"
reset_case
if output=$(run_deploy 2>&1); then update_status=0; else update_status=$?; fi
(( update_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'unchanged-digest release failed'; }
[[ "$(<"$case_root/api-stage")" == old && "$(<"$case_root/web-stage")" == old ]] || fail 'unchanged-digest release recreated an app container'
[[ ! -s "$case_root/pull.log" && ! -s "$case_root/backup.log" && ! -s "$case_root/schema.log" ]] || fail 'unchanged-digest release pulled images or touched database state'
! grep -E '^(up|stop)$' "$case_root/compose.log" | grep -q . || fail 'unchanged digests caused an app service restart'
pass 'unchanged digests do not pull images, back up the database, or recreate containers'

manifest="$tmp_dir/api-config-only-manifest.json"
write_scope_manifest noop "$manifest"
reset_case
if output=$(FAKE_CONFIG_API_CHANGED=1 run_deploy 2>&1); then update_status=0; else update_status=$?; fi
(( update_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'API runtime-config-only release failed'; }
[[ -s "$case_root/backup.log" && -s "$case_root/schema.log" ]] || fail 'API runtime-config-only release skipped recovery checks'
[[ ! -s "$case_root/pull.log" ]] || fail 'API runtime-config-only release pulled an unchanged image'
[[ "$(grep -c '^up|' "$case_root/compose-detail.log")" == 1 ]] || fail 'API runtime-config-only release updated more than one app service'
grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps api' || fail 'API runtime-config-only release did not converge API'
! grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps web' || fail 'API runtime-config-only release recreated Web'
pass 'API runtime-config changes update only API without pulling an unchanged image'
manifest=$saved_manifest
write_scope_bundle "$manifest"

reset_case
set +e
stop_output=$(FAKE_STOP_FAIL=1 DEPLOY_TEST_FAIL=health-after-start run_deploy 2>&1)
stop_status=$?
set -e
(( stop_status == 2 )) || fail 'failed candidate stop did not block rollback'
grep -Fq 'CRITICAL_ROLLBACK_FAILED' <<<"$stop_output" || fail 'failed candidate stop was not reported'
[[ "$(grep -c '^up$' "$case_root/compose.log")" == 1 ]] || fail 'failed candidate stop restarted the old API'
pass 'failure to stop the candidate never starts the old API'

reset_case
set +e
rollback_output=$(DEPLOY_TEST_FAIL=rollback-start run_deploy 2>&1)
rollback_status=$?
set -e
(( rollback_status == 2 )) || fail 'rollback failure did not return status 2'
grep -Fq 'CRITICAL_ROLLBACK_FAILED' <<<"$rollback_output" || fail 'rollback failure did not fail closed'
cmp -s "$base_state" "$case_root/deploy-state.json" || fail 'rollback failure changed accepted state'
[[ ! -e "$case_root/accepted-images.env" ]] || fail 'rollback failure recreated a second accepted image state'
[[ -f "$case_root/candidates/$candidate_sha.failed.json" ]] || fail 'rollback failure did not mark candidate FAILED'
! grep -Ev 'dry=yes' "$case_root/retention.log" | grep -q . || fail 'rollback failure executed destructive retention'
printf 'PRE-COMMIT rollback-failure status=%s state=unchanged retention=none\n' "$rollback_status"
pass 'rollback failure returns CRITICAL_ROLLBACK_FAILED without retention'

reset_case
if output=$(run_deploy 2>&1); then success_status=0; else success_status=$?; fi
(( success_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'successful candidate deployment failed'; }
[[ "$(awk '/^stop$|^up$/ {print; if (++count == 2) exit}' "$case_root/compose.log")" == $'stop\nup' ]] || fail 'candidate started before stopping the old API'
grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps api' || fail 'combined release did not update API'
grep -F 'up|' "$case_root/compose-detail.log" | grep -Fq -- '--no-deps web' || fail 'combined release did not update Web'
grep -Fq 'backup-project=myshop' "$case_root/helper-projects.log" || fail 'legacy production backup did not retain the myshop project ID'
grep -Fq 'schema-project=myshop' "$case_root/helper-projects.log" || fail 'legacy production schema check did not retain the myshop project ID'
grep -Fq 'retention-project=myshop' "$case_root/helper-projects.log" || fail 'legacy production retention did not retain the myshop project ID'
grep -Fq 'DEPLOYMENT TRANSACTION COMMITTED' <<<"$output" || fail 'commit point was not reported'
grep -Fq 'DEPLOYMENT ACCEPTED' <<<"$output" || fail 'successful deployment was not accepted'
python3 - "$case_root/deploy-state.json" "$old_sha" "$rollback1_sha" "$candidate_sha" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding='utf-8'))
old_sha, rollback1_sha, candidate_sha = sys.argv[2:]
assert state['active']['source_sha'] == candidate_sha
assert [item['source_sha'] for item in state['rollback']] == [old_sha, rollback1_sha]
assert state['history'][-1]['source_sha'] == candidate_sha
assert len(state['rollback']) == 2
PY
[[ "$(<"$case_root/running")" == candidate ]] || fail 'successful deployment did not leave candidate running'
[[ ! -e "$case_root/accepted-images.env" ]] || fail 'successful deployment created a second accepted image state'
grep -Fq 'dry=yes' "$case_root/retention.log" || fail 'prospective retention preflight was not executed'
grep -Fq 'dry=no' "$case_root/retention.log" || fail 'post-commit retention was not executed'
printf 'COMMIT SUCCESS active=%s rollback=[%s,%s] history_append=%s\n' "$candidate_sha" "$old_sha" "$rollback1_sha" "$candidate_sha"
pass 'candidate is promoted only after identity and prospective retention checks'

# The first greenfield acceptance has no rollback records. Exercise the normal
# upgrade transaction from that state as well as the two-rollback case above.
reset_case
python3 - "$case_root/deploy-state.json" <<'PY'
import json, sys
path = sys.argv[1]
with open(path, encoding='utf-8') as handle:
    state = json.load(handle)
state['rollback'] = []
state['history'] = [state['active']]
with open(path, 'w', encoding='utf-8') as handle:
    json.dump(state, handle)
PY
if output=$(run_deploy 2>&1); then success_status=0; else success_status=$?; fi
(( success_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'upgrade from first accepted state failed'; }
python3 - "$case_root/deploy-state.json" "$old_sha" "$candidate_sha" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding='utf-8'))
assert state['active']['source_sha'] == sys.argv[3]
assert [item['source_sha'] for item in state['rollback']] == [sys.argv[2]]
PY
pass 'ordinary upgrade from the first accepted state preserves the initial version for rollback'

printf 'SHARED FIRST-INSTALL BOOTSTRAP REGRESSION\n'
reset_case
rm -f "$case_root/deploy-state.json"
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
if output=$(FAKE_SHARED_TEST=1 run_deploy 2>&1); then bootstrap_status=0; else bootstrap_status=$?; fi
(( bootstrap_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'shared first-install bootstrap failed'; }
grep -Fq 'DEPLOYMENT ACCEPTED' <<<"$output" || fail 'shared bootstrap did not reach acceptance'
python3 - "$case_root/deploy-state.json" "$candidate_sha" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding='utf-8'))
assert state['active']['source_sha'] == sys.argv[2]
assert state['rollback'] == []
PY
[[ "$(<"$case_root/running")" == candidate ]] || fail 'shared bootstrap did not keep the accepted candidate running'
[[ -f "$case_root/recovery-point.dump" ]] || fail 'shared bootstrap did not preserve its fresh database recovery point'
[[ "$(head -n 1 "$case_root/compose.log")" == db-up ]] || fail 'shared bootstrap did not start only PostgreSQL before making its recovery point'
grep -Fxq myshop-friend-prod "$case_root/docker-projects.log" || fail 'shared bootstrap did not target the dedicated Compose project'
if grep -vFxq myshop-friend-prod "$case_root/docker-projects.log"; then fail 'shared bootstrap queried a non-shared Compose project'; fi
if grep -vE '^(compose|backup|schema|retention)-project=myshop-friend-prod$' "$case_root/helper-projects.log"; then fail 'a shared helper used a non-shared Compose project'; fi
for helper in compose backup schema retention; do
  grep -Fxq "$helper-project=myshop-friend-prod" "$case_root/helper-projects.log" || fail "shared $helper helper did not receive the dedicated Compose project"
done
pass 'shared first install starts without a baseline and commits only after loopback candidate acceptance'

reset_case
rm -f "$case_root/deploy-state.json"
cat >"$case_root/.env" <<EOF
PUBLIC_SITE_URL=https://myshop-candidate.mc520.top
MYSHOP_OFFLINE_IMAGES=1
MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE=$offline_bundle
EOF
if output=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_CANDIDATE_HOST=myshop-candidate.mc520.top run_deploy 2>&1); then offline_status=0; else offline_status=$?; fi
(( offline_status == 0 )) || { printf '%s\n' "$output" >&2; fail 'offline shared candidate deployment failed'; }
grep -Fq 'DEPLOYMENT ACCEPTED' <<<"$output" || fail 'offline shared candidate was not accepted'
[[ ! -s "$case_root/pull.log" ]] || fail 'offline deployment attempted a registry image pull'
if grep -Fxq pull "$case_root/compose.log"; then fail 'offline deployment invoked docker compose pull'; fi
python3 - "$case_root/deploy-state.json" "$candidate_sha" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding='utf-8'))
active = state['active']
assert active['source_sha'] == sys.argv[2]
assert active['images']['api']['image_id'] == 'sha256:' + '5' * 64
assert active['images']['web']['image_id'] == 'sha256:' + '6' * 64
assert active['images']['api']['digest'].startswith('sha256:4')
PY
grep -Fxq 'http://127.0.0.1:8082/' "$case_root/deploy-curl.log" || fail 'offline candidate smoke did not target the local candidate port'
pass 'offline shared deployment verifies loaded image IDs, skips registry pulls, and accepts the temporary candidate host'

reset_case
rm -f "$case_root/deploy-state.json"
cat >"$case_root/.env" <<EOF
PUBLIC_SITE_URL=https://myshop-candidate.mc520.top
MYSHOP_OFFLINE_IMAGES=1
MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE=$offline_bundle
EOF
printf 'old production data untouched\n' >"$case_root/old-production-sentinel"
set +e
offline_failure=$(FAKE_SHARED_TEST=1 FAKE_OFFLINE_TEST=1 FAKE_CANDIDATE_HOST=myshop-candidate.mc520.top DEPLOY_TEST_FAIL=smoke run_deploy 2>&1)
offline_failure_status=$?
set -e
(( offline_failure_status == 1 )) || { printf '%s\n' "$offline_failure" >&2; fail 'offline first-install smoke failure was not rejected'; }
grep -Fq 'BOOTSTRAP FAILED' <<<"$offline_failure" || fail 'offline bootstrap failure was treated as a successful rollback target'
[[ ! -e "$case_root/deploy-state.json" ]] || fail 'offline failed candidate was recorded as accepted'
[[ -f "$case_root/recovery-point.dump" && -f "$case_root/candidates/$candidate_sha.failed.json" ]] || fail 'offline failed candidate lost its recovery record'
[[ "$(<"$case_root/running")" == stopped ]] || fail 'offline failed candidate app services were left running'
[[ ! -s "$case_root/pull.log" ]] || fail 'offline failure path attempted a registry pull'
grep -Fxq 'old production data untouched' "$case_root/old-production-sentinel" || fail 'offline failure changed the old production fixture'
pass 'offline first-install failure stops only candidate apps and preserves recovery data and old production'

reset_case
rm -f "$case_root/deploy-state.json"
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
set +e
first_pull_failure=$(FAKE_SHARED_TEST=1 DEPLOY_TEST_FAIL=pull run_deploy 2>&1)
first_pull_status=$?
set -e
(( first_pull_status == 1 )) || { printf '%s\n' "$first_pull_failure" >&2; fail 'first shared image-pull failure did not preserve bootstrap state'; }
grep -Fq 'BOOTSTRAP FAILED' <<<"$first_pull_failure" || fail 'first shared pull failure was incorrectly treated as an accepted rollback'
[[ -s "$case_root/backup.log" && -f "$case_root/recovery-point.dump" ]] || fail 'first shared pull failure happened before the database recovery point was made'
[[ ! -e "$case_root/deploy-state.json" ]] || fail 'first shared pull failure wrote accepted state'
pass 'first shared attempt backs up the restored database before registry pulls'

reset_case
rm -f "$case_root/deploy-state.json"
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
set +e
unconfirmed_output=$(FAKE_SHARED_TEST=1 FAKE_BAOTA_PROXY_CONFIRMED=0 FAKE_BAOTA_PROXY_CIDR= run_deploy 2>&1)
unconfirmed_status=$?
set -e
(( unconfirmed_status != 0 )) || fail 'shared deployment started without a confirmed Baota proxy address'
grep -Fq 'source address has not been explicitly confirmed' <<<"$unconfirmed_output" || fail 'unconfirmed proxy source failed for an unrelated reason'
[[ ! -s "$case_root/pull.log" && ! -s "$case_root/backup.log" && ! -s "$case_root/docker-projects.log" ]] || fail 'unconfirmed proxy source reached Docker operations'
[[ ! -e "$case_root/deploy-state.json" ]] || fail 'unconfirmed proxy source created accepted deployment state'
pass 'unconfirmed Baota-to-Caddy source address blocks deployment before any Docker operation'

reset_case
rm -f "$case_root/deploy-state.json"
printf 'PUBLIC_SITE_URL=https://mc.mc520.top\n' >"$case_root/.env"
printf 'old production domain and data unchanged\n' >"$case_root/old-production-sentinel"
PATH="$fake_bin:$PATH" FAKE_ROOT="$case_root" FAKE_CURL_LOG="$case_root/old-site-check.log" \
  FAKE_CANDIDATE_DOWN=1 curl --fail --silent --show-error https://mc.mc520.top/ \
  || fail 'old public production-site fixture should be healthy'
: >"$case_root/deploy-curl.log"
set +e
bootstrap_failure_output=$(FAKE_SHARED_TEST=1 FAKE_CANDIDATE_DOWN=1 run_deploy 2>&1)
bootstrap_failure_status=$?
set -e
(( bootstrap_failure_status == 1 )) || { printf '%s\n' "$bootstrap_failure_output" >&2; fail 'failed shared candidate did not stop without accepting state'; }
grep -Fq 'BOOTSTRAP FAILED' <<<"$bootstrap_failure_output" || fail 'first-install failure was reported as a rollback to an accepted version'
[[ ! -e "$case_root/deploy-state.json" ]] || fail 'failed first-install candidate was written as accepted state'
[[ -f "$case_root/candidates/$candidate_sha.failed.json" ]] || fail 'failed first-install candidate record is missing'
[[ -f "$case_root/recovery-point.dump" ]] || fail 'failed first-install candidate removed the database recovery point'
[[ "$(<"$case_root/running")" == stopped ]] || fail 'failed first-install app services were not stopped'
grep -Fxq 'old production domain and data unchanged' "$case_root/old-production-sentinel" || fail 'shared failure changed old production data fixture'
[[ -s "$case_root/old-site-check.log" ]] || fail 'old production site fixture was not verified healthy'
if grep -v '^http://127\.0\.0\.1:8082' "$case_root/deploy-curl.log" | grep -q .; then fail 'shared smoke attempted a non-candidate URL'; fi
if grep -q 'mc\.mc520\.top' "$case_root/deploy-curl.log"; then fail 'shared smoke used the public old-domain URL'; fi
if grep -vFxq myshop-friend-prod "$case_root/docker-projects.log"; then fail 'shared failure inspected a non-shared Compose project'; fi
pass 'healthy old domain cannot mask a failing candidate; no accepted state is written and recovery data stays intact'

assert_post_commit_failure() {
  local hook=$1 output status maintenance_file
  reset_case
  set +e
  output=$(DEPLOY_TEST_FAIL="$hook" run_deploy 2>&1)
  status=$?
  set -e
  (( status != 0 )) || fail "$hook post-commit failure unexpectedly succeeded"
  grep -Fq "maintenance_status=FAILED phase=$hook" <<<"$output" || fail "$hook did not report post-commit maintenance failure"
  python3 - "$case_root/deploy-state.json" "$old_sha" "$rollback1_sha" "$candidate_sha" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding='utf-8'))
old_sha, rollback1_sha, candidate_sha = sys.argv[2:]
assert state['active']['source_sha'] == candidate_sha
assert [item['source_sha'] for item in state['rollback']] == [old_sha, rollback1_sha]
assert state['history'][-1]['source_sha'] == candidate_sha
PY
  [[ "$(<"$case_root/running")" == candidate ]] || fail "$hook restored old stack after commit"
  [[ ! -e "$case_root/candidates/$candidate_sha.failed.json" ]] || fail "$hook marked accepted candidate FAILED"
  maintenance_file="$case_root/maintenance/$candidate_sha.failed.json"
  [[ -f "$maintenance_file" ]] || fail "$hook did not write maintenance failure record"
  python3 - "$maintenance_file" "$candidate_sha" "$hook" <<'PY'
import json, sys
record = json.load(open(sys.argv[1], encoding='utf-8'))
assert record['source_sha'] == sys.argv[2]
assert record['accepted'] is True
assert record['maintenance_status'] == 'FAILED'
assert record['phase'] == sys.argv[3]
PY
  printf 'POST-COMMIT %-17s status=%s state=committed running=candidate maintenance=recorded\n' "$hook" "$status"
}

printf 'POST-COMMIT FAILURE REGRESSION\n'
assert_post_commit_failure retention
assert_post_commit_failure post-storage
pass 'post-commit failures remain accepted and never restore the previous application'

invariant_root="$tmp_dir/invariant"
mkdir -p "$invariant_root"
printf 'candidate\n' >"$invariant_root/running"
cp "$base_state" "$invariant_root/deploy-state.json"
bash "$state_writer" "$invariant_root/deploy-state.json" "$manifest" >/dev/null
cp "$invariant_root/deploy-state.json" "$invariant_root/promoted-state.json"
python3 - "$base_state" "$invariant_root/promoted-state.json" "$old_sha" "$rollback1_sha" "$rollback2_sha" "$candidate_sha" <<'PY'
import json, sys
before = json.load(open(sys.argv[1], encoding='utf-8'))
promoted = json.load(open(sys.argv[2], encoding='utf-8'))
old_sha, rollback1_sha, rollback2_sha, candidate_sha = sys.argv[3:]
assert [before['active']['source_sha'], *[item['source_sha'] for item in before['rollback']]] == [old_sha, rollback1_sha, rollback2_sha]
assert [promoted['active']['source_sha'], *[item['source_sha'] for item in promoted['rollback']]] == [candidate_sha, old_sha, rollback1_sha]
PY

invariant_removals="$invariant_root/removals.log"
: >"$invariant_removals"
PATH="$fake_bin:$PATH" \
  FAKE_MODE=retention \
  FAKE_ROOT="$invariant_root" \
  OLD_SHA="$old_sha" \
  ROLLBACK1_SHA="$rollback1_sha" \
  ROLLBACK2_SHA="$rollback2_sha" \
  CANDIDATE_SHA="$candidate_sha" \
  FAKE_REGISTRY="$registry" \
  FAKE_REMOVAL_LOG="$invariant_removals" \
  MYSHOP_RUNTIME_ROOT="$invariant_root" \
  MYSHOP_DEPLOY_STATE="$invariant_root/deploy-state.json" \
  HOST_DEPLOY_LOCK_FILE="$invariant_root/deploy.lock" \
  HOST_DEPLOY_LOCK_HELD=1 \
  bash "$retention_script" >/dev/null
grep -Fxq "$registry/myshop-api:$rollback2_sha" "$invariant_removals" || fail 'retention did not remove old API rollback C'
grep -Fxq "$registry/myshop-web:$rollback2_sha" "$invariant_removals" || fail 'retention did not remove old Web rollback C'
if grep -E "$candidate_sha|$old_sha|$rollback1_sha" "$invariant_removals" | grep -q .; then fail 'retention removed an accepted source-SHA image'; fi
if grep -Ev "^$registry/myshop-(api|web)(:$rollback2_sha|@sha256:$(printf '3%.0s' {1..64}))$" "$invariant_removals" | grep -q .; then fail 'retention removed a non-rollback-C image ref'; fi
cmp -s "$invariant_root/promoted-state.json" "$invariant_root/deploy-state.json" || fail 'retention modified committed state'
printf 'DESTRUCTIVE-RETENTION INVARIANT before=active:%s rollback:[%s,%s] promoted=active:%s rollback:[%s,%s] deleted=[%s,%s] post-maintenance-failure=state remains active:%s rollback:[%s,%s]\n' \
  "$old_sha" "$rollback1_sha" "$rollback2_sha" "$candidate_sha" "$old_sha" "$rollback1_sha" \
  "$registry/myshop-api:$rollback2_sha" "$registry/myshop-web:$rollback2_sha" "$candidate_sha" "$old_sha" "$rollback1_sha"
pass 'accepted D,A,B remains the invariant after C deletion and post-commit maintenance failure'

shared_retention_root="$tmp_dir/shared-retention"
mkdir -p "$shared_retention_root"
printf 'candidate\n' >"$shared_retention_root/running"
cp "$invariant_root/deploy-state.json" "$shared_retention_root/deploy-state.json"
: >"$shared_retention_root/removals.log"
PATH="$fake_bin:$PATH" \
  FAKE_MODE=retention \
  FAKE_ROOT="$shared_retention_root" \
  OLD_SHA="$old_sha" \
  ROLLBACK1_SHA="$rollback1_sha" \
  ROLLBACK2_SHA="$rollback2_sha" \
  CANDIDATE_SHA="$candidate_sha" \
  FAKE_REGISTRY="$registry" \
  FAKE_REMOVAL_LOG="$shared_retention_root/removals.log" \
  MYSHOP_RUNTIME_ROOT="$shared_retention_root" \
  MYSHOP_DEPLOY_STATE="$shared_retention_root/deploy-state.json" \
  MYSHOP_COMPOSE_PROJECT_NAME=myshop-friend-prod \
  MYSHOP_SHARED_SERVER=1 \
  HOST_DEPLOY_LOCK_FILE="$shared_retention_root/deploy.lock" \
  HOST_DEPLOY_LOCK_HELD=1 \
  bash "$retention_script" >/dev/null
if grep -q '^myshop-api:friend-custom$' "$shared_retention_root/removals.log"; then fail 'shared retention removed a similarly named friend image'; fi
printf 'PROJECT ISOLATION shared retention removed no unrelated local myshop-api image\n'
pass 'shared retention limits deletion to repositories present in the accepted MyShop state'

printf 'Deployment transaction shell integration regression passed\n'

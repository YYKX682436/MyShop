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
    "version": 1,
    "project": "myshop",
    "source_sha": candidate,
    "created_at": "2026-09-24T00:00:00Z",
    "images": {
        service: {"ref": f"{registry}/myshop-{service}:{candidate}", "digest": digests[candidate]}
        for service in ("api", "web")
    },
}
Path(manifest_path).write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

fake_bin="$tmp_dir/fake-bin"
mkdir -p "$fake_bin"

cat >"$fake_bin/flock" <<'FLOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
exit 0
FLOCK

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

digest_for_sha() {
  case "$1" in
    "$old_sha") printf 'sha256:%s' "$(printf '1%.0s' {1..64})" ;;
    "$rollback1_sha") printf 'sha256:%s' "$(printf '2%.0s' {1..64})" ;;
    "$rollback2_sha") printf 'sha256:%s' "$(printf '3%.0s' {1..64})" ;;
    "$candidate_sha") printf 'sha256:%s' "$(printf '4%.0s' {1..64})" ;;
    *) printf 'sha256:%s' "$(printf '9%.0s' {1..64})" ;;
  esac
}

ref_for_service() {
  local service=$1 selected_sha=$old_sha
  [[ "$stage" == candidate ]] && selected_sha=$candidate_sha
  printf '%s/myshop-%s:%s' "$registry" "$service" "$selected_sha"
}

service_from_args() {
  local arg
  for arg in "$@"; do
    [[ "$arg" == label=com.docker.compose.service=* ]] && { printf '%s' "${arg##*=}"; return; }
  done
}

container_id() {
  printf 'myshop-%s-%s' "$1" "$stage"
}

if [[ "$mode" == retention ]]; then
  case "${1:-}" in
    ps)
      if [[ " $* " == *" -aq "* || "${2:-}" == -aq ]]; then
        printf 'myshop-api-d\nmyshop-web-d\nmyshop-db\nmyshop-caddy\n'
      else
        service=$(service_from_args "$@")
        [[ -n "$service" ]] || exit 0
        printf 'myshop-%s-d|%s/myshop-%s:%s\n' "$service" "$registry" "$service" "$candidate_sha"
      fi
      ;;
    inspect)
      format=${3:-}
      target=${4:-}
      case "$format" in
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
        tag=${target##*:}
        digest=$(digest_for_sha "$tag")
        if [[ "$format" == '{{.Id}}' ]]; then
          printf '%s\n' "$digest"
        else
          printf '%s@%s\n' "${target%:*}" "$digest"
        fi
      elif [[ "${2:-}" == ls ]]; then
        for service in api web; do
          for sha in "$candidate_sha" "$old_sha" "$rollback1_sha" "$rollback2_sha"; do
            printf '%s/myshop-%s|%s|%s\n' "$registry" "$service" "$sha" "$(digest_for_sha "$sha")"
          done
        done
      else
        printf '%s\n' "${3:-}" >>"${FAKE_REMOVAL_LOG:?}"
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
  pull)
    [[ "${DEPLOY_TEST_FAIL:-}" != pull ]] || exit 1
    printf '%s\n' "${2:-}" >>"${FAKE_PULL_LOG:?}"
    ;;
  ps)
    if [[ " $* " == *" -aq "* || "${2:-}" == -aq ]]; then
      printf 'myshop-db-%s\nmyshop-api-%s\nmyshop-web-%s\nmyshop-caddy-%s\n' "$stage" "$stage" "$stage" "$stage"
    else
      service=$(service_from_args "$@")
      [[ -n "$service" ]] || exit 0
      if [[ " $* " == *" -q "* ]]; then
        printf '%s\n' "$(container_id "$service")"
      else
        printf '%s|%s\n' "$(container_id "$service")" "$(ref_for_service "$service")"
      fi
    fi
    ;;
  inspect)
    format=${3:-}
    target=${4:-}
    service=${target#myshop-}
    service=${service%-old}
    service=${service%-candidate}
    case "$format" in
      '{{.Config.Image}}')
        ref=$(ref_for_service "$service")
        if [[ "$stage" == candidate && "${DEPLOY_TEST_FAIL:-}" == final-identity ]]; then
          printf '%s-wrong\n' "$ref"
        else
          printf '%s\n' "$ref"
        fi
        ;;
      '{{.Image}}')
        sha=$old_sha
        [[ "$stage" == candidate ]] && sha=$candidate_sha
        if [[ "$stage" == candidate && "${DEPLOY_TEST_FAIL:-}" == final-identity ]]; then
          printf 'sha256:%s\n' "$(printf '8%.0s' {1..64})"
        else
          digest_for_sha "$sha"
          printf '\n'
        fi
        ;;
      '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}') printf 'healthy\n' ;;
      *) printf 'healthy\n' ;;
    esac
    ;;
  image)
    if [[ "${2:-}" == inspect ]]; then
      format=${4:-}
      target=${5:-}
      tag=${target##*:}
      digest=$(digest_for_sha "$tag")
      if [[ "$format" == '{{.Id}}' ]]; then
        printf '%s\n' "$digest"
      else
        printf '%s@%s\n' "${target%:*}" "$digest"
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
  exec) exit 0 ;;
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
  case "$arg" in config|pull|up) action=$arg ;; esac
done
candidate=0
[[ "${MYSHOP_API_IMAGE:-}" == *":${CANDIDATE_SHA}" ]] && candidate=1
case "$action" in
  config)
    [[ ! ( "$candidate" == 1 && "${DEPLOY_TEST_FAIL:-}" == compose ) ]] || exit 1
    ;;
  pull) ;;
  up)
    [[ ! ( "$candidate" == 1 && "${DEPLOY_TEST_FAIL:-}" == start ) ]] || exit 1
    [[ ! ( "$candidate" == 0 && "${DEPLOY_TEST_FAIL:-}" == rollback-start ) ]] || exit 1
    if (( candidate )); then printf 'candidate\n' >"$root/running"; else printf 'old\n' >"$root/running"; fi
    ;;
  *) exit 99 ;;
esac
COMPOSE

cat >"$tmp_dir/backup.sh" <<'BACKUP'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'backup\n' >>"${FAKE_BACKUP_LOG:?}"
[[ "${DEPLOY_TEST_FAIL:-}" != backup ]]
BACKUP

cat >"$tmp_dir/schema.sh" <<'SCHEMA'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'schema\n' >>"${FAKE_SCHEMA_LOG:?}"
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
if [[ "${DEPLOY_TEST_FAIL:-}" == retention-preflight && "$dry" == yes ]]; then exit 1; fi
if [[ "${DEPLOY_TEST_FAIL:-}" == retention && "$dry" == no ]]; then exit 1; fi
exit 0
RETENTION

cat >"$fake_bin/curl" <<'CURL'
#!/usr/bin/env bash
set -Eeuo pipefail
count_file="$FAKE_ROOT/curl-count"
count=$(cat "$count_file" 2>/dev/null || printf 0)
if [[ "${DEPLOY_TEST_FAIL:-}" == smoke && "$count" -eq 0 ]]; then
  printf '1\n' >"$count_file"
  exit 1
fi
printf '%s\n' "$((count + 1))" >"$count_file"
exit 0
CURL

chmod +x "$fake_bin/docker" "$fake_bin/flock" "$tmp_dir/compose.sh" "$tmp_dir/backup.sh" "$tmp_dir/schema.sh" "$tmp_dir/health.sh" "$tmp_dir/retention.sh" "$fake_bin/curl"

case_root="$tmp_dir/case"
reset_case() {
  rm -rf "$case_root"
  mkdir -p "$case_root/candidates" "$case_root/maintenance"
  cp "$base_state" "$case_root/deploy-state.json"
  rm -f "$case_root/accepted-images.env"
  printf 'old\n' >"$case_root/running"
  printf 'PUBLIC_SITE_URL=http://myshop.test\n' >"$case_root/.env"
  : >"$case_root/pull.log"
  : >"$case_root/removal.log"
  : >"$case_root/backup.log"
  : >"$case_root/schema.log"
  : >"$case_root/health.log"
  : >"$case_root/retention.log"
  rm -f "$case_root/health-count" "$case_root/curl-count"
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
    FAKE_REMOVAL_LOG="$case_root/removal.log" \
    FAKE_BACKUP_LOG="$case_root/backup.log" \
    FAKE_SCHEMA_LOG="$case_root/schema.log" \
    FAKE_HEALTH_LOG="$case_root/health.log" \
    FAKE_RETENTION_LOG="$case_root/retention.log" \
    MYSHOP_RUNTIME_ROOT="$case_root" \
    MYSHOP_DEPLOY_STATE="$case_root/deploy-state.json" \
    MYSHOP_ENV_FILE="$case_root/.env" \
    MYSHOP_COMPOSE_WRAPPER="$tmp_dir/compose.sh" \
    MYSHOP_STATE_WRITER="$state_writer" \
    MYSHOP_SCHEMA_SCRIPT="$tmp_dir/schema.sh" \
    MYSHOP_BACKUP_SCRIPT="$tmp_dir/backup.sh" \
    MYSHOP_RETENTION_SCRIPT="$tmp_dir/retention.sh" \
    HOST_STORAGE_HEALTH_SCRIPT="$tmp_dir/health.sh" \
    HOST_DEPLOY_LOCK_FILE="$case_root/deploy.lock" \
    MYSHOP_APPROVED_REGISTRY="$registry" \
    MYSHOP_MAINTENANCE_DIR="$case_root/maintenance" \
    bash "$deploy_script" "$manifest"
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

printf 'PRE-COMMIT FAILURE REGRESSION\n'
for hook in pull backup compose start health-after-start schema smoke candidate-storage final-identity retention-preflight; do
  assert_pre_commit_failure "$hook"
done
pass 'all ten pre-commit failure paths restore old state and stack without destructive retention'

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
if grep -Ev "$rollback2_sha" "$invariant_removals" | grep -q .; then fail 'retention removed an accepted image'; fi
cmp -s "$invariant_root/promoted-state.json" "$invariant_root/deploy-state.json" || fail 'retention modified committed state'
printf 'DESTRUCTIVE-RETENTION INVARIANT before=active:%s rollback:[%s,%s] promoted=active:%s rollback:[%s,%s] deleted=[%s,%s] post-maintenance-failure=state remains active:%s rollback:[%s,%s]\n' \
  "$old_sha" "$rollback1_sha" "$rollback2_sha" "$candidate_sha" "$old_sha" "$rollback1_sha" \
  "$registry/myshop-api:$rollback2_sha" "$registry/myshop-web:$rollback2_sha" "$candidate_sha" "$old_sha" "$rollback1_sha"
pass 'accepted D,A,B remains the invariant after C deletion and post-commit maintenance failure'

printf 'Deployment transaction shell integration regression passed\n'

#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-runtime-config] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 1 )) || die 'usage: deploy-runtime-config.sh <runtime-config-manifest.json>'
manifest=$1
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"

PROJECT=myshop
PROJECT_ROOT=${MYSHOP_RUNTIME_ROOT:-/opt/myshop/runtime}
RELEASE_STATE=${MYSHOP_DEPLOY_STATE:-$PROJECT_ROOT/deploy-state.json}
RUNTIME_STATE=${MYSHOP_RUNTIME_CONFIG_STATE:-$PROJECT_ROOT/runtime-config-state.json}
ENV_FILE=${MYSHOP_ENV_FILE:-/opt/myshop/.env}
COMPOSE_WRAPPER=${MYSHOP_COMPOSE_WRAPPER:-$SCRIPT_DIR/scripts/myshop-compose.sh}
SAFETY_READER=${MYSHOP_RUNTIME_CONFIG_SAFETY_READER:-$SCRIPT_DIR/scripts/read-runtime-config-safety.sh}
CONFIG_TOOL=${MYSHOP_RUNTIME_CONFIG_TOOL:-$SCRIPT_DIR/scripts/runtime_config_state.py}
HOST_LOCK=${HOST_DEPLOY_LOCK_FILE:-/run/myshop-xianyu-deploy.lock}
RUNTIME_CANDIDATES="$PROJECT_ROOT/candidates/runtime-config"
HEALTH_DEADLINE_SECONDS=180

[[ -f "$manifest" && -f "$ENV_FILE" && -f "$RELEASE_STATE" ]] || die 'manifest, production env, or accepted release state is missing'
[[ -x "$COMPOSE_WRAPPER" && -x "$SAFETY_READER" && -f "$CONFIG_TOOL" ]] || die 'runtime-config deployment helper is missing'
command -v python3 >/dev/null && command -v flock >/dev/null || die 'python3 and flock are required'

one_container() {
  local project=$1 service=$2 ids count
  ids=$(docker ps --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=$service" -q) || return 1
  count=$(printf '%s\n' "$ids" | awk 'NF {n++} END {print n+0}')
  (( count == 1 )) || return 1
  printf '%s\n' "$ids"
}
readiness_probe() {
  local service=$1 id fields runtime health
  id=$(one_container myshop "$service") || { printf 'missing unknown 0\n'; return 1; }
  fields=$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id") || return 1
  read -r runtime health <<<"$fields"
  printf '%s %s 1\n' "$runtime" "$health"
  [[ "$runtime" == running && ( "$health" == healthy || "$health" == none ) ]]
}
wait_target_healthy() {
  local deadline=$((SECONDS + HEALTH_DEADLINE_SECONDS))
  while true; do
    readiness_probe api >/dev/null && return 0
    (( SECONDS < deadline )) || return 1
    sleep 2
  done
}

tmp_dir=$(mktemp -d)
chmod 700 "$tmp_dir"
cleanup_tmp() { rm -rf -- "$tmp_dir"; }
trap cleanup_tmp EXIT
candidate_env="$tmp_dir/candidate.env"
original_env="$tmp_dir/original.env"
state_backup="$tmp_dir/runtime-config-state.json"
exec 9>"$HOST_LOCK"
flock -n 9 || die 'another deployment or storage lifecycle operation is active'

release_state_hash=$(sha256sum "$RELEASE_STATE" | awk '{print $1}')
state_existed=0
if [[ -f "$RUNTIME_STATE" ]]; then
  state_existed=1
  python3 "$CONFIG_TOOL" backup-env --source "$RUNTIME_STATE" --target "$state_backup"
  chmod 600 "$state_backup"
fi
container_before=$(one_container myshop api) || die 'ACTIVE_RELEASE_MISMATCH: api container selection failed'
[[ -n "$container_before" ]] || die 'ACTIVE_RELEASE_MISMATCH: api container is missing'
readiness_probe api >/dev/null || die 'accepted api is not healthy'
plan_json=$(python3 "$CONFIG_TOOL" prepare --manifest "$manifest" --release-state "$RELEASE_STATE" \
  --runtime-state "$RUNTIME_STATE" --env-file "$ENV_FILE" --container-id "$container_before" \
  --service api --candidate-env "$candidate_env") || exit $?
get_plan() {
  python3 -c 'import json,sys; v=json.loads(sys.stdin.read())[sys.argv[1]]; print(str(v).lower() if isinstance(v,bool) else v)' "$1" <<<"$plan_json"
}
source_sha=$(get_plan source_sha)
accepted_ref=$(get_plan image_ref)
accepted_digest=$(get_plan image_config_digest)
candidate_values=$(python3 -c 'import json,sys; print(json.dumps(json.loads(sys.stdin.read())["candidate_values"],sort_keys=True,separators=(",",":")))' <<<"$plan_json")
no_op=$(get_plan no_op)
runtime_state_exists=$(get_plan runtime_state_exists)
revision=$(get_plan revision)
candidate_config_digest=$(get_plan candidate_config_digest)

verify_identity() {
  local id=$1 ref image_id actual_id repo_digest
  ref=$(docker inspect --format '{{.Config.Image}}' "$id") || return 1
  [[ "$ref" == "$accepted_ref" ]] || return 1
  image_id=$(docker inspect --format '{{.Image}}' "$id") || return 1
  actual_id=$(docker image inspect --format '{{.Id}}' "$accepted_ref" 2>/dev/null) || return 1
  [[ "$image_id" == "$actual_id" ]] || return 1
  if [[ "$accepted_digest" != "$actual_id" ]]; then
    repo_digest=$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$accepted_ref" 2>/dev/null) || return 1
    grep -F "${accepted_ref%:*}@$accepted_digest" <<<"$repo_digest" >/dev/null || return 1
  fi
}
verify_identity "$container_before" || die 'ACTIVE_RELEASE_MISMATCH: running api identity differs from accepted release state'
if (( state_existed )); then
  python3 "$CONFIG_TOOL" verify-baseline --runtime-state "$RUNTIME_STATE" --env-file "$ENV_FILE" \
    --container-id "$container_before" || die 'RUNTIME_CONFIG_DRIFT'
fi

safety_before=$("$SAFETY_READER") || die 'production worker safety query failed'
worker_enable=$(python3 - "$plan_json" <<'PY'
import json
import sys
plan = json.loads(sys.argv[1])
print(str(plan["current_values"]["SALES_CHANNEL_WORKER_ENABLED"] == "false"
          and plan["candidate_values"]["SALES_CHANNEL_WORKER_ENABLED"] == "true").lower())
PY
)
check_preconditions() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
value = json.loads(sys.argv[1])
worker_enable = sys.argv[2] == "true"
if value.get("running_ensure") != 0 or value.get("stale_running_ensure") != 0:
    raise SystemExit(1)
if worker_enable:
    keys = ("active_listings", "ready_selector", "attempted_recovery_ensure",
            "legacy_unresolved", "agent_nonterminal_operations",
            "agent_running_unknown_commands", "agent_active_provider_attempts")
    if any(value.get(key) != 0 for key in keys):
        raise SystemExit(1)
    if value.get("agent_writes_enabled") is not False or value.get("agent_adoption_enabled") is not False:
        raise SystemExit(1)
PY
}
if [[ "$no_op" != true ]] && ! check_preconditions "$safety_before" "$worker_enable"; then
  if [[ "$worker_enable" == true ]]; then
    die 'RUNTIME_CONFIG_WORKER_ZERO_WORK_GUARD'
  fi
  die 'RUNTIME_CONFIG_RUNNING_ENSURE'
fi

env_for_compose=$ENV_FILE
if [[ "$no_op" != true ]]; then env_for_compose=$candidate_env; fi
MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_ENV_FILE="$env_for_compose" "$COMPOSE_WRAPPER" \
  --env-file "$env_for_compose" config --quiet || die 'RUNTIME_CONFIG_CANDIDATE_COMPOSE_INVALID'
[[ "$(sha256sum "$RELEASE_STATE" | awk '{print $1}')" == "$release_state_hash" ]] || die 'ACTIVE_RELEASE_MISMATCH: release state changed during preflight'

assert_release_state_unchanged() {
  [[ "$(sha256sum "$RELEASE_STATE" | awk '{print $1}')" == "$release_state_hash" ]]
}
compare_safety_totals() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
before, after = (json.loads(item) for item in sys.argv[1:])
keys = ("attempted_ensure_total", "myshop_operation_total",
        "agent_operation_total", "agent_provider_attempt_total")
raise SystemExit(0 if all(before[key] == after[key] for key in keys) else 1)
PY
}
post_boundary_crossed() {
  python3 - "$1" "$2" <<'PY'
import json
import sys
before, after = (json.loads(item) for item in sys.argv[1:])
crossed = (
    after.get("running_ensure", 0) > 0
    or after.get("attempted_ensure_total", 0) > before.get("attempted_ensure_total", 0)
    or after.get("agent_operation_total", 0) > before.get("agent_operation_total", 0)
    or after.get("agent_provider_attempt_total", 0) > before.get("agent_provider_attempt_total", 0)
)
raise SystemExit(0 if crossed else 1)
PY
}

env_install_attempted=0
candidate_start_attempted=0
rollback_old() {
  assert_release_state_unchanged || return 1
  if (( candidate_start_attempted )); then
    local now
    now=$("$SAFETY_READER") || return 1
    python3 - "$now" <<'PY'
import json
import sys
raise SystemExit(0 if json.loads(sys.argv[1]).get("running_ensure") == 0 else 1)
PY
  fi
  if (( env_install_attempted )); then
    python3 "$CONFIG_TOOL" install-env --candidate "$original_env" --target "$ENV_FILE" || return 1
  fi
  if (( candidate_start_attempted )); then
    MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_ENV_FILE="$ENV_FILE" "$COMPOSE_WRAPPER" \
      --env-file "$ENV_FILE" config --quiet || return 1
    MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_ENV_FILE="$ENV_FILE" "$COMPOSE_WRAPPER" \
      --env-file "$ENV_FILE" up -d --no-deps --force-recreate --pull never api || return 1
    wait_target_healthy || return 1
  fi
  current_id=$(one_container myshop api) || return 1
  [[ -n "$current_id" ]] || return 1
  verify_identity "$current_id" || return 1
  python3 "$CONFIG_TOOL" verify-container-env --env-file "$ENV_FILE" --container-id "$current_id" || return 1
  if (( state_existed )); then
    python3 "$CONFIG_TOOL" restore-state --backup "$state_backup" --target "$RUNTIME_STATE" --existed true || return 1
  else
    python3 "$CONFIG_TOOL" restore-state --backup "$state_backup" --target "$RUNTIME_STATE" --existed false || return 1
  fi
  assert_release_state_unchanged
}
fail_transaction() {
  local reason=$1
  if rollback_old; then
    printf 'RUNTIME CONFIG FAILED / ROLLED BACK project=%s source=%s reason=%s\n' "$PROJECT" "$source_sha" "$reason" >&2
    exit 1
  fi
  printf 'CRITICAL_RUNTIME_CONFIG_ROLLBACK_FAILED project=%s source=%s reason=%s\n' "$PROJECT" "$source_sha" "$reason" >&2
  exit 2
}

if [[ "$no_op" != true ]]; then
  python3 "$CONFIG_TOOL" backup-env --source "$ENV_FILE" --target "$original_env" || fail_transaction env-backup
  env_install_attempted=1
  python3 "$CONFIG_TOOL" install-env --candidate "$candidate_env" --target "$ENV_FILE" || fail_transaction env-install
  MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_ENV_FILE="$ENV_FILE" "$COMPOSE_WRAPPER" \
    --env-file "$ENV_FILE" config --quiet || fail_transaction compose-after-install
  candidate_start_attempted=1
  [[ "${DEPLOY_TEST_FAIL:-}" != start ]] || fail_transaction start
  MYSHOP_RUNTIME_ROOT="$PROJECT_ROOT" MYSHOP_ENV_FILE="$ENV_FILE" "$COMPOSE_WRAPPER" \
    --env-file "$ENV_FILE" up -d --no-deps --force-recreate --pull never api || fail_transaction start
  [[ "${DEPLOY_TEST_FAIL:-}" != rollback-start ]] || fail_transaction health
  [[ "${DEPLOY_TEST_FAIL:-}" != health ]] || fail_transaction health
  wait_target_healthy || fail_transaction health
  container_after=$(one_container myshop api) || fail_transaction identity
  [[ -n "$container_after" ]] || fail_transaction identity
  verify_identity "$container_after" || fail_transaction identity
  python3 "$CONFIG_TOOL" verify-container-env --env-file "$ENV_FILE" --container-id "$container_after" \
    || fail_transaction container-env
  assert_release_state_unchanged || fail_transaction release-state-changed
  safety_after=$("$SAFETY_READER") || fail_transaction safety-query
  if [[ "$worker_enable" == true ]] && post_boundary_crossed "$safety_before" "$safety_after"; then
    mkdir -p -- "$RUNTIME_CANDIDATES"
    evidence_path="$RUNTIME_CANDIDATES/$source_sha.side-effect-boundary-crossed.json"
    python3 "$CONFIG_TOOL" record-side-effect-boundary --path "$evidence_path" --manifest "$manifest" \
      --source-sha "$source_sha" --before-json "$safety_before" --after-json "$safety_after" \
      --runtime-state "$RUNTIME_STATE" || die 'RUNTIME_CONFIG_SIDE_EFFECT_EVIDENCE_FAILED'
    printf 'RUNTIME_CONFIG_SIDE_EFFECT_BOUNDARY_CROSSED source=%s evidence=%s\n' "$source_sha" "$evidence_path" >&2
    exit 3
  fi
  compare_safety_totals "$safety_before" "$safety_after" || fail_transaction operation-totals-changed
  [[ "${DEPLOY_TEST_FAIL:-}" != promote ]] || fail_transaction promote
  python3 "$CONFIG_TOOL" promote --runtime-state "$RUNTIME_STATE" --source-sha "$source_sha" \
    --values-json "$candidate_values" || fail_transaction promote
  revision=$((revision + 1))
  [[ -f "$RUNTIME_STATE" ]] || fail_transaction state-missing-after-promotion
  assert_release_state_unchanged || fail_transaction release-state-changed
  printf 'RUNTIME CONFIG ACCEPTED project=%s source=%s revision=%s config_digest=%s values=%s\n' \
    "$PROJECT" "$source_sha" "$revision" "$candidate_config_digest" "$candidate_values"
else
  [[ "$(one_container myshop api)" == "$container_before" ]] || die 'RUNTIME_CONFIG_NOOP_RECREATED_SERVICE'
  if [[ "$runtime_state_exists" == false ]]; then
    [[ "${DEPLOY_TEST_FAIL:-}" != promote ]] || die 'runtime config baseline state promotion failed'
    if ! python3 "$CONFIG_TOOL" promote --runtime-state "$RUNTIME_STATE" --source-sha "$source_sha" \
      --values-json "$candidate_values"; then
      python3 "$CONFIG_TOOL" restore-state --backup "$state_backup" --target "$RUNTIME_STATE" --existed false || \
        die 'CRITICAL_RUNTIME_CONFIG_ROLLBACK_FAILED reason=baseline-state-promotion'
      die 'runtime config baseline state promotion failed'
    fi
    revision=1
    printf 'RUNTIME CONFIG BASELINE ACCEPTED project=%s source=%s revision=%s config_digest=%s values=%s\n' \
      "$PROJECT" "$source_sha" "$revision" "$candidate_config_digest" "$candidate_values"
  else
    printf 'RUNTIME CONFIG NOOP project=%s source=%s revision=%s config_digest=%s values=%s\n' \
      "$PROJECT" "$source_sha" "$revision" "$candidate_config_digest" "$candidate_values"
  fi
  [[ "$(one_container myshop api)" == "$container_before" ]] || die 'RUNTIME_CONFIG_NOOP_RECREATED_SERVICE'
  verify_identity "$container_before" || die 'ACTIVE_RELEASE_MISMATCH: runtime config noop changed image identity'
  safety_after=$("$SAFETY_READER") || die 'production worker safety query failed after noop'
  compare_safety_totals "$safety_before" "$safety_after" || die 'operation totals changed during runtime config noop'
  assert_release_state_unchanged || die 'ACTIVE_RELEASE_MISMATCH: release state changed during runtime config noop'
fi

final_state=$(python3 "$CONFIG_TOOL" summary --runtime-state "$RUNTIME_STATE") || die 'accepted runtime config state failed final validation'
final_release_hash=$(sha256sum "$RELEASE_STATE" | awk '{print $1}')
[[ "$final_release_hash" == "$release_state_hash" ]] || die 'ACTIVE_RELEASE_MISMATCH: release state changed during runtime config transaction'
printf 'RELEASE_STATE_UNCHANGED sha256=%s\n' "$final_release_hash"
printf 'RUNTIME_CONFIG_ACTIVE %s\n' "$final_state"

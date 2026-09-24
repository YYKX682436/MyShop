#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
orchestrator="$repo_dir/ops/host-ops/storage-lifecycle.sh"
tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }

fake_bin="$tmp_dir/bin"
mkdir -p -- "$fake_bin"
lock_file="$tmp_dir/deploy.lock"
event_log="$tmp_dir/events.log"

cat >"$fake_bin/findmnt" <<'FINDMNT'
#!/usr/bin/env bash
set -Eeuo pipefail
printf '/data\n'
FINDMNT

cat >"$tmp_dir/health.sh" <<'HEALTH'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'health\n' >>"${EVENT_LOG:?}"
counter_file=${HEALTH_COUNTER_FILE:?}
count=$(cat "$counter_file" 2>/dev/null || printf 0)
count=$((count + 1))
printf '%s\n' "$count" >"$counter_file"
if (( count == 1 )); then
  exit "${HEALTH_PRE_STATUS:-0}"
fi
exit "${HEALTH_POST_STATUS:-0}"
HEALTH

cat >"$tmp_dir/myshop-retention.sh" <<'RETENTION'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'myshop held=%s lock=%s\n' "${HOST_DEPLOY_LOCK_HELD:-}" "${HOST_DEPLOY_LOCK_FILE:-}" >>"${EVENT_LOG:?}"
RETENTION

cat >"$tmp_dir/agent-retention.sh" <<'RETENTION'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'agent held=%s lock=%s\n' "${HOST_DEPLOY_LOCK_HELD:-}" "${HOST_DEPLOY_LOCK_FILE:-}" >>"${EVENT_LOG:?}"
RETENTION

cat >"$tmp_dir/maintenance.sh" <<'MAINTENANCE'
#!/usr/bin/env bash
set -Eeuo pipefail
printf 'maintenance held=%s lock=%s\n' "${HOST_DEPLOY_LOCK_HELD:-}" "${HOST_DEPLOY_LOCK_FILE:-}" >>"${EVENT_LOG:?}"
MAINTENANCE

chmod +x -- "$fake_bin/findmnt" "$tmp_dir/health.sh" "$tmp_dir/myshop-retention.sh" "$tmp_dir/agent-retention.sh" "$tmp_dir/maintenance.sh"

run_orchestrator() {
  : >"$tmp_dir/health-count"
  PATH="$fake_bin:$PATH" \
    EVENT_LOG="$1" \
    HEALTH_COUNTER_FILE="$tmp_dir/health-count" \
    HOST_DEPLOY_LOCK_FILE="$lock_file" \
    DATA_MOUNTPOINT=/data \
    HOST_STORAGE_HEALTH_SCRIPT="$tmp_dir/health.sh" \
    MYSHOP_RETENTION_SCRIPT="$tmp_dir/myshop-retention.sh" \
    AGENT_RETENTION_SCRIPT="$tmp_dir/agent-retention.sh" \
    HOST_MAINTENANCE_SCRIPT="$tmp_dir/maintenance.sh" \
    bash "$orchestrator"
}

run_orchestrator "$event_log" >"$tmp_dir/success.out"
mapfile -t events <"$event_log"
expected=(
  health
  "myshop held=1 lock=$lock_file"
  "agent held=1 lock=$lock_file"
  "maintenance held=1 lock=$lock_file"
  health
)
[[ "${#events[@]}" -eq "${#expected[@]}" ]] || fail "unexpected orchestrator event count: ${events[*]}"
for index in "${!expected[@]}"; do
  [[ "${events[$index]}" == "${expected[$index]}" ]] || fail "orchestrator order/lock propagation mismatch: ${events[*]}"
done
grep -Fq 'SUCCESS pre_status=0 post_status=0' "$tmp_dir/success.out" || fail 'successful orchestrator run did not report success'
pass 'orchestrator owns one lock and invokes both retainers plus host maintenance in order'

run_high_water_case() {
  local label=$1 pre_status=$2 post_status=$3 expected=$4 log="$tmp_dir/$1-events.log" output status
  : >"$log"
  set +e
  output=$(HEALTH_PRE_STATUS="$pre_status" HEALTH_POST_STATUS="$post_status" run_orchestrator "$log" 2>&1)
  status=$?
  set -e
  grep -Fq 'myshop held=1' "$log" || fail "$label did not run MyShop retention"
  grep -Fq 'agent held=1' "$log" || fail "$label did not run Agent retention"
  grep -Fq 'maintenance held=1' "$log" || fail "$label did not run host maintenance"
  [[ "$(grep -c '^health$' "$log")" -eq 2 ]] || fail "$label did not run both storage checks"
  if [[ "$expected" == SUCCESS ]]; then
    (( status == 0 )) || fail "$label unexpectedly failed: $output"
    grep -Fq '[storage-lifecycle] SUCCESS' <<<"$output" || fail "$label did not report success"
  else
    (( status != 0 )) || fail "$label incorrectly returned success"
    grep -Fq "$expected" <<<"$output" || fail "$label did not report $expected"
  fi
  pass "$label precheck=$pre_status cleanup=executed postcheck=$post_status result=$expected"
}

run_high_water_case 69-percent 1 0 SUCCESS
run_high_water_case 75-percent 4 4 MAINTENANCE_INCOMPLETE
run_high_water_case 85-percent 3 3 HARD_FAIL
run_high_water_case 95-percent 2 2 CRITICAL

contention_log="$tmp_dir/contention-events.log"
ready_file="$tmp_dir/lock-holder.ready"
(
  exec 8>"$lock_file"
  flock -n 8
  : >"$ready_file"
  sleep 5
) &
holder_pid=$!
for _ in $(seq 1 50); do
  [[ -f "$ready_file" ]] && break
  sleep 0.02
done
[[ -f "$ready_file" ]] || fail 'lock holder did not acquire the lock'

set +e
run_orchestrator "$contention_log" >"$tmp_dir/contention.out" 2>&1
contention_status=$?
set -e
wait "$holder_pid" || true
(( contention_status != 0 )) || fail 'lock contention returned success'
[[ ! -s "$contention_log" ]] || fail 'child retention/maintenance ran while host lock was held'
grep -Fq 'deployment/maintenance lock is held' "$tmp_dir/contention.out" || fail 'lock contention failure was not explicit'
pass 'external lock contention fails closed before any child script executes'

printf 'Storage lifecycle shell integration regression passed\n'

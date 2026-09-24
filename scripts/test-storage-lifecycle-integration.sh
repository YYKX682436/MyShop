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
exit 0
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
  PATH="$fake_bin:$PATH" \
    EVENT_LOG="$1" \
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

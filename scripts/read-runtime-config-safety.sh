#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-runtime-config] ERROR: %s\n' "$*" >&2; exit 1; }
one_container() {
  local project=$1 service=$2 ids count
  ids=$(docker ps --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=$service" -q) || return 1
  count=$(printf '%s\n' "$ids" | awk 'NF {n++} END {print n+0}')
  (( count == 1 )) || return 1
  printf '%s\n' "$ids"
}

db_id=$(one_container myshop db) || die 'MyShop database container selection failed'
myshop_csv=$(docker exec -i "$db_id" sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -X -qAt -F "," -v ON_ERROR_STOP=1' <<'SQL'
SELECT
  (SELECT COUNT(*) FROM sales_channel_operations
    WHERE operation_type = 'ENSURE_LISTING' AND status = 'RUNNING'),
  (SELECT COUNT(*) FROM sales_channel_operations
    WHERE operation_type = 'ENSURE_LISTING' AND status = 'RUNNING'
      AND started_at IS NOT NULL AND started_at < CURRENT_TIMESTAMP - INTERVAL '5 minutes'),
  (SELECT COUNT(*) FROM sales_channel_listings
    WHERE convergence_cutover_state = 'ACTIVE'),
  (SELECT COUNT(*) FROM sales_channel_operations operation
    JOIN sales_channel_listings listing ON listing.id = operation.sales_channel_listing_id
    WHERE operation.status = 'PENDING'
      AND operation.operation_type = 'ENSURE_LISTING'
      AND (operation.attempt_count > 0 OR listing.convergence_cutover_state = 'ACTIVE')
      AND (operation.next_attempt_at IS NULL OR operation.next_attempt_at <= CURRENT_TIMESTAMP)
      AND NOT EXISTS (
        SELECT 1 FROM sales_channel_operations running_operation
        WHERE running_operation.sales_channel_listing_id = operation.sales_channel_listing_id
          AND running_operation.status = 'RUNNING')
      AND NOT EXISTS (
        SELECT 1 FROM sales_channel_operations legacy_unknown
        WHERE legacy_unknown.sales_channel_listing_id = operation.sales_channel_listing_id
          AND legacy_unknown.operation_type <> 'ENSURE_LISTING'
          AND legacy_unknown.status = 'UNKNOWN')
      AND NOT EXISTS (
        SELECT 1 FROM sales_channel_operations older
        WHERE older.sales_channel_listing_id = operation.sales_channel_listing_id
          AND older.operation_type = 'ENSURE_LISTING'
          AND older.desired_revision < operation.desired_revision
          AND older.status IN ('PENDING', 'RUNNING', 'UNKNOWN'))),
  (SELECT COUNT(*) FROM sales_channel_operations
    WHERE operation_type = 'ENSURE_LISTING'
      AND status IN ('PENDING', 'RUNNING', 'UNKNOWN') AND attempt_count > 0),
  (SELECT COUNT(*) FROM sales_channel_operations
    WHERE operation_type <> 'ENSURE_LISTING'
      AND (status IN ('RUNNING', 'UNKNOWN')
        OR (status = 'PENDING' AND (attempt_count > 0 OR operation_type = 'RECONCILE')))),
  (SELECT COUNT(*) FROM sales_channel_operations
    WHERE operation_type = 'ENSURE_LISTING' AND attempt_count > 0),
  (SELECT COUNT(*) FROM sales_channel_operations);
SQL
) || die 'MyShop worker safety query failed'

agent_runtime_root=${AGENT_RUNTIME_ROOT:-/opt/xianyu-agent/runtime}
agent_env_file=${AGENT_ENV_FILE:-/opt/xianyu-agent/.env.production}
agent_config_tool=${AGENT_RUNTIME_CONFIG_TOOL:-$agent_runtime_root/ops/runtime_config_state.py}
agent_safety_reader=${AGENT_RUNTIME_CONFIG_SAFETY_READER:-$agent_runtime_root/ops/read-runtime-config-safety.sh}
agent_state=${AGENT_RUNTIME_CONFIG_STATE:-$agent_runtime_root/runtime-config-state.json}
agent_container=$(one_container xianyu-agent backend-web) || die 'Agent backend-web container selection failed'
[[ -f "$agent_config_tool" && -x "$agent_safety_reader" ]] || die 'Agent runtime-config safety helpers are missing'
python3 "$agent_config_tool" verify-baseline --runtime-state "$agent_state" --env-file "$agent_env_file" \
  --container-id "$agent_container" >/dev/null || die 'Agent runtime config baseline is not accepted'
agent_active=$(python3 "$agent_config_tool" summary --runtime-state "$agent_state") || die 'Agent runtime config state is invalid'
agent_safety=$("$agent_safety_reader") || die 'Agent durable command safety query failed'

python3 - "$myshop_csv" "$agent_active" "$agent_safety" <<'PY'
import json
import re
import sys

fields = sys.argv[1].split(",")
if len(fields) != 8 or any(not re.fullmatch(r"[0-9]+", item) for item in fields):
    raise SystemExit("MyShop worker safety query returned an invalid result")
keys = ("running_ensure", "stale_running_ensure", "active_listings", "ready_selector",
        "attempted_recovery_ensure", "legacy_unresolved", "attempted_ensure_total",
        "myshop_operation_total")
myshop = dict(zip(keys, map(int, fields)))
active = json.loads(sys.argv[2])
agent = json.loads(sys.argv[3])
values = active.get("values", {})
if set(values) != {"MYSHOP_INTEGRATION_V2_WRITES_ENABLED", "MYSHOP_INTEGRATION_V2_ADOPTION_ENABLED"}:
    raise SystemExit("Agent accepted runtime values are incomplete")
myshop.update({
    "agent_writes_enabled": values["MYSHOP_INTEGRATION_V2_WRITES_ENABLED"] == "true",
    "agent_adoption_enabled": values["MYSHOP_INTEGRATION_V2_ADOPTION_ENABLED"] == "true",
    "agent_operation_total": agent["operation_total"],
    "agent_provider_attempt_total": agent["provider_attempt_total"],
    "agent_nonterminal_operations": agent["nonterminal_operations"],
    "agent_running_unknown_commands": agent["running_unknown_commands"],
    "agent_active_provider_attempts": agent["active_provider_attempts"],
})
print(json.dumps(myshop, sort_keys=True, separators=(",", ":")))
PY

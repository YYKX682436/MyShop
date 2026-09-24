#!/usr/bin/env bash
set -Eeuo pipefail

# The contract is packaged by the exact API artifact at
# /app/production-schema-contract.json. It is versioned with the code that
# consumes the schema, not maintained as a second migration-number list here.
die() { printf '[myshop-schema] ERROR: %s\n' "$*" >&2; exit 1; }
api_id=$(docker ps --filter 'label=com.docker.compose.project=myshop' --filter 'label=com.docker.compose.service=api' -q | head -n 1)
db_id=$(docker ps --filter 'label=com.docker.compose.project=myshop' --filter 'label=com.docker.compose.service=db' -q | head -n 1)
[[ -n "$api_id" && -n "$db_id" ]] || die 'MyShop api/db containers are missing'
contract_json=$(docker exec "$api_id" sh -c 'cat /app/production-schema-contract.json') || die 'API artifact has no production schema contract'
sql=$(python3 - "$contract_json" <<'PY'
import json
import re
import sys

contract = json.loads(sys.argv[1])
if contract.get("version") != 1:
    raise SystemExit("unsupported contract version")
for identifier in list(contract.get("tables", {})) + contract.get("indexes", []):
    if not re.fullmatch(r"[a-z][a-z0-9_]*", identifier):
        raise SystemExit(f"unsafe identifier: {identifier}")
for table, columns in contract["tables"].items():
    for column in columns:
        if not re.fullmatch(r"[a-z][a-z0-9_]*", column):
            raise SystemExit(f"unsafe column: {table}.{column}")
print("SELECT 'flyway_failures=' || COUNT(*) FROM flyway_schema_history WHERE success = false;")
print("SELECT 'flyway_count=' || COUNT(*) FROM flyway_schema_history WHERE type = 'SQL';")
print("SELECT 'flyway_latest=' || COALESCE(MAX(version), '') FROM flyway_schema_history WHERE type = 'SQL';")
for table, columns in contract["tables"].items():
    print(f"SELECT 'table={table}' WHERE to_regclass('public.{table}') IS NOT NULL;")
    for column in columns:
        print("SELECT 'column=%s.%s' FROM information_schema.columns WHERE table_schema='public' AND table_name='%s' AND column_name='%s';" % (table, column, table, column))
for index in contract["indexes"]:
    print(f"SELECT 'index={index}' FROM pg_indexes WHERE schemaname='public' AND indexname='{index}';")
PY
) || die 'could not materialize schema contract query'
result=$(printf '%s\n' "$sql" | docker exec -i "$db_id" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atq') || die 'schema contract query failed'
expected_count=$(printf '%s' "$contract_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["migration_count"])')
expected_latest=$(printf '%s' "$contract_json" | python3 -c 'import json,sys; print(json.load(sys.stdin)["latest_migration_version"])')
printf '%s\n' "$result"
printf '%s\n' "$result" | grep -Fx 'flyway_failures=0' >/dev/null || die 'Flyway contains failed migrations'
printf '%s\n' "$result" | grep -Fx "flyway_count=$expected_count" >/dev/null || die 'Flyway migration count does not match artifact contract'
printf '%s\n' "$result" | grep -Fx "flyway_latest=$expected_latest" >/dev/null || die 'Flyway latest migration does not match artifact contract'
actual_expected=$(printf '%s\n' "$sql" | grep -Ec "^SELECT '(table|column|index)=")
actual_found=$(printf '%s\n' "$result" | grep -Ec '^(table|column|index)=')
[[ "$actual_found" == "$actual_expected" ]] || die "schema contract objects missing: expected=$actual_expected found=$actual_found"
printf 'MYSHOP_SCHEMA_CONTRACT_OK objects=%s migrations=%s latest=%s\n' "$actual_found" "$expected_count" "$expected_latest"

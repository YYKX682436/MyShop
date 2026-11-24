#!/usr/bin/env bash
set -Eeuo pipefail

# The contract is packaged by the exact API artifact at
# /app/production-schema-contract.json. It is versioned with the code that
# consumes the schema, not maintained as a second migration-number list here.
die() { printf '[myshop-schema] ERROR: %s\n' "$*" >&2; exit 1; }
project_name=${MYSHOP_COMPOSE_PROJECT_NAME:-myshop}
[[ "$project_name" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die 'MYSHOP_COMPOSE_PROJECT_NAME is invalid'
api_id=$(docker ps --filter "label=com.docker.compose.project=$project_name" --filter 'label=com.docker.compose.service=api' -q \
  | awk 'NF {count++; value=$0} END {if (count == 1) print value; else exit 1}') || true
db_id=$(docker ps --filter "label=com.docker.compose.project=$project_name" --filter 'label=com.docker.compose.service=db' -q \
  | awk 'NF {count++; value=$0} END {if (count == 1) print value; else exit 1}') || true
[[ -n "$api_id" && -n "$db_id" ]] || die "MyShop api/db containers are missing for Compose project $project_name"
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
# Flyway stores version as text. A lexical MAX reports `9` after `34`, which
# would reject a valid production schema even though all migrations succeeded.
print("SELECT 'flyway_latest=' || COALESCE(MAX(version::bigint)::text, '') FROM flyway_schema_history WHERE type = 'SQL';")
for table, columns in contract["tables"].items():
    print(f"SELECT 'table={table}' WHERE to_regclass('public.{table}') IS NOT NULL;")
    for column in columns:
        print("SELECT 'column=%s.%s' FROM information_schema.columns WHERE table_schema='public' AND table_name='%s' AND column_name='%s';" % (table, column, table, column))
for index in contract["indexes"]:
    print(f"SELECT 'index={index}' FROM pg_indexes WHERE schemaname='public' AND indexname='{index}';")
if {"media_assets", "media_references"}.issubset(contract["tables"]):
    print("SELECT 'media_index_complete=' || CASE WHEN obj_description(to_regclass('public.media_references'), 'pg_class') = 'myshop-media-index-complete-v1' THEN '1' ELSE '0' END;")
    print("SELECT 'media_legacy_guards=' || COUNT(*) FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND NOT t.tgisinternal AND t.tgname='myshop_media_guard' AND t.tgfoid=to_regprocedure('public.myshop_media_guard_write()');")
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
if printf '%s\n' "$sql" | grep -Fq "SELECT 'media_index_complete="; then
  printf '%s\n' "$result" | grep -Fx 'media_index_complete=1' >/dev/null || die 'media index cutover marker is missing'
  printf '%s\n' "$result" | grep -Fx 'media_legacy_guards=0' >/dev/null || die 'legacy global media trigger remains active'
fi
printf 'MYSHOP_SCHEMA_CONTRACT_OK objects=%s migrations=%s latest=%s\n' "$actual_found" "$expected_count" "$expected_latest"

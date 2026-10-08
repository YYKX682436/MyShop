#!/usr/bin/env bash
set -Eeuo pipefail

script="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/check-production-schema.sh"

# Flyway's version column is textual. This regression prevents a lexical MAX
# from treating version 9 as newer than version 34.
grep -Fq "MAX(version::bigint)::text" "$script"
if grep -Fq "MAX(version), ''" "$script"; then
  printf 'FAIL: schema contract still uses lexical Flyway MAX\n' >&2
  exit 1
fi

python3 - "$(CDPATH= cd -- "$(dirname -- "$script")/../apps/api/src/main/resources/db" && pwd -P)/production-schema-contract.json" <<'PY'
import json
import pathlib
import re
import sys

contract_path = pathlib.Path(sys.argv[1])
contract = json.load(open(contract_path, encoding="utf-8"))
versions = []
for migration in (contract_path.parent / "migration").glob("V*__*.sql"):
    match = re.fullmatch(r"V(\d+)__.+\.sql", migration.name)
    assert match, f"invalid Flyway migration filename: {migration.name}"
    versions.append(int(match.group(1)))
assert versions, "no Flyway SQL migrations found"
assert len(versions) == len(set(versions)), "duplicate Flyway migration versions"
assert contract["migration_count"] == len(versions), (
    f"contract migration_count={contract['migration_count']} does not match source={len(versions)}"
)
assert contract["latest_migration_version"] == str(max(versions)), (
    "contract latest_migration_version does not match source migrations"
)
print(
    "production schema migration contract matches source "
    f"(count={len(versions)}, latest={max(versions)})"
)
assert contract["tables"]["products"] == ["id", "title", "retail_price"]
assert contract["tables"]["sales_channel_accounts"] == ["id", "provider", "status"]
assert contract["tables"]["sales_channel_orders"] == [
    "id",
    "sales_channel_account_id",
    "sales_channel_listing_id",
    "sales_channel_listing_generation_id",
    "order_id",
    "ingest_status",
    "delivery_status",
]
assert "name" not in contract["tables"]["products"]
assert "price" not in contract["tables"]["products"]
assert "channel_type" not in contract["tables"]["sales_channel_accounts"]
assert "status" not in contract["tables"]["sales_channel_orders"]
print("production schema contract current-column regression passed")

versions = ["1", "9", "10", "30", "31", "32", "33", "34"]
assert max(map(int, versions)) == 34
assert max(versions) == "9"  # documents the failure this test prevents
print("production schema contract numeric-version regression passed")
PY

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
import sys

contract = json.load(open(sys.argv[1], encoding="utf-8"))
assert contract["tables"]["products"] == ["id", "title", "retail_price"]
assert contract["tables"]["sales_channel_accounts"] == ["id", "provider", "status"]
assert contract["tables"]["sales_channel_orders"] == [
    "id",
    "sales_channel_account_id",
    "sales_channel_listing_id",
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

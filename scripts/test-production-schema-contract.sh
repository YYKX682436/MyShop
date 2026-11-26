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
assert contract["tables"]["products"] == ["id", "title", "retail_price", "card_content_column_count"]
assert "account_payment_transaction_id" in contract["tables"]["payment_attempts"]
assert "account_payment_transactions" in contract["tables"]
assert "upgrade_rank" in contract["tables"]["user_levels"]
assert "manual_delivery" in contract["tables"]["support_system_events"]
assert "subsite_level_upgrade_rules" in contract["tables"]
for required_index in (
    "uq_user_levels_upgrade_rank",
    "uq_account_payment_pending_level_upgrade",
    "uq_payment_attempt_unresolved_account_transaction",
    "uq_balance_transactions_account_payment_type",
):
    assert required_index in contract["indexes"], f"missing account-payment index: {required_index}"
assert contract["tables"]["user_secret_prices"] == [
    "id", "user_id", "product_id", "spec_id", "price", "created_at", "updated_at"
]
for required_index in ("uq_user_secret_price_product", "uq_user_secret_price_spec"):
    assert required_index in contract["indexes"], f"missing user secret-price unique index: {required_index}"
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

# Exercise the actual schema checker against three database responses. The
# deployed API's contract selects the cutover checks; neither is advisory.
tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT
mkdir -p "$tmp_dir/bin"
cat >"$tmp_dir/bin/docker" <<'PYDOCKER'
#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
if args[0] == "ps":
    print("isolated-container")
elif args[0] == "exec" and "production-schema-contract.json" in " ".join(args):
    print(open(os.environ["FAKE_SCHEMA_CONTRACT"], encoding="utf-8").read())
elif args[0] == "exec" and "psql" in " ".join(args):
    sql = sys.stdin.read()
    contract = json.load(open(os.environ["FAKE_SCHEMA_CONTRACT"], encoding="utf-8"))
    print("flyway_failures=0")
    print("flyway_count=" + str(contract["migration_count"]))
    print("flyway_latest=" + str(contract["latest_migration_version"]))
    for table, columns in contract["tables"].items():
        print("table=" + table)
        for column in columns: print("column=" + table + "." + column)
    for index in contract["indexes"]: print("index=" + index)
    if "media_index_complete=" in sql:
        print("media_index_complete=" + ("0" if os.environ.get("FAKE_SCHEMA_CASE") == "missing" else "1"))
        print("media_legacy_guards=" + ("1" if os.environ.get("FAKE_SCHEMA_CASE") == "legacy" else "0"))
else:
    raise SystemExit("unexpected fake Docker command")
PYDOCKER
chmod +x "$tmp_dir/bin/docker"
contract_path="$(CDPATH= cd -- "$(dirname -- "$script")/../apps/api/src/main/resources/db" && pwd -P)/production-schema-contract.json"
for case_name in complete missing legacy; do
  if output=$(PATH="$tmp_dir/bin:$PATH" FAKE_SCHEMA_CONTRACT="$contract_path" FAKE_SCHEMA_CASE="$case_name" bash "$script" 2>&1); then
    [[ "$case_name" == complete ]] || { printf 'FAIL: accepted %s media cutover\n' "$case_name" >&2; exit 1; }
  else
    [[ "$case_name" != complete ]] || { printf '%s\n' "$output" >&2; exit 1; }
    if [[ "$case_name" == missing ]]; then [[ "$output" == *'media index cutover marker is missing'* ]] || exit 1
    else [[ "$output" == *'legacy global media trigger remains active'* ]] || exit 1; fi
  fi
done
printf 'production schema cutover checks reject missing markers and old global triggers\n'

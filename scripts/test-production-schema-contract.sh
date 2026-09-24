#!/usr/bin/env bash
set -Eeuo pipefail

script="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)/check-production-schema.sh"

# Flyway's version column is textual. This regression prevents a lexical MAX
# from treating version 9 as newer than version 33.
grep -Fq "MAX(version::bigint)::text" "$script"
if grep -Fq "MAX(version), ''" "$script"; then
  printf 'FAIL: schema contract still uses lexical Flyway MAX\n' >&2
  exit 1
fi

python3 - <<'PY'
versions = ["1", "9", "10", "30", "31", "32", "33"]
assert max(map(int, versions)) == 33
assert max(versions) == "9"  # documents the failure this test prevents
print("production schema contract numeric-version regression passed")
PY

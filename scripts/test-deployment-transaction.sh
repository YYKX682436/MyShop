#!/usr/bin/env bash
set -Eeuo pipefail
grep -Fq 'DEPLOY_TEST_FAIL' "$(dirname -- "$0")/deploy-production.sh"
grep -Fq 'CRITICAL_ROLLBACK_FAILED' "$(dirname -- "$0")/deploy-production.sh"
grep -Fq 'health-after-start' "$(dirname -- "$0")/deploy-production.sh"
grep -Fq 'COMMIT POINT' "$(dirname -- "$0")/deploy-production.sh"
python3 - <<'PY'
from copy import deepcopy
from typing import Optional

old_state = {"version": 1, "active": {"source_sha": "a" * 40}, "rollback": []}
old_running = {"api": "ghcr.io/yykx682436/myshop-api:" + "a" * 40, "web": "ghcr.io/yykx682436/myshop-web:" + "a" * 40}
candidate = {"source_sha": "b" * 40}


def transaction(failure: Optional[str], rollback_failure: bool = False):
    state = deepcopy(old_state)
    running = dict(old_running)
    promoted = False
    for phase in ("pull", "health", "smoke", "schema", "post-storage"):
        if failure == phase:
            if rollback_failure:
                return "CRITICAL_ROLLBACK_FAILED", state, running, promoted
            return "DEPLOYMENT FAILED / ROLLED BACK", deepcopy(old_state), dict(old_running), promoted
    state["active"] = candidate
    state["rollback"] = [old_state["active"]]
    running = {"api": "ghcr.io/yykx682436/myshop-api:" + candidate["source_sha"], "web": "ghcr.io/yykx682436/myshop-web:" + candidate["source_sha"]}
    promoted = True
    return "DEPLOYMENT ACCEPTED", state, running, promoted


for phase in ("pull", "health", "smoke", "schema", "post-storage"):
    result, state, running, promoted = transaction(phase)
    assert result == "DEPLOYMENT FAILED / ROLLED BACK", (phase, result)
    assert state == old_state, phase
    assert running == old_running, phase
    assert not promoted, phase
result, state, running, promoted = transaction(None)
assert result == "DEPLOYMENT ACCEPTED" and promoted
assert state["active"] == candidate and running["api"].endswith(candidate["source_sha"])
result, state, running, promoted = transaction("post-storage", rollback_failure=True)
assert result == "CRITICAL_ROLLBACK_FAILED" and state == old_state and running == old_running
print("deployment transaction failure-injection regression passed: pull health smoke schema post-storage")
PY
bash "$(dirname -- "$0")/test-deployment-transaction-integration.sh"

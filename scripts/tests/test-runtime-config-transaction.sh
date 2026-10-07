#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd -P)
deploy="$repo_dir/scripts/deploy-runtime-config.sh"
config_tool="$repo_dir/scripts/runtime_config_state.py"
tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT
fake_bin="$tmp_dir/fake-bin"
mkdir -p "$fake_bin"

cat >"$fake_bin/flock" <<'FLOCK'
#!/usr/bin/env bash
exit 0
FLOCK
cat >"$fake_bin/docker" <<'DOCKER'
#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

root = Path(os.environ["FAKE_ROOT"])
args = sys.argv[1:]
if args[0] == "ps":
    print((root / "container-id").read_text())
elif args[0] == "inspect" and "{{json .}}" in args:
    print((root / "container.json").read_text())
elif args[0] == "inspect":
    value = json.loads((root / "container.json").read_text())
    fmt = args[2]
    if fmt == "{{.Config.Image}}":
        print(value["Config"]["Image"])
    elif fmt == "{{.Image}}":
        print(value["Image"])
    elif fmt == "{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}":
        print(value["State"]["Status"] + " " + value["State"]["Health"]["Status"])
    else:
        raise SystemExit("unexpected docker inspect format")
elif args[0] == "image" and args[1] == "inspect":
    ref = args[-1]
    digest = "sha256:" + ("1" if "myshop-api:" in ref else "2") * 64
    if args[2] == "--format" and args[3] == "{{.Id}}":
        print(digest)
    elif args[2] == "--format" and args[3] == "{{range .RepoDigests}}{{println .}}{{end}}":
        print(ref.rsplit(":", 1)[0] + "@" + digest)
    else:
        raise SystemExit("unexpected docker image inspect format")
else:
    raise SystemExit("unexpected fake docker command")
DOCKER
cat >"$tmp_dir/compose.py" <<'COMPOSE'
#!/usr/bin/env python3
import json
import os
import re
import sys
from pathlib import Path

root = Path(os.environ["FAKE_ROOT"])
args = sys.argv[1:]
env_path = Path(args[args.index("--env-file") + 1])
action = next((item for item in args if item in ("config", "up")), "")
data = env_path.read_text()
mode = env_path.stat().st_mode & 0o777
with (root / "compose.log").open("a") as log:
    log.write(json.dumps({"action": action, "args": args, "mode": mode}) + "\n")
worker = re.search(r"^SALES_CHANNEL_WORKER_ENABLED=(.*)$", data, re.M).group(1)
if action == "config":
    if os.environ.get("DEPLOY_TEST_FAIL") == "compose" and worker == "true":
        raise SystemExit(1)
    raise SystemExit(0)
if action == "up":
    if os.environ.get("DEPLOY_TEST_FAIL") == "rollback-start" and worker == "false":
        raise SystemExit(1)
    if os.environ.get("DEPLOY_TEST_FAIL") == "container-env" and worker == "true":
        data = re.sub(r"(?m)^SALES_CHANNEL_WORKER_ENABLED=.*$", "SALES_CHANNEL_WORKER_ENABLED=false", data)
    generation_path = root / "generation"
    generation = int(generation_path.read_text()) + 1
    generation_path.write_text(str(generation))
    (root / "container-id").write_text(f"api-{generation}")
    env_values = [line for line in data.splitlines() if "=" in line]
    image = "ghcr.io/yykx682436/myshop-api:" + "a" * 40
    container = {
        "Config": {"Image": image, "Env": env_values},
        "State": {"Status": "running", "Health": {"Status": "healthy"}},
        "Image": "sha256:" + "1" * 64,
    }
    (root / "container.json").write_text(json.dumps(container))
    raise SystemExit(0)
raise SystemExit(99)
COMPOSE
cat >"$tmp_dir/safety.sh" <<'SAFETY'
#!/usr/bin/env python3
import json
import os
from pathlib import Path
root = Path(os.environ["FAKE_ROOT"])
calls = int((root / "safety-calls").read_text()) + 1
(root / "safety-calls").write_text(str(calls))
value = {
 "running_ensure": 0, "stale_running_ensure": 0, "active_listings": 0,
 "ready_selector": 0, "attempted_recovery_ensure": 0, "legacy_unresolved": 0,
 "attempted_ensure_total": 0, "myshop_operation_total": 0,
 "agent_writes_enabled": False, "agent_adoption_enabled": False,
 "agent_operation_total": 0, "agent_provider_attempt_total": 0,
 "agent_nonterminal_operations": 0, "agent_running_unknown_commands": 0,
 "agent_active_provider_attempts": 0
}
blocked = os.environ.get("FAKE_BLOCK", "")
if blocked:
    value[blocked] = 1
side = os.environ.get("FAKE_SIDE_EFFECT", "")
if calls >= 2:
    if side == "attempt":
        value["attempted_ensure_total"] = 1
    elif side == "running":
        value["running_ensure"] = 1
    elif side == "agent-operation":
        value["agent_operation_total"] = 1
    elif side == "agent-attempt":
        value["agent_provider_attempt_total"] = 1
print(json.dumps(value, sort_keys=True))
SAFETY
chmod 755 "$fake_bin/flock" "$fake_bin/docker" "$tmp_dir/compose.py" "$tmp_dir/safety.sh"

write_fixture() {
  local case_root=$1 change=$2 state_mode=$3
  mkdir -p "$case_root/runtime"
  printf 'DB_PASSWORD=do-not-print-this-secret\nSALES_CHANNEL_WORKER_ENABLED=false\nSALES_CHANNEL_WORKER_BATCH_SIZE=10\nSALES_CHANNEL_WORKER_INTERVAL_MS=1000\n' >"$case_root/env"
  chmod 640 "$case_root/env"
  python3 - "$case_root" "$change" "$state_mode" "$config_tool" <<'PY'
import importlib.util
import json
import sys
from pathlib import Path
root=Path(sys.argv[1])
change=sys.argv[2] == "change"
sha="a"*40
images={service:{"ref":f"ghcr.io/yykx682436/myshop-{service}:{sha}",
 "digest":"sha256:"+("1" if service=="api" else "2")*64} for service in ("api","web")}
release={"version":1,
 "active":{"source_sha":sha,"accepted_at":"2026-10-06T00:00:00Z","images":images},
 "rollback":[],"history":[]}
(root/"runtime/deploy-state.json").write_text(json.dumps(release,sort_keys=True))
manifest={"version":1,"project":"myshop","expected_active_source_sha":sha,
 "created_at":"2026-10-07T00:00:00Z","changes":{}}
if change:
 manifest["changes"]={"SALES_CHANNEL_WORKER_ENABLED":{"from":"false","to":"true"}}
(root/"manifest.json").write_text(json.dumps(manifest,sort_keys=True))
spec=importlib.util.spec_from_file_location("runtime_config_state",sys.argv[4])
module=importlib.util.module_from_spec(spec)
sys.path.insert(0,str(Path(sys.argv[4]).parent))
spec.loader.exec_module(module)
if sys.argv[3] == "present":
 module.promote_state(root/"runtime/runtime-config-state.json",{
  "SALES_CHANNEL_WORKER_BATCH_SIZE":"10",
  "SALES_CHANNEL_WORKER_ENABLED":"false",
  "SALES_CHANNEL_WORKER_INTERVAL_MS":"1000"},sha)
env=(root/"env").read_text().splitlines()
container={"Config":{"Image":images["api"]["ref"],"Env":[line for line in env if "=" in line]},
 "State":{"Status":"running","Health":{"Status":"healthy"}},"Image":"sha256:"+"1"*64}
(root/"container.json").write_text(json.dumps(container))
(root/"container-id").write_text("api-1")
(root/"generation").write_text("1")
(root/"compose.log").write_text("")
(root/"safety-calls").write_text("0")
PY
}

run_case() {
  local name=$1 change=$2 state_mode=$3 injected=${4:-} block=${5:-} side=${6:-}
  local case_root="$tmp_dir/$name" output status
  mkdir -p "$case_root"
  write_fixture "$case_root" "$change" "$state_mode"
  local release_hash state_hash env_hash id_before
  release_hash=$(sha256sum "$case_root/runtime/deploy-state.json" | awk '{print $1}')
  state_hash=absent
  [[ ! -f "$case_root/runtime/runtime-config-state.json" ]] || state_hash=$(sha256sum "$case_root/runtime/runtime-config-state.json" | awk '{print $1}')
  env_hash=$(sha256sum "$case_root/env" | awk '{print $1}')
  id_before=$(cat "$case_root/container-id")
  set +e
  output=$(env PATH="$fake_bin:$PATH" FAKE_ROOT="$case_root" DEPLOY_TEST_FAIL="$injected" \
    FAKE_BLOCK="$block" FAKE_SIDE_EFFECT="$side" MYSHOP_RUNTIME_ROOT="$case_root/runtime" \
    MYSHOP_DEPLOY_STATE="$case_root/runtime/deploy-state.json" \
    MYSHOP_RUNTIME_CONFIG_STATE="$case_root/runtime/runtime-config-state.json" \
    MYSHOP_ENV_FILE="$case_root/env" MYSHOP_COMPOSE_WRAPPER="$tmp_dir/compose.py" \
    MYSHOP_RUNTIME_CONFIG_SAFETY_READER="$tmp_dir/safety.sh" \
    HOST_DEPLOY_LOCK_FILE="$case_root/lock" "$deploy" "$case_root/manifest.json" 2>&1)
  status=$?
  set -e
  printf '%s\n' "$output" >"$case_root/output"
  if [[ "$name" == noop-bootstrap || "$name" == noop-existing ]]; then
    [[ $status == 0 ]] || { cat "$case_root/output"; exit 1; }
    ! grep -q '"action": "up"' "$case_root/compose.log"
    [[ "$(cat "$case_root/container-id")" == "$id_before" ]]
    grep -q 'RELEASE_STATE_UNCHANGED' "$case_root/output"
  fi
  if [[ "$name" == worker-enable-success ]]; then
    [[ $status == 0 ]] || { cat "$case_root/output"; exit 1; }
    grep -q 'RUNTIME CONFIG ACCEPTED' "$case_root/output"
    [[ "$(cat "$case_root/container-id")" != "$id_before" ]]
    python3 - "$case_root" <<'PY'
import json,sys
from pathlib import Path
root=Path(sys.argv[1])
state=json.loads((root/"runtime/runtime-config-state.json").read_text())
assert state["active"]["revision"] == 2
assert state["active"]["values"]["SALES_CHANNEL_WORKER_ENABLED"] == "true"
events=[json.loads(x) for x in (root/"compose.log").read_text().splitlines()]
ups=[event for event in events if event["action"] == "up"]
assert len(ups) == 1
assert ups[0]["args"][-1] == "api"
assert ups[0]["mode"] == 0o640
assert any(event["mode"] == 0o600 for event in events)
PY
  fi
  if [[ "$name" == fail-health || "$name" == fail-container-env || "$name" == fail-promote ]]; then
    [[ $status == 1 ]] || { cat "$case_root/output"; exit 1; }
    grep -q 'RUNTIME CONFIG FAILED / ROLLED BACK' "$case_root/output"
    [[ "$(sha256sum "$case_root/env" | awk '{print $1}')" == "$env_hash" ]]
    [[ "$(sha256sum "$case_root/runtime/deploy-state.json" | awk '{print $1}')" == "$release_hash" ]]
    [[ "$(sha256sum "$case_root/runtime/runtime-config-state.json" | awk '{print $1}')" == "$state_hash" ]]
    grep -q 'SALES_CHANNEL_WORKER_ENABLED=false' "$case_root/env"
    [[ "$(cat "$case_root/container-id")" != "$id_before" ]]
  fi
  if [[ "$name" == fail-rollback ]]; then
    [[ $status == 2 ]] || { cat "$case_root/output"; exit 1; }
    grep -q 'CRITICAL_RUNTIME_CONFIG_ROLLBACK_FAILED' "$case_root/output"
  fi
  if [[ "$name" == guard-* ]]; then
    [[ $status == 1 ]] || { cat "$case_root/output"; exit 1; }
    grep -q 'RUNTIME_CONFIG_WORKER_ZERO_WORK_GUARD' "$case_root/output"
    ! grep -q '"action": "up"' "$case_root/compose.log"
    [[ "$(sha256sum "$case_root/env" | awk '{print $1}')" == "$env_hash" ]]
    [[ "$(cat "$case_root/container-id")" == "$id_before" ]]
  fi
  if [[ "$name" == side-effect-* ]]; then
    [[ $status == 3 ]] || { cat "$case_root/output"; exit 1; }
    grep -q 'RUNTIME_CONFIG_SIDE_EFFECT_BOUNDARY_CROSSED' "$case_root/output"
    [[ -f "$case_root/runtime/candidates/runtime-config/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.side-effect-boundary-crossed.json" ]]
    [[ "$(sha256sum "$case_root/runtime/runtime-config-state.json" | awk '{print $1}')" == "$state_hash" ]]
    grep -q 'SALES_CHANNEL_WORKER_ENABLED=true' "$case_root/env"
    [[ "$(cat "$case_root/container-id")" != "$id_before" ]]
    [[ "$(grep -c '"action": "up"' "$case_root/compose.log")" == 1 ]]
  fi
  if [[ "$name" == fail-compose ]]; then
    [[ $status == 1 ]] || { cat "$case_root/output"; exit 1; }
    grep -q 'RUNTIME_CONFIG_CANDIDATE_COMPOSE_INVALID' "$case_root/output"
    ! grep -q '"action": "up"' "$case_root/compose.log"
    [[ "$(sha256sum "$case_root/env" | awk '{print $1}')" == "$env_hash" ]]
  fi
  [[ "$(sha256sum "$case_root/runtime/deploy-state.json" | awk '{print $1}')" == "$release_hash" ]]
  ! grep -q 'do-not-print-this-secret' "$case_root/output"
}

run_case noop-bootstrap noop absent
run_case noop-existing noop present
run_case worker-enable-success change present
run_case fail-health change present health
run_case fail-container-env change present container-env
run_case fail-promote change present promote
run_case fail-rollback change present rollback-start
run_case guard-active change present "" active_listings
run_case guard-ready change present "" ready_selector
run_case guard-attempted-recovery change present "" attempted_recovery_ensure
run_case guard-legacy change present "" legacy_unresolved
run_case guard-running change present "" running_ensure
run_case guard-stale change present "" stale_running_ensure
run_case guard-agent-writes change present "" agent_writes_enabled
run_case side-effect-attempt change present "" "" attempt
run_case side-effect-running change present "" "" running
run_case side-effect-agent-operation change present "" "" agent-operation
run_case fail-compose change present compose
printf 'MyShop runtime-config transaction integration regression passed\n'

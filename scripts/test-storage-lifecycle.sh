#!/usr/bin/env bash
set -Eeuo pipefail

# Local regression tests. They use temporary directories and a fake Docker
# client; they never connect to, or mutate, a production Docker engine.
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }

state_writer="$repo_dir/scripts/write-deploy-state.sh"
retention="$repo_dir/scripts/cleanup-docker-retention.sh"
prod_compose="$repo_dir/docker-compose.prod.yml"
health="$repo_dir/ops/host-ops/check-storage-health.sh"

grep -Eq 'flock' "$retention" || fail 'MyShop retention has no deployment lock'
grep -Eq 'deploy-state.json|MYSHOP_DEPLOY_STATE' "$retention" || fail 'MyShop retention does not read deployment state'
grep -Fq 'protected_ids' "$retention" || fail 'MyShop retention does not protect running image IDs'
grep -Fq 'accepted' "$retention" || fail 'MyShop retention does not protect accepted releases'
grep -Fq 'BuildKit cache were not touched' "$retention" || fail 'MyShop retention must not own host BuildKit GC'
! grep -Eiq 'xianyu-agent|docker volume|volume prune' "$retention" || fail 'MyShop retention crosses the Agent/volume boundary'
! grep -Eq '^[[:space:]]*build:' "$prod_compose" || fail 'production Compose contains build:'
! grep -Eq '(:latest|:local)([[:space:]]|$)' "$prod_compose" || fail 'production Compose contains mutable tags'
grep -Fq 'driver: local' "$prod_compose" || fail 'production Compose has no bounded logging policy'
grep -Fq 'HOST_DEPLOY_LOCK_FILE' "$repo_dir/scripts/deploy-production.sh" || fail 'deployment has no shared host lock'
grep -Fq 'DATA_DISK_HIGH_WATERMARK' "$repo_dir/scripts/deploy-production.sh" || fail 'deployment has no hard disk gate'
grep -Fq 'docker inspect' "$repo_dir/ops/host-ops/capture-phase0.sh" || fail 'Phase 0 evidence capture is incomplete'
grep -Fq 'docker exec' "$repo_dir/ops/host-ops/capture-phase0.sh" || fail 'Phase 0 backup/database evidence is incomplete'
grep -Fq 'dockerd --validate' "$repo_dir/ops/host-ops/validate-docker-config.sh" || fail 'Docker config validation is missing'
grep -Fq 'systemctl restart docker' "$repo_dir/ops/host-ops/apply-daemon-gc.sh" || fail 'Docker config apply lacks explicit restart/rollback path'
pass 'static MyShop retention, production Compose, logging, and gate checks'

active=$(printf 'a%.0s' {1..40})
rollback_one=$(printf 'b%.0s' {1..40})
rollback_two=$(printf 'c%.0s' {1..40})
digest=sha256:1111111111111111111111111111111111111111111111111111111111111111
compose_bin="$tmp_dir/compose-bin"
mkdir -p "$compose_bin"
cat >"$compose_bin/docker" <<'DOCKER'
#!/usr/bin/env bash
if [[ "$1 ${2:-}" == 'compose version' || "$1" == compose ]]; then exit 0; fi
exit 99
DOCKER
chmod +x "$compose_bin/docker"
compose_env="$tmp_dir/compose.env"
printf 'MYSHOP_API_IMAGE=ghcr.io/example/myshop-api:%s\nMYSHOP_WEB_IMAGE=ghcr.io/example/myshop-web:%s\n' "$active" "$active" >"$compose_env"
PATH="$compose_bin:$PATH" MYSHOP_APPROVED_REGISTRY=ghcr.io/example "$repo_dir/scripts/myshop-compose.sh" --env-file "$compose_env" config >/dev/null
if PATH="$compose_bin:$PATH" MYSHOP_APPROVED_REGISTRY=ghcr.io/example "$repo_dir/scripts/myshop-compose.sh" --env-file "$compose_env" up --build >/dev/null 2>&1; then
  fail 'MyShop production wrapper accepted up --build'
fi
printf 'MYSHOP_API_IMAGE=ghcr.io/example/myshop-api:latest\nMYSHOP_WEB_IMAGE=ghcr.io/example/myshop-web:%s\n' "$active" >"$compose_env"
if PATH="$compose_bin:$PATH" MYSHOP_APPROVED_REGISTRY=ghcr.io/example "$repo_dir/scripts/myshop-compose.sh" --env-file "$compose_env" config >/dev/null 2>&1; then
  fail 'MyShop production wrapper accepted latest'
fi
pass 'MyShop production wrapper rejects build and mutable tags'

state="$tmp_dir/deploy-state.json"
"$state_writer" "$state" "$active" "$rollback_one" "$rollback_two" \
  "ghcr.io/example/myshop-api:$active" "$digest" \
  "ghcr.io/example/myshop-web:$active" "$digest" >/dev/null
python3 - "$state" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding="utf-8"))
assert state["accepted"] == [state["active"], *state["rollback"]]
assert len(state["rollback"]) == 2
assert state["images"]["api"]["ref"].endswith(state["active"])
PY
pass 'atomic deployment state contains active plus exactly two rollbacks'

if "$state_writer" "$state" "$active" "$active" "$rollback_two" \
  "ghcr.io/example/myshop-api:$active" "$digest" \
  "ghcr.io/example/myshop-web:$active" "$digest" >/dev/null 2>&1; then
  fail 'duplicate accepted SHA was accepted'
fi
if "$state_writer" "$state" "$active" "$rollback_one" "$rollback_two" \
  'ghcr.io/example/myshop-api:latest' "$digest" \
  "ghcr.io/example/myshop-web:$active" "$digest" >/dev/null 2>&1; then
  fail 'mutable image tag was accepted'
fi
printf '{"active":"broken"}\n' >"$state"
fake_bin="$tmp_dir/bin"
mkdir -p "$fake_bin"
cat >"$fake_bin/docker" <<'DOCKER'
#!/usr/bin/env bash
printf 'unexpected fake Docker invocation: %s\n' "$*" >&2
exit 99
DOCKER
chmod +x "$fake_bin/docker"
if PATH="$fake_bin:$PATH" MYSHOP_ROOT="$tmp_dir/missing" MYSHOP_DEPLOY_STATE="$state" "$retention" --dry-run >/dev/null 2>&1; then
  fail 'malformed deployment state did not fail closed'
fi
pass 'malformed deployment state fails closed before Docker mutation'

# Exercise the retention decision with a fake Docker client. Accepted image
# refs, a stopped-container image, and an unrelated project must all survive;
# only the non-accepted MyShop candidate/release may be selected.
"$state_writer" "$state" "$active" "$rollback_one" "$rollback_two" \
  "ghcr.io/example/myshop-api:$active" "$digest" \
  "ghcr.io/example/myshop-web:$active" "$digest" >/dev/null
release_root="$tmp_dir/myshop/releases"
for sha in "$active" "$rollback_one" "$rollback_two"; do mkdir -p "$release_root/$sha"; done
failed_candidate=$(printf 'd%.0s' {1..40})
mkdir -p "$release_root/$failed_candidate"
fake_log="$tmp_dir/fake-docker.log"
cat >"$fake_bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
log=${FAKE_DOCKER_LOG:?}
if [[ "$1 ${2:-}" == 'ps --format' ]]; then
  printf 'api-container\tghcr.io/example/myshop-api:%s\tmyshop\tapi\n' "$FAKE_ACTIVE_SHA"
  printf 'web-container\tghcr.io/example/myshop-web:%s\tmyshop\tweb\n' "$FAKE_ACTIVE_SHA"
elif [[ "$1 ${2:-}" == 'ps -aq' ]]; then
  printf 'api-container\nweb-container\nstopped-container\n'
elif [[ "$1" == inspect ]]; then
  case "$4" in
    api-container) printf 'sha256:accepted-api\n' ;;
    web-container) printf 'sha256:accepted-web\n' ;;
    stopped-container) printf 'sha256:stopped\n' ;;
  esac
elif [[ "$1" == image && "$2" == inspect ]]; then
  ref=${@: -1}
  case "$ref" in
    *myshop-api:*) printf 'sha256:accepted-api\n' ;;
    *myshop-web:*) printf 'sha256:accepted-web\n' ;;
  esac
elif [[ "$1" == image && "$2" == ls ]]; then
  printf 'ghcr.io/example/myshop-api\t%s\tsha256:accepted-api\n' "$FAKE_ACTIVE_SHA"
  printf 'ghcr.io/example/myshop-api\t%s\tsha256:accepted-api\n' "$FAKE_ROLLBACK_ONE"
  printf 'ghcr.io/example/myshop-api\t%s\tsha256:accepted-api\n' "$FAKE_ROLLBACK_TWO"
  printf 'ghcr.io/example/myshop-api\tcandidate\tsha256:candidate\n'
  printf 'ghcr.io/example/myshop-api\tstopped\tsha256:stopped\n'
  printf 'ghcr.io/example/other\told\tsha256:other\n'
  printf 'ghcr.io/example/myshop-web\t%s\tsha256:accepted-web\n' "$FAKE_ACTIVE_SHA"
  printf 'ghcr.io/example/myshop-web\t%s\tsha256:accepted-web\n' "$FAKE_ROLLBACK_ONE"
  printf 'ghcr.io/example/myshop-web\t%s\tsha256:accepted-web\n' "$FAKE_ROLLBACK_TWO"
  printf 'ghcr.io/example/myshop-web\tcandidate\tsha256:candidate-web\n'
elif [[ "$1" == image && "$2" == rm ]]; then
  printf '%s\n' "$3" >>"$log"
elif [[ "$1" == system && "$2" == df ]]; then
  printf 'fake docker system df\n'
else
  printf 'unsupported fake Docker call: %s\n' "$*" >&2
  exit 99
fi
DOCKER
chmod +x "$fake_bin/docker"
PATH="$fake_bin:$PATH" FAKE_DOCKER_LOG="$fake_log" FAKE_ACTIVE_SHA="$active" FAKE_ROLLBACK_ONE="$rollback_one" FAKE_ROLLBACK_TWO="$rollback_two" MYSHOP_ROOT="$tmp_dir/myshop" MYSHOP_DEPLOY_STATE="$state" HOST_DEPLOY_LOCK_FILE="$tmp_dir/retention.lock" "$retention" >/dev/null
[[ -d "$release_root/$active" && -d "$release_root/$rollback_one" && -d "$release_root/$rollback_two" ]] || fail 'retention removed an accepted release'
[[ ! -d "$release_root/$failed_candidate" ]] || fail 'retention kept a failed candidate release'
grep -Fq 'ghcr.io/example/myshop-api:candidate' "$fake_log" || fail 'retention did not select the candidate image'
! grep -Eq "($active|$rollback_one|$rollback_two|stopped|other)" "$fake_log" || fail 'retention selected a protected/unrelated image'
pass 'current, both rollbacks, stopped image, and unrelated project are protected'

fake_findmnt="$tmp_dir/findmnt"
fake_df="$tmp_dir/df"
cat >"$fake_findmnt" <<'FINDMNT'
#!/usr/bin/env bash
if [[ "$*" == *'-o SOURCE'* ]]; then printf '/dev/fake\n'; else printf '/data\n'; fi
FINDMNT
chmod +x "$fake_findmnt"
cat >"$fake_df" <<'DF'
#!/usr/bin/env bash
used=${FAKE_USED_PERCENT:-85}
printf 'Filesystem Type 1024-blocks Used Available Capacity Mounted on\n'
printf '/dev/fake ext4 100000 85000 15000 %s%% /data\n' "$used"
DF
chmod +x "$fake_df"
mkdir -p "$tmp_dir/containerd" "$tmp_dir/docker" "$tmp_dir/myshop/releases" "$tmp_dir/agent/releases"
if PATH="$tmp_dir:$PATH" DATA_MOUNTPOINT=/data CONTAINERD_ROOT="$tmp_dir/containerd" DOCKER_ROOT="$tmp_dir/docker" MYSHOP_ROOT="$tmp_dir/myshop" AGENT_ROOT="$tmp_dir/agent" FAKE_USED_PERCENT=85 "$health" >/dev/null 2>&1; then
  fail '85 percent disk usage did not hard-fail'
fi
if PATH="$tmp_dir:$PATH" DATA_MOUNTPOINT=/data CONTAINERD_ROOT="$tmp_dir/containerd" DOCKER_ROOT="$tmp_dir/docker" MYSHOP_ROOT="$tmp_dir/myshop" AGENT_ROOT="$tmp_dir/agent" FAKE_USED_PERCENT=90 "$health" >/dev/null 2>&1; then
  fail '90 percent disk usage unexpectedly passed'
fi
pass '80 percent and 90 percent disk gates fail closed'

if grep -Fq 'docker volume prune' "$repo_dir/ops/host-ops/docker-maintenance.sh"; then
  fail 'host maintenance contains forbidden volume prune'
fi
pass 'host maintenance does not prune volumes'
printf 'storage lifecycle regression tests passed\n'

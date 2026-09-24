#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
pass() { printf 'PASS: %s\n' "$*"; }

state_writer="$repo_dir/scripts/write-deploy-state.sh"
retention="$repo_dir/scripts/cleanup-docker-retention.sh"
wrapper="$repo_dir/scripts/myshop-compose.sh"
compose="$repo_dir/docker-compose.prod.yml"
! grep -Eq '^[[:space:]]*build:' "$compose" || fail 'production Compose contains build:'
! grep -Eq '(:latest|:local)([[:space:]@]|$)' "$compose" || fail 'production Compose contains mutable tags'
grep -Fq 'driver: local' "$compose" || fail 'production Compose has no bounded logging policy'
grep -Eq 'image: [^$[:space:]]+@sha256:' "$compose" || fail 'infrastructure images are not digest-pinned'
grep -Fq 'api_prefix=' "$wrapper" || fail 'wrapper still embeds registry in a regex'
grep -Fq 'os.fsync' "$state_writer" || fail 'state writer has no fsync'
! grep -Fq 'rollback-sha' "$repo_dir/scripts/deploy-production.sh" || fail 'deployment accepts caller rollback SHA'
! grep -Eiq 'RELEASE_ROOT|/releases/' "$retention" || fail 'retention still depends on source releases'
! grep -Eiq 'docker volume|volume prune|builder prune|system prune' "$retention" || fail 'app retention owns forbidden cleanup'
[[ -f "$repo_dir/ops/systemd/storage-lifecycle.service" && -f "$repo_dir/ops/systemd/storage-lifecycle.timer" ]] || fail 'orchestrator units are missing'
[[ ! -e "$repo_dir/ops/systemd/myshop-docker-retention.timer" ]] || fail 'old retention timer remains'
grep -Fq '0|1|4' "$repo_dir/ops/host-ops/storage-lifecycle.sh" || fail 'orchestrator does not continue maintenance-required precheck'
grep -Fq 'candidate_started=0' "$repo_dir/scripts/deploy-production.sh" || fail 'bootstrap candidate start tracking is missing'
grep -Fq '! state_existed && ! candidate_started' "$repo_dir/scripts/deploy-production.sh" || fail 'bootstrap pre-start rollback is not fail-closed'
grep -Fq 'prepare_bootstrap_rollback_refs' "$repo_dir/scripts/deploy-production.sh" || fail 'bootstrap post-start rollback does not normalize legacy image refs'
grep -Fq 'ENV_FILE=${MYSHOP_ENV_FILE:-/opt/myshop/.env}' "$repo_dir/scripts/deploy-production.sh" || fail 'production secret path is not separated from runtime artifacts'
pass 'static image-only/runtime/state/retention/orchestrator checks'

sha1=$(printf 'a%.0s' {1..40})
sha2=$(printf 'b%.0s' {1..40})
sha3=$(printf 'c%.0s' {1..40})
digest=sha256:1111111111111111111111111111111111111111111111111111111111111111
manifest="$tmp_dir/manifest.json"
state="$tmp_dir/deploy-state.json"
python3 - "$manifest" "$sha1" "$digest" <<'PY'
import json, sys
path, sha, digest = sys.argv[1:]
json.dump({"version": 1, "project": "myshop", "source_sha": sha, "created_at": "builder", "images": {
    "api": {"ref": f"registry.example:5000/team/myshop-api:{sha}", "digest": digest},
    "web": {"ref": f"registry.example:5000/team/myshop-web:{sha}", "digest": digest},
}}, open(path, "w", encoding="utf-8"))
PY
bash "$state_writer" "$state" "$manifest" >/dev/null
python3 - "$state" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding='utf-8'))
assert state['version'] == 1 and state['rollback'] == [] and len(state['history']) == 1
PY
pass 'bootstrap writes active plus zero rollback'

for next_sha in "$sha2" "$sha3"; do
  python3 - "$manifest" "$next_sha" <<'PY'
import json, sys
path, sha = sys.argv[1:]
data = json.load(open(path, encoding='utf-8'))
data['source_sha'] = sha
for service in ('api', 'web'):
    data['images'][service]['ref'] = data['images'][service]['ref'].rsplit(':', 1)[0] + ':' + sha
json.dump(data, open(path, 'w', encoding='utf-8'))
PY
  bash "$state_writer" "$state" "$manifest" >/dev/null
done
python3 - "$state" "$sha1" "$sha2" "$sha3" <<'PY'
import json, sys
state = json.load(open(sys.argv[1], encoding='utf-8'))
sha1, sha2, sha3 = sys.argv[2:]
assert state['active']['source_sha'] == sha3
assert [x['source_sha'] for x in state['rollback']] == [sha2, sha1]
assert [x['source_sha'] for x in state['history']] == [sha1, sha2, sha3]
PY
if bash "$state_writer" "$state" "$sha1" "$sha2" "$sha3" >/dev/null 2>&1; then fail 'old state writer interface still accepted'; fi
pass 'history rolls automatically to two derived rollback records'

printf '{"version":1,"active":{"source_sha":"broken"}}\n' >"$state"
fake_bin="$tmp_dir/fake-bin"
mkdir -p "$fake_bin"
printf '#!/usr/bin/env bash\nexit 99\n' >"$fake_bin/docker"
chmod +x "$fake_bin/docker"
if PATH="$fake_bin:$PATH" MYSHOP_RUNTIME_ROOT="$tmp_dir/runtime" MYSHOP_DEPLOY_STATE="$state" HOST_DEPLOY_LOCK_FILE="$tmp_dir/retention.lock" "$retention" --dry-run >/dev/null 2>&1; then fail 'malformed state did not fail closed'; fi
pass 'malformed state fails closed before Docker'

rm -f -- "$state"
python3 - "$manifest" "$sha3" "$digest" <<'PY'
import json, sys
path, sha, digest = sys.argv[1:]
json.dump({"version": 1, "project": "myshop", "source_sha": sha, "created_at": "builder", "images": {
    "api": {"ref": f"registry.example:5000/team/myshop-api:{sha}", "digest": digest},
    "web": {"ref": f"registry.example:5000/team/myshop-web:{sha}", "digest": digest},
}}, open(path, "w", encoding="utf-8"))
PY
bash "$state_writer" "$state" "$manifest" >/dev/null
mkdir -p "$tmp_dir/runtime"
fake_log="$tmp_dir/docker.log"
cat >"$fake_bin/docker" <<'DOCKER'
#!/usr/bin/env bash
set -Eeuo pipefail
log=${FAKE_DOCKER_LOG:?}; active=${FAKE_ACTIVE_SHA:?}; digest=${FAKE_DIGEST:?}
if [[ "$1" == ps && "$*" == *"--format"* ]]; then
  [[ "$*" == *"service=api"* ]] && printf 'api-container|registry.example:5000/team/myshop-api:%s\n' "$active"
  [[ "$*" == *"service=web"* ]] && printf 'web-container|registry.example:5000/team/myshop-web:%s\n' "$active"
elif [[ "$1" == ps && "$2" == -aq ]]; then printf 'api-container\nweb-container\nstopped-container\n'
elif [[ "$1" == inspect ]]; then case "${4:-}" in api-container|web-container) printf 'sha256:accepted\n';; stopped-container) printf 'sha256:stopped\n';; esac
elif [[ "$1" == image && "$2" == inspect ]]; then
  target=${5:-}
  [[ "${4:-}" == "{{.Id}}" ]] && printf 'sha256:accepted\n' || printf 'registry.example:5000/team/%s@%s\n' "$(basename "${target%:*}")" "$digest"
elif [[ "$1" == image && "$2" == ls ]]; then
  printf 'registry.example:5000/team/myshop-api|%s|sha256:accepted\n' "$active"
  printf 'registry.example:5000/team/myshop-api|candidate|sha256:candidate\n'
  printf 'registry.example:5000/team/myshop-web|%s|sha256:accepted\n' "$active"
  printf 'registry.example:5000/team/myshop-web|candidate|sha256:candidate-web\n'
elif [[ "$1" == image && "$2" == rm ]]; then printf '%s\n' "$3" >>"$log"
elif [[ "$1" == system && "$2" == df ]]; then printf 'fake docker system df\n'
else exit 99; fi
exit 0
DOCKER
chmod +x "$fake_bin/docker"
PATH="$fake_bin:$PATH" FAKE_DOCKER_LOG="$fake_log" FAKE_ACTIVE_SHA="$sha3" FAKE_DIGEST="$digest" MYSHOP_RUNTIME_ROOT="$tmp_dir/runtime" MYSHOP_DEPLOY_STATE="$state" HOST_DEPLOY_LOCK_FILE="$tmp_dir/retention.lock" "$retention" >/dev/null
grep -Fq 'registry.example:5000/team/myshop-api:candidate' "$fake_log" || fail 'candidate was not selected'
! grep -Fq "$sha3" "$fake_log" || fail 'active image was selected'
pass 'retention selects only own non-accepted image refs'
printf 'MyShop storage lifecycle regression tests passed\n'

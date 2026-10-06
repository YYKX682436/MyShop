#!/usr/bin/env bash
set -Eeuo pipefail

readonly PROJECT_ROOT="${MYSHOP_RUNTIME_ROOT:-$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)}"
readonly COMPOSE_FILE="$PROJECT_ROOT/docker-compose.prod.yml"
die() { printf 'ERROR: %s\n' "$*" >&2; exit 2; }
[[ -f "$COMPOSE_FILE" ]] || die "production Compose file is missing: $COMPOSE_FILE"
command -v docker >/dev/null 2>&1 || die 'docker is unavailable'
docker compose version >/dev/null 2>&1 || die 'docker compose is unavailable'

env_file="${MYSHOP_ENV_FILE:-$PROJECT_ROOT/.env}"
args=("$@")
for ((i=0; i<${#args[@]}; i++)); do case "${args[$i]}" in
  build|--build) die 'production wrapper refuses Docker image build' ;;
  -p|-p*|--project-name|--project-name=*|-f|-f*|--file|--file=*|--project-directory|--project-directory=*) die "production wrapper rejects project/file override: ${args[$i]}" ;;
  --env-file) (( i + 1 < ${#args[@]} )) || die '--env-file requires a path'; env_file=${args[$((i + 1))]} ;;
  --env-file=*) env_file=${args[$i]#--env-file=} ;;
esac; done

grep -Eq '^[[:space:]]*build:' "$COMPOSE_FILE" && die 'production Compose contains build:'
grep -Eq '(:latest|:local)([[:space:]]|$)' "$COMPOSE_FILE" && die 'production Compose contains a mutable latest/local image'
[[ -f "$env_file" ]] || die "production env file is missing: $env_file"
approved_registry=${MYSHOP_APPROVED_REGISTRY:-}
[[ -n "$approved_registry" ]] || die 'MYSHOP_APPROVED_REGISTRY must be supplied by host deployment configuration'
[[ "$approved_registry" == ghcr.io/yykx682436 ]] || die 'MYSHOP_APPROVED_REGISTRY must be ghcr.io/yykx682436'

read_env_value() { local key=$1 value source_file=${2:-$env_file}; value=$(awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$source_file"); value=${value#\"}; value=${value%\"}; value=${value#\'}; value=${value%\'}; printf '%s' "$value"; }
state_file=${MYSHOP_DEPLOY_STATE:-$PROJECT_ROOT/deploy-state.json}

read_state_ref() {
  local service=$1
  python3 - "$state_file" "$service" <<'PY'
import json
import re
import sys
from pathlib import Path

path = Path(sys.argv[1])
service = sys.argv[2]
try:
    state = json.loads(path.read_text(encoding="utf-8"))
except Exception as exc:
    raise SystemExit(f"accepted deployment state is not valid JSON: {exc}")
if state.get("version") != 1 or not isinstance(state.get("active"), dict):
    raise SystemExit("accepted deployment state is malformed")
active = state["active"]
source_sha = active.get("source_sha")
images = active.get("images")
if not re.fullmatch(r"[0-9a-f]{40}", str(source_sha or "")) or not isinstance(images, dict):
    raise SystemExit("accepted deployment state active record is malformed")
image = images.get(service)
if not isinstance(image, dict):
    raise SystemExit(f"accepted deployment state has no active {service} image")
ref = image.get("ref")
if not isinstance(ref, str) or not ref or ref.rsplit(":", 1)[-1] != source_sha:
    raise SystemExit(f"accepted deployment state active {service} ref is malformed")
print(ref)
PY
}

bootstrap_ref_from_running() {
  local service=$1 container_id
  container_id=$(docker ps --filter 'label=com.docker.compose.project=myshop' --filter "label=com.docker.compose.service=$service" -q | awk 'NF {count++; value=$0} END {if (count == 1) print value; else exit 1}') \
    || die "bootstrap requires exactly one running legacy $service container"
  docker inspect --format '{{.Config.Image}}' "$container_id" \
    || die "cannot read running legacy $service image ref"
}

api_ref=${MYSHOP_API_IMAGE:-}
web_ref=${MYSHOP_WEB_IMAGE:-}
bootstrap_mode=0
if [[ -z "$api_ref" && -z "$web_ref" ]]; then
  if [[ -f "$state_file" ]]; then
    api_ref=$(read_state_ref api)
    web_ref=$(read_state_ref web)
  else
    bootstrap_mode=1
    api_ref=$(read_env_value MYSHOP_API_IMAGE)
    web_ref=$(read_env_value MYSHOP_WEB_IMAGE)
    api_ref=${api_ref:-$(bootstrap_ref_from_running api)}
    web_ref=${web_ref:-$(bootstrap_ref_from_running web)}
  fi
elif [[ -z "$api_ref" || -z "$web_ref" ]]; then
  die 'MYSHOP_API_IMAGE and MYSHOP_WEB_IMAGE must be supplied together'
fi
sha_re='[0-9a-f]{40}'
api_prefix="$approved_registry/myshop-api:"
web_prefix="$approved_registry/myshop-web:"
if (( ! bootstrap_mode )); then
  [[ "$api_ref" == "$api_prefix"* ]] || die 'MYSHOP_API_IMAGE is outside the approved registry namespace'
  [[ "$web_ref" == "$web_prefix"* ]] || die 'MYSHOP_WEB_IMAGE is outside the approved registry namespace'
else
  [[ "$api_ref" != *:latest && "$api_ref" != *:local && "$web_ref" != *:latest && "$web_ref" != *:local ]] || die 'bootstrap baseline image is mutable'
fi
api_tag=${api_ref#"$api_prefix"}
web_tag=${web_ref#"$web_prefix"}
if (( bootstrap_mode )); then
  api_tag=${api_ref##*:}
  web_tag=${web_ref##*:}
fi
[[ "$api_tag" =~ ^$sha_re$ && "$web_tag" =~ ^$sha_re$ ]] || die 'MyShop application image tags must be full lowercase SHAs'
[[ "$api_tag" == "$web_tag" ]] || die 'API and Web must use the same full release SHA'

export MYSHOP_API_IMAGE="$api_ref" MYSHOP_WEB_IMAGE="$web_ref"
export COMPOSE_PROJECT_NAME=myshop
exec docker compose --project-directory "$PROJECT_ROOT" --file "$COMPOSE_FILE" --env-file "$env_file" "${args[@]}"

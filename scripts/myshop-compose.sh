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

read_env_value() { local key=$1 value source_file=${2:-$env_file}; value=$(awk -F= -v key="$key" '$1 == key {sub(/^[^=]*=/, ""); print; exit}' "$source_file"); value=${value#\"}; value=${value%\"}; value=${value#\'}; value=${value%\'}; printf '%s' "$value"; }
image_env_file="$PROJECT_ROOT/accepted-images.env"
api_ref=${MYSHOP_API_IMAGE:-}
web_ref=${MYSHOP_WEB_IMAGE:-}
[[ -n "$api_ref" ]] || [[ ! -f "$image_env_file" ]] || api_ref=$(read_env_value MYSHOP_API_IMAGE "$image_env_file")
[[ -n "$web_ref" ]] || [[ ! -f "$image_env_file" ]] || web_ref=$(read_env_value MYSHOP_WEB_IMAGE "$image_env_file")
api_ref=${api_ref:-$(read_env_value MYSHOP_API_IMAGE)}
web_ref=${web_ref:-$(read_env_value MYSHOP_WEB_IMAGE)}
sha_re='[0-9a-f]{40}'
api_prefix="$approved_registry/myshop-api:"
web_prefix="$approved_registry/myshop-web:"
[[ "$api_ref" == "$api_prefix"* ]] || die 'MYSHOP_API_IMAGE is outside the approved registry namespace'
[[ "$web_ref" == "$web_prefix"* ]] || die 'MYSHOP_WEB_IMAGE is outside the approved registry namespace'
api_tag=${api_ref#"$api_prefix"}
web_tag=${web_ref#"$web_prefix"}
[[ "$api_tag" =~ ^$sha_re$ && "$web_tag" =~ ^$sha_re$ ]] || die 'MyShop application image tags must be full lowercase SHAs'
[[ "$api_tag" == "$web_tag" ]] || die 'API and Web must use the same full release SHA'

export COMPOSE_PROJECT_NAME=myshop
exec docker compose --project-directory "$PROJECT_ROOT" --file "$COMPOSE_FILE" --env-file "$env_file" "${args[@]}"

#!/usr/bin/env bash
set -Eeuo pipefail

readonly PRODUCTION_PROJECT_NAME="myshop"
readonly SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
readonly PROJECT_ROOT="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)"
readonly COMPOSE_FILE="$PROJECT_ROOT/docker-compose.yml"

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 2
}

reject_project_override() {
  local argument
  for argument in "$@"; do
    case "$argument" in
      -p|-p*|--project-name|--project-name=*|-f|-f*|--file|--file=*|--project-directory|--project-directory=*)
        die "production Compose wrapper rejects project/file override argument: $argument"
        ;;
    esac
  done
}

reject_project_override "$@"
[[ -d "$PROJECT_ROOT" ]] || die "repository/release root is missing: $PROJECT_ROOT"
[[ -f "$COMPOSE_FILE" ]] || die "repository/release Compose file is missing: $COMPOSE_FILE"
command -v docker >/dev/null 2>&1 || die "docker is unavailable"
docker compose version >/dev/null 2>&1 || die "docker compose is unavailable"

# The explicit environment assignment wins over a caller-provided
# COMPOSE_PROJECT_NAME while the command-line options pin the file and root.
exec COMPOSE_PROJECT_NAME="$PRODUCTION_PROJECT_NAME" \
  docker compose \
  --project-directory "$PROJECT_ROOT" \
  --file "$COMPOSE_FILE" \
  "$@"

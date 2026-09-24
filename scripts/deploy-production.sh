#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-deploy] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 5 )) || die 'usage: deploy-production.sh <active-sha> <rollback-sha-1> <rollback-sha-2> <api-digest> <web-digest>'
active_sha=$1
rollback_one=$2
rollback_two=$3
api_digest=$4
web_digest=$5
for sha in "$active_sha" "$rollback_one" "$rollback_two"; do [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || die 'all release values must be full lowercase SHAs'; done
[[ "$api_digest" =~ ^sha256:[0-9a-f]{64}$ && "$web_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || die 'image digests must be sha256 digests'
[[ "$active_sha" != "$rollback_one" && "$active_sha" != "$rollback_two" && "$rollback_one" != "$rollback_two" ]] || die 'accepted SHAs must be unique'

readonly PROJECT_ROOT=/opt/myshop
readonly HOST_LOCK=${HOST_DEPLOY_LOCK_FILE:-/run/myshop-xianyu-deploy.lock}
readonly HEALTH_SCRIPT=${HOST_STORAGE_HEALTH_SCRIPT:-/opt/host-ops/check-storage-health.sh}
readonly CURRENT_ROOT="$PROJECT_ROOT"
[[ -x "$HEALTH_SCRIPT" ]] || die "storage health gate is missing: $HEALTH_SCRIPT"
[[ -x "$CURRENT_ROOT/scripts/myshop-compose.sh" ]] || die 'current production wrapper is missing'

mkdir -p -- "$(dirname -- "$HOST_LOCK")"
exec 9>"$HOST_LOCK"
flock -n 9 || die 'another deployment or host maintenance operation is active'
set +e
"$HEALTH_SCRIPT"
pre_status=$?
set -e
case "$pre_status" in
  0|1) die "predeploy storage gate failed: $pre_status" ;;
  2|3) die 'DATA_DISK_HIGH_WATERMARK' ;;
  4)
    [[ -f "$PROJECT_ROOT/deploy-state.json" ]] || die 'MAINTENANCE_REQUIRED but no accepted deployment state exists'
    MYSHOP_ROOT="$PROJECT_ROOT" MYSHOP_DEPLOY_STATE="$PROJECT_ROOT/deploy-state.json" "$PROJECT_ROOT/scripts/cleanup-docker-retention.sh"
    HOST_DEPLOY_LOCK_HELD=1 /opt/host-ops/docker-maintenance.sh
    set +e
    "$HEALTH_SCRIPT"
    pre_status=$?
    set -e
    (( pre_status < 4 )) || die "predeploy maintenance did not lower data disk below 70%: $pre_status"
    ;;
esac

env_file="$PROJECT_ROOT/.env"
[[ -f "$env_file" ]] || die 'production .env is missing'
verified_backup=${MYSHOP_VERIFIED_BACKUP_PATH:-}
[[ -n "$verified_backup" && -s "$verified_backup" ]] || die 'MYSHOP_VERIFIED_BACKUP_PATH must point to a non-empty verified PostgreSQL backup'
pg_restore -l "$verified_backup" >/dev/null 2>&1 || die 'verified PostgreSQL backup cannot be listed by pg_restore'
approved_registry=${MYSHOP_APPROVED_REGISTRY:-}
[[ -n "$approved_registry" ]] || die 'MYSHOP_APPROVED_REGISTRY is not configured'
api_ref="$approved_registry/myshop-api:$active_sha"
web_ref="$approved_registry/myshop-web:$active_sha"
docker pull "$api_ref"
docker pull "$web_ref"
api_repo_digest=$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$api_ref" | grep -F "$api_digest" | head -n 1 || true)
web_repo_digest=$(docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' "$web_ref" | grep -F "$web_digest" | head -n 1 || true)
[[ -n "$api_repo_digest" && -n "$web_repo_digest" ]] || die 'pulled image digest does not match supplied acceptance digest'

MYSHOP_API_IMAGE="$api_ref" MYSHOP_WEB_IMAGE="$web_ref" "$CURRENT_ROOT/scripts/myshop-compose.sh" --env-file "$env_file" config --quiet
MYSHOP_API_IMAGE="$api_ref" MYSHOP_WEB_IMAGE="$web_ref" "$CURRENT_ROOT/scripts/myshop-compose.sh" --env-file "$env_file" pull
MYSHOP_API_IMAGE="$api_ref" MYSHOP_WEB_IMAGE="$web_ref" "$CURRENT_ROOT/scripts/myshop-compose.sh" --env-file "$env_file" up -d

for attempt in $(seq 1 30); do healthy=1; for service in db api web caddy; do container_id=$(docker ps --filter "label=com.docker.compose.project=myshop" --filter "label=com.docker.compose.service=$service" -q | head -n 1); [[ -n "$container_id" ]] || { healthy=0; break; }; status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container_id"); [[ "$status" == healthy || ( "$service" == db && "$status" == running ) ]] || { healthy=0; break; }; done; (( healthy == 1 )) && break; (( attempt == 30 )) && die 'health gate timed out'; sleep 2; done

public_url=$(awk -F= '$1 == "PUBLIC_SITE_URL" {sub(/^[^=]*=/, ""); print; exit}' "$env_file")
[[ -n "$public_url" ]] || die 'PUBLIC_SITE_URL is missing'
curl --fail --silent --show-error --output /dev/null "$public_url/"
curl --fail --silent --show-error --output /dev/null "$public_url/api/site/config"
MYSHOP_API_IMAGE="$api_ref" MYSHOP_WEB_IMAGE="$web_ref" "$CURRENT_ROOT/scripts/myshop-compose.sh" --env-file "$env_file" exec -T db sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT 1;"' | grep -Fx 1 >/dev/null

"$PROJECT_ROOT/scripts/write-deploy-state.sh" "$PROJECT_ROOT/deploy-state.json" "$active_sha" "$rollback_one" "$rollback_two" "$api_ref" "$api_digest" "$web_ref" "$web_digest"
MYSHOP_ROOT="$PROJECT_ROOT" MYSHOP_DEPLOY_STATE="$PROJECT_ROOT/deploy-state.json" "$PROJECT_ROOT/scripts/cleanup-docker-retention.sh"
set +e
"$HEALTH_SCRIPT"
post_status=$?
set -e
(( post_status == 0 )) || die 'DEPLOYMENT NOT ACCEPTED: postdeploy storage gate is not OK'
if ! df -P /data | awk 'NR==2 {gsub(/%/,"",$5); exit !($5 < 70)}'; then die 'DEPLOYMENT NOT ACCEPTED: data disk is not below 70%'; fi
printf 'DEPLOYMENT ACCEPTED: source=%s api_digest=%s web_digest=%s\n' "$active_sha" "$api_digest" "$web_digest"

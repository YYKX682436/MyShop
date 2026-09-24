#!/usr/bin/env bash
set -Eeuo pipefail

# Read-only Phase 0 evidence capture. It writes only this bounded audit file;
# it never changes Docker, containerd, databases, volumes, or application data.
AUDIT_ROOT=${AUDIT_ROOT:-/opt/host-ops/audit}
stamp=$(date -u +%Y%m%dT%H%M%SZ)
mkdir -p -- "$AUDIT_ROOT"
tmp_file=$(mktemp "$AUDIT_ROOT/.phase0.XXXXXX")
trap 'rm -f -- "$tmp_file"' EXIT

section() { printf '\n===== %s =====\n' "$1"; }

{
  printf 'phase=0\nrecorded_at=%s\n' "$stamp"
  section 'df'
  df -hT
  section 'findmnt-data'
  findmnt /data
  section 'docker-info'
  docker info --format 'server={{.ServerVersion}} root={{.DockerRootDir}} driver={{.Driver}} containerd={{.DriverStatus}}'
  section 'docker-system-df'
  docker system df -v
  section 'docker-ps-identities'
  docker ps -a --no-trunc --format '{{.ID}}\t{{.Image}}\t{{.Names}}\t{{.Status}}\t{{.Label "com.docker.compose.project"}}\t{{.Label "com.docker.compose.service"}}'
  section 'docker-image-identities'
  docker image ls -a --no-trunc --format '{{.Repository}}\t{{.Tag}}\t{{.ID}}\t{{.Size}}'
  section 'docker-volumes'
  docker volume ls --format '{{.Name}}\t{{.Driver}}'
  while IFS= read -r volume; do
    [[ -n "$volume" ]] || continue
    docker volume inspect --format '{{.Name}}\t{{.Driver}}\t{{.Mountpoint}}' "$volume"
  done < <(docker volume ls -q)
  section 'docker-networks'
  docker network ls --no-trunc --format '{{.ID}}\t{{.Name}}\t{{.Driver}}\t{{.Scope}}'
  section 'running-image-digests-and-compose-labels'
  while IFS= read -r container; do
    [[ -n "$container" ]] || continue
    image_ref=$(docker inspect --format '{{.Config.Image}}' "$container")
    docker inspect --format '{{.Id}}\t{{.Config.Image}}\t{{.Image}}\t{{index .Config.Labels "com.docker.compose.project"}}\t{{index .Config.Labels "com.docker.compose.service"}}' "$container"
    docker image inspect --format '{{.Id}}\t{{range .RepoDigests}}{{println .}}{{end}}' "$image_ref" 2>/dev/null || true
  done < <(docker ps -aq)
  section 'deployment-markers'
  for file in /opt/myshop/DEPLOYED_SHA /opt/myshop/DEPLOYED_SHA.pre-* /opt/myshop/.image-tags-*.rollback /opt/xianyu-agent/DEPLOYED_SOURCE_SHA /opt/xianyu-agent/DEPLOYED_SOURCE_SHA.pre-*; do
    [[ -f "$file" ]] || continue
    printf '%s\t' "$file"
    tr -d '\n' <"$file"
    printf '\n'
  done
  section 'docker-and-containerd-config'
  printf '%s\n' '--- /etc/docker/daemon.json ---'
  cat /etc/docker/daemon.json
  printf '%s\n' '--- /etc/containerd/config.toml ---'
  cat /etc/containerd/config.toml
  section 'backup-files'
  find /data/myshop/backups /opt/xianyu-agent/backups -maxdepth 2 -type f -printf '%p\t%s bytes\t%TY-%Tm-%TdT%TH:%TM:%TS\n' 2>/dev/null | sort
  section 'database-read-only-smoke'
  postgres_id=$(docker ps --filter 'label=com.docker.compose.project=myshop' --filter 'label=com.docker.compose.service=db' -q | head -n 1 || true)
  if [[ -n "$postgres_id" ]]; then
    docker exec "$postgres_id" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "SELECT current_database(), 1;"'
  fi
  mysql_id=$(docker ps --filter 'label=com.docker.compose.project=xianyu-agent' --filter 'label=com.docker.compose.service=mysql' -q | head -n 1 || true)
  if [[ -n "$mysql_id" ]]; then
    docker exec "$mysql_id" sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -Nse "SELECT DATABASE(), 1;"'
  fi
  section 'journal-and-logs'
  journalctl --disk-usage
  du -h /var/log/* 2>/dev/null | sort -h || true
  section 'deleted-open-files'
  if command -v lsof >/dev/null 2>&1; then lsof +L1 || true; else printf 'lsof unavailable\n'; fi
  section 'data-breakdown'
  du -xhd1 /data 2>/dev/null | sort -h
} >"$tmp_file"

chmod 0640 -- "$tmp_file"
mv -f -- "$tmp_file" "$AUDIT_ROOT/phase0-$stamp.txt"
trap - EXIT
printf '%s\n' "$AUDIT_ROOT/phase0-$stamp.txt"

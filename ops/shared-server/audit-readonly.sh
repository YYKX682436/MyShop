#!/usr/bin/env bash
set -u

# Read-only inventory for a shared CentOS/Baota host. It prints no environment
# values, container commands, passwords, private keys, or Nginx key contents.
section() { printf '\n===== %s =====\n' "$1"; }

section identity-and-os
printf 'host=%s\n' "$(hostname 2>/dev/null || true)"
id
if [[ -r /etc/os-release ]]; then
  sed -nE 's/^(NAME|VERSION|VERSION_ID|PRETTY_NAME)=/\1=/p' /etc/os-release
fi
printf 'kernel='; uname -srmo
printf 'uptime='; uptime -p 2>/dev/null || uptime

section resources
if command -v lscpu >/dev/null 2>&1; then
  lscpu | awk -F: '/^Architecture:|^CPU\(s\):|^Model name:/ {gsub(/^[ \t]+/, "", $2); print $1 "=" $2}'
else
  printf 'cpu_count=%s\n' "$(getconf _NPROCESSORS_ONLN 2>/dev/null || true)"
fi
free -h 2>/dev/null || true
df -hT 2>/dev/null || true
df -ih 2>/dev/null || true

section docker
if command -v docker >/dev/null 2>&1; then
  docker version --format 'client={{.Client.Version}} server={{.Server.Version}}' 2>&1 || true
  docker info --format 'root={{.DockerRootDir}} containers={{.Containers}} images={{.Images}} driver={{.Driver}}' 2>&1 || true
  docker compose version 2>&1 || true
  docker compose ls --all 2>&1 || true
  docker ps -a --format '{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}|{{.Label "com.docker.compose.project"}}|{{.Label "com.docker.compose.service"}}' 2>&1 || true
  docker volume ls --format '{{.Name}}|{{.Driver}}|{{.Label "com.docker.compose.project"}}' 2>&1 || true
  while IFS= read -r network_id; do
    [[ -n "$network_id" ]] || continue
    docker network inspect --format '{{.Name}}|{{.Driver}}|{{.Scope}}|{{range .IPAM.Config}}{{.Subnet}} {{end}}|project={{index .Labels "com.docker.compose.project"}}' "$network_id" 2>/dev/null || true
  done < <(docker network ls -q 2>/dev/null)
  docker system df 2>&1 || true
else
  printf 'docker=not-installed\n'
fi

section listeners
ss -lntup 2>/dev/null || true

section services
systemctl list-units --type=service --state=running --no-legend 2>/dev/null \
  | grep -Ei 'nginx|mariadb|mysql|postgres|php.*fpm|docker|containerd|httpd|apache|caddy' || true
for unit in nginx mariadb mysql mysqld postgresql docker containerd; do
  status=$(systemctl is-active "$unit" 2>/dev/null || true)
  [[ -n "$status" ]] && printf '%s=%s\n' "$unit" "$status"
done
if compgen -G '/www/server/php/*/bin/php' >/dev/null; then
  printf 'baota_php_binaries='; printf '%s\n' /www/server/php/*/bin/php
fi

section nginx-site-summary
nginx_bin=$(command -v nginx 2>/dev/null || true)
if [[ -z "$nginx_bin" ]]; then
  for candidate in /www/server/nginx/sbin/nginx /usr/sbin/nginx /usr/local/nginx/sbin/nginx; do
    if [[ -x "$candidate" ]]; then nginx_bin=$candidate; break; fi
  done
fi
if [[ -n "$nginx_bin" && -x "$nginx_bin" ]]; then
  printf 'nginx_binary=%s\n' "$nginx_bin"
  "$nginx_bin" -v 2>&1 || true
  # Capture -T before filtering so failures are observable and config contents
  # remain in memory only. Never print headers, auth directives, or full config.
  if nginx_dump=$("$nginx_bin" -T 2>/dev/null); then
    printf '%s\n' "$nginx_dump" | awk '
    /^# configuration file / {file=$0}
    /^[[:space:]]*(listen|server_name|proxy_pass|ssl_certificate)[[:space:]]/ {
      line=$0
      sub(/[[:space:]]+#.*/, "", line)
      scheme=index(line, "://")
      at=index(line, "@")
      if (scheme && at > scheme + 3) {
        line=substr(line, 1, scheme + 2) "[redacted]@" substr(line, at + 1)
      }
      query=index(line, "?")
      if (query) line=substr(line, 1, query - 1) " [query redacted]"
      print file " " line
    }'
    printf 'nginx_config_check=complete\n'
  else
    printf 'nginx_config_check=INCOMPLETE reason=config-dump-failed-or-unreadable\n'
  fi
else
  printf 'nginx_binary=not-found-or-not-executable nginx_config_check=INCOMPLETE\n'
fi

section firewall
if command -v getenforce >/dev/null 2>&1; then
  printf 'selinux='; getenforce 2>&1 || true
fi
if command -v firewall-cmd >/dev/null 2>&1; then
  printf 'firewalld_state='; firewall-cmd --state 2>&1 || true
  printf 'firewalld_services='; firewall-cmd --list-services 2>&1 || true
  printf 'firewalld_ports='; firewall-cmd --list-ports 2>&1 || true
else
  printf 'firewalld=not-installed-or-not-in-path\n'
fi
if command -v ufw >/dev/null 2>&1; then ufw status 2>&1 || true; fi

section routes
if command -v ip >/dev/null 2>&1; then
  ip -brief address 2>/dev/null || true
  ip route 2>/dev/null || true
fi

printf '\nAudit is read-only. Check the provider firewall/security group separately; it is not visible from this host.\n'

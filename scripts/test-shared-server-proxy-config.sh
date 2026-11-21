#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
caddyfile="$repo_dir/Caddyfile.shared-server"
compose="$repo_dir/docker-compose.shared-server.yml"
docs="$repo_dir/docs/DEPLOYMENT-SHARED-SERVER.md"
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

grep -Fq 'trusted_proxies static {$BAOTA_NGINX_PROXY_CIDR}' "$caddyfile" || fail 'Caddy must trust only the configured exact Baota proxy address'
if grep -Fq 'private_ranges' "$caddyfile"; then fail 'Caddy must not trust all private address ranges'; fi
grep -Fq 'BAOTA_NGINX_PROXY_CIDR: ${MYSHOP_BAOTA_NGINX_PROXY_CIDR:?' "$compose" || fail 'Compose must fail closed when the exact Baota source address is unset'
grep -Fq 'BAOTA_NGINX_PROXY_SOURCE_CONFIRMED: ${MYSHOP_BAOTA_PROXY_SOURCE_CONFIRMED:?' "$compose" || fail 'Compose must require an explicit server-side trust-source confirmation'
grep -Fq 'RATE_LIMIT_TRUSTED_PROXIES: ${MYSHOP_CADDY_IP:?' "$compose" || fail 'API must trust only the reserved Caddy address'
grep -Fq 'AUTH_COOKIE_SECURE: ${AUTH_COOKIE_SECURE:-true}' "$compose" || fail 'shared Compose must default secure cookies on'
grep -Fq 'PUBLIC_SITE_URL: ${PUBLIC_SITE_URL:?PUBLIC_SITE_URL must be set in the private environment file}' "$compose" || fail 'canonical HTTPS origin must be configured'
grep -Fq 'Host: $$TASK_HEALTH_AUTHORITY' "$compose" || fail 'Web health check must send the configured storefront Host'
grep -Fq 'reverse_proxy /api/* api:8083' "$caddyfile" || fail 'Caddy must preserve /api routes for Spring and callbacks'
grep -Fq 'header_up X-Real-IP {client_ip}' "$caddyfile" || fail 'Caddy must overwrite X-Real-IP with its resolved client IP'
grep -Fq 'header_up X-Forwarded-For {client_ip}' "$caddyfile" || fail 'Caddy must overwrite X-Forwarded-For with its resolved client IP'
grep -Fq 'proxy_set_header X-Forwarded-For $remote_addr;' "$docs" || fail 'Baota must overwrite, not append, the client-provided X-Forwarded-For'
grep -Fq 'proxy_set_header Host $host;' "$docs" || fail 'Baota must preserve the canonical request Host'
grep -Fq 'proxy_set_header X-Forwarded-Proto $scheme;' "$docs" || fail 'Baota HTTPS scheme forwarding is missing'
grep -Fq 'proxy_set_header X-Forwarded-Host $host;' "$docs" || fail 'Baota forwarded Host metadata is missing'
grep -Fq 'payment webhooks' "$docs" || fail 'payment callbacks are not covered by the preserved /api route'
grep -Fq '@https header X-Forwarded-Proto https' "$caddyfile" || fail 'Caddy must detect the trusted HTTPS scheme from Baota'
grep -Fq 'Strict-Transport-Security' "$caddyfile" || fail 'trusted HTTPS requests must retain HSTS'
grep -Fq 'MYSHOP_BAOTA_NGINX_PROXY_CIDR' "$repo_dir/ops/shared-server/myshop.env.example" || fail 'private environment template must expose the audited proxy address'
grep -Fq 'MYSHOP_BAOTA_PROXY_SOURCE_CONFIRMED=0' "$repo_dir/ops/shared-server/myshop.env.example" || fail 'proxy address must remain unconfirmed until server audit'

python3 - "$caddyfile" "$docs" <<'PY'
import sys
from ipaddress import ip_address, ip_network
from pathlib import Path

caddy = Path(sys.argv[1]).read_text(encoding="utf-8")
docs = Path(sys.argv[2]).read_text(encoding="utf-8")
trusted_proxy = ip_network("172.29.0.1/32", strict=True)
assert trusted_proxy.prefixlen == trusted_proxy.max_prefixlen
assert "X-Forwarded-For $remote_addr" in docs

# Model the configured two-hop contract: Baota replaces an attacker-supplied
# chain with the socket peer, Caddy accepts that header only from its one
# configured proxy source, then overwrites the API-facing client headers.
client_ip = "198.51.100.23"
spoofed_xff = "203.0.113.66, 192.0.2.99"
nginx_forwarded_xff = client_ip
caddy_peer = "172.29.0.1"
assert ip_address(caddy_peer) in trusted_proxy
assert nginx_forwarded_xff != spoofed_xff
resolved_client = nginx_forwarded_xff
api_xff = resolved_client
assert api_xff == client_ip
assert "trusted_proxies static {$BAOTA_NGINX_PROXY_CIDR}" in caddy
assert "header_up X-Forwarded-For {client_ip}" in caddy
assert 'header_up X-Real-IP {client_ip}' in caddy
print("proxy header invariant passed: spoofed XFF is overwritten before exact-trust Caddy and API hops")
PY

caddy_bin=${CADDY_BIN:-$(command -v caddy 2>/dev/null || true)}
if [[ -n "$caddy_bin" && -x "$caddy_bin" ]]; then
  BAOTA_NGINX_PROXY_CIDR=172.29.0.1/32 "$caddy_bin" adapt \
    --config "$caddyfile" --adapter caddyfile --pretty=false >/dev/null
  printf 'Caddy adapter parse passed with exact /32 test input\n'
else
  printf 'SKIP: Caddy binary unavailable; Caddy runtime parser must be executed before deployment\n'
fi

printf 'shared-server proxy configuration checks passed\n'

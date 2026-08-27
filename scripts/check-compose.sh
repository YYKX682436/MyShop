#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
valid_env="$(mktemp)"
missing_env="$(mktemp)"
trap 'rm -f "$valid_env" "$missing_env"' EXIT

cat >"$valid_env" <<'EOF'
SITE_DOMAIN=store.example.test
ACME_EMAIL=ops@example.test
DB_PASSWORD=compose-validation-password
JWT_SECRET=compose-validation-jwt-secret-with-at-least-64-random-characters-0123456789
EOF

cat >"$missing_env" <<'EOF'
DB_PASSWORD=compose-validation-password
EOF

rendered_config="$(docker compose --project-directory "$repo_dir" --env-file "$valid_env" config)"

for expected in \
  "subnet: 172.30.0.0/24" \
  "ipv4_address: 172.30.0.2" \
  "ipv4_address: 172.30.0.20" \
  "ipv4_address: 172.30.0.30" \
  "ipv4_address: 172.30.0.10" \
  "ipv4_address: 172.30.0.40"; do
  if [[ "$rendered_config" != *"$expected"* ]]; then
    echo "compose validation missing expected topology: $expected" >&2
    exit 1
  fi
done

if ! grep -Fq 'image: quay.io/prometheus/node-exporter:v1.12.1' <<<"$rendered_config"; then
  echo "compose validation missing pinned node-exporter image" >&2
  exit 1
fi

if grep -Eq '(^|[^0-9])9100:9100([^0-9]|$)' <<<"$rendered_config"; then
  echo "compose validation found a published node-exporter port" >&2
  exit 1
fi

if grep -Fq '/var/run/docker.sock' "$repo_dir/docker-compose.yml"; then
  echo "compose validation found a forbidden Docker socket mount" >&2
  exit 1
fi

if ! grep -Fq 'header_up X-Real-IP {remote_host}' "$repo_dir/Caddyfile" \
  || ! grep -Fq 'header_up X-Forwarded-For {remote_host}' "$repo_dir/Caddyfile"; then
  echo "compose validation found no Caddy client-IP overwrite rules" >&2
  exit 1
fi

for expected_header in \
  'X-Content-Type-Options "nosniff"' \
  'Referrer-Policy "strict-origin-when-cross-origin"' \
  'Permissions-Policy "camera=(), microphone=(), geolocation=(), payment=()"' \
  'X-Frame-Options "DENY"' \
  'header @https Strict-Transport-Security "max-age=31536000; includeSubDomains"'; do
  if ! grep -Fq "$expected_header" "$repo_dir/Caddyfile"; then
    echo "compose validation missing Caddy security header: $expected_header" >&2
    exit 1
  fi
done

if docker compose --project-directory "$repo_dir" --env-file "$missing_env" config --quiet; then
  echo "compose validation unexpectedly accepted missing required variables" >&2
  exit 1
fi

echo "docker compose required-variable validation passed"

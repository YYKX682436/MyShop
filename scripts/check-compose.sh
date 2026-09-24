#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
valid_env="$(mktemp)"
missing_env="$(mktemp)"
production_env="$(mktemp)"
trap 'rm -f "$valid_env" "$missing_env" "$production_env"' EXIT

cat >"$valid_env" <<'EOF'
SITE_DOMAIN=store.example.test
ACME_EMAIL=ops@example.test
PUBLIC_SITE_URL=https://store.example.test
DB_PASSWORD=compose-validation-password
JWT_SECRET=compose-validation-jwt-secret-with-at-least-64-random-characters-0123456789
UPLOAD_HOST_PATH=./uploads
PRIVATE_UPLOAD_HOST_PATH=./private-uploads
IP_GEO_HOST_PATH=./ipdb
EOF

cat >"$production_env" <<'EOF'
SITE_DOMAIN=store.example.test
ACME_EMAIL=ops@example.test
PUBLIC_SITE_URL=https://store.example.test
DB_PASSWORD=compose-validation-password
JWT_SECRET=compose-validation-jwt-secret-with-at-least-64-random-characters-0123456789
UPLOAD_HOST_PATH=/data/myshop/uploads
PRIVATE_UPLOAD_HOST_PATH=/data/myshop/private-uploads
IP_GEO_HOST_PATH=/data/myshop/ipdb
MYSHOP_API_IMAGE=ghcr.io/example/myshop-api:0000000000000000000000000000000000000001
MYSHOP_WEB_IMAGE=ghcr.io/example/myshop-web:0000000000000000000000000000000000000001
EOF

cat >"$missing_env" <<'EOF'
DB_PASSWORD=compose-validation-password
EOF

assert_project_name() {
  local rendered_config="$1"
  local context="$2"
  local project_name

  if ! project_name="$(awk '
    /^name: / { count++; value=substr($0, 7) }
    END {
      if (count != 1) exit 1
      print value
    }
  ' <<<"$rendered_config")"; then
    echo "compose validation could not find exactly one top-level project name ($context)" >&2
    exit 1
  fi
  if [[ "$project_name" != "myshop" ]]; then
    echo "compose validation resolved the wrong project name ($context): $project_name" >&2
    exit 1
  fi
}

rendered_config="$(docker compose --project-directory "$repo_dir" --env-file "$valid_env" config)"
production_config="$(docker compose --project-directory "$repo_dir" --file "$repo_dir/docker-compose.prod.yml" --env-file "$production_env" config)"
assert_project_name "$rendered_config" "normal repository"
assert_project_name "$production_config" "production environment"

if grep -Eq '^[[:space:]]*build:' "$repo_dir/docker-compose.prod.yml"; then
  echo "production Compose unexpectedly contains build:" >&2
  exit 1
fi
if grep -Eq '(:latest|:local)([[:space:]]|$)' "$repo_dir/docker-compose.prod.yml"; then
  echo "production Compose unexpectedly contains latest/local" >&2
  exit 1
fi
if [[ "$(grep -Fc 'driver: local' "$repo_dir/docker-compose.prod.yml")" -lt 1 ]]; then
  echo "production Compose is missing the bounded local logging policy" >&2
  exit 1
fi

wrapper_path="$repo_dir/scripts/myshop-compose.sh"
wrapper_config="$(MYSHOP_APPROVED_REGISTRY=ghcr.io/example "$wrapper_path" --env-file "$production_env" config)"
assert_project_name "$wrapper_config" "production wrapper"

wrapper_must_reject() {
  local label="$1"
  shift
  if "$wrapper_path" "$@" --env-file "$valid_env" config >/dev/null 2>&1; then
    echo "production wrapper unexpectedly accepted override: $label" >&2
    exit 1
  fi
}

wrapper_must_reject '-p' -p wrong
wrapper_must_reject '--project-name' --project-name wrong
wrapper_must_reject '--project-name=...' --project-name=wrong
wrapper_must_reject '-f' -f "$repo_dir/docker-compose.yml"
wrapper_must_reject '--file' --file "$repo_dir/docker-compose.yml"
wrapper_must_reject '--project-directory' --project-directory "$repo_dir"
wrapper_must_reject '--project-directory=...' --project-directory="$repo_dir"
wrapper_must_reject 'build' build
wrapper_must_reject '--build' up --build

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

if [[ "$(grep -Fc 'create_host_path: false' "$repo_dir/docker-compose.yml")" -lt 3 ]]; then
  echo "compose validation missing create_host_path: false for the public/private upload and IP database binds" >&2
  exit 1
fi

if ! grep -Fq 'target: /app/uploads' <<<"$rendered_config"; then
  echo "compose validation missing the /app/uploads container target" >&2
  exit 1
fi

if ! grep -Fq 'target: /app/private-uploads' <<<"$rendered_config"; then
  echo "compose validation missing the /app/private-uploads container target" >&2
  exit 1
fi

if ! grep -Fq 'PUBLIC_SITE_URL: https://store.example.test' <<<"$rendered_config"; then
  echo "compose validation missing the canonical public site URL" >&2
  exit 1
fi

if ! grep -Fq 'source: /data/myshop/uploads' <<<"$production_config"; then
  echo "compose validation did not resolve the production upload path to /data/myshop/uploads" >&2
  exit 1
fi

if ! grep -Fq 'ghcr.io/example/myshop-api:0000000000000000000000000000000000000001' <<<"$production_config" \
  || ! grep -Fq 'ghcr.io/example/myshop-web:0000000000000000000000000000000000000001' <<<"$production_config"; then
  echo "production Compose did not resolve immutable full-SHA application images" >&2
  exit 1
fi

if ! grep -Fq 'source: /data/myshop/private-uploads' <<<"$production_config"; then
  echo "compose validation did not resolve the production private upload path to /data/myshop/private-uploads" >&2
  exit 1
fi

if ! grep -Fq 'target: /app/ipdb' <<<"$rendered_config" \
  || ! grep -Fq 'read_only: true' <<<"$rendered_config"; then
  echo "compose validation missing the read-only /app/ipdb bind" >&2
  exit 1
fi

if ! grep -Fq 'source: /data/myshop/ipdb' <<<"$production_config"; then
  echo "compose validation did not resolve the production IP database path to /data/myshop/ipdb" >&2
  exit 1
fi

if grep -Fq '/opt/myshop/uploads' "$repo_dir/docker-compose.yml"; then
  echo "compose validation found the retired /opt/myshop/uploads symlink path" >&2
  exit 1
fi

for expected_user in \
  'groupadd --gid 10001' \
  'useradd --uid 10001' \
  'USER 10001:10001'; do
  if ! grep -Fq "$expected_user" "$repo_dir/Dockerfile.api"; then
    echo "compose validation missing fixed API identity declaration: $expected_user" >&2
    exit 1
  fi
done

if ! grep -Fq 'image: quay.io/prometheus/node-exporter:v1.12.1' <<<"$rendered_config"; then
  echo "compose validation missing pinned node-exporter image" >&2
  exit 1
fi

caddy_config="$(awk '
  /^  caddy:$/ { in_caddy=1; print; next }
  in_caddy && /^  [^[:space:]]/ { exit }
  in_caddy { print }
' "$repo_dir/docker-compose.yml")"
if grep -Fq 'node-exporter' <<<"$caddy_config"; then
  echo "compose validation found node-exporter in the Caddy startup dependency block" >&2
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

echo "docker compose project identity validation passed: myshop"
echo "docker compose required-variable validation passed"

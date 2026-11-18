#!/usr/bin/env bash
set -Eeuo pipefail
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
data="$HOME/Library/Application Support/MyShop/local-dev"
pg_bin="${MYSHOP_PG_BIN:-/opt/homebrew/opt/postgresql@16/bin}"
pg_port=55432
api_pid= web_pid= pg_started=0
next_env_before=$(cat "$root/apps/web/next-env.d.ts")

stop_tree() {
  local pid=$1 child
  for child in $(pgrep -P "$pid" || true); do stop_tree "$child"; done
  kill -TERM "$pid" 2>/dev/null || true
}
cleanup() {
  trap - EXIT INT TERM
  [[ -z "$web_pid" ]] || stop_tree "$web_pid"
  [[ -z "$api_pid" ]] || stop_tree "$api_pid"
  [[ "$pg_started" != 1 ]] || "$pg_bin/pg_ctl" -D "$data/postgres" -m fast -w stop >/dev/null
  # Next dev changes only its generated route paths; preserve any other edits.
  local generated=${next_env_before//.next\/types\//.next\/dev\/types\/}
  if [[ -f "$root/apps/web/next-env.d.ts" && "$(cat "$root/apps/web/next-env.d.ts")" == "$generated" ]]; then
    printf '%s\n' "$next_env_before" > "$root/apps/web/next-env.d.ts"
  fi
  echo 'MyShop local preview stopped.'
}
trap cleanup EXIT
trap 'exit 130' INT TERM
for cmd in node pnpm java mvn curl; do command -v "$cmd" >/dev/null || { echo "Missing $cmd" >&2; exit 1; }; done
[[ -x "$pg_bin/pg_ctl" ]] || { echo 'Install PostgreSQL 16 with: brew install postgresql@16' >&2; exit 1; }
for port in 3000 8083 "$pg_port"; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN -t >/dev/null 2>&1; then echo "Port $port is already in use." >&2; exit 1; fi
done
umask 077
mkdir -p "$data" "$data/uploads" "$data/private-uploads"
if [[ ! -f "$data/postgres/PG_VERSION" ]]; then
  "$pg_bin/initdb" -D "$data/postgres" -U myshop_local --auth-local=trust --auth-host=trust --encoding=UTF8 --no-locale >/dev/null
fi
"$pg_bin/pg_ctl" -D "$data/postgres" -l "$data/postgres.log" -o "-h 127.0.0.1 -p $pg_port -k '$data'" -w start >/dev/null
pg_started=1
if ! "$pg_bin/psql" -h 127.0.0.1 -p "$pg_port" -U myshop_local -d postgres -Atqc "select 1 from pg_database where datname='myshop_local'" | grep -qx 1; then
  "$pg_bin/createdb" -h 127.0.0.1 -p "$pg_port" -U myshop_local myshop_local
fi
(
  cd "$root/apps/api"
  exec env -i HOME="$HOME" PATH="$PATH" \
    DB_URL="jdbc:postgresql://127.0.0.1:$pg_port/myshop_local" DB_USERNAME=myshop_local DB_PASSWORD=localonly \
    JWT_SECRET='local-preview-only-change-before-any-real-deployment-0123456789-abcdefghijklmnopqrstuvwxyz' \
    PUBLIC_SITE_URL=http://localhost:3000 AUTH_COOKIE_SECURE=false MAIL_ENABLED=false SERVER_ADDRESS=127.0.0.1 \
    BOOTSTRAP_SUPER_ADMIN_USERNAME=muchen-admin BOOTSTRAP_SUPER_ADMIN_PASSWORD=LocalPreview123! \
    UPLOAD_PATH="$data/uploads" PRIVATE_UPLOAD_PATH="$data/private-uploads" mvn -q spring-boot:run
) >"$data/api.log" 2>&1 & api_pid=$!
for _ in $(seq 1 120); do
  if curl -fsS http://127.0.0.1:8083/api/site/config >/dev/null 2>&1; then break; fi
  kill -0 "$api_pid" 2>/dev/null || { echo "API failed; see $data/api.log" >&2; exit 1; }
  sleep 1
done
curl -fsS http://127.0.0.1:8083/api/site/config >/dev/null || { echo "API did not start; see $data/api.log" >&2; exit 1; }
"$pg_bin/psql" -X -v ON_ERROR_STOP=1 -h 127.0.0.1 -p "$pg_port" -U myshop_local -d myshop_local >/dev/null <<'SQL'
INSERT INTO product_categories (id, name) VALUES ('5ce00000-0000-4000-8000-000000000001', '本地演示') ON CONFLICT (id) DO NOTHING;
INSERT INTO products (id, title, description, detail_md, retail_price, cost_price, category_id, stock_mode, delivery_type)
VALUES ('5ce00000-0000-4000-8000-000000000002', '演示商品', '本地预览数据', '用于预览的示例商品。', 9.90, 5.00, '5ce00000-0000-4000-8000-000000000001', 'UNLIMITED', 'MANUAL') ON CONFLICT (id) DO NOTHING;
SQL
(cd "$root"; exec env -i HOME="$HOME" PATH="$PATH" BACKEND_URL=http://127.0.0.1:8083 PUBLIC_SITE_URL=http://localhost:3000 NEXT_TELEMETRY_DISABLED=1 pnpm --filter @myshop/web dev --hostname 127.0.0.1) >"$data/web.log" 2>&1 & web_pid=$!
for _ in $(seq 1 90); do
  if curl -fsS http://127.0.0.1:3000/ >/dev/null 2>&1; then break; fi
  kill -0 "$web_pid" 2>/dev/null || { echo "Web failed; see $data/web.log" >&2; exit 1; }
  sleep 1
done
curl -fsS http://127.0.0.1:3000/ >/dev/null || { echo "Web did not start; see $data/web.log" >&2; exit 1; }
echo 'Preview: http://localhost:3000  |  Ctrl-C to stop'
wait "$web_pid"

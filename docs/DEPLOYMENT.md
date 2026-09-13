# MyShop Phase 1 deployment runbook

This document describes the intended single-host deployment. It is a runbook,
not a production deployment action performed by CI or this acceptance pass.

## Compose project identity and production entrypoint

The top-level `name: myshop` in `docker-compose.yml` is the canonical
production Compose project identity. The basename of a checkout or of a
release directory such as `/opt/myshop/releases/<SHA>` must never change the
stack name. Production therefore keeps using the existing resources
`myshop_postgres_data`, `myshop_caddy_data`, `myshop_caddy_config`, and
`myshop_internal`.

From a checkout or release directory, use the repository wrapper for every
production Compose operation:

```bash
release=/opt/myshop/releases/<full-sha>
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env config
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env build api web
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env up -d db node-exporter api web caddy
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env ps
```

`scripts/myshop-compose.sh` resolves its own repository/release root, pins
`docker-compose.yml` and `--project-directory` to that root, and explicitly
sets `COMPOSE_PROJECT_NAME=myshop`. It accepts normal Compose arguments such
as `--env-file`, `config`, `build`, `up`, `ps`, `logs`, and `exec`, but rejects
caller-supplied `-p`/`--project-name`, `-f`/`--file`, and
`--project-directory` overrides. Do not rely on `docker compose -p myshop` or
pass the old server-side runtime override as a second Compose file. Local or
CI experiments may call Compose directly when they intentionally need an
isolated project name; that does not change the production rule.

## Topology

Use one Docker Compose project with five services:

```text
Internet -> Caddy (80/443) -> Web (private) -> API (private) -> PostgreSQL (private)
                                      \-> node-exporter (private host metrics)
```

Only Caddy publishes host ports. PostgreSQL uses a named persistent volume;
public uploads use the explicit `UPLOAD_HOST_PATH` bind and private after-sale
evidence uses the separate `PRIVATE_UPLOAD_HOST_PATH` bind (production uses
`/data/myshop/uploads` and `/data/myshop/private-uploads`, never release-local
directories or symlinks). The private directory is mounted at
`PRIVATE_UPLOAD_PATH=/app/private-uploads` and has no static resource mapping;
the API streams an attachment only after its database ownership check. IP
geography XDB files use the separate `/data/myshop/ipdb` bind mounted
read-only at `/app/ipdb`; Caddy uses persistent certificate/config volumes. The
application services have restart policies and health checks; node-exporter is
intentionally optional to API startup so a missing exporter only degrades the
Dashboard metrics.

Compose attaches all five services to the private `172.30.0.0/24` network.
Caddy is fixed at `172.30.0.2`, API at `172.30.0.20`, Web at `172.30.0.30`,
PostgreSQL at `172.30.0.10`, and node-exporter at `172.30.0.40`. The API trusts only the exact Caddy address,
and Caddy overwrites `X-Real-IP` and `X-Forwarded-For` before proxying. Do not
add a broad private CIDR or publish API/Web/DB/node-exporter ports.

node-exporter is pinned to `quay.io/prometheus/node-exporter:v1.12.1`, has no
host `ports` mapping, and is reachable only from the Compose network. It reads
the host `/proc`, `/sys`, and root filesystem through read-only mounts to expose
host CPU, memory, root filesystem, uptime, and load metrics. It does not mount
`/var/run/docker.sock`, and the API does not need Docker access. Do not install
Prometheus or Grafana for this dashboard.

## First setup

1. Provision a supported Linux host with Docker Engine and Compose v2.
2. Copy the repository and `.env.example` to a protected deployment directory.
3. Copy `.env.example` to `.env`; set a unique long database password, a random JWT secret of at least 64 bytes, `SITE_DOMAIN`, matching `PUBLIC_SITE_URL`, `ACME_EMAIL`, `AUTH_COOKIE_SECURE=true`, `UPLOAD_HOST_PATH`, `PRIVATE_UPLOAD_HOST_PATH`, and `IP_GEO_HOST_PATH=/data/myshop/ipdb`.
4. Set the bootstrap admin variables only for the first startup. Passwords use 6-72 ASCII visible characters. If a fixed super administrator is required, also set `BOOTSTRAP_SUPER_ADMIN_USERNAME` and `BOOTSTRAP_SUPER_ADMIN_PASSWORD`; an existing account is never reset by a later restart. Remove bootstrap password variables after successful initialization.
5. Point DNS for `SITE_DOMAIN` to the host and allow TCP 80/443.
6. Create the public upload directory and the separate private after-sale directory with owner `10001:10001` and mode `0750`, and create `/data/myshop/ipdb` before Compose starts because all three binds use `create_host_path: false`. Provision the two official non-empty XDB files there when available, then run `bash scripts/myshop-compose.sh --env-file .env up -d --build`, inspect `bash scripts/myshop-compose.sh --env-file .env ps`, and read API logs with `bash scripts/myshop-compose.sh --env-file .env logs api`. Missing XDB files only degrade the detail estimate to `未知`; they do not prevent API startup.
7. After API and Web health checks pass, run `bash /opt/myshop/scripts/cleanup-docker-retention.sh` to retain only the active release and two rollback releases and to remove unused Docker build cache. The script never touches Docker volumes, uploads, or database backups.

Before startup, `bash scripts/check-compose.sh` verifies that Compose receives
`SITE_DOMAIN`, `ACME_EMAIL`, `DB_PASSWORD`, and `JWT_SECRET`, and that missing
required values fail fast. The Caddy service explicitly receives its domain
and ACME email; it does not depend on root `.env` values being implicitly
passed into a container.

## Public response headers

Caddy adds the baseline security headers at the public edge, so they cover both
Spring API responses and Next.js pages: HSTS on HTTPS requests only,
`X-Content-Type-Options: nosniff`,
`Referrer-Policy: strict-origin-when-cross-origin`, a restrictive
`Permissions-Policy`, and `X-Frame-Options: DENY`. The repository's
`scripts/check-compose.sh` performs a lightweight presence check for these
rules and the Caddy client-IP overwrite rules.

No CSP is enabled in this Phase 1 pass. Before production, validate a staged
CSP against Next.js assets, payment QR/image loading, and Cloudflare Turnstile;
only then add a policy that does not break login or payment flows.

Flyway runs before the application validates the schema. Do not change
production `spring.jpa.hibernate.ddl-auto` from `validate`, and do not run
`flyway clean`.

## Dedicated host data disk

For a single-shop host with a separate ext4 data disk, keep the operating
system and release files on the root filesystem, and mount the data disk at
`/data` before Docker starts. The recommended production layout is:

```text
/data/docker             Docker metadata, containers, and named volumes
/data/containerd         Docker image layers and BuildKit/containerd snapshots
/data/myshop/uploads     Product and site uploads
/data/myshop/private-uploads  Private after-sale evidence
/data/myshop/ipdb        Official ip2region IPv4/IPv6 XDB files
/data/myshop/backups     PostgreSQL backups
/opt/myshop              Releases, Compose files, and runtime configuration
```

Configure Docker's `data-root` to `/data/docker` and set the production `.env`
values `UPLOAD_HOST_PATH=/data/myshop/uploads`,
`PRIVATE_UPLOAD_HOST_PATH=/data/myshop/private-uploads`, and
`IP_GEO_HOST_PATH=/data/myshop/ipdb`. Do not create or depend on an
`/opt/myshop/uploads` symlink. Set `DATA_DISK_MOUNTPOINT=/data` so the
Dashboard reports the real data filesystem. Do not format an existing disk as
part of a migration; verify its device, stable UUID, filesystem, mountpoint,
and contents first, then copy Docker and application data while Docker is
stopped. Retain a database backup before changing Docker's data root.

Use a stable UUID entry in `/etc/fstab`, for example:

```fstab
UUID=<data-disk-uuid> /data ext4 defaults 0 2
```

Make Docker wait for the data disk with
`/etc/systemd/system/docker.service.d/myshop-data.conf`:

```ini
[Unit]
RequiresMountsFor=/data
```

Before every deployment, require all of the following to succeed:

```bash
findmnt -M /data
docker info --format '{{.DockerRootDir}}' # must be /data/docker
docker volume inspect myshop_postgres_data myshop_caddy_data myshop_caddy_config \
  --format '{{.Name}} {{.Mountpoint}}' # each mountpoint must be under /data/docker/volumes
bash /opt/myshop/current/scripts/myshop-compose.sh --env-file /opt/myshop/.env config
```

Never remove Docker volumes as part of a data-disk or schema migration. Verify
that PostgreSQL and Caddy data remain available before continuing.

## IP geography database updates

The API uses the replaceable `IpLocationService` boundary and official
ip2region Java binding. The API never downloads XDB data during startup. Run
the repository's operator script from the deployment checkout to fetch the
latest formal official release, verify non-empty IPv4 and IPv6 files with the
binding, atomically replace both files, write `/data/myshop/ipdb/manifest`, and
restart only the API so the new data is loaded:

```bash
bash ops/update-ip2region.sh
```

The script never touches PostgreSQL/Caddy volumes, uploads, backups, or the
application source. If download or query validation fails, the existing XDB
files remain in place. Set `RESTART_API=false` when a later controlled restart
is preferred.

## Dashboard server metrics

The admin-only `GET /api/admin/dashboard/system-status` endpoint fetches the
node-exporter text endpoint through `NODE_EXPORTER_URL` (the Compose default is
`http://node-exporter:9100`). CPU usage is calculated from consecutive
`node_cpu_seconds_total` counter samples; memory uses MemTotal minus
MemAvailable; disk uses the real host root filesystem and excludes pseudo
filesystems; data disk uses the real filesystem mounted at
`DATA_DISK_MOUNTPOINT` (default `/data`); uptime comes from boot time and load
from `node_load1`. If the configured data mountpoint is absent, the data disk
metric is unavailable while the root disk metric remains independent.

The endpoint also performs short best-effort checks for PostgreSQL (`SELECT 1`),
Web (`http://web:3000/`), and a TCP connection to Caddy port 80. Every check is
isolated: an unavailable exporter or service returns `available=false`, null
metrics, or a `DOWN`/`UNKNOWN` status inside a successful JSON response. These
checks are not a public health or metrics API.

## Payment callback checklist

- Set the canonical public storefront origin in `PUBLIC_SITE_URL`, aligned with `SITE_DOMAIN` and without a query or fragment.
- Configure one enabled Epay-compatible channel with only `api_url` (支付网关), `pid` (商户号 / PID), and `key` (商户密钥 / KEY). A root gateway URL or explicit `submit.php` uses a browser Form POST to `submit.php`; explicit `mapi.php` or `pay/apisubmit` uses the server-side API POST. API `qrcode` / `qr_code` responses are always raw QR payloads rendered locally by the storefront, even when the value is an HTTP(S) URL. Valid HTTP(S) values in `payurl`, `payurl2`, `url`, or `pay_action_url` are top-level redirects, and `urlscheme` is a DeepLink. An HTML or non-JSON response from an explicitly configured API endpoint fails with guidance to configure the gateway root or `submit.php`; order queries use `/api.php` on the normalized gateway root.
- The server generates the provider-generic callback route `/api/payments/webhook/{providerId}` and the storefront payment-result return page; no callback or return URL is entered in the admin UI.
- Verify the generated callback route is reachable through Caddy while the API remains private.
- Test a successful callback, wrong amount, bad signature, unpaid server query, query-unavailable policy, and duplicate webhook before accepting real orders.
- Disabling, editing, or soft-deleting a channel blocks only new payment attempts. Existing attempts continue with their immutable provider/config/payment-type/channel snapshots.
- Never put Epay keys in Compose files, Git, screenshots, or ordinary logs.

## Delivery correctness

The P0 invariant is all-or-nothing delivery. The payment transaction commits
`PAID` and its webhook event first. A separate fulfillment transaction locks
the order row, locks every requested available card-key pool with PostgreSQL
`FOR UPDATE SKIP LOCKED`, verifies all quantities, and only then changes all
keys and the order. A shortage throws and rolls back the earlier locks and
mutations while leaving the committed order `PAID`. A one-minute scheduler
retries a bounded batch of paid, undelivered orders after stock is restored.
A second scheduler or compensation attempt waits on the order lock and then
observes DELIVERED, so it cannot allocate a second set of keys. Fulfillment is
started by the internal service and scheduler paths; it has no browser-facing
fallback endpoint.

The PostgreSQL integration tests cover automatic callback delivery without a
browser request, duplicate callbacks, shortage rollback plus later retry,
guest idempotency and session ownership, and two concurrent delivery/payment
attempts.
They require Docker because they start real PostgreSQL Testcontainers instances.

## Authentication and sensitive data

The API refuses to start when `JWT_SECRET` is missing, shorter than 64 bytes, or
has obviously insufficient character diversity. Test configuration supplies a
separate fixed test value; `application.yml` has no production fallback. Login
and registration JSON contains only the public user profile. The JWT is created
only as an internal result and written to an HttpOnly cookie by the controller.

Guest ownership is bound to the browser session captured when the order is
created. The session token is sent through the request header and must never be
copied into admin exports, operation logs, exception messages, screenshots, or
support bundles. A signed-in user cannot use authentication alone to access a
guest order without that order's matching session.

## Runtime support gate

The API baseline is Java 25 LTS with Spring Boot 4.1.1. The web baseline is
Next.js 16.3.3, upgraded to the official security release for the 16.3.x
Active LTS line. The security-version upgrade is complete in this baseline;
production deployment requires a successful CI run for the exact revision and
explicit browser acceptance. After deployment, record the exact deployed
revision in /opt/myshop/DEPLOYED_SHA.

## Release deployment sequence

Deploy the exact pushed SHA as a release snapshot; do not run production from
a Git checkout. Before changing containers, record `/opt/myshop/DEPLOYED_SHA`,
the `/opt/myshop/current` target, the active Compose labels, the existing
`myshop_*` volumes and `myshop_internal` network, and a verified PostgreSQL
custom backup. Extract the release to
`/opt/myshop/releases/<full-sha>`, set the protected production `.env` image
tags to `myshop-api:<full-sha>` and `myshop-web:<full-sha>`, and validate it
from the SHA directory:

```bash
release=/opt/myshop/releases/<full-sha>
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env config
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env build api web
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env up -d db node-exporter api web caddy
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env ps
```

The rendered project must be `myshop`; the deployment must reuse
`myshop_postgres_data`, `myshop_caddy_data`, `myshop_caddy_config`, and
`myshop_internal`. It must not create resources named with the release SHA.
After the services are healthy, atomically update `DEPLOYED_SHA` and
`current`, perform the smoke/schema/health checks below, and only then run
retention cleanup. Never delete or recreate the PostgreSQL volume as part of
this sequence.

## Post-release smoke, schema, and safety checks

Run these checks with the same SHA-named release directory that started the
containers. They deliberately avoid authentication credentials and real order
identifiers. The first two checks validate the internal container health
contracts; the public requests validate the Caddy-to-Web/API path:

```bash
release=/opt/myshop/releases/<full-sha>
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env ps
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env exec -T api \
  wget -q -O /dev/null http://127.0.0.1:8083/api/site/config
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env exec -T web \
  wget -q -O /dev/null http://127.0.0.1:3000/
public_url="$(awk -F= '/^PUBLIC_SITE_URL=/{print $2; exit}' /opt/myshop/.env)"
curl --fail --silent --show-error --output /dev/null "$public_url/"
curl --fail --silent --show-error --output /dev/null "$public_url/api/site/config"
```

Verify Flyway V28 and its database contracts without printing credentials or
card contents. The query must report one successful V28 migration, the
`users.avatar_url` column, the `orders.refunded_at` column, an
`orders_status_check` constraint containing `AFTER_SALE`, the
`order_items.product_cover_url` column, the
`balance_transactions_refund_contract_check` constraint, the partial unique
refund index, both after-sale tables, their core constraints, and the partial
unique pending-request index:

```bash
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env exec -T db sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "
    SELECT version, success FROM flyway_schema_history WHERE version = '\''28'\'' AND success;
    SELECT to_regclass('\''public.users'\'') IS NOT NULL
      AND EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = '\''users'\'' AND column_name = '\''avatar_url'\''
                    AND is_nullable = '\''YES'\'' AND character_maximum_length = 500)
      AND to_regclass('\''public.orders'\'') IS NOT NULL
      AND EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = '\''orders'\'' AND column_name = '\''refunded_at'\'')
      AND EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = '\''order_items'\'' AND column_name = '\''product_cover_url'\''
                    AND is_nullable = '\''YES'\'')
      AND EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = '\''orders_status_check'\''
                    AND pg_get_constraintdef(oid) LIKE '\''%AFTER_SALE%'\'')
      AND EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = '\''balance_transactions_refund_contract_check'\'')
      AND EXISTS (SELECT 1 FROM pg_indexes
                  WHERE indexname = '\''uq_balance_transactions_order_refund'\'')
      AND EXISTS (SELECT 1 FROM pg_class
                  WHERE relname = '\''card_key_reclaims'\'')
      AND EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = '\''uq_card_key_reclaims_order_card_key'\'')
      AND EXISTS (SELECT 1 FROM pg_indexes
                  WHERE indexname = '\''idx_card_key_reclaims_order'\'')
      AND to_regclass('\''public.after_sale_requests'\'') IS NOT NULL
      AND to_regclass('\''public.after_sale_attachments'\'') IS NOT NULL
      AND EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = '\''after_sale_requests'\''
                    AND column_name = '\''source_order_status'\'')
      AND EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = '\''after_sale_requests'\''
                    AND column_name = '\''admin_reply'\'')
      AND EXISTS (SELECT 1 FROM information_schema.columns
                  WHERE table_name = '\''after_sale_attachments'\''
                    AND column_name = '\''storage_key'\'')
      AND EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = '\''after_sale_requests_status_check'\'')
      AND EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = '\''after_sale_requests_source_status_check'\'')
      AND EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conname = '\''after_sale_attachments_file_size_check'\'')
      AND EXISTS (SELECT 1 FROM pg_indexes
                  WHERE indexname = '\''uq_after_sale_requests_pending_order'\''
                    AND indexdef LIKE '\''%WHERE%PENDING%'\'');
  "'
```

Finish the production safety-ablation check with a fixed UUID that is not an
order identifier. Do not substitute a real order ID. Without an admin session,
the refund endpoint must reject the request with HTTP 401 or 403 before any
state change; a successful response is a deployment failure and must be
investigated before retention cleanup:

```bash
probe_order_id=00000000-0000-4000-8000-000000000000
status_code="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --request POST --header 'Content-Type: application/json' \
  --data '{"confirmed":true,"reason":"safe deployment negative probe"}' \
  "$public_url/api/admin/orders/$probe_order_id/refund")"
test "$status_code" = 401 || test "$status_code" = 403
```

This probe is only an authorization-boundary check. It is not a substitute for
the dedicated CI/API refund tests, which cover optional reason normalization,
guest rejection, terminal-state rejection, idempotency, balance/ledger
constraints, safe card-key reclaim with rollback on anomalies, and concurrent
refund, fulfillment, and payment transitions. Never perform a real production
refund as part of a release smoke test.

## Backup and rollback

Before every schema or image change:

```bash
mkdir -p /data/myshop/backups
release=/opt/myshop/current
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env exec -T db \
  sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' \
  > /data/myshop/backups/myshop-$(date +%Y%m%d-%H%M%S).dump
bash "$release/scripts/myshop-compose.sh" --env-file /opt/myshop/.env ps
```

Keep backups in `/data/myshop/backups`, outside the repository, and periodically
perform a restore test into an isolated PostgreSQL instance. To roll back an application image,
change `API_IMAGE`/`WEB_IMAGE` to the previous immutable tags and run
`bash /opt/myshop/current/scripts/myshop-compose.sh --env-file /opt/myshop/.env up -d`; never roll back a database migration by deleting rows
or editing Flyway history. A migration requiring rollback must ship a new
forward migration after a reviewed backup.

## Docker retention

Production keeps exactly three application versions: the active version and
the two most recent rollback candidates. The retention boundary applies to
both `/opt/myshop/releases/<SHA>` directories and the matching
`myshop-api:<SHA>`/`myshop-web:<SHA>` image tags.

After every successful deployment, run:

```bash
bash /opt/myshop/scripts/cleanup-docker-retention.sh
```

The script removes unused application images, dangling images, and all unused
BuildKit cache, then removes release directories outside the three-version
window. It verifies `DEPLOYED_SHA`, the `current` symlink, and the running
API/Web image plus `com.docker.compose.project=myshop` and
`com.docker.compose.service=api|web` labels before deleting anything. If the
active service labels identify a SHA-named or otherwise ambiguous project,
retention fails closed without deleting an image or release. It must not be replaced with
`docker volume prune` or `docker system prune --volumes`; PostgreSQL, Caddy,
uploads, and backups are outside the cleanup scope.

Install `ops/systemd/myshop-docker-retention.service` and
`ops/systemd/myshop-docker-retention.timer` on the production host as a daily
fallback. The deployment hook remains required so fast release iteration does
not leave a full day's build cache on the data disk.

## Operational boundaries

There is no Kubernetes, message broker, Elasticsearch, Redis requirement, or
automated production deploy in Phase 1. Add those only when measured load or
reliability requirements justify them and after preserving the transaction
boundaries described above.

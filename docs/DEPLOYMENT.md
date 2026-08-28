# MyShop Phase 1 deployment runbook

This document describes the intended single-host deployment. It is a runbook,
not a production deployment action performed by CI or this acceptance pass.

## Topology

Use one Docker Compose project with five services:

```text
Internet -> Caddy (80/443) -> Web (private) -> API (private) -> PostgreSQL (private)
                                      \-> node-exporter (private host metrics)
```

Only Caddy publishes host ports. PostgreSQL uses a named persistent volume;
uploads use `./uploads` on the host (the production path may be a symlink to a
dedicated data disk); Caddy uses persistent certificate/config volumes. The
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
3. Copy `.env.example` to `.env`; set a unique long database password, a random JWT secret of at least 64 bytes, `SITE_DOMAIN`, `ACME_EMAIL`, and `AUTH_COOKIE_SECURE=true`.
4. Set all bootstrap admin variables only for the first startup. Use a password of at least 12 characters, then remove `BOOTSTRAP_ADMIN_PASSWORD` after successful login.
5. Point DNS for `SITE_DOMAIN` to the host and allow TCP 80/443.
6. Run `docker compose up -d --build` and inspect `docker compose ps` and API logs.

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
/data/docker             Docker images, build cache, containers, and named volumes
/data/myshop/uploads     Product and site uploads
/data/myshop/backups     PostgreSQL backups
/opt/myshop              Releases, Compose files, and runtime configuration
```

Configure Docker's `data-root` to `/data/docker`, keep the operational upload
path `/opt/myshop/uploads` pointing to `/data/myshop/uploads`, and keep the
operational backup path `/opt/myshop/backups` pointing to `/data/myshop/backups`.
When the data disk is mounted elsewhere, set `DATA_DISK_MOUNTPOINT` to that
absolute mountpoint. Do not format an existing disk as part of a migration;
verify its device, UUID, filesystem, mountpoint, and contents first, then copy
Docker and application data while Docker is stopped. Retain a database backup
before changing Docker's data root.

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

- Configure one enabled Epay channel with `pid`, `key`, `api_url`, `notify_url`, and `return_url`.
- Use the public HTTPS URL `/api/payments/webhook/epay` as the Epay notify URL.
- Verify the configured callback URL is reachable through Caddy while the API remains private.
- Test a successful callback, wrong amount, bad signature, unpaid server query, and duplicate `trade_no` before accepting real orders.
- Never put Epay keys in Compose files, Git, screenshots, or ordinary logs.

## Delivery correctness

The P0 invariant is all-or-nothing delivery. The payment transaction commits
`PAID` and its webhook event first. A separate fulfillment transaction locks
the order row, locks every requested available card-key pool with PostgreSQL
`FOR UPDATE SKIP LOCKED`, verifies all quantities, and only then changes all
keys and the order. A shortage throws and rolls back the earlier locks and
mutations while leaving the committed order `PAID`. A one-minute scheduler
retries a bounded batch of paid, undelivered orders after stock is restored.
A second delivery request or compensation attempt waits on the order lock and
then observes DELIVERED, so it cannot allocate a second set of keys. Browser
`/orders/{id}/deliver` remains only a credential-gated idempotent fallback.

The PostgreSQL integration tests cover automatic callback delivery without a
browser request, duplicate callbacks, shortage rollback plus later retry,
guest idempotency/token recovery, and two concurrent delivery/payment attempts.
They require Docker because they start real PostgreSQL Testcontainers instances.

## Authentication and sensitive data

The API refuses to start when `JWT_SECRET` is missing, shorter than 64 bytes, or
has obviously insufficient character diversity. Test configuration supplies a
separate fixed test value; `application.yml` has no production fallback. Login
and registration JSON contains only the public user profile. The JWT is created
only as an internal result and written to an HttpOnly cookie by the controller.

Guest lookup tokens are recoverable by deliberate project choice for this
single-owner, low-volume deployment. Treat the `guest_lookup_token` column and
all database backups as sensitive credentials. Never add it to admin exports,
operation logs, exception messages, screenshots, or support bundles.

## Runtime support gate

The API baseline is Java 25 LTS with Spring Boot 4.1.1. The web baseline is
Next.js 16.3.3, upgraded to the official security release for the 16.3.x
Active LTS line. The security-version upgrade is complete in this baseline;
production release still requires this revision's complete CI run and a
separate authorized real-device acceptance. This acceptance pass intentionally
does not deploy production.

## Backup and rollback

Before every schema or image change:

```bash
docker compose exec -T db pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom > backups/myshop-$(date +%Y%m%d-%H%M%S).dump
docker compose ps
```

Keep backups outside the repository and periodically perform a restore test
into an isolated PostgreSQL instance. To roll back an application image,
change `API_IMAGE`/`WEB_IMAGE` to the previous immutable tags and run
`docker compose up -d`; never roll back a database migration by deleting rows
or editing Flyway history. A migration requiring rollback must ship a new
forward migration after a reviewed backup.

## Operational boundaries

There is no Kubernetes, message broker, Elasticsearch, Redis requirement, or
automated production deploy in Phase 1. Add those only when measured load or
reliability requirements justify them and after preserving the transaction
boundaries described above.

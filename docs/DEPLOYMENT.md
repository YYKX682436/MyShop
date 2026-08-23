# MyShop Phase 1 deployment runbook

This document describes the intended single-host deployment. It is a runbook,
not a production deployment action performed by CI or this acceptance pass.

## Topology

Use one Docker Compose project with four services:

```text
Internet -> Caddy (80/443) -> Web (private) -> API (private) -> PostgreSQL (private)
```

Only Caddy publishes host ports. PostgreSQL uses a named persistent volume;
uploads use `./uploads` on the host; Caddy uses persistent certificate/config
volumes. All services have restart policies and health checks.

Compose attaches all four services to the private `172.30.0.0/24` network.
Caddy is fixed at `172.30.0.2`, API at `172.30.0.20`, Web at `172.30.0.30`,
and PostgreSQL at `172.30.0.10`. The API trusts only the exact Caddy address,
and Caddy overwrites `X-Real-IP` and `X-Forwarded-For` before proxying. Do not
add a broad private CIDR or publish API/Web/DB ports.

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
Next.js 16.3.2, the latest stable 16.3.x selected for this acceptance pass.
Next has an announced security release on 2026-08-26; before any public
production launch, upgrade to that repaired version (or later stable version),
regenerate the lockfile, and rerun the complete CI and Compose checks. This
acceptance pass intentionally does not deploy production.

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

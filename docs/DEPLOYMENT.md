# MyShop Phase 1 deployment runbook

This document describes the intended single-host deployment. It was not
executed as part of the Phase 1 implementation request.

## Topology

Use one Docker Compose project with four services:

```text
Internet -> Caddy (80/443) -> Web (private) -> API (private) -> PostgreSQL (private)
```

Only Caddy publishes host ports. PostgreSQL uses a named persistent volume;
uploads use `./uploads` on the host; Caddy uses persistent certificate/config
volumes. All services have restart policies and health checks.

## First setup

1. Provision a supported Linux host with Docker Engine and Compose v2.
2. Copy the repository and `.env.example` to a protected deployment directory.
3. Copy `.env.example` to `.env`; set a unique long database password, a random JWT secret, `SITE_DOMAIN`, `ACME_EMAIL`, and `AUTH_COOKIE_SECURE=true`.
4. Set all bootstrap admin variables only for the first startup. Use a password of at least 12 characters, then remove `BOOTSTRAP_ADMIN_PASSWORD` after successful login.
5. Point DNS for `SITE_DOMAIN` to the host and allow TCP 80/443.
6. Run `docker compose up -d --build` and inspect `docker compose ps` and API logs.

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

The P0 invariant is all-or-nothing delivery. One transaction locks the order
row, locks every requested available card-key pool with PostgreSQL
`FOR UPDATE SKIP LOCKED`, verifies all quantities, and only then changes all
keys and the order. A shortage throws and rolls back the earlier locks and
mutations. A second delivery request for the same order waits on the order
lock and then observes DELIVERED, so it cannot allocate a second set of keys.

The `PostgresDeliveryIntegrationTest` covers both shortage rollback and two
concurrent delivery attempts. It requires Docker because it starts a real
PostgreSQL Testcontainers instance.

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

# MyShop

MyShop Phase 1 is a digital-goods (card-key) storefront. It provides product details, direct purchase, order lookup, admin, and theme surfaces while making order credentials, payment callbacks, and automated delivery explicit security boundaries.

The source and license boundary is recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Orion Key is the primary code baseline; ACG-Faka is used only for behavior and UI design reference. This repository does not run PHP or copy ACG runtime code.

## Phase 1 scope

- Storefront: products/specifications, guest/member direct purchase, Alipay/WeChat payment, order lookup, and card-key display/export.
- Admin: products, categories, card pools, orders, users, site settings, operation logs, and payment-channel management.
- Payments: extensible `PaymentProvider` SPI with Epay built in; only Alipay and WeChat are enabled in this phase.
- Data: PostgreSQL with versioned Flyway migrations; non-test `ddl-auto=validate`; no `data.sql` seed path.
- Security: BCrypt, HttpOnly session cookie plus CSRF double-submit protection, trusted-proxy IP resolution, and hashed random guest order credentials.
- Delivery: per-line PostgreSQL `FOR UPDATE SKIP LOCKED`; every pool is checked before any mutation, and one shortage rolls back the whole delivery.

The storefront uses CNY-only pricing. Not in Phase 1: coupons/custom fields/membership tiers, Kafka/RabbitMQ/Elasticsearch/Kubernetes, Redis-by-default rate limiting, microservice splitting, or production deployment.

## Layout

```text
apps/api/       Spring Boot API, services, Flyway migrations, and tests
apps/web/       Next.js storefront/admin
docs/           payment SPI and deployment runbook
Caddyfile       Compose reverse proxy
docker-compose.yml
```

## Local development

Use Node.js 22+, pnpm 10, Java 25 LTS, and Maven 3.9+. The local API defaults to PostgreSQL on `localhost:5432`; never use default database credentials or a development JWT secret in a production environment.

```powershell
Copy-Item .env.example .env
# Edit .env with DB_URL, DB_USERNAME, DB_PASSWORD, and JWT_SECRET.

pnpm install --frozen-lockfile
pnpm --filter @myshop/web dev

cd apps/api
mvn spring-boot:run
```

For Compose, edit `.env` with `SITE_DOMAIN`, `ACME_EMAIL`, and database/JWT values, then run:

```powershell
docker compose up -d --build
docker compose ps
docker compose logs -f api
```

The default Compose topology contains only PostgreSQL, API, Web, and Caddy. PostgreSQL is persistent; API and Web have no host-facing ports, and public traffic enters through Caddy. Server setup, backup, restore, and rollback are documented in [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

## Administrator bootstrap

The application does not create `admin/123456` and never logs an administrator password. Before first startup, set all three variables to enable the one-time bootstrap:

```text
BOOTSTRAP_ADMIN_USERNAME
BOOTSTRAP_ADMIN_EMAIL
BOOTSTRAP_ADMIN_PASSWORD   # at least 12 characters
```

After logging in, remove the bootstrap password variable. Admin password resets are available in the admin UI, and passwords are always stored as BCrypt hashes.

## Order credentials and delivery invariants

For a guest order, the API returns a random `lookup_token` only in the creation response; the database stores only its SHA-256 digest. Guest lookup, delivery, card-key export, and guest payment actions require the order ID plus this credential. An authenticated owner may access their own order without the guest credential. An order ID alone cannot read or export card keys.

The delivery transaction locks the order, then locks each available card-key pool for every order line. It collects and verifies every requested quantity before marking any key SOLD, binding keys to the order, or changing the order to DELIVERED. Concurrent delivery attempts for one order are serialized by the order-row lock. See [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) for the PostgreSQL concurrency test.

## Payment channels

Each external payment creates an independent `PaymentAttempt` with immutable channel, provider, payment-type, and configuration snapshots. Callback signature verification, exact amount comparison, server-side order query, and webhook idempotency use that attempt snapshot; the `Order` stores no provider trade number or payment action. A client callback cannot mark an order paid by itself; an Epay-compatible gateway whose query endpoint explicitly returns HTML may use its already verified signed callback when the amount and success status also match, while network, empty, malformed, and query-error responses remain failures. A repeated `trade_no` is handled through the unique webhook event. The provider extension contract is documented in [docs/PAYMENT_PROVIDER.md](docs/PAYMENT_PROVIDER.md).

An Epay-compatible channel stores only `api_url` (gateway), `pid` (merchant ID), and `key` (merchant secret). A root gateway URL returns a browser `Form POST` to `/submit.php`; an explicit `/submit.php` URL posts directly to that endpoint; an explicit `/mapi.php` or `/pay/apisubmit` URL uses a server-side form API POST. API `qrcode` and `qr_code` values are always raw QR payloads rendered locally by the frontend, including HTTP(S) values; they are never treated as pages or embedded in the QR area. Valid HTTP(S) values in `payurl`, `payurl2`, `url`, or `pay_action_url` become top-level redirects, while `urlscheme` becomes a deep link. If an explicitly configured API endpoint returns HTML or non-JSON, creation fails with guidance to configure the gateway root or `submit.php`. Order queries remove the three endpoint suffixes and call `/api.php` on the gateway root. The server generates callback and payment-result return URLs from `PUBLIC_SITE_URL`; sensitive fields are masked in admin responses, and public APIs never return provider configuration or keys.

## Verification commands

```powershell
# Web
pnpm install --frozen-lockfile
pnpm --filter @myshop/web exec tsc --noEmit
pnpm --filter @myshop/web lint
pnpm --filter @myshop/web build

# API unit/application tests
cd apps/api
mvn -B verify

# PostgreSQL delivery integration tests (requires local Docker)
mvn -B -Dtest=PostgresDeliveryIntegrationTest test
```

GitHub Actions runs build and test jobs only; it contains no server deployment step.

## Reference and license

The Orion Key MIT license is retained. Third-party sources, retained scope, and the ACG behavior-reference boundary are recorded in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

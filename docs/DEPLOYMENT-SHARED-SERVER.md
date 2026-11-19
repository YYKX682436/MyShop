# MyShop production on the shared Baota host

**The production store already exists. Routine releases upgrade it in place.**

| Setting | Production value |
| --- | --- |
| Public URL | `https://mc.mc520.top` |
| Server | `110.42.34.161` |
| Compose project | `myshop-friend-prod` |
| Runtime directory | `/opt/myshop-friend/runtime` |
| Private environment file | `/etc/myshop-friend/myshop.env` |
| Persistent data root | `/srv/myshop-friend` |

The old `myshop` Compose project is not the production target. The temporary
`myshop-candidate.mc520.top` route is intentionally disabled; a 502 there is
not a deployment blocker. Do not restore it. Routine releases do not recreate
the stack, restore a database from another host, or change DNS or TLS.

## Routine production upgrade

1. Follow `AGENTS.md`: preview locally, push the selected commit, and wait for
   the owner's production release approval. Run the manual GitHub release
   workflow for the **exact** approved commit. Confirm that the Web, API
   (including PostgreSQL integration tests), and Compose checks passed and
   that the matching immutable API/Web images and release manifest are
   available. Stop if checks were skipped or publication did not complete.
2. Use an authorized terminal on `110.42.34.161`. The already authorized Baota
   web terminal works for this purpose when available; it does not require
   separate SSH credentials. First verify the host identity, the running
   `myshop-friend-prod` services and current accepted release, and the
   existing runtime, mounts, and loopback binding. If the expected stack or
   accepted state is missing, stop and check the target. Do not fall back to
   first-install mode.
3. Use the existing production configuration and persistence. Load the
   shared-host settings below and run the read-only `upgrade` preflight as
   root on the target host.
   Resolve any failure before deploying; do not bypass the checks or touch
   other projects on the host.
4. Run the installed `deploy-production.sh` with the validated **new**
   release-manifest path using the same settings. The existing release
   transaction checks images and digests, keeps its predeploy recovery point,
   verifies health/schema/smoke checks, and rolls back the app images on
   pre-commit failure only when the Flyway history is unchanged. If the
   candidate has started, rollback stops the app-facing services before
   checking history; a changed or unreadable history blocks the old API and
   leaves the database and recovery point for operator assessment. It does
   not automatically restore a live database. Leave the existing proxy and
   data in place.
5. Verify the accepted release and the real HTTPS site, including the homepage
   and `/api/site/config`. Verify the changed feature and report the version
   deployed. Do not use the disabled candidate site for acceptance.

Use the currently configured loopback port, confirmed on the host. The example
below uses `8082`; if it differs, match the verified production binding.
`PUBLIC_SITE_URL` in the existing private configuration remains
`https://mc.mc520.top`. Do not copy secrets into the repository or logs.

```bash
export MYSHOP_RUNTIME_ROOT=/opt/myshop-friend/runtime
export MYSHOP_ENV_FILE=/etc/myshop-friend/myshop.env
export MYSHOP_COMPOSE_FILE=/opt/myshop-friend/runtime/docker-compose.shared-server.yml
export MYSHOP_COMPOSE_PROJECT_NAME=myshop-friend-prod
export MYSHOP_SHARED_SERVER=1
export MYSHOP_BACKUP_DIR=/srv/myshop-friend/backups/database
export MYSHOP_STORAGE_MOUNTPOINT=/srv/myshop-friend
export HOST_STORAGE_HEALTH_SCRIPT=/opt/myshop-friend/ops/shared-server/check-storage-health.sh
export HOST_DEPLOY_LOCK_FILE=/run/myshop-friend-deploy.lock
export MYSHOP_RUN_HOST_MAINTENANCE=0
export MYSHOP_APPROVED_REGISTRY=ghcr.io/yykx682436
export MYSHOP_SMOKE_BASE_URL=http://127.0.0.1:8082
export MYSHOP_SMOKE_HOST_HEADER=mc.mc520.top

bash /opt/myshop-friend/scripts/preflight-shared-server.sh --mode upgrade
# Only after preflight succeeds, substitute the validated new manifest path:
bash /opt/myshop-friend/scripts/deploy-production.sh /path/to/new-release-manifest.json
```

The existing production stack is independent of the friend's other sites and
services. Do not run `scripts/bootstrap-greenfield.sh`, modify global
Nginx/Docker or firewall settings, or run host-wide cleanup as part of a release.

## Request path

```mermaid
flowchart LR
  Browser[Browser HTTPS] --> Nginx[Existing Baota Nginx :443]
  Nginx -->|127.0.0.1:MYSHOP_WEB_PORT| Caddy[MyShop Caddy :80]
  Caddy -->|/api/*, path preserved| API[Spring API]
  Caddy -->|other routes| Web[Next.js Web]
  API --> DB[(Private PostgreSQL network)]
```

Baota keeps public ports 80/443 and TLS. The only published MyShop port is the
preflight-selected loopback port on Caddy. PostgreSQL, API, Web, and node-exporter
have no host-published ports. Compose allocates isolated database and metrics
networks; only API can access the node-exporter metrics service. The edge subnet,
Docker dynamic allocation range, Caddy IP, and Baota-to-Caddy source CIDR are
explicit. Keep Caddy's fixed address outside the dynamic range so Compose cannot
assign it to an API container first.

The browser sends `/api/*` to the same origin. Caddy forwards the path unchanged
to Spring, including uploads and payment webhooks. Baota must overwrite
`X-Real-IP` and `X-Forwarded-For` with `$remote_addr`, and set
`X-Forwarded-Proto` to `$scheme`; Caddy trusts forwarded headers only from the
single audited source address configured as `MYSHOP_BAOTA_NGINX_PROXY_CIDR`
(`/32` or `/128`) and passes the resolved client IP to the API. Do not fill this
value from an assumed Docker gateway. Observe the address Caddy receives from
the Baota proxy first, then set `MYSHOP_BAOTA_PROXY_SOURCE_CONFIRMED=1`. Both
preflight and the deploy script reject a missing confirmation. Keep it `0` while
the real connection source is unknown. If a CDN is in front of Baota, configure
its trusted real-IP ranges in that site before relying on the client's IP.

## Files

- `docker-compose.shared-server.yml`: image-only stack with a unique project,
  private PostgreSQL, independent upload paths, isolated networks, and one
  loopback ingress port.
- `Caddyfile.shared-server`: internal HTTP routing and proxy-header handling;
  it does not request or store public certificates.
- `ops/shared-server/myshop.env.example`: credential-free operational template.
  It is a reference only; do not replace the live environment with it.
- `ops/shared-server/audit-readonly.sh`: sanitized read-only host inventory for
  a server operator to run when direct access is unavailable.
- `scripts/preflight-shared-server.sh`: renders Compose without starting it and
  verifies Docker versions, resource names, port binding, edge-network
  conflicts, secrets-file permissions, image refs, and canonical persistent
  paths. Use `--mode upgrade` for this production instance.
- The existing compose, schema, backup, deploy, and retention helpers accept a
  project/file override while retaining `myshop` as their default.
- `scripts/test-deployment-transaction.sh` covers accepted upgrades, first
  shared-host acceptance, candidate-only smoke failure, rollback, and project
  isolation. The proxy and audit config checks have separate focused scripts.

## Base image registry mirrors

The shared-host Compose file uses copies of the upstream PostgreSQL, Caddy, and
node-exporter manifests in the MyShop GHCR namespace. Routine application
releases reuse their pinned digests.

The three personal GHCR packages must be public for anonymous pulls from the
friend server. Their contents are byte-for-byte copies of already public
upstream images; no application secret or private source is included. The
shared Compose file pins each GHCR image by digest as well as a content-named
tag. `scripts/test-shared-server-images.sh` checks these pins and confirms the
legacy production Compose remains on its existing references.

If GHCR metadata is reachable but blob downloads reset, export each exact
digest reference with Skopeo to a Docker archive in a trusted build environment.
Run `scripts/create-offline-image-bundle.py` with the validated release manifest
and archive directory, then verify it with
`scripts/verify-offline-image-bundle.py --manifest ... --verify-archives ...`.
Transfer the archives, manifest, bundle, and runtime files over an authenticated
channel. On the host, `scripts/import-offline-image-bundle.sh <manifest>
<bundle> <archive-dir>` checks archive hashes, tags, full config digests, and
`linux/amd64`, then loads the images without creating containers. Docker may
normalize local image metadata during import, so the importer also compares the
runtime config and all filesystem layer diff IDs against the checked archive,
then records the daemon's local image IDs. With `MYSHOP_OFFLINE_IMAGES=1`,
deployment checks those recorded IDs and runtime contents, skips registry pulls,
adds `--pull never` to Compose startup, and records both the GHCR manifest
digest and local image ID for future rollback/retention. Keep the three
infrastructure image overrides blank when pulling from GHCR; set them to the
approved digest-derived `mirror-<digest>` tags only after an offline import.

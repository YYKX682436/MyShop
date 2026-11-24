# MyShop production storage-lifecycle runbook

This runbook describes the only supported production model. The application
artifact is an immutable container image reference plus its builder-generated
release manifest. Production runs only from the immutable image manifest
recorded under `/opt/myshop/runtime`. Production normally never builds
application images; the one-time initial-release exception is recorded below.

## Host layout and immutable Compose

The host framework is `/opt/myshop/runtime`:

```text
/opt/myshop/runtime/docker-compose.prod.yml
/opt/myshop/runtime/deploy-state.json
/opt/myshop/runtime/candidates/*.failed.json
/opt/myshop/scripts/*.sh
```

`/opt/myshop/.env` remains a protected secret file. The Compose project name
is always `myshop`; existing PostgreSQL, Caddy, upload, private-upload, and
ipdb identities are preserved. `docker-compose.prod.yml` contains no `build:`
entry, application images use full SHA tags, and infrastructure images use
explicit tag plus digest pins. The wrapper rejects `build`, `--build`,
`latest`, `local`, project/file overrides, and an image outside the exact
approved registry namespace.

Manual inspection is allowed only through the installed wrapper:

```bash
export MYSHOP_RUNTIME_ROOT=/opt/myshop/runtime
export MYSHOP_ENV_FILE=/opt/myshop/.env
export MYSHOP_APPROVED_REGISTRY=ghcr.io/yykx682436

bash /opt/myshop/scripts/myshop-compose.sh --env-file /opt/myshop/.env config
bash /opt/myshop/scripts/myshop-compose.sh --env-file /opt/myshop/.env ps
```

Do not run `docker compose` directly in the production directory and do not
run `up --build`.

## First installation on an empty host

`scripts/bootstrap-greenfield.sh` is a separate entry point for a new store.
Use it only with a verified release manifest for the exact source SHA and with
the matching Compose file and production helpers installed. Ordinarily the
manifest is produced by CI; the one-time owner-approved exception below permits
a locally built manifest for the November 17, 2026 first installation.
Set the three wrapper variables above. The environment must include the real
`SITE_DOMAIN`, matching HTTPS `PUBLIC_SITE_URL`, `ACME_EMAIL`, fresh database
and JWT secrets, and the first administrator's bootstrap credentials.

Before running it, stop and disable the old storage and Docker maintenance
timers, wait for any old maintenance process, and install the complete helper
set together. Verify the `/data` mount, Docker/containerd roots, and empty
upload and private-upload directories. Supply the read-only IP database
directory separately. Preserve the credentials outside the environment file
in a root-only or equivalent secure store.

```bash
bash /opt/myshop/scripts/bootstrap-greenfield.sh /path/to/release-manifest.json
```

The entry point refuses any existing deployment state, project container,
project network, or project database/Caddy volume. It verifies the manifest and pulled digests,
starts only a new PostgreSQL database, verifies that it is empty, and creates
and validates a fresh database backup before starting the application. It then
checks the packaged schema contract, HTTPS, public endpoints, canonical URL,
running image identity and storage. Only after those checks does it atomically
create the initial state with an empty rollback list.

Candidate containers have restart disabled until the state is committed. On an
error or handled interruption, the public and application services are stopped
and the database, backup and logs are retained. A separate guard also attempts
to stop unaccepted services if the operator process disappears. A host crash
or loss of both processes can leave containers and volumes behind; inspect
them before any manual recovery. The first-install command deliberately
refuses to reuse them. There is no previous application version to roll back to.

After the first administrator login is verified, remove the bootstrap
password from the protected environment file, recreate the API through
`myshop-compose.sh`, and verify its health and administrator login again.
Keep the administrator credential in the protected credential store. Enable
the new storage timer only after all helpers have been updated together and
the first accepted state exists. If restart-policy configuration fails after
the state commit, inspect the accepted containers and repair their policies
before declaring host-reboot recovery verified.

## Release manifest and transaction

For the November 17, 2026 greenfield launch only, the owner approved skipping
GitHub Actions. The operator must run local validation, build both images from
the exact release commit, push full-SHA tags, record the registry-reported
digests in a validated release manifest, and complete production acceptance.
This exception does not represent a successful CI run and does not remove the
backup, image identity, health, schema, HTTPS, or storage gates. Building on
the host is authorized for this one-time initial release before the store is
running; future releases follow the normal immutable-image workflow.

The manually dispatched CI workflow publishes each changed app image under a
full source-SHA tag, records its immutable digest, and uploads a checksummed
full-release manifest. The manifest has one entry per service; an unchanged
service may keep its previously validated digest and source SHA. CI release SHA
and production accepted SHA are recorded separately. The operator supplies the
successful run's manifest:

```bash
bash /opt/myshop/scripts/deploy-production.sh /path/to/release-manifest.json
```

The deployer holds `/run/myshop-deploy.lock` for the entire transaction
and performs the following sequence:

1. Verify the `/data` mount and disk watermark.
2. Read the previous accepted state, or capture the currently running image
   refs/digests for the first bootstrap.
3. Compare candidate digests with accepted digests. Pull only changed app images
   by digest and require local RepoDigest equality. A Web-only update does not
   pull or restart API; an API-only update does not pull or restart Web.
4. When API changes, create a fresh PostgreSQL custom backup in the transaction
   and verify it with `pg_restore -l`; emit a mode-600 backup manifest containing
   source active SHA, candidate SHA, database identity, file size, SHA-256, and
   validation result. Web-only releases do not back up or touch the database.
5. Start only the changed app services and wait for health. API updates run the
   artifact-packaged Flyway/schema contract; all releases run public smoke and
   candidate storage gates. A migration still stops API writers before startup.
6. Promote the manifest into state atomically, run MyShop-scoped retention,
   and run the post-retention storage gate.
7. Verify running image refs and digests and print `DEPLOYMENT ACCEPTED`.

Any failure before acceptance writes a small `FAILED` candidate marker and
restores only services that were changed or stopped. The state file is
unchanged. If restoration
fails, the command returns `CRITICAL_ROLLBACK_FAILED` and does not run
retention. The regression suite injects pull, health, smoke, schema, and
post-storage failures and checks this contract.

## Deployment state

`/opt/myshop/runtime/deploy-state.json` is the sole accepted-history source:

```json
{
  "version": 1,
  "active": {
    "source_sha": "<full sha>",
    "accepted_at": "<UTC>",
    "images": {
      "api": {
        "ref": "<approved>/myshop-api@sha256:<64 hex>",
        "digest": "sha256:<64 hex>",
        "source_sha": "<image source sha>",
        "runtime_ref": "<same digest or verified offline tag>",
        "tag_ref": "<approved>/myshop-api:<image source sha>"
      },
      "web": {
        "ref": "<approved>/myshop-web@sha256:<64 hex>",
        "digest": "sha256:<64 hex>",
        "source_sha": "<image source sha>",
        "runtime_ref": "<same digest or verified offline tag>",
        "tag_ref": "<approved>/myshop-web:<image source sha>"
      }
    }
  },
  "rollback": [],
  "history": []
}
```

`rollback` is allowed to contain zero, one, or two records. The first accepted
deployment therefore has active plus zero rollback; the second derives one
rollback from the old active; the third derives two. Later promotions keep the
two most recent accepted records. `history` contains only completed
acceptances. Failed candidates are never added. The state file is written with
a temporary file, fsync, atomic rename, and directory fsync on Linux.
Production wrappers read active image references directly from this file; no
second accepted-image state file is maintained. Malformed state fails closed.

Retention verifies source SHA, image ref, and image digest together. It protects
all accepted records, all running container image IDs, and never infers history
from mtime. It processes only `myshop-api` and `myshop-web`, never volumes,
uploads, backups, unrelated images, source release directories, or BuildKit cache.

## Schema and backup acceptance

The API image packages `db/production-schema-contract.json`. The deployment
extracts that contract from the exact running artifact and checks Flyway has no
failed migration, the expected migration count/latest version, every declared
table and column, and the retained historical indexes/constraints. `SELECT 1` alone
is not an acceptance check. A schema failure rolls the candidate back.

The backup is created by the deployment itself while the lock is held. An old
operator-supplied `MYSHOP_VERIFIED_BACKUP_PATH` is not accepted as proof for a
new deployment. Database rollback is never performed by deleting rows or
editing Flyway history; a required schema rollback ships a reviewed forward
migration after a verified backup.

Uploads and private uploads are not copied on every application deploy. Their
backup workflow is independent and must keep a verified recovery point with an
explicit local retention policy. This lifecycle does not delete the unique
database or upload recovery point.

## Storage lifecycle and timers

`/opt/host-ops/storage-lifecycle.sh` is the only scheduled orchestrator. It
obtains the same host lock and runs, in order:

1. storage precheck;
2. MyShop scoped retention;
3. host BuildKit/dangling-image maintenance;
4. storage postcheck.

The service is non-zero for a lock collision or any failed step. A collision is
not reported as successful maintenance. Enable and verify:

```bash
systemctl enable --now storage-lifecycle.timer
systemctl is-enabled storage-lifecycle.timer
systemctl is-active storage-lifecycle.timer
systemctl status storage-lifecycle.service
journalctl -u storage-lifecycle.service --since today
```

Watermarks are based on the real `/data` filesystem: below 60% `OK`, 60--69%
`WARNING`, 70--79% `MAINTENANCE_REQUIRED`, 80--89% `HARD_FAIL`, and 90%+
`CRITICAL`. Deployment precheck refuses 80% or more. A post-retention result
of 70% or more is not accepted. Host GC never uses volume prune and never
touches `/data/docker` or `/data/containerd` directly.

The storage lifecycle service has a different responsibility from deployment:
after the mount, Docker, and accepted-state safety prerequisites pass, it still
runs MyShop retention and host safe maintenance at every watermark,
including 80% and 90%+. Its postcheck returns `SUCCESS` below 70%,
`MAINTENANCE_INCOMPLETE` at 70--79%, `HARD_FAIL` at 80--89%, and `CRITICAL`
at 90%+.

## Acceptance contract

Every production deployment must record all of the following before it is
accepted:

```text
SOURCE_SHA verified
IMAGE_DIGEST verified
compose project verified
/data mount verified
Docker/containerd roots verified
transaction-created verified database backup
health passed
smoke passed
schema contract passed
state atomically committed
application retention passed
host storage lifecycle passed
disk <70%
active plus valid rollback set
```

Missing any item means `DEPLOYMENT NOT ACCEPTED`. A healthy container by itself
is not a successful deployment.

## Retiring the former marketplace integration

The application no longer includes marketplace UI, controllers, workers, clients,
shared networks, or the former channel-only runtime configuration control plane.
The 42 Flyway migrations and schema contract are retained unchanged, as are the
historic order/pricing enum values needed to read existing orders. Do not drop
historical tables, remove Flyway rows, or delete database/upload/backup volumes.

Before installing these host helpers, stop the old storage lifecycle and Docker
maintenance timers and wait for any old deployment/maintenance process to finish.
Update the helpers together, then restart the timers: all maintained helpers now
use `/run/myshop-deploy.lock`. This prevents two generations of scripts from
running maintenance under different locks during the cutover.

On the host, inventory the separately deployed retired Agent services, timers,
Compose project, bind mounts, volumes, and running images before stopping them.
Disable only the verified Agent execution/auto-start entries. Keep its data and
backups separate from runnable artifacts. Never use a broad `docker system prune`,
`docker volume prune`, or recursive deletion of shared paths as retirement.
Remove the former Agent network only after verifying it has no connected
containers. The independent Git repository and database archives are not deleted
by this application release.

`PUBLIC_SITE_URL` is injected into both API and Web at runtime and must be the
canonical store origin. Robots, sitemap, metadata and payment URLs use it; a
production image does not need a domain-specific rebuild. Optional Next.js
telemetry is disabled in both image stages and CI.

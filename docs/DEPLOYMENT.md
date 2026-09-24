# MyShop production storage-lifecycle runbook

This runbook describes the only supported production model. The application
artifact is an immutable container image reference plus its builder-generated
release manifest. Production never builds application images and runs only
from the immutable image manifest recorded under `/opt/myshop/runtime`.

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
bash /opt/myshop/scripts/myshop-compose.sh --env-file /opt/myshop/.env config
bash /opt/myshop/scripts/myshop-compose.sh --env-file /opt/myshop/.env ps
```

Do not run `docker compose` directly in the production directory and do not
run `up --build`.

## Release manifest and transaction

CI publishes `myshop-api:<full-source-sha>` and
`myshop-web:<full-source-sha>` to the approved registry and uploads a JSON
manifest containing `source_sha`, exact refs, and builder-reported digests.
The operator supplies only that manifest:

```bash
bash /opt/myshop/scripts/deploy-production.sh /path/to/release-manifest.json
```

The deployer holds `/run/myshop-xianyu-deploy.lock` for the entire transaction
and performs the following sequence:

1. Verify the `/data` mount and disk watermark.
2. Read the previous accepted state, or capture the currently running image
   refs/digests for the first bootstrap.
3. Pull exact candidate refs and require local RepoDigest equality with the
   manifest digest. A failed registry pull stops before a backup is created.
4. Create a fresh PostgreSQL custom backup in the transaction and verify it
   with `pg_restore -l`; emit a mode-600 backup manifest containing source
   active SHA, candidate SHA, database identity, file size, SHA-256, and
   validation result.
5. Start the candidate, wait for health, run public smoke checks, run the
   artifact-packaged Flyway/schema contract, and run the candidate storage
   gate.
6. Promote the manifest into state atomically, run MyShop-scoped retention,
   and run the post-retention storage gate.
7. Verify running image refs and digests and print `DEPLOYMENT ACCEPTED`.

Any failure before acceptance writes a small `FAILED` candidate marker, restores
the previous accepted image refs, runs `compose up -d` for the old stack, and
verifies old health and smoke. The state file is unchanged. If restoration
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
      "api": {"ref": "<approved>/myshop-api:<sha>", "digest": "sha256:<64 hex>"},
      "web": {"ref": "<approved>/myshop-web:<sha>", "digest": "sha256:<64 hex>"}
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
uploads, backups, Agent images, source release directories, or BuildKit cache.

## Schema and backup acceptance

The API image packages `db/production-schema-contract.json`. The deployment
extracts that contract from the exact running artifact and checks Flyway has no
failed migration, the expected migration count/latest version, every declared
table and column, and the sales-channel indexes/constraints. `SELECT 1` alone
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
3. Agent scoped retention;
4. host BuildKit/dangling-image maintenance;
5. storage postcheck.

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
runs both scoped retainers and host safe maintenance at every watermark,
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

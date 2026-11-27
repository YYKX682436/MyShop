#!/usr/bin/env python3
"""Validate a complete release manifest with per-service image provenance."""
from __future__ import annotations

import json
import re
import sys
from datetime import datetime
from pathlib import Path

SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
SERVICES = ("api", "web")
APPROVED_REGISTRY = "ghcr.io/yykx682436"


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"[manifest] ERROR: {message}")


if len(sys.argv) != 4:
    raise SystemExit("usage: validate-release-manifest.py <manifest> <approved-registry> <project>")
path = Path(sys.argv[1])
registry = sys.argv[2]
project = sys.argv[3]
if registry != APPROVED_REGISTRY:
    fail(f"approved registry must be exactly {APPROVED_REGISTRY}")
try:
    manifest = json.loads(path.read_text(encoding="utf-8"))
except Exception as exc:
    fail(f"manifest JSON is invalid: {exc}")
if not isinstance(manifest, dict) or manifest.get("project") != project or project != "myshop":
    fail("manifest project is unsupported")
version = manifest.get("version")
if type(version) is not int or version not in (1, 2, 3):
    fail("manifest version is unsupported")
source_sha = manifest.get("source_sha")
if not isinstance(source_sha, str) or not SHA.fullmatch(source_sha):
    fail("source_sha must be a full lowercase SHA")
created_at = manifest.get("created_at")
if not isinstance(created_at, str) or not created_at:
    fail("created_at is required")
try:
    created_time = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
except ValueError:
    fail("created_at must be an ISO-8601 timestamp")
if created_time.tzinfo is None:
    fail("created_at must include a timezone")

images = manifest.get("images")
if not isinstance(images, dict) or set(images) != set(SERVICES):
    fail(f"images must contain exactly {SERVICES}")
if version == 1:
    if set(manifest) != {"version", "project", "source_sha", "created_at", "images"}:
        fail("legacy manifest fields are unsupported")
    built_services = list(SERVICES)
    # A v1 manifest has no change-scope metadata. Treat it as migration-capable
    # so production keeps the strict API stop/backup/schema transaction.
    database_migration = True
else:
    expected_fields = {"version", "project", "source_sha", "created_at", "built_services", "database_migration", "images"}
    if version == 3:
        expected_fields.add("accepted_sha")
        if not isinstance(manifest.get("accepted_sha"), str) or not SHA.fullmatch(manifest["accepted_sha"]):
            fail("accepted_sha must be a full lowercase SHA")
    if set(manifest) != expected_fields:
        fail("manifest fields are unsupported")
    built_services = manifest.get("built_services")
    if (not isinstance(built_services, list)
            or any(not isinstance(item, str) or item not in SERVICES for item in built_services)
            or len(set(built_services)) != len(built_services)
            or built_services != sorted(built_services)):
        fail("built_services must be a sorted unique subset of api/web")
    database_migration = manifest.get("database_migration")
    if type(database_migration) is not bool:
        fail("database_migration must be a boolean")

normalized = {
    "version": 3 if version == 3 else 2,
    "project": project,
    "source_sha": source_sha,
    "created_at": created_at,
    "built_services": built_services,
    "database_migration": database_migration,
    "images": {},
}
if version == 3:
    normalized["accepted_sha"] = manifest["accepted_sha"]
for service in SERVICES:
    item = images.get(service)
    if not isinstance(item, dict):
        fail(f"images.{service} must be an object")
    if version == 1:
        if set(item) != {"ref", "digest"}:
            fail(f"legacy images.{service} fields do not match the manifest schema")
        image_source_sha = source_sha
    else:
        if set(item) != {"ref", "digest", "source_sha"}:
            fail(f"images.{service} fields do not match the manifest schema")
        image_source_sha = item.get("source_sha")
        if not isinstance(image_source_sha, str) or not SHA.fullmatch(image_source_sha):
            fail(f"images.{service}.source_sha must be a full lowercase SHA")
    digest = item.get("digest")
    if not isinstance(digest, str) or not DIGEST.fullmatch(digest):
        fail(f"images.{service}.digest is invalid")
    ref = item.get("ref")
    if version == 1:
        legacy_ref = f"{registry}/myshop-{service}:{source_sha}"
        if ref != legacy_ref:
            fail(f"legacy images.{service}.ref must equal the full-SHA ref {legacy_ref}")
        expected_ref = f"{registry}/myshop-{service}@{digest}"
    else:
        expected_ref = f"{registry}/myshop-{service}@{digest}"
        if ref != expected_ref:
            fail(f"images.{service}.ref must be the exact digest-pinned ref {expected_ref}")
    normalized["images"][service] = {
        "ref": expected_ref,
        "digest": digest,
        "source_sha": image_source_sha,
    }
print(json.dumps(normalized, sort_keys=True))

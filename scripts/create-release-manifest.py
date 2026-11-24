#!/usr/bin/env python3
"""Combine newly built service digests with the last validated CI manifest."""
from __future__ import annotations

import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

REGISTRY = "ghcr.io/yykx682436"
SERVICES = ("api", "web")
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"[release-manifest] ERROR: {message}")


def env_bool(name: str) -> bool:
    value = os.environ.get(name, "")
    if value not in {"true", "false"}:
        fail(f"{name} must be true or false")
    return value == "true"


source_sha = os.environ.get("SOURCE_SHA", "")
baseline_sha = os.environ.get("BASELINE_SHA", "")
output = Path(os.environ.get("OUTPUT_FILE", ""))
if not SHA.fullmatch(source_sha):
    fail("SOURCE_SHA must be a full lowercase SHA")
if baseline_sha and not SHA.fullmatch(baseline_sha):
    fail("BASELINE_SHA must be empty or a full lowercase SHA")
if not str(output):
    fail("OUTPUT_FILE is required")
api_build, web_build = env_bool("API_BUILD"), env_bool("WEB_BUILD")
database_migration = env_bool("DATABASE_MIGRATION")
built = {service: os.environ.get(f"{service.upper()}_DIGEST", "") for service in SERVICES}
images: dict[str, dict[str, str]] = {}
baseline_path = Path(os.environ.get("BASELINE_FILE", str(Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "base-release-manifest.json")))

if baseline_sha:
    if not baseline_path.is_file() or baseline_path.is_symlink():
        fail("validated baseline manifest is missing or unsafe")
    try:
        baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"baseline manifest is invalid JSON: {exc}")
    if baseline.get("source_sha") != baseline_sha or baseline.get("version") != 2:
        fail("baseline manifest does not match the selected successful CI release")
    if not isinstance(baseline.get("images"), dict) or set(baseline["images"]) != set(SERVICES):
        fail("baseline manifest must describe both API and Web images")
    for service in SERVICES:
        item = baseline["images"][service]
        digest = item.get("digest") if isinstance(item, dict) else None
        image_sha = item.get("source_sha") if isinstance(item, dict) else None
        expected_ref = f"{REGISTRY}/myshop-{service}@{digest}"
        if (not isinstance(digest, str) or not DIGEST.fullmatch(digest)
                or item.get("ref") != expected_ref or not isinstance(image_sha, str) or not SHA.fullmatch(image_sha)):
            fail(f"baseline {service} image identity is invalid")
        images[service] = {"ref": expected_ref, "digest": digest, "source_sha": image_sha}
else:
    if not api_build or not web_build:
        fail("without a successful baseline both service images must be built")

for service, should_build in (("api", api_build), ("web", web_build)):
    if not should_build:
        continue
    digest = built[service]
    if not DIGEST.fullmatch(digest):
        fail(f"{service} build did not return an immutable image digest")
    images[service] = {
        "ref": f"{REGISTRY}/myshop-{service}@{digest}",
        "digest": digest,
        "source_sha": source_sha,
    }

if set(images) != set(SERVICES):
    fail("the release manifest must describe API and Web")
payload = {
    "version": 2,
    "project": "myshop",
    "source_sha": source_sha,
    "created_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "built_services": sorted(service for service, value in (("api", api_build), ("web", web_build)) if value),
    "database_migration": database_migration,
    "images": images,
}
output.parent.mkdir(parents=True, exist_ok=True)
output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"RELEASE_MANIFEST_CREATED source_sha={source_sha} built={','.join(payload['built_services']) or 'none'}")

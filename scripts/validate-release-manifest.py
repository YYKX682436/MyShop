#!/usr/bin/env python3
"""Validate the MyShop source-SHA manifest carried by GHCR."""
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
if (not isinstance(manifest, dict) or set(manifest) != {"version", "project", "source_sha", "created_at", "images"}
        or type(manifest.get("version")) is not int or manifest.get("version") != 1 or manifest.get("project") != project
        or project != "myshop"):
    fail("manifest version/project/fields are unsupported")
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
normalized = {"version": 1, "project": project, "source_sha": source_sha, "created_at": created_at, "images": {}}
for service in SERVICES:
    item = images.get(service)
    if not isinstance(item, dict) or set(item) != {"ref", "digest"}:
        fail(f"images.{service} fields do not match the manifest schema")
    ref = item.get("ref")
    expected_ref = f"{registry}/myshop-{service}:{source_sha}"
    if ref != expected_ref:
        fail(f"images.{service}.ref must be the exact full-SHA ref {expected_ref}")
    digest = item.get("digest")
    if not isinstance(digest, str) or not DIGEST.fullmatch(digest):
        fail(f"images.{service}.digest is invalid")
    normalized["images"][service] = {"ref": expected_ref, "digest": digest}
print(json.dumps(normalized, sort_keys=True))

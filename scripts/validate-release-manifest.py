#!/usr/bin/env python3
"""Validate a builder-generated MyShop release manifest without network calls."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
SERVICES = ("api", "web")


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"[manifest] ERROR: {message}")


if len(sys.argv) != 4:
    raise SystemExit("usage: validate-release-manifest.py <manifest> <approved-registry> <project>")
path = Path(sys.argv[1])
registry = sys.argv[2]
project = sys.argv[3]
if not registry or any(character.isspace() for character in registry) or registry.endswith("/"):
    fail("approved registry is empty, contains whitespace, or ends with /")
try:
    manifest = json.loads(path.read_text(encoding="utf-8"))
except Exception as exc:
    fail(f"manifest JSON is invalid: {exc}")
if not isinstance(manifest, dict) or manifest.get("version") != 1 or manifest.get("project") != project:
    fail("manifest version/project is unsupported")
source_sha = manifest.get("source_sha")
if not isinstance(source_sha, str) or not SHA.fullmatch(source_sha):
    fail("source_sha must be a full lowercase SHA")
images = manifest.get("images")
if not isinstance(images, dict) or set(images) != set(SERVICES):
    fail(f"images must contain exactly {SERVICES}")
normalized = {"version": 1, "project": project, "source_sha": source_sha, "created_at": manifest.get("created_at"), "images": {}}
for service in SERVICES:
    item = images.get(service)
    if not isinstance(item, dict):
        fail(f"images.{service} must be an object")
    ref = item.get("ref")
    digest = item.get("digest")
    expected_prefix = f"{registry}/myshop-{service}:"
    if not isinstance(ref, str) or not ref.startswith(expected_prefix):
        fail(f"images.{service}.ref is outside the approved namespace")
    tag = ref[len(expected_prefix):]
    if tag != source_sha or not SHA.fullmatch(tag):
        fail(f"images.{service}.ref tag must equal source_sha")
    if not isinstance(digest, str) or not DIGEST.fullmatch(digest):
        fail(f"images.{service}.digest is invalid")
    normalized["images"][service] = {"ref": ref, "digest": digest}
print(json.dumps(normalized, sort_keys=True))

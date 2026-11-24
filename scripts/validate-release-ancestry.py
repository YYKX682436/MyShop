#!/usr/bin/env python3
"""Require every reused or newly built image to come from this source history."""
from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

SHA = re.compile(r"^[0-9a-f]{40}$")

if len(sys.argv) != 3:
    raise SystemExit("usage: validate-release-ancestry.py <release-manifest> <expected-source-sha>")
manifest_path = Path(sys.argv[1])
expected_sha = sys.argv[2]
if not SHA.fullmatch(expected_sha):
    raise SystemExit("expected source SHA must be a full lowercase SHA")
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("source_sha") != expected_sha or not isinstance(manifest.get("images"), dict):
    raise SystemExit("manifest source SHA or images are invalid")
for service in ("api", "web"):
    image = manifest["images"].get(service)
    image_sha = image.get("source_sha") if isinstance(image, dict) else None
    if not isinstance(image_sha, str) or not SHA.fullmatch(image_sha):
        raise SystemExit(f"manifest images.{service}.source_sha is invalid")
    result = subprocess.run(["git", "merge-base", "--is-ancestor", image_sha, expected_sha], check=False)
    if result.returncode:
        raise SystemExit(f"{service} image source {image_sha} is not an ancestor of release {expected_sha}")
    if service in manifest.get("built_services", []) and image_sha != expected_sha:
        raise SystemExit(f"newly built {service} image must be from the current release SHA")
print(f"RELEASE_IMAGE_ANCESTRY_VERIFIED source_sha={expected_sha}")

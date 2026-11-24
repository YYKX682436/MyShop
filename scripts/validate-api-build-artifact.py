#!/usr/bin/env python3
"""Verify the exact API JAR artifact produced by a successful Maven verify."""
from __future__ import annotations

import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path

SHA = re.compile(r"^[0-9a-f]{40}$")
HEX = re.compile(r"^[0-9a-f]{64}$")
SCHEMA_RESOURCE = "BOOT-INF/classes/db/production-schema-contract.json"


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"[api-build-artifact] ERROR: {message}")


def digest(path: Path) -> str:
    value = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


if len(sys.argv) != 3:
    raise SystemExit("usage: validate-api-build-artifact.py <artifact-directory> <expected-source-sha>")
root = Path(sys.argv[1]).resolve(strict=True)
expected_sha = sys.argv[2]
if not SHA.fullmatch(expected_sha):
    fail("expected source SHA must be a full lowercase SHA")
for name in ("app.jar", "production-schema-contract.json", "api-build-manifest.json"):
    path = root / name
    if path.is_symlink() or not path.is_file() or path.resolve(strict=True).parent != root:
        fail(f"{name} is missing, symlinked, or outside the artifact directory")
try:
    manifest = json.loads((root / "api-build-manifest.json").read_text(encoding="utf-8"))
except Exception as exc:
    fail(f"API artifact manifest is invalid JSON: {exc}")
if (not isinstance(manifest, dict)
        or set(manifest) != {"version", "source_sha", "jar_sha256", "schema_contract_sha256"}
        or type(manifest.get("version")) is not int or manifest["version"] != 1):
    fail("API artifact manifest version/fields are unsupported")
if manifest["source_sha"] != expected_sha or not SHA.fullmatch(str(manifest["source_sha"])):
    fail("API JAR artifact source SHA does not match the requested release")
for field in ("jar_sha256", "schema_contract_sha256"):
    if not isinstance(manifest[field], str) or not HEX.fullmatch(manifest[field]):
        fail(f"{field} is invalid")
if digest(root / "app.jar") != manifest["jar_sha256"]:
    fail("API JAR SHA-256 does not match its build manifest")
if digest(root / "production-schema-contract.json") != manifest["schema_contract_sha256"]:
    fail("schema contract SHA-256 does not match its build manifest")
try:
    with zipfile.ZipFile(root / "app.jar") as archive:
        members = [item for item in archive.infolist() if item.filename == SCHEMA_RESOURCE]
        if len(members) != 1:
            fail("API JAR must contain exactly one production schema contract resource")
        embedded = archive.read(SCHEMA_RESOURCE)
except zipfile.BadZipFile as exc:
    fail(f"API JAR is not a valid ZIP/JAR: {exc}")
if embedded != (root / "production-schema-contract.json").read_bytes():
    fail("staged schema contract does not match the resource inside the API JAR")
try:
    contract = json.loads(embedded)
except Exception as exc:
    fail(f"embedded schema contract is invalid JSON: {exc}")
if not isinstance(contract, dict) or contract.get("version") != 1:
    fail("embedded production schema contract version is unsupported")
print(json.dumps({
    "source_sha": expected_sha,
    "jar_sha256": manifest["jar_sha256"],
    "schema_contract_sha256": manifest["schema_contract_sha256"],
}, sort_keys=True))

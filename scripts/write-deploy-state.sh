#!/usr/bin/env bash
set -Eeuo pipefail

# Promote one fully accepted builder manifest. Rollbacks are derived only from
# the previous state; callers cannot provide or invent rollback SHAs.
(( $# == 2 )) || {
  printf 'usage: %s <state-file> <release-manifest.json>\n' "$0" >&2
  exit 2
}

exec python3 - "$1" "$2" <<'PY'
from __future__ import annotations

import json
import os
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

state_path = Path(sys.argv[1])
manifest_path = Path(sys.argv[2])
sha_re = re.compile(r"^[0-9a-f]{40}$")
digest_re = re.compile(r"^sha256:[0-9a-f]{64}$")
service_re = re.compile(r"^[a-z0-9][a-z0-9._-]*$")


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"[myshop-state] ERROR: {message}")


def read_json(path: Path, label: str) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"{label} is not valid JSON: {exc}")
    if not isinstance(value, dict):
        fail(f"{label} must be a JSON object")
    return value


def validate_record(record: object, label: str, services: set[str] | None = None) -> dict:
    if not isinstance(record, dict):
        fail(f"{label} must be an object")
    sha = record.get("source_sha")
    accepted_at = record.get("accepted_at")
    images = record.get("images")
    if not isinstance(sha, str) or not sha_re.fullmatch(sha):
        fail(f"{label}.source_sha must be a full lowercase SHA")
    if not isinstance(accepted_at, str) or not accepted_at:
        fail(f"{label}.accepted_at is required")
    if not isinstance(images, dict) or not images:
        fail(f"{label}.images must be a non-empty object")
    names = set(images)
    if services is not None and names != services:
        fail(f"{label}.images services differ from the existing state")
    for service, image in images.items():
        if not isinstance(service, str) or not service_re.fullmatch(service):
            fail(f"{label}.images contains an invalid service name")
        if not isinstance(image, dict):
            fail(f"{label}.images.{service} must be an object")
        ref = image.get("ref")
        digest = image.get("digest")
        if not isinstance(ref, str) or not ref or ":latest" in ref or ":local" in ref:
            fail(f"{label}.images.{service}.ref is mutable")
        if not isinstance(digest, str) or not digest_re.fullmatch(digest):
            fail(f"{label}.images.{service}.digest is invalid")
    return {
        "source_sha": sha,
        "accepted_at": accepted_at,
        "images": images,
    }


manifest = read_json(manifest_path, "release manifest")
if manifest.get("version") != 1 or manifest.get("project") != "myshop":
    fail("manifest version/project is not supported")
candidate = {
    "source_sha": manifest.get("source_sha"),
    "accepted_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "images": manifest.get("images"),
}
candidate = validate_record(candidate, "candidate")

old_state = None
if state_path.exists():
    old_state = read_json(state_path, "deployment state")
    if old_state.get("version") != 1:
        fail("deployment state version is unsupported")
    old_active = validate_record(old_state.get("active"), "state.active")
    old_rollback_raw = old_state.get("rollback")
    old_history_raw = old_state.get("history")
    if not isinstance(old_rollback_raw, list) or len(old_rollback_raw) > 2:
        fail("state.rollback must contain 0..2 records")
    if not isinstance(old_history_raw, list):
        fail("state.history must be an array")
    services = set(old_active["images"])
    old_rollback = [validate_record(item, f"state.rollback[{i}]", services) for i, item in enumerate(old_rollback_raw)]
    old_history = [validate_record(item, f"state.history[{i}]", services) for i, item in enumerate(old_history_raw)]
    if set(candidate["images"]) != services:
        fail("candidate service set differs from accepted state")
    if candidate["source_sha"] in {old_active["source_sha"], *(item["source_sha"] for item in old_rollback)}:
        fail("candidate SHA is already accepted")
else:
    old_active = None
    old_rollback = []
    old_history = []
    services = set(candidate["images"])

if old_active is not None:
    # The previous active becomes rollback #1; previous rollback #1 becomes
    # rollback #2. No caller-controlled value enters this list.
    new_rollback = [old_active, *old_rollback[:1]]
else:
    new_rollback = []

state = {
    "version": 1,
    "active": candidate,
    "rollback": new_rollback,
    "history": [*old_history, candidate],
}

state_path.parent.mkdir(parents=True, exist_ok=True)
fd, temporary = tempfile.mkstemp(prefix=".deploy-state.", dir=state_path.parent)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(state, handle, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(temporary, 0o640)
    os.replace(temporary, state_path)
    try:
        directory_fd = os.open(state_path.parent, os.O_RDONLY)
    except OSError:
        if os.name != "nt":
            raise
    else:
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)

# Compose restart identity is a small runtime artifact, not a second source
# release. It is derived from the same accepted record and written atomically.
image_env_path = state_path.parent / "accepted-images.env"
fd, image_env_tmp = tempfile.mkstemp(prefix=".accepted-images.", dir=state_path.parent)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        for service, image in sorted(candidate["images"].items()):
            handle.write(f"MYSHOP_{service.upper()}_IMAGE={image['ref']}\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(image_env_tmp, 0o640)
    os.replace(image_env_tmp, image_env_path)
    try:
        directory_fd = os.open(state_path.parent, os.O_RDONLY)
    except OSError:
        if os.name != "nt":
            raise
    else:
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
finally:
    if os.path.exists(image_env_tmp):
        os.unlink(image_env_tmp)

print(json.dumps({
    "state": str(state_path),
    "active": candidate["source_sha"],
    "rollback": [item["source_sha"] for item in new_rollback],
}, sort_keys=True))
PY

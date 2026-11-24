#!/usr/bin/env bash
set -Eeuo pipefail

(( $# == 2 )) || {
  printf 'usage: %s <state-file> <release-manifest.json>\n' "$0" >&2
  exit 2
}

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
exec python3 - "$script_dir" "$1" "$2" <<'PY'
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

script_dir = Path(sys.argv[1])
state_path = Path(sys.argv[2])
manifest_path = Path(sys.argv[3])
sha_re = re.compile(r"^[0-9a-f]{40}$")
digest_re = re.compile(r"^sha256:[0-9a-f]{64}$")
registry = "ghcr.io/yykx682436"
services = {"api", "web"}
bundle_services = {"api", "web", "postgres", "caddy", "node-exporter"}


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"[myshop-state] ERROR: {message}")


def read_json(path: Path, label: str) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"{label} is not valid JSON: {exc}")
    if not isinstance(value, dict):
        fail(f"{label} must be an object")
    return value


def valid_ref(value: object, service: str, digest: str, image_sha: str, label: str) -> str:
    if not isinstance(value, str) or ":latest" in value or ":local" in value:
        fail(f"{label} is mutable or invalid")
    repo = f"{registry}/myshop-{service}"
    if value not in {f"{repo}@{digest}", f"{repo}:{image_sha}"}:
        fail(f"{label} is not the exact digest ref or immutable source-SHA tag")
    return value


def validate_record(record: object, label: str, required_services: set[str] | None = None) -> dict:
    if not isinstance(record, dict):
        fail(f"{label} must be an object")
    sha = record.get("source_sha")
    accepted_at = record.get("accepted_at")
    images = record.get("images")
    if not isinstance(sha, str) or not sha_re.fullmatch(sha):
        fail(f"{label}.source_sha must be a full lowercase SHA")
    if not isinstance(accepted_at, str) or not accepted_at:
        fail(f"{label}.accepted_at is required")
    if not isinstance(images, dict) or set(images) != services:
        fail(f"{label}.images must contain exactly {sorted(services)}")
    normalized = {"source_sha": sha, "accepted_at": accepted_at, "images": {}}
    for service, item in images.items():
        if not isinstance(item, dict):
            fail(f"{label}.images.{service} must be an object")
        digest = item.get("digest")
        if not isinstance(digest, str) or not digest_re.fullmatch(digest):
            fail(f"{label}.images.{service}.digest is invalid")
        image_sha = item.get("source_sha", sha)
        if not isinstance(image_sha, str) or not sha_re.fullmatch(image_sha):
            fail(f"{label}.images.{service}.source_sha is invalid")
        ref = valid_ref(item.get("ref"), service, digest, image_sha, f"{label}.images.{service}.ref")
        runtime_ref = item.get("runtime_ref", ref)
        runtime_ref = valid_ref(runtime_ref, service, digest, image_sha, f"{label}.images.{service}.runtime_ref")
        tag_ref = item.get("tag_ref", f"{registry}/myshop-{service}:{image_sha}")
        if tag_ref != f"{registry}/myshop-{service}:{image_sha}":
            fail(f"{label}.images.{service}.tag_ref is not the exact immutable source-SHA tag")
        image_id = item.get("image_id")
        if image_id is not None and (not isinstance(image_id, str) or not digest_re.fullmatch(image_id)):
            fail(f"{label}.images.{service}.image_id is invalid")
        normalized["images"][service] = {
            "ref": ref,
            "digest": digest,
            "source_sha": image_sha,
            "runtime_ref": runtime_ref,
            "tag_ref": tag_ref,
            **({"image_id": image_id} if image_id else {}),
        }
    return normalized


validation = subprocess.run(
    [sys.executable, str(script_dir / "validate-release-manifest.py"), str(manifest_path), registry, "myshop"],
    check=False, text=True, capture_output=True,
)
if validation.returncode:
    fail(f"release manifest is invalid: {validation.stderr.strip()}")
manifest = json.loads(validation.stdout)
if manifest.get("version") != 2 or manifest.get("project") != "myshop":
    fail("manifest version/project is not supported")
candidate_sha = manifest.get("source_sha")
if not isinstance(candidate_sha, str) or not sha_re.fullmatch(candidate_sha):
    fail("manifest source_sha must be a full lowercase SHA")
images = manifest.get("images")
if not isinstance(images, dict) or set(images) != services:
    fail("release manifest must contain API and Web images")

offline_mode = os.environ.get("MYSHOP_OFFLINE_IMAGES", "0")
if offline_mode not in {"0", "1"}:
    fail("MYSHOP_OFFLINE_IMAGES must be 0 or 1")
runtime_refs = {
    "api": os.environ.get("MYSHOP_API_RUNTIME_REF", ""),
    "web": os.environ.get("MYSHOP_WEB_RUNTIME_REF", ""),
}
image_ids: dict[str, str] = {}
if offline_mode == "1":
    bundle_path = os.environ.get("MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE", "")
    if not bundle_path:
        fail("offline image mode requires MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE")
    try:
        bundle = json.loads(Path(bundle_path).read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"offline image bundle is invalid: {exc}")
    if bundle.get("version") != 4 or bundle.get("source_sha") != candidate_sha:
        fail("offline image bundle does not match candidate source SHA")
    bundle_images = bundle.get("images")
    if not isinstance(bundle_images, dict) or set(bundle_images) != bundle_services:
        fail("offline image bundle must contain the two app and three pinned infrastructure records")
    for service in services:
        item = bundle_images[service]
        target = images[service]
        if (item.get("source_ref") != target["ref"] or item.get("source_digest") != target["digest"]
                or item.get("source_sha") != target["source_sha"]):
            fail(f"offline image bundle {service} identity differs from release manifest")
        runtime_refs[service] = item.get("archive_ref", "")
        image_ids[service] = item.get("loaded_image_id", "")

candidate = {
    "source_sha": candidate_sha,
    "accepted_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "images": {},
}
for service in sorted(services):
    target = images[service]
    ref = valid_ref(target.get("ref"), service, target.get("digest"), target.get("source_sha"), f"candidate.{service}.ref")
    runtime_ref = runtime_refs[service] or ref
    runtime_ref = valid_ref(runtime_ref, service, target["digest"], target["source_sha"], f"candidate.{service}.runtime_ref")
    image = {
        "ref": ref,
        "digest": target["digest"],
        "source_sha": target["source_sha"],
        "runtime_ref": runtime_ref,
        "tag_ref": f"{registry}/myshop-{service}:{target['source_sha']}",
    }
    if offline_mode == "1":
        if not digest_re.fullmatch(image_ids[service]):
            fail(f"offline image bundle {service} loaded image ID is invalid")
        image["image_id"] = image_ids[service]
    elif os.environ.get(f"MYSHOP_{service.upper()}_IMAGE_ID"):
        image_id = os.environ[f"MYSHOP_{service.upper()}_IMAGE_ID"]
        if not digest_re.fullmatch(image_id):
            fail(f"candidate {service} local image ID is invalid")
        image["image_id"] = image_id
    candidate["images"][service] = image
candidate = validate_record(candidate, "candidate")

old_active = None
old_rollback: list[dict] = []
old_history: list[dict] = []
if state_path.exists():
    old_state = read_json(state_path, "deployment state")
    if old_state.get("version") != 1:
        fail("deployment state version is unsupported")
    old_active = validate_record(old_state.get("active"), "state.active")
    rollback_raw = old_state.get("rollback")
    history_raw = old_state.get("history")
    if not isinstance(rollback_raw, list) or len(rollback_raw) > 2 or not isinstance(history_raw, list):
        fail("state.rollback/history shape is invalid")
    old_rollback = [validate_record(item, f"state.rollback[{i}]") for i, item in enumerate(rollback_raw)]
    old_history = [validate_record(item, f"state.history[{i}]") for i, item in enumerate(history_raw)]
    if candidate["source_sha"] in {old_active["source_sha"], *(item["source_sha"] for item in old_rollback)}:
        fail("candidate SHA is already accepted")

new_rollback = [old_active, *old_rollback[:1]] if old_active else []
state = {"version": 1, "active": candidate, "rollback": new_rollback,
         "history": [*old_history, candidate]}
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
    directory_fd = os.open(state_path.parent, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)

print(json.dumps({"state": str(state_path), "active": candidate["source_sha"],
                  "rollback": [item["source_sha"] for item in new_rollback]}, sort_keys=True))
PY

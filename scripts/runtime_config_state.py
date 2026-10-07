#!/usr/bin/env python3
"""Strict runtime configuration manifest, env, and independent state handling."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

PROJECT = "myshop"
SERVICES = ("api",)
ALLOWLIST = (
    "SALES_CHANNEL_WORKER_BATCH_SIZE",
    "SALES_CHANNEL_WORKER_ENABLED",
    "SALES_CHANNEL_WORKER_INTERVAL_MS",
)
BOOL_KEYS = {"SALES_CHANNEL_WORKER_ENABLED"}
INTEGER_RANGES = {
    "SALES_CHANNEL_WORKER_BATCH_SIZE": (1, 100),
    "SALES_CHANNEL_WORKER_INTERVAL_MS": (100, 60000),
}
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
ASSIGNMENT_RE = re.compile(r"^[ \t]*(?:export[ \t]+)?([A-Za-z_][A-Za-z0-9_]*)[ \t]*=(.*?)(?:\r?\n)?$")
STATE_FIELDS = {"version", "project", "active", "history"}
RECORD_FIELDS = {"revision", "accepted_at", "changed_under_source_sha", "config_digest", "values"}


class RuntimeConfigError(ValueError):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


def fail(code: str, message: str) -> None:
    raise RuntimeConfigError(code, message)


def _object_no_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            fail("RUNTIME_CONFIG_MANIFEST_INVALID", f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _reject_constant(value: str) -> None:
    fail("RUNTIME_CONFIG_MANIFEST_INVALID", f"invalid JSON constant: {value}")


def read_json(path: Path, label: str) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_object_no_duplicates,
                          parse_constant=_reject_constant)
    except RuntimeConfigError:
        raise
    except Exception as exc:
        fail("RUNTIME_CONFIG_MANIFEST_INVALID", f"{label} is not valid JSON: {exc}")


def valid_time(value: object) -> bool:
    if not isinstance(value, str) or not value:
        return False
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return False
    return parsed.tzinfo is not None


def normalize_value(key: str, value: object) -> str:
    if key not in ALLOWLIST or not isinstance(value, str):
        fail("RUNTIME_CONFIG_VALUE_INVALID", f"{key} must be a string")
    if key in BOOL_KEYS:
        if value not in ("true", "false"):
            fail("RUNTIME_CONFIG_VALUE_INVALID", f"{key} must be exactly true or false")
        return value
    if key in INTEGER_RANGES:
        if not re.fullmatch(r"(?:0|[1-9][0-9]*)", value):
            fail("RUNTIME_CONFIG_VALUE_INVALID", f"{key} must be a canonical decimal integer string")
        number = int(value)
        lower, upper = INTEGER_RANGES[key]
        if not lower <= number <= upper:
            fail("RUNTIME_CONFIG_VALUE_INVALID", f"{key} must be between {lower} and {upper}")
        return str(number)
    fail("RUNTIME_CONFIG_VALUE_INVALID", f"unsupported managed key: {key}")


def normalize_values(value: object) -> dict[str, str]:
    if not isinstance(value, dict) or set(value) != set(ALLOWLIST):
        fail("RUNTIME_CONFIG_VALUE_INVALID", "values must contain exactly the MyShop runtime allowlist")
    return {key: normalize_value(key, value[key]) for key in sorted(ALLOWLIST)}


def config_digest(values: dict[str, str]) -> str:
    canonical = json.dumps(normalize_values(values), sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def validate_manifest(path: Path) -> dict[str, Any]:
    value = read_json(path, "runtime config manifest")
    required = {"version", "project", "expected_active_source_sha", "created_at", "changes"}
    if not isinstance(value, dict) or set(value) != required or type(value.get("version")) is not int or value["version"] != 1:
        fail("RUNTIME_CONFIG_MANIFEST_INVALID", "manifest fields/version do not match runtime config manifest v1")
    if value["project"] != PROJECT:
        fail("RUNTIME_CONFIG_MANIFEST_INVALID", "manifest project does not match myshop")
    source_sha = value["expected_active_source_sha"]
    if not isinstance(source_sha, str) or not SHA_RE.fullmatch(source_sha):
        fail("RUNTIME_CONFIG_MANIFEST_INVALID", "expected_active_source_sha must be a full lowercase SHA")
    if not valid_time(value["created_at"]):
        fail("RUNTIME_CONFIG_MANIFEST_INVALID", "created_at must be an ISO-8601 timestamp with timezone")
    changes = value["changes"]
    if not isinstance(changes, dict):
        fail("RUNTIME_CONFIG_MANIFEST_INVALID", "changes must be an object")
    normalized: dict[str, dict[str, str]] = {}
    for key, change in changes.items():
        if key not in ALLOWLIST:
            fail("RUNTIME_CONFIG_MANIFEST_INVALID", f"unknown or sensitive runtime key: {key}")
        if not isinstance(change, dict) or set(change) != {"from", "to"}:
            fail("RUNTIME_CONFIG_MANIFEST_INVALID", f"changes.{key} must contain only from and to")
        normalized[key] = {
            "from": normalize_value(key, change["from"]),
            "to": normalize_value(key, change["to"]),
        }
    return {
        "version": 1,
        "project": PROJECT,
        "expected_active_source_sha": source_sha,
        "created_at": value["created_at"],
        "changes": normalized,
    }


def _strip_env_value(raw: str, key: str) -> str:
    value = raw.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        value = value[1:-1]
        if any(char in value for char in ("'", '"', "\\", "\n", "\r")):
            fail("RUNTIME_CONFIG_ENV_INVALID", f"{key} uses unsupported quoting")
    return normalize_value(key, value)


def parse_env_bytes(data: bytes) -> tuple[dict[str, str], dict[str, int]]:
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as exc:
        fail("RUNTIME_CONFIG_ENV_INVALID", f"production env file is not UTF-8: {exc}")
    values: dict[str, str] = {}
    locations: dict[str, int] = {}
    for index, line in enumerate(text.splitlines(keepends=True)):
        match = ASSIGNMENT_RE.match(line)
        if not match:
            continue
        key, raw = match.group(1), match.group(2)
        if key not in ALLOWLIST:
            continue
        if key in values:
            fail("RUNTIME_CONFIG_ENV_INVALID", f"managed key is defined more than once: {key}")
        values[key] = _strip_env_value(raw, key)
        locations[key] = index
    missing = sorted(set(ALLOWLIST) - set(values))
    if missing:
        fail("RUNTIME_CONFIG_ENV_INVALID", f"managed key is missing: {missing[0]}")
    return {key: values[key] for key in sorted(values)}, locations


def parse_container_env(env: object) -> dict[str, str]:
    if not isinstance(env, list):
        fail("RUNTIME_CONFIG_CONTAINER_ENV_INVALID", "container environment is malformed")
    values: dict[str, str] = {}
    for entry in env:
        if not isinstance(entry, str) or "=" not in entry:
            continue
        key, raw = entry.split("=", 1)
        if key not in ALLOWLIST:
            continue
        if key in values:
            fail("RUNTIME_CONFIG_CONTAINER_ENV_INVALID", f"managed container key is duplicated: {key}")
        values[key] = normalize_value(key, raw)
    missing = sorted(set(ALLOWLIST) - set(values))
    if missing:
        fail("RUNTIME_CONFIG_CONTAINER_ENV_INVALID", f"managed container key is missing: {missing[0]}")
    return {key: values[key] for key in sorted(values)}


def inspect_container(container_id: str, docker: str = "docker") -> dict[str, Any]:
    try:
        raw = subprocess.check_output([docker, "inspect", "--format", "{{json .}}", container_id],
                                      text=True, stderr=subprocess.PIPE)
        value = json.loads(raw)
    except Exception as exc:
        fail("RUNTIME_CONFIG_CONTAINER_INVALID", f"container inspection failed: {exc}")
    if not isinstance(value, dict) or not isinstance(value.get("Config"), dict):
        fail("RUNTIME_CONFIG_CONTAINER_INVALID", "container inspection result is malformed")
    if (value.get("State") or {}).get("Status") != "running":
        fail("RUNTIME_CONFIG_CONTAINER_INVALID", "accepted application container is not running")
    return value


def read_release_state(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=_object_no_duplicates)
        if (not isinstance(value, dict) or type(value.get("version")) is not int or value.get("version") != 1
                or not isinstance(value.get("active"), dict)):
            raise ValueError("release state does not match Deployment State v1")
        if set(value) != {"version", "active", "rollback", "history"}:
            raise ValueError("release state has an invalid field set")
        active = value["active"]
        source_sha, images = active.get("source_sha"), active.get("images")
        if (not isinstance(source_sha, str) or not SHA_RE.fullmatch(source_sha)
                or not isinstance(images, dict) or set(images) != {"api", "web"}):
            raise ValueError("release state active record is malformed")
        for service in ("api", "web"):
            image = images.get(service)
            if not isinstance(image, dict):
                raise ValueError(f"release state has no {service} image")
            ref, digest = image.get("ref"), image.get("digest")
            if (not isinstance(ref, str) or not ref.endswith(":" + source_sha)
                    or not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest)):
                raise ValueError(f"release state {service} identity is malformed")
            if ref.rsplit(":", 1)[0] != f"ghcr.io/yykx682436/myshop-{service}":
                raise ValueError(f"release state {service} ref is outside the approved namespace")
        if not isinstance(value["rollback"], list) or len(value["rollback"]) > 2 or not isinstance(value["history"], list):
            raise ValueError("release state rollback/history fields are malformed")
        return value
    except RuntimeConfigError:
        raise
    except Exception as exc:
        fail("ACTIVE_RELEASE_MISMATCH", f"accepted release state is invalid: {exc}")


def validate_state(value: object) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != STATE_FIELDS or type(value.get("version")) is not int or value["version"] != 1:
        fail("RUNTIME_CONFIG_STATE_INVALID", "runtime config state must match version 1 schema")
    if value.get("project") != PROJECT:
        fail("RUNTIME_CONFIG_STATE_INVALID", "runtime config state project does not match")
    history = value.get("history")
    if not isinstance(history, list):
        fail("RUNTIME_CONFIG_STATE_INVALID", "runtime config history must be an array")
    records = []
    for index, record in enumerate(history):
        records.append(validate_record(record, f"history[{index}]"))
    active = validate_record(value.get("active"), "active")
    revisions = [record["revision"] for record in records]
    if revisions != list(range(1, active["revision"])) or active["revision"] < 1:
        fail("RUNTIME_CONFIG_STATE_INVALID", "runtime config revisions must be contiguous and monotonic")
    return {"version": 1, "project": PROJECT, "active": active, "history": records}


def validate_record(value: object, label: str) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != RECORD_FIELDS:
        fail("RUNTIME_CONFIG_STATE_INVALID", f"{label} has an invalid field set")
    revision = value["revision"]
    if type(revision) is not int or revision < 1:
        fail("RUNTIME_CONFIG_STATE_INVALID", f"{label}.revision must be a positive integer")
    if not valid_time(value["accepted_at"]):
        fail("RUNTIME_CONFIG_STATE_INVALID", f"{label}.accepted_at must be timezone-aware ISO-8601")
    if not isinstance(value["changed_under_source_sha"], str) or not SHA_RE.fullmatch(value["changed_under_source_sha"]):
        fail("RUNTIME_CONFIG_STATE_INVALID", f"{label}.changed_under_source_sha is invalid")
    values = normalize_values(value["values"])
    if value["config_digest"] != config_digest(values):
        fail("RUNTIME_CONFIG_STATE_INVALID", f"{label}.config_digest does not match canonical values")
    return {
        "revision": revision,
        "accepted_at": value["accepted_at"],
        "changed_under_source_sha": value["changed_under_source_sha"],
        "config_digest": value["config_digest"],
        "values": values,
    }


def write_private(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
        fsync_dir(path.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def backup_private(source: Path, target: Path) -> None:
    if source.is_symlink() or not source.is_file():
        fail("RUNTIME_CONFIG_ENV_INVALID", "production env backup source is not a regular file")
    write_private(target, source.read_bytes())


def fsync_dir(path: Path) -> None:
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_install_env(candidate: Path, target: Path) -> None:
    info = target.lstat()
    if not stat.S_ISREG(info.st_mode) or target.is_symlink():
        fail("RUNTIME_CONFIG_ENV_INVALID", "production env target must be a regular non-symlink file")
    data = candidate.read_bytes()
    fd, temporary = tempfile.mkstemp(prefix=f".{target.name}.runtime-config.", dir=target.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.chown(temporary, info.st_uid, info.st_gid)
        os.chmod(temporary, stat.S_IMODE(info.st_mode))
        os.replace(temporary, target)
        fsync_dir(target.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def atomic_restore_state(backup: Path, target: Path, existed: bool) -> None:
    if not existed:
        if target.exists():
            target.unlink()
            fsync_dir(target.parent)
        return
    data = backup.read_bytes()
    current = target.stat() if target.exists() else backup.stat()
    fd, temporary = tempfile.mkstemp(prefix=f".{target.name}.restore.", dir=target.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
            handle.flush()
            os.fsync(handle.fileno())
        os.chown(temporary, current.st_uid, current.st_gid)
        os.chmod(temporary, stat.S_IMODE(current.st_mode) if target.exists() else 0o640)
        os.replace(temporary, target)
        fsync_dir(target.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def release_image_record(release_state: dict[str, Any], service: str) -> tuple[str, str, str]:
    active = release_state["active"]
    sha = active["source_sha"]
    image = active["images"].get(service)
    if not isinstance(image, dict):
        fail("ACTIVE_RELEASE_MISMATCH", f"accepted release has no {service} image")
    ref = image["ref"]
    digest = image["digest"]
    if not ref.endswith(":" + sha):
        fail("ACTIVE_RELEASE_MISMATCH", "accepted application image tag differs from active source SHA")
    return sha, ref, digest


def prepare_plan(args: argparse.Namespace) -> dict[str, Any]:
    manifest = validate_manifest(Path(args.manifest))
    release = read_release_state(Path(args.release_state))
    source_sha, ref, image_digest = release_image_record(release, args.service)
    if manifest["expected_active_source_sha"] != source_sha:
        fail("ACTIVE_RELEASE_MISMATCH", "manifest expected source differs from accepted release state")
    container = inspect_container(args.container_id, args.docker)
    config = container["Config"]
    if config.get("Image") != ref:
        fail("ACTIVE_RELEASE_MISMATCH", "running application image ref differs from accepted release state")
    env_path = Path(args.env_file)
    if env_path.is_symlink() or not env_path.is_file():
        fail("RUNTIME_CONFIG_ENV_INVALID", "production env file must be a regular file")
    env_bytes = env_path.read_bytes()
    current_values, locations = parse_env_bytes(env_bytes)
    container_values = parse_container_env(config.get("Env"))
    runtime_state_path = Path(args.runtime_state)
    state_exists = runtime_state_path.exists()
    runtime_state = None
    if state_exists:
        try:
            runtime_state = validate_state(read_json(runtime_state_path, "runtime config state"))
        except RuntimeConfigError:
            raise
        except Exception as exc:
            fail("RUNTIME_CONFIG_STATE_INVALID", f"runtime config state is invalid: {exc}")
        accepted_values = runtime_state["active"]["values"]
        if current_values != accepted_values or container_values != accepted_values:
            fail("RUNTIME_CONFIG_DRIFT", "production env/container values differ from accepted runtime config state")
    elif current_values != container_values:
        fail("RUNTIME_CONFIG_BASELINE_DRIFT", "production env and running container managed values differ")
    if not state_exists and manifest["changes"]:
        fail("RUNTIME_CONFIG_BASELINE_REQUIRED", "submit changes={} once to accept the verified runtime baseline first")

    candidate_values = dict(current_values)
    for key, change in manifest["changes"].items():
        if current_values[key] != change["from"]:
            fail("RUNTIME_CONFIG_FROM_MISMATCH", f"{key} current value differs from declared from")
        candidate_values[key] = change["to"]
    candidate_values = normalize_values(candidate_values)
    no_op = candidate_values == current_values
    if not no_op:
        if not args.candidate_env:
            fail("RUNTIME_CONFIG_INTERNAL_ERROR", "candidate env output path is required")
        lines = env_bytes.decode("utf-8").splitlines(keepends=True)
        for key, value in candidate_values.items():
            if current_values[key] == value:
                continue
            line_index = locations[key]
            line = lines[line_index]
            ending = "\r\n" if line.endswith("\r\n") else "\n" if line.endswith("\n") else ""
            equal = line.find("=")
            lines[line_index] = line[:equal + 1] + value + ending
        write_private(Path(args.candidate_env), "".join(lines).encode("utf-8"))
    return {
        "project": PROJECT,
        "source_sha": source_sha,
        "service": args.service,
        "container_id": args.container_id,
        "image_ref": ref,
        "image_config_digest": image_digest,
        "runtime_state_exists": state_exists,
        "no_op": no_op,
        "current_values": current_values,
        "candidate_values": candidate_values,
        "current_config_digest": config_digest(current_values),
        "candidate_config_digest": config_digest(candidate_values),
        "revision": runtime_state["active"]["revision"] if state_exists else 0,
    }


def verify_state_baseline(state_path: Path, env_path: Path, container_id: str, docker: str) -> None:
    state = validate_state(read_json(state_path, "runtime config state"))
    values, _ = parse_env_bytes(env_path.read_bytes())
    container = inspect_container(container_id, docker)
    container_values = parse_container_env(container["Config"].get("Env"))
    if values != state["active"]["values"] or container_values != state["active"]["values"]:
        fail("RUNTIME_CONFIG_DRIFT", "production env/container values differ from accepted runtime config state")


def verify_container_env(env_path: Path, container_id: str, docker: str) -> None:
    values, _ = parse_env_bytes(env_path.read_bytes())
    container = inspect_container(container_id, docker)
    if parse_container_env(container["Config"].get("Env")) != values:
        fail("RUNTIME_CONFIG_CONTAINER_ENV_MISMATCH", "running container managed values differ from candidate env")


def promote_state(path: Path, values: dict[str, str], source_sha: str) -> tuple[dict[str, Any], bool]:
    values = normalize_values(values)
    if not SHA_RE.fullmatch(source_sha):
        fail("RUNTIME_CONFIG_STATE_INVALID", "source SHA for runtime config state is invalid")
    old = None
    if path.exists():
        old = validate_state(read_json(path, "runtime config state"))
        if old["active"]["values"] == values:
            return old, False
        history = [*old["history"], old["active"]]
        revision = old["active"]["revision"] + 1
    else:
        history = []
        revision = 1
    active = {
        "revision": revision,
        "accepted_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "changed_under_source_sha": source_sha,
        "config_digest": config_digest(values),
        "values": values,
    }
    state = validate_state({"version": 1, "project": PROJECT, "active": active, "history": history})
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o750)
    existing = path.stat() if path.exists() else None
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(state, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        if existing:
            os.chown(temporary, existing.st_uid, existing.st_gid)
            mode = stat.S_IMODE(existing.st_mode)
            os.chmod(temporary, mode if mode & 0o077 == 0 else 0o640)
        else:
            os.chmod(temporary, 0o640)
        os.replace(temporary, path)
        fsync_dir(path.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return state, True


def record_boundary(path: Path, manifest_path: Path, source_sha: str,
                    before_json: str, after_json: str, runtime_state_path: Path) -> dict[str, Any]:
    if not SHA_RE.fullmatch(source_sha):
        fail("RUNTIME_CONFIG_EVIDENCE_INVALID", "side-effect evidence source SHA is invalid")
    try:
        before, after = json.loads(before_json), json.loads(after_json)
    except Exception:
        fail("RUNTIME_CONFIG_EVIDENCE_INVALID", "side-effect evidence safety summary is invalid")
    state = validate_state(read_json(runtime_state_path, "runtime config state"))
    payload = {
        "version": 1,
        "project": PROJECT,
        "status": "RUNTIME_CONFIG_SIDE_EFFECT_BOUNDARY_CROSSED",
        "source_sha": source_sha,
        "manifest_sha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
        "detected_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
        "runtime_config_revision_before_promotion": state["active"]["revision"],
        "runtime_config_digest_before_promotion": state["active"]["config_digest"],
        "safety_before": before,
        "safety_after": after,
    }
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o750)
    fd, temporary = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.chmod(temporary, 0o640)
        os.replace(temporary, path)
        fsync_dir(path.parent)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    return payload


def command_main() -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    plan = sub.add_parser("prepare")
    plan.add_argument("--manifest", required=True)
    plan.add_argument("--release-state", required=True)
    plan.add_argument("--runtime-state", required=True)
    plan.add_argument("--env-file", required=True)
    plan.add_argument("--container-id", required=True)
    plan.add_argument("--service", choices=SERVICES, required=True)
    plan.add_argument("--candidate-env")
    plan.add_argument("--docker", default="docker")
    check = sub.add_parser("verify-baseline")
    check.add_argument("--runtime-state", required=True)
    check.add_argument("--env-file", required=True)
    check.add_argument("--container-id", required=True)
    check.add_argument("--docker", default="docker")
    container_check = sub.add_parser("verify-container-env")
    container_check.add_argument("--env-file", required=True)
    container_check.add_argument("--container-id", required=True)
    container_check.add_argument("--docker", default="docker")
    make = sub.add_parser("promote")
    make.add_argument("--runtime-state", required=True)
    make.add_argument("--source-sha", required=True)
    make.add_argument("--values-json", required=True)
    install = sub.add_parser("install-env")
    install.add_argument("--candidate", required=True)
    install.add_argument("--target", required=True)
    backup = sub.add_parser("backup-env")
    backup.add_argument("--source", required=True)
    backup.add_argument("--target", required=True)
    restore = sub.add_parser("restore-state")
    restore.add_argument("--backup", required=True)
    restore.add_argument("--target", required=True)
    restore.add_argument("--existed", choices=("true", "false"), required=True)
    summary = sub.add_parser("summary")
    summary.add_argument("--runtime-state", required=True)
    evidence = sub.add_parser("record-side-effect-boundary")
    evidence.add_argument("--path", required=True)
    evidence.add_argument("--manifest", required=True)
    evidence.add_argument("--source-sha", required=True)
    evidence.add_argument("--before-json", required=True)
    evidence.add_argument("--after-json", required=True)
    evidence.add_argument("--runtime-state", required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            print(json.dumps(prepare_plan(args), sort_keys=True, separators=(",", ":")))
        elif args.command == "verify-baseline":
            verify_state_baseline(Path(args.runtime_state), Path(args.env_file), args.container_id, args.docker)
            print("RUNTIME_CONFIG_BASELINE_OK")
        elif args.command == "verify-container-env":
            verify_container_env(Path(args.env_file), args.container_id, args.docker)
            print("RUNTIME_CONFIG_CONTAINER_ENV_OK")
        elif args.command == "promote":
            try:
                values = json.loads(args.values_json, object_pairs_hook=_object_no_duplicates)
            except Exception as exc:
                fail("RUNTIME_CONFIG_VALUE_INVALID", f"candidate values are invalid JSON: {exc}")
            state, changed = promote_state(Path(args.runtime_state), values, args.source_sha)
            print(json.dumps({"revision": state["active"]["revision"], "changed": changed,
                              "config_digest": state["active"]["config_digest"]}, sort_keys=True))
        elif args.command == "install-env":
            atomic_install_env(Path(args.candidate), Path(args.target))
        elif args.command == "backup-env":
            backup_private(Path(args.source), Path(args.target))
        elif args.command == "restore-state":
            atomic_restore_state(Path(args.backup), Path(args.target), args.existed == "true")
        elif args.command == "summary":
            state = validate_state(read_json(Path(args.runtime_state), "runtime config state"))
            print(json.dumps(state["active"], sort_keys=True, separators=(",", ":")))
        elif args.command == "record-side-effect-boundary":
            payload = record_boundary(Path(args.path), Path(args.manifest), args.source_sha,
                                      args.before_json, args.after_json, Path(args.runtime_state))
            print(json.dumps({"evidence": str(Path(args.path)), "status": payload["status"]}, sort_keys=True))
        return 0
    except RuntimeConfigError as exc:
        print(f"{exc.code}: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(command_main())

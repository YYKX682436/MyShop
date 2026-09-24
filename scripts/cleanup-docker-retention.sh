#!/usr/bin/env bash
set -Eeuo pipefail

# MyShop-only retention. The accepted set is the state file's active record
# plus up to two derived rollback records. This script never touches releases,
# volumes, BuildKit cache, or another project's namespace.
exec python3 - "$@" <<'PY'
from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import subprocess
import sys
from contextlib import nullcontext
from pathlib import Path

ROOT = Path(os.environ.get("MYSHOP_RUNTIME_ROOT", "/opt/myshop/runtime"))
STATE = Path(os.environ.get("MYSHOP_DEPLOY_STATE", str(ROOT / "deploy-state.json")))
LOCK = Path(os.environ.get("HOST_DEPLOY_LOCK_FILE", "/run/myshop-xianyu-deploy.lock"))
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
SERVICES = {"api", "web"}


def die(message: str) -> "NoReturn":
    print(f"[myshop-retention] ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def docker(*args: str, check: bool = True) -> str:
    result = subprocess.run(["docker", *args], check=check, text=True, capture_output=True)
    return result.stdout


def record(value: object, label: str, services: set[str] = SERVICES) -> dict:
    if not isinstance(value, dict):
        die(f"{label} must be an object")
    sha = value.get("source_sha")
    accepted_at = value.get("accepted_at")
    images = value.get("images")
    if not isinstance(sha, str) or not SHA.fullmatch(sha) or not isinstance(accepted_at, str) or not accepted_at:
        die(f"{label} has invalid source_sha/accepted_at")
    if not isinstance(images, dict) or set(images) != services:
        die(f"{label}.images must contain exactly {sorted(services)}")
    for service, item in images.items():
        if not isinstance(item, dict):
            die(f"{label}.images.{service} must be an object")
        ref, digest = item.get("ref"), item.get("digest")
        if not isinstance(ref, str) or not ref or ":latest" in ref or ":local" in ref or not isinstance(digest, str) or not DIGEST.fullmatch(digest):
            die(f"{label}.images.{service} is mutable or has an invalid digest")
    return value


def load_records() -> list[dict]:
    if not STATE.is_file():
        die(f"accepted deployment state is missing: {STATE}")
    try:
        state = json.loads(STATE.read_text(encoding="utf-8"))
    except Exception as exc:
        die(f"malformed deployment state: {exc}")
    if not isinstance(state, dict) or state.get("version") != 1:
        die("deployment state version is unsupported")
    active = record(state.get("active"), "state.active")
    rollback = state.get("rollback")
    history = state.get("history")
    if not isinstance(rollback, list) or len(rollback) > 2 or not isinstance(history, list):
        die("state rollback/history shape is invalid")
    records = [active]
    for index, item in enumerate(rollback):
        records.append(record(item, f"state.rollback[{index}]"))
    all_shas = [item["source_sha"] for item in records]
    if len(set(all_shas)) != len(all_shas):
        die("active and rollback source SHAs must be unique")
    for index, item in enumerate(history):
        record(item, f"state.history[{index}]")
    return records


def image_matches(ref: str, expected_digest: str) -> tuple[str, bool]:
    output = docker("image", "inspect", "--format", "{{.Id}}\t{{range .RepoDigests}}{{println .}}{{end}}", ref, check=False)
    if not output.strip():
        return "", False
    lines = output.splitlines()
    image_id = lines[0].strip()
    return image_id, image_id == expected_digest or any(line.endswith("@" + expected_digest) for line in lines[1:])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    records = load_records()
    active = records[0]

    LOCK.parent.mkdir(parents=True, exist_ok=True)
    lock_context = nullcontext(None) if os.environ.get("HOST_DEPLOY_LOCK_HELD") == "1" else LOCK.open("a+")
    with lock_context as lock:
        if lock is not None:
            try:
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                die("deployment/maintenance lock is held")

        running_ids: set[str] = set()
        for container_id in docker("ps", "-aq").splitlines():
            image_id = docker("inspect", "--format", "{{.Image}}", container_id, check=False).strip()
            if image_id:
                running_ids.add(image_id)

        accepted_refs: set[str] = set()
        accepted_ids: set[str] = set(running_ids)
        repositories: set[str] = set()
        for item in records:
            for service, image in item["images"].items():
                ref = image["ref"]
                repo, tag = ref.rsplit(":", 1)
                if service == "api" and not (repo == "myshop-api" or repo.endswith("/myshop-api")):
                    die(f"API image is outside MyShop namespace: {repo}")
                if service == "web" and not (repo == "myshop-web" or repo.endswith("/myshop-web")):
                    die(f"Web image is outside MyShop namespace: {repo}")
                if tag != item["source_sha"]:
                    die(f"accepted image tag does not match source SHA: {ref}")
                image_id, matches = image_matches(ref, image["digest"])
                if not matches:
                    die(f"accepted image digest cannot be verified locally: {ref}")
                accepted_refs.add(ref)
                repositories.add(repo)
                accepted_ids.add(image_id)

        for service, expected in active["images"].items():
            rows = docker("ps", "--filter", "label=com.docker.compose.project=myshop", "--filter", f"label=com.docker.compose.service={service}", "--format", "{{.ID}}	{{.Image}}")
            matches = [line.split("\t", 1) for line in rows.splitlines() if "\t" in line]
            if len(matches) != 1:
                die(f"expected one active MyShop {service} container, found {len(matches)}")
            if matches[0][1] != expected["ref"]:
                die(f"active MyShop {service} image differs from state")
            running_id = docker("inspect", "--format", "{{.Image}}", matches[0][0]).strip()
            if running_id not in accepted_ids:
                die(f"active MyShop {service} image ID is not protected")

        rows = docker("image", "ls", "-a", "--no-trunc", "--format", "{{.Repository}}\t{{.Tag}}\t{{.ID}}").splitlines()
        for row in rows:
            fields = row.split("\t", 2)
            if len(fields) != 3:
                continue
            repo, tag, image_id = fields
            if repo not in repositories or not tag or tag == "<none>":
                continue
            ref = f"{repo}:{tag}"
            if ref in accepted_refs or image_id in accepted_ids:
                continue
            print(f"[myshop-retention] {'would remove' if args.dry_run else 'remove'} image: {ref}")
            if not args.dry_run:
                docker("image", "rm", ref)

        print(docker("system", "df", "-v"), end="")
        print(subprocess.run(["df", "-h", "/data"], check=False, text=True, capture_output=True).stdout, end="")
        print("[myshop-retention] completed; no source releases, volumes, or BuildKit cache were touched")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
PY

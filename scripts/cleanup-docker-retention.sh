#!/usr/bin/env python3
"""Fail-closed MyShop-only retention; never infer rollback from mtime."""
from __future__ import annotations

import argparse
import fcntl
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

PROJECT_ROOT = Path(os.environ.get("MYSHOP_ROOT", "/opt/myshop"))
RELEASE_ROOT = PROJECT_ROOT / "releases"
STATE_FILE = Path(os.environ.get("MYSHOP_DEPLOY_STATE", str(PROJECT_ROOT / "deploy-state.json")))
LOCK_FILE = Path(os.environ.get("HOST_DEPLOY_LOCK_FILE", "/run/myshop-xianyu-deploy.lock"))
SHA_RE = re.compile(r"^[0-9a-f]{40}$")
DIGEST_RE = re.compile(r"^sha256:[0-9a-f]{64}$")


def die(message: str) -> "NoReturn":
    print(f"[myshop-retention] ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def docker(*args: str, check: bool = True) -> str:
    result = subprocess.run(["docker", *args], check=check, text=True, capture_output=True)
    return result.stdout


def load_state() -> tuple[str, list[str], str, str]:
    try:
        state = json.loads(STATE_FILE.read_text(encoding="utf-8"))
    except Exception as exc:
        die(f"malformed deployment state: {exc}")
    active = state.get("active")
    rollback = state.get("rollback")
    accepted = state.get("accepted")
    images = state.get("images")
    if not isinstance(active, str) or not SHA_RE.fullmatch(active):
        die("state.active must be a full lowercase SHA")
    if not isinstance(rollback, list) or len(rollback) != 2 or not all(isinstance(v, str) and SHA_RE.fullmatch(v) for v in rollback):
        die("state.rollback must contain exactly two full SHAs")
    if len({active, *rollback}) != 3 or accepted != [active, *rollback]:
        die("state accepted set is not exactly active plus two unique rollbacks")
    if not isinstance(images, dict):
        die("state.images is missing")
    refs = []
    for service in ("api", "web"):
        image = images.get(service)
        if not isinstance(image, dict):
            die(f"state.images.{service} is missing")
        ref = image.get("ref")
        digest = image.get("digest")
        if not isinstance(ref, str) or not ref or ref.endswith(":latest") or ref.endswith(":local"):
            die(f"state.images.{service}.ref is mutable")
        if not isinstance(digest, str) or not DIGEST_RE.fullmatch(digest):
            die(f"state.images.{service}.digest is invalid")
        refs.append(ref)
    return active, rollback, refs[0], refs[1]


def check_active_service(service: str, expected: str) -> None:
    output = docker("ps", "--format", "{{.ID}}\t{{.Image}}\t{{.Label \"com.docker.compose.project\"}}\t{{.Label \"com.docker.compose.service\"}}")
    matches = []
    for line in output.splitlines():
        parts = line.split("\t")
        if len(parts) == 4 and parts[3] == service:
            matches.append(parts)
    if len(matches) != 1:
        die(f"expected one active MyShop {service} container, found {len(matches)}")
    _, image, project, _ = matches[0]
    if project != "myshop":
        die(f"active {service} belongs to unexpected Compose project: {project}")
    if image != expected:
        die(f"active {service} image differs from state: {image} != {expected}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    if not RELEASE_ROOT.is_dir() or not STATE_FILE.is_file():
        die("release root or machine-readable deployment state is missing")
    LOCK_FILE.parent.mkdir(parents=True, exist_ok=True)
    with LOCK_FILE.open("a+") as lock:
        try:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            print("[myshop-retention] deployment/maintenance lock is held; no cleanup performed")
            return 0
        active, rollback, api_ref, web_ref = load_state()
        current = PROJECT_ROOT / "current"
        if current.exists() and not current.is_symlink():
            die("current must be a symlink to the accepted active release")
        if current.is_symlink() and current.resolve() != (RELEASE_ROOT / active).resolve():
            die("current symlink does not match state.active")
        for sha in [active, *rollback]:
            if not (RELEASE_ROOT / sha).is_dir():
                die(f"accepted release directory is missing: {sha}")
        check_active_service("api", api_ref)
        check_active_service("web", web_ref)
        protected_ids = set()
        for container_id in docker("ps", "-aq").splitlines():
            image_id = docker("inspect", "--format", "{{.Image}}", container_id, check=False).strip()
            if image_id:
                protected_ids.add(image_id)
        api_repo, web_repo = api_ref.rsplit(":", 1)[0], web_ref.rsplit(":", 1)[0]
        if not (api_repo == "myshop-api" or api_repo.endswith("/myshop-api")):
            die(f"API image is outside MyShop namespace: {api_repo}")
        if not (web_repo == "myshop-web" or web_repo.endswith("/myshop-web")):
            die(f"Web image is outside MyShop namespace: {web_repo}")
        keep_refs = set()
        for sha in [active, *rollback]:
            for repo in (api_repo, web_repo):
                ref = f"{repo}:{sha}"
                keep_refs.add(ref)
                image_id = docker("image", "inspect", "--format", "{{.Id}}", ref, check=False).strip()
                if not image_id:
                    die(f"accepted image is missing: {ref}")
                protected_ids.add(image_id)
        rows = docker("image", "ls", "-a", "--no-trunc", "--format", "{{.Repository}}\t{{.Tag}}\t{{.ID}}").splitlines()
        for row in rows:
            fields = row.split("\t", 2)
            if len(fields) != 3:
                continue
            repo, tag, image_id = fields
            if repo not in (api_repo, web_repo) or not tag or tag == "<none>":
                continue
            ref = f"{repo}:{tag}"
            if ref in keep_refs or image_id in protected_ids:
                continue
            print(f"[myshop-retention] remove image: {ref}")
            if not args.dry_run:
                docker("image", "rm", ref)
        release_root_real = RELEASE_ROOT.resolve()
        for release_dir in RELEASE_ROOT.iterdir():
            if not release_dir.is_dir() or not SHA_RE.fullmatch(release_dir.name) or release_dir.name in {active, *rollback}:
                continue
            if release_dir.resolve().parent != release_root_real:
                die(f"unsafe release path: {release_dir}")
            print(f"[myshop-retention] remove release: {release_dir}")
            if not args.dry_run:
                shutil.rmtree(release_dir)
        print(docker("system", "df", "-v"), end="")
        print(subprocess.run(["df", "-h", "/data"], check=False, text=True, capture_output=True).stdout, end="")
        print("[myshop-retention] completed; volumes and BuildKit cache were not touched")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

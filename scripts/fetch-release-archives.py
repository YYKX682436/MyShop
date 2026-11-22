#!/usr/bin/env python3
"""Download the existing CI release artifact in parallel and verify it."""
from __future__ import annotations

import concurrent.futures
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

REPO = "YYKX682436/MyShop"
FILES = {"api.tar", "web.tar", "release-manifest.json", "manifest-carrier-digest.txt", "SHA256SUMS"}
CHUNK_SIZE = 1024 * 1024
WORKERS = 32


def gh_api(endpoint: str) -> dict:
    output = subprocess.check_output(["gh", "api", endpoint], text=True, timeout=30)
    return json.loads(output)


def find_artifact(sha: str) -> tuple[dict, int]:
    runs = gh_api(f"repos/{REPO}/actions/workflows/cicd.yml/runs?branch=main&status=success&per_page=50")
    for run in runs["workflow_runs"]:
        if run.get("head_sha") != sha or run.get("conclusion") != "success":
            continue
        for artifact in gh_api(f"repos/{REPO}/actions/runs/{run['id']}/artifacts")["artifacts"]:
            if artifact.get("name") == f"myshop-release-{sha}" and not artifact.get("expired"):
                return artifact, run["id"]
    raise ValueError("no unexpired artifact from a successful main-branch run for this SHA")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def artifact_url(artifact_id: int) -> str:
    token = os.environ.get("GH_TOKEN") or subprocess.check_output(["gh", "auth", "token"], text=True, timeout=30).strip()
    request = urllib.request.Request(
        f"https://api.github.com/repos/{REPO}/actions/artifacts/{artifact_id}/zip",
        headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "User-Agent": "myshop-release"},
    )
    try:
        urllib.request.build_opener(NoRedirect).open(request, timeout=30)
    except urllib.error.HTTPError as error:
        if error.code in (301, 302, 303, 307, 308):
            url = error.headers.get("Location", "")
            if urllib.parse.urlsplit(url).scheme == "https":
                return url
    raise ValueError("GitHub did not return a secure artifact download location")


class ArtifactURL:
    """Refresh the short-lived download URL during slow transfers."""
    def __init__(self, artifact_id: int):
        self.artifact_id = artifact_id
        self.lock = threading.Lock()
        self.url = ""
        self.refresh_at = 0.0

    def get(self) -> str:
        with self.lock:
            now = time.monotonic()
            if not self.url or now >= self.refresh_at:
                self.url = artifact_url(self.artifact_id)
                self.refresh_at = time.monotonic() + 240
            return self.url


def download_part(url, destination: Path, start: int, length: int) -> None:
    # Supply the short-lived signed URL on stdin, never as a process argument.
    for attempt in range(3):
        current_url = url() if callable(url) else url
        config = f'url = {json.dumps(current_url)}\nrange = "{start}-{start + length - 1}"\n'
        result = subprocess.run(
            ["curl", "--silent", "--show-error", "--fail", "--connect-timeout", "20", "--max-time", "180",
             "--output", str(destination), "--write-out", "%{http_code}", "--config", "-"],
            input=config, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        if result.returncode == 0 and result.stdout == "206" and destination.stat().st_size == length:
            return
    raise ValueError(f"artifact byte range at offset {start} could not be downloaded")


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def extract_and_verify(archive: Path, output: Path, sha: str) -> None:
    with zipfile.ZipFile(archive) as package:
        entries = package.infolist()
        if len(entries) != len(FILES) or {item.filename for item in entries} != FILES:
            raise ValueError("release artifact has unexpected or duplicate files")
        if any(item.is_dir() or ((item.external_attr >> 16) & 0o170000) == 0o120000 for item in entries):
            raise ValueError("release artifact contains a directory or symbolic link")
        if sum(item.file_size for item in entries) > 2 * 1024**3:
            raise ValueError("release artifact expands beyond the expected size")
        package.extractall(output)
    checksums = {}
    for line in (output / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([a-zA-Z0-9.-]+)", line)
        if not match or match.group(2) in checksums:
            raise ValueError("invalid artifact checksum list")
        checksums[match.group(2)] = match.group(1)
    if set(checksums) != FILES - {"SHA256SUMS"}:
        raise ValueError("artifact checksum list has missing or unexpected files")
    for name, expected in checksums.items():
        if digest_file(output / name) != expected:
            raise ValueError(f"artifact checksum mismatch: {name}")
    validator = Path(__file__).with_name("validate-release-manifest.py")
    result = subprocess.run([sys.executable, str(validator), str(output / "release-manifest.json"),
                             "ghcr.io/yykx682436", "myshop"], capture_output=True, text=True, check=True)
    if json.loads(result.stdout)["source_sha"] != sha:
        raise ValueError("artifact release manifest does not match the requested SHA")
    if not re.fullmatch(r"sha256:[0-9a-f]{64}\n?", (output / "manifest-carrier-digest.txt").read_text(encoding="utf-8")):
        raise ValueError("artifact carrier digest is invalid")


def main() -> None:
    if len(sys.argv) != 3 or not re.fullmatch(r"[0-9a-f]{40}", sys.argv[1]):
        raise ValueError("usage: fetch-release-archives.py <full-source-sha> <empty-output-directory>")
    sha, target = sys.argv[1], Path(sys.argv[2]).absolute()
    if target.is_symlink() or (target.exists() and (not target.is_dir() or any(target.iterdir()))):
        raise ValueError("output directory must be absent or empty, and not a symlink")
    artifact, run_id = find_artifact(sha)
    size, expected = artifact.get("size_in_bytes"), artifact.get("digest", "")
    if not isinstance(size, int) or size <= 0 or size > 2 * 1024**3 or not re.fullmatch(r"sha256:[0-9a-f]{64}", expected):
        raise ValueError("GitHub artifact size or digest is invalid")
    url = ArtifactURL(artifact["id"])
    with tempfile.TemporaryDirectory(prefix="myshop-release-archives-", dir=target.parent) as temporary:
        root = Path(temporary)
        parts = [(i, start, min(CHUNK_SIZE, size - start)) for i, start in enumerate(range(0, size, CHUNK_SIZE))]
        print(f"Downloading run {run_id}: {len(parts)} ranges, up to {WORKERS} at a time", flush=True)
        with concurrent.futures.ThreadPoolExecutor(max_workers=WORKERS) as pool:
            futures = [pool.submit(download_part, url.get, root / str(i), start, length) for i, start, length in parts]
            for future in concurrent.futures.as_completed(futures):
                future.result()
        archive = root / "release.zip"
        with archive.open("wb") as merged:
            for i, _, _ in parts:
                with (root / str(i)).open("rb") as part:
                    shutil.copyfileobj(part, merged, length=1024 * 1024)
        if digest_file(archive) != expected.removeprefix("sha256:"):
            raise ValueError("downloaded ZIP digest does not match GitHub")
        verified = root / "verified"
        verified.mkdir()
        extract_and_verify(archive, verified, sha)
        target.mkdir(mode=0o700, exist_ok=True)
        for name in FILES:
            os.replace(verified / name, target / name)
    print(f"RELEASE_ARCHIVES_VERIFIED source_sha={sha} run={run_id} output={target}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError, urllib.error.URLError, zipfile.BadZipFile, json.JSONDecodeError) as exc:
        print(f"[release-archives] ERROR: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)

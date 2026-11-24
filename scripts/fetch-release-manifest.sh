#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-manifest-fetch] ERROR: %s\n' "$*" >&2; exit 1; }
(( $# == 2 )) || die 'usage: fetch-release-manifest.sh <source_sha> <output-path>'
source_sha=$1
output=$2
[[ "$source_sha" =~ ^[0-9a-f]{40}$ ]] || die 'source SHA must be exactly 40 lowercase hexadecimal characters'
[[ -n "$output" ]] || die 'output path is empty'
command -v gh >/dev/null 2>&1 || die 'gh is unavailable'
command -v python3 >/dev/null 2>&1 || die 'python3 is required'

script_dir="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
registry=ghcr.io/yykx682436
exec python3 - "$script_dir" "$registry" "$source_sha" "$output" <<'PY'
from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

script_dir, registry, source_sha, output_text = sys.argv[1:]
repo = "YYKX682436/MyShop"
sha_re = re.compile(r"^[0-9a-f]{40}$")
if not sha_re.fullmatch(source_sha):
    raise SystemExit("[myshop-manifest-fetch] invalid source SHA")


def gh_json(*args: str) -> dict:
    return json.loads(subprocess.check_output(["gh", "api", *args], text=True, timeout=60))


runs = gh_json(f"repos/{repo}/actions/workflows/cicd.yml/runs?head_sha={source_sha}&status=success&per_page=100")
selected = None
for run in runs.get("workflow_runs", []):
    if run.get("head_sha") != source_sha or run.get("conclusion") != "success":
        continue
    jobs = gh_json(f"repos/{repo}/actions/runs/{run['id']}/jobs?per_page=100")
    if any(job.get("name") == "Publish immutable release manifest" and job.get("conclusion") == "success"
           for job in jobs.get("jobs", [])):
        selected = run
        break
if selected is None:
    raise SystemExit("[myshop-manifest-fetch] no successful immutable release manifest exists for this SHA")

artifacts = gh_json(f"repos/{repo}/actions/runs/{selected['id']}/artifacts")
artifact_name = f"myshop-release-manifest-{source_sha}"
if not any(item.get("name") == artifact_name and not item.get("expired") for item in artifacts.get("artifacts", [])):
    raise SystemExit("[myshop-manifest-fetch] the successful manifest artifact is missing or expired")

target = Path(output_text)
target.parent.mkdir(parents=True, exist_ok=True)
if target.is_dir() or target.is_symlink():
    raise SystemExit("[myshop-manifest-fetch] output must be a regular file path")
with tempfile.TemporaryDirectory(prefix="myshop-manifest-fetch-", dir=target.parent) as temp_name:
    temp = Path(temp_name)
    subprocess.run(["gh", "run", "download", str(selected["id"]), "--repo", repo,
                    "--name", artifact_name, "--dir", str(temp)], check=True, timeout=180)
    expected_files = {"release-manifest.json", "SHA256SUMS"}
    actual_files = {path.name for path in temp.iterdir()}
    if actual_files != expected_files or any(path.is_symlink() or not path.is_file() for path in temp.iterdir()):
        raise SystemExit("[myshop-manifest-fetch] artifact has unexpected files")
    checksums = (temp / "SHA256SUMS").read_text(encoding="utf-8").splitlines()
    if len(checksums) != 1:
        raise SystemExit("[myshop-manifest-fetch] artifact checksum list is invalid")
    match = re.fullmatch(r"([0-9a-f]{64})  release-manifest\.json", checksums[0])
    if not match:
        raise SystemExit("[myshop-manifest-fetch] artifact checksum entry is invalid")
    content = (temp / "release-manifest.json").read_bytes()
    if hashlib.sha256(content).hexdigest() != match.group(1):
        raise SystemExit("[myshop-manifest-fetch] artifact manifest checksum mismatch")
    raw_path = temp / "release-manifest.json"
    validated = subprocess.run([sys.executable, str(Path(script_dir) / "validate-release-manifest.py"),
                                str(raw_path), registry, "myshop"], check=True, text=True,
                               capture_output=True, timeout=30)
    manifest = json.loads(validated.stdout)
    if manifest["source_sha"] != source_sha:
        raise SystemExit("[myshop-manifest-fetch] manifest source SHA does not match request")
    temporary = target.with_name(f".{target.name}.partial")
    temporary.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.chmod(temporary, 0o600)
    os.replace(temporary, target)

print(f"RELEASE_MANIFEST_FETCHED source_sha={source_sha} run={selected['id']} path={target}")
PY

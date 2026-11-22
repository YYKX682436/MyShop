#!/usr/bin/env python3
"""Classify changes since the most recent successful ancestor release."""
from __future__ import annotations

import json
import os
import subprocess
import sys

RELEASE_JOB = "Publish immutable production images"
WEB_ROOT_FILES = {"package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", "Dockerfile.web"}
DOCUMENT_FILES = {"README.md", "README.en.md", "AGENTS.md", "LICENSE", "THIRD_PARTY_NOTICES.md"}


def classify(paths: list[str]) -> dict[str, bool]:
    """Treat unrecognized files as affecting every check, not as safe to skip."""
    scope = {"api": False, "web": False, "compose": False, "runtime": False}
    for path in paths:
        if path.startswith("docs/") or path in DOCUMENT_FILES or path.rsplit("/", 1)[-1] in {"AGENTS.md", "CLAUDE.md"}:
            continue
        if path in {"scripts/ci-release-scope.py", "scripts/tests/test_ci_release_scope.py"}:
            return {key: True for key in scope}
        if path == "scripts/fetch-release-archives.py" or path.startswith("scripts/tests/") or path.startswith("scripts/test-"):
            scope["compose"] = True
            if path == "scripts/test-web-image-host-routing.sh":
                scope["web"] = scope["runtime"] = True
            continue
        if path.startswith("apps/api/src/test/"):
            scope["api"] = True
            continue
        if path.startswith("apps/web/") and (path.startswith("apps/web/scripts/") or path.endswith((".test.mjs", ".test.ts", ".test.tsx"))):
            scope["web"] = True
            continue
        scope["runtime"] = True
        if path.startswith("apps/web/") or path in WEB_ROOT_FILES:
            scope["web"] = True
            if path == "Dockerfile.web":
                scope["compose"] = True
        elif path.startswith("apps/api/"):
            # An API change can also change the contract consumed by the Web.
            scope["api"] = scope["web"] = True
            if path.startswith("apps/api/src/main/resources/db/"):
                scope["compose"] = True
        elif path.startswith(("scripts/", "ops/")) or path.startswith(("docker-compose", "Caddyfile")):
            scope["compose"] = True
        else:
            return {key: True for key in scope}
    return scope


def command(*args: str) -> str:
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout


def prior_release(repository: str, current_sha: str, current_run: str) -> tuple[str, int] | None:
    endpoint = f"repos/{repository}/actions/workflows/cicd.yml/runs?branch=main&status=success&per_page=50"
    runs = json.loads(command("gh", "api", endpoint))["workflow_runs"]
    for run in runs:
        sha, run_id = run.get("head_sha", ""), run.get("id")
        if not sha or sha == current_sha or str(run_id) == current_run:
            continue
        if subprocess.run(["git", "merge-base", "--is-ancestor", sha, current_sha],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10).returncode:
            continue
        jobs = json.loads(command("gh", "api", f"repos/{repository}/actions/runs/{run_id}/jobs?per_page=100"))["jobs"]
        if any(job.get("name") == RELEASE_JOB and job.get("conclusion") == "success" for job in jobs):
            return sha, run_id
    return None


def successful_release_for_sha(repository: str, source_sha: str, current_run: str) -> int | None:
    endpoint = (
        f"repos/{repository}/actions/workflows/cicd.yml/runs"
        f"?head_sha={source_sha}&status=success&per_page=100"
    )
    runs = json.loads(command("gh", "api", endpoint))["workflow_runs"]
    for run in runs:
        run_id = run.get("id")
        if str(run_id) == current_run or run.get("head_sha") != source_sha:
            continue
        jobs = json.loads(command("gh", "api", f"repos/{repository}/actions/runs/{run_id}/jobs?per_page=100"))["jobs"]
        if any(job.get("name") == RELEASE_JOB and job.get("conclusion") == "success" for job in jobs):
            return int(run_id)
    return None


def validate_manual_release(repository: str, source_sha: str, current_run: str, ref: str, attempt: str = "1") -> None:
    if attempt != "1":
        raise SystemExit("Refusing a workflow re-run; use a fresh workflow dispatch")
    if ref != "refs/heads/main":
        return
    ref_data = json.loads(command("gh", "api", f"repos/{repository}/git/ref/heads/main"))
    main_sha = ref_data.get("object", {}).get("sha", "")
    if main_sha != source_sha:
        raise SystemExit(f"Refusing stale release SHA {source_sha}; main is now {main_sha}")
    previous_run = successful_release_for_sha(repository, source_sha, current_run)
    if previous_run is not None:
        raise SystemExit(f"Release already succeeded for SHA {source_sha} in workflow run {previous_run}")


def main() -> None:
    # Fail closed: an unavailable API, an untrusted base, or an unexpected
    # repository state means all checks run and a release can still be built.
    scope = {key: True for key in ("api", "web", "compose", "runtime")}
    baseline = "none; running all checks"
    sha = os.environ["GITHUB_SHA"]
    repository = os.environ["GITHUB_REPOSITORY"]
    validate_manual_release(
        repository,
        sha,
        os.environ["GITHUB_RUN_ID"],
        os.environ.get("GITHUB_REF", ""),
        os.environ.get("GITHUB_RUN_ATTEMPT", "1"),
    )
    try:
        if command("git", "rev-parse", "HEAD").strip() != sha:
            raise ValueError("checkout does not match the workflow SHA")
        prior = prior_release(repository, sha, os.environ["GITHUB_RUN_ID"])
        if prior:
            base_sha, run_id = prior
            raw = subprocess.run(["git", "diff", "--name-only", "--no-renames", "-z", base_sha, sha],
                                 check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout
            paths = [path.decode("utf-8") for path in raw.split(b"\0") if path]
            scope = classify(paths)
            baseline = f"{base_sha} (successful release run {run_id})"
    except (KeyError, ValueError, OSError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print(f"CI scope fallback: {type(exc).__name__}: {exc}", file=sys.stderr)
    result = "".join(f"{key}={str(value).lower()}\n" for key, value in scope.items())
    print(f"validated baseline: {baseline}\n{result}", end="")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(result)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(f"### Release validation scope\n\nBaseline: `{baseline}`\n\n")
            summary.write("\n".join(f"- {key}: {'run' if value else 'unchanged'}" for key, value in scope.items()) + "\n")


if __name__ == "__main__":
    main()

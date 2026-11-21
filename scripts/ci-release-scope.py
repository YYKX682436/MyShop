#!/usr/bin/env python3
"""Reuse a previously successful full CI run when classifying release checks."""
from __future__ import annotations

import json
import os
import subprocess
import sys

REQUIRED_JOBS = {
    "API build and tests",
    "Web typecheck, lint, and build",
    "Compose configuration smoke test",
    "Publish immutable production images",
}
API_GATE_STEPS = {
    "Enforce PostgreSQL integration tests",
    "Build and test, including required PostgreSQL integration tests",
}
WEB_ROOT_FILES = {"package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", "Dockerfile.web"}
DOCUMENT_FILES = {"README.md", "README.en.md", "AGENTS.md", "LICENSE", "THIRD_PARTY_NOTICES.md"}


def classify(paths: list[str]) -> dict[str, bool]:
    """Treat unrecognized files as affecting every check, not as safe to skip."""
    scope = {"api": False, "web": False, "compose": False, "runtime": False}
    for path in paths:
        if path.startswith("docs/") or path in DOCUMENT_FILES:
            continue
        scope["runtime"] = True
        if path in {"scripts/ci-release-scope.py", "scripts/tests/test_ci_release_scope.py"}:
            return {key: True for key in scope}
        if path.startswith("apps/web/") or path in WEB_ROOT_FILES:
            scope["web"] = True
            if path == "Dockerfile.web":
                scope["compose"] = True
        elif path.startswith("apps/api/"):
            # An API change can also change the contract consumed by the Web.
            scope["api"] = scope["web"] = True
            if path.startswith("apps/api/src/main/resources/db/"):
                scope["compose"] = True
        elif path == "scripts/test-web-image-host-routing.sh":
            scope["web"] = scope["compose"] = True
        elif path.startswith(("scripts/", "ops/")) or path.startswith(("docker-compose", "Caddyfile")):
            scope["compose"] = True
        else:
            return {key: True for key in scope}
    return scope


def command(*args: str) -> str:
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout


def prior_full_run(repository: str, current_sha: str, current_run: str) -> tuple[str, int] | None:
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
        passed = {job["name"] for job in jobs if job.get("conclusion") == "success"}
        api_gate_passed = any(
            job.get("name") == "API build and tests" and
            any(step.get("name") in API_GATE_STEPS and step.get("conclusion") == "success"
                for step in job.get("steps", []))
            for job in jobs
        )
        if REQUIRED_JOBS <= passed and api_gate_passed:
            return sha, run_id
    return None


def main() -> None:
    # Fail closed: an unavailable API, an untrusted base, or an unexpected
    # repository state means all checks run and a release can still be built.
    scope = {key: True for key in ("api", "web", "compose", "runtime")}
    baseline = "none; running all checks"
    try:
        sha = os.environ["GITHUB_SHA"]
        if command("git", "rev-parse", "HEAD").strip() != sha:
            raise ValueError("checkout does not match the workflow SHA")
        prior = prior_full_run(os.environ["GITHUB_REPOSITORY"], sha, os.environ["GITHUB_RUN_ID"])
        if prior:
            base_sha, run_id = prior
            raw = subprocess.run(["git", "diff", "--name-only", "--no-renames", "-z", base_sha, sha],
                                 check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout
            paths = [path.decode("utf-8") for path in raw.split(b"\0") if path]
            scope = classify(paths)
            baseline = f"{base_sha} (successful full CI run {run_id})"
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

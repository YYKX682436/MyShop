#!/usr/bin/env python3
"""Select test and image-build scopes from the production-accepted release."""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

RELEASE_JOB = "Publish immutable release manifest"
WEB_ROOT_FILES = {
    "package.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", "Dockerfile.web",
    "tsconfig.json", "eslint.config.mjs", ".npmrc",
}
DOCUMENT_FILES = {"README.md", "README.en.md", "AGENTS.md", "LICENSE", "THIRD_PARTY_NOTICES.md"}
DEPLOYMENT_SCRIPTS = {
    "scripts/ci-release-scope.py", "scripts/tests/test_ci_release_scope.py",
    "scripts/deploy-production.sh", "scripts/myshop-compose.sh",
    "scripts/preflight-shared-server.sh", "scripts/write-deploy-state.sh",
    "scripts/validate-release-manifest.py", "scripts/fetch-release-manifest.sh",
    "scripts/create-offline-image-bundle.py", "scripts/verify-offline-image-bundle.py",
    "scripts/import-offline-image-bundle.sh", "scripts/cleanup-docker-retention.sh",
    "scripts/create-predeploy-backup.sh", "scripts/check-production-schema.sh",
}

SCOPE_KEYS = ("api_test", "web_test", "compose_test", "api_image", "web_image", "runtime", "migration",
              "api_full_test", "web_full_test")
SHA = re.compile(r"^[0-9a-f]{40}$")
CORE_PATH = re.compile(r"payment|balance|order|deliver|inventory|stock|licen[sc]|authoriz|auth/|concurr|transaction|refund", re.I)
WEB_STATIC = (".css", ".scss", ".png", ".jpg", ".jpeg", ".webp", ".avif", ".svg", ".ico", ".woff", ".woff2")


def all_scopes() -> dict[str, bool]:
    return {key: True for key in SCOPE_KEYS}


def classify(paths: list[str]) -> dict[str, bool]:
    """Unknown paths fail closed. Tests and image construction stay independent."""
    scope = {key: False for key in SCOPE_KEYS}
    for path in paths:
        basename = path.rsplit("/", 1)[-1]
        if path.startswith("docs/") or path in DOCUMENT_FILES or basename in {"AGENTS.md", "CLAUDE.md"}:
            continue
        if path.startswith(".github/") or path in {".dockerignore", "scripts/ci-release-scope.py", "scripts/tests/test_ci_release_scope.py"}:
            return all_scopes()

        if path.startswith("apps/api/src/test/"):
            scope["api_test"] = True
            continue
        if path.startswith("apps/web/") and (path.startswith("apps/web/scripts/") or path.endswith((".test.mjs", ".test.ts", ".test.tsx"))):
            scope["web_test"] = True
            continue

        if path.startswith("apps/api/src/main/"):
            scope["api_test"] = scope["web_test"] = scope["api_image"] = scope["runtime"] = True
            if CORE_PATH.search(path):
                scope["api_full_test"] = scope["web_full_test"] = True
            if path.startswith("apps/api/src/main/resources/db/"):
                scope["compose_test"] = scope["migration"] = scope["api_full_test"] = True
            continue
        if path == "apps/api/pom.xml":
            scope["api_test"] = scope["web_test"] = scope["api_image"] = scope["runtime"] = True
            scope["api_full_test"] = scope["web_full_test"] = True
            continue
        if path == "Dockerfile.api" or path.startswith("scripts/validate-api-build-artifact.py"):
            scope["api_test"] = scope["api_image"] = scope["runtime"] = True
            scope["api_full_test"] = True
            continue

        if path.startswith("apps/web/"):
            scope["web_image"] = scope["runtime"] = True
            if not path.endswith(WEB_STATIC):
                scope["web_test"] = True
            if CORE_PATH.search(path):
                scope["web_test"] = scope["web_full_test"] = True
            continue
        if path in WEB_ROOT_FILES:
            scope["web_test"] = scope["web_image"] = scope["runtime"] = True
            scope["web_full_test"] = True
            continue
        if path.startswith(("packages/", "apps/shared/")):
            scope["api_test"] = scope["web_test"] = scope["api_image"] = scope["web_image"] = scope["runtime"] = True
            scope["api_full_test"] = scope["web_full_test"] = True
            continue
        if path.startswith("scripts/tests/") or path.startswith("scripts/test-"):
            scope["compose_test"] = True
            if path == "scripts/test-web-image-host-routing.sh":
                scope["web_test"] = scope["web_image"] = scope["runtime"] = True
            continue
        if path in DEPLOYMENT_SCRIPTS or path.startswith(("ops/", "scripts/")) or path.startswith(("docker-compose", "Caddyfile")):
            scope["compose_test"] = scope["runtime"] = True
            continue

        return all_scopes()
    return scope


def targeted_api_tests(paths: list[str]) -> str:
    """Select tests referencing changed Java types; empty means full verification."""
    test_root = Path("apps/api/src/test/java")
    changed = {Path(path).stem for path in paths
               if path.startswith("apps/api/src/main/") and path.endswith(".java")}
    changed.update(Path(path).stem for path in paths
                   if path.startswith("apps/api/src/test/") and path.endswith("Test.java"))
    if not changed or not test_root.is_dir():
        return ""
    names = []
    patterns = [re.compile(rf"\b{re.escape(name)}\b") for name in changed]
    for test in test_root.rglob("*Test.java"):
        source = test.read_text(encoding="utf-8")
        if test.stem in changed or any(pattern.search(source) for pattern in patterns):
            names.append(test.stem)
    return ",".join(sorted(set(names))) if 0 < len(names) <= 20 else ""


def command(*args: str) -> str:
    return subprocess.run(args, check=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout


def prior_release(repository: str, current_sha: str, current_run: str) -> tuple[str, int] | None:
    endpoint = f"repos/{repository}/actions/workflows/cicd.yml/runs?branch=main&status=success&per_page=50"
    artifact_endpoint = f"repos/{repository}/actions/artifacts?per_page=100"
    with ThreadPoolExecutor(max_workers=2) as pool:
        runs_future = pool.submit(command, "gh", "api", endpoint)
        artifacts_future = pool.submit(command, "gh", "api", artifact_endpoint)
        runs = json.loads(runs_future.result())["workflow_runs"]
        artifacts = json.loads(artifacts_future.result())["artifacts"]

    manifest_runs: dict[int, tuple[str, str]] = {}
    for artifact in artifacts:
        workflow_run = artifact.get("workflow_run") or {}
        sha = workflow_run.get("head_sha", "")
        run_id = workflow_run.get("id")
        if (artifact.get("expired") or workflow_run.get("head_branch") != "main"
                or not isinstance(sha, str) or not run_id
                or artifact.get("name") != f"myshop-release-manifest-{sha}"):
            continue
        manifest_runs[int(run_id)] = (sha, artifact["name"])

    for run in runs:
        sha, run_id = run.get("head_sha", ""), run.get("id")
        if (not sha or sha == current_sha or str(run_id) == current_run
                or run.get("head_branch") != "main" or not run_id):
            continue
        artifact = manifest_runs.get(int(run_id))
        if not artifact or artifact[0] != sha:
            continue
        if subprocess.run(["git", "merge-base", "--is-ancestor", sha, current_sha],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10).returncode:
            continue
        return sha, int(run_id)
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
    scope = all_scopes()
    baseline = "none; running all checks and building both images"
    baseline_sha = ""
    baseline_run_id = ""
    api_filter = ""
    sha = os.environ["GITHUB_SHA"]
    repository = os.environ["GITHUB_REPOSITORY"]
    accepted_sha = os.environ.get("MYSHOP_ACCEPTED_SHA", "")
    if not SHA.fullmatch(accepted_sha):
        raise SystemExit("MYSHOP_ACCEPTED_SHA must be the observed production accepted SHA")
    validate_manual_release(repository, sha, os.environ["GITHUB_RUN_ID"],
                            os.environ.get("GITHUB_REF", ""), os.environ.get("GITHUB_RUN_ATTEMPT", "1"))
    try:
        if command("git", "rev-parse", "HEAD").strip() != sha:
            raise ValueError("checkout does not match the workflow SHA")
        if subprocess.run(["git", "merge-base", "--is-ancestor", accepted_sha, sha],
                          timeout=10).returncode:
            raise SystemExit("production accepted SHA is not an ancestor of this release")
        run_id = successful_release_for_sha(repository, accepted_sha, os.environ["GITHUB_RUN_ID"])
        if run_id:
            baseline_sha = accepted_sha
            baseline_run_id = str(run_id)
            raw = subprocess.run(["git", "diff", "--name-only", "--no-renames", "-z", baseline_sha, sha],
                                 check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30).stdout
            paths = [path.decode("utf-8") for path in raw.split(b"\0") if path]
            scope = classify(paths)
            baseline = f"{baseline_sha} (production accepted; CI run {run_id})"
            if scope["api_test"] and not scope["api_full_test"]:
                api_filter = targeted_api_tests(paths)
                if not api_filter:
                    scope["api_full_test"] = True
            else:
                api_filter = ""
        else:
            api_filter = ""
    except (KeyError, ValueError, OSError, subprocess.SubprocessError, json.JSONDecodeError) as exc:
        print(f"CI scope fallback: {type(exc).__name__}: {exc}", file=sys.stderr)

    result = "".join(f"{key}={str(value).lower()}\n" for key, value in scope.items())
    result += f"api_test_filter={api_filter}\n"
    result += f"baseline_sha={baseline_sha}\nbaseline_run_id={baseline_run_id}\n"
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

#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
python3 - "$repo_dir/scripts/preflight-shared-server.sh" <<'PY'
import ast
import os
import stat
import sys
import tempfile
from pathlib import Path

script = Path(sys.argv[1]).read_text(encoding="utf-8")
start = script.index("<<'PY'\n") + len("<<'PY'\n")
end = script.rindex("\nPY")
body = script[start:end]
tree = ast.parse(body, filename="preflight-shared-server-embedded")
functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in {"fail", "assert_no_symlink_components"}]
namespace = {"os": os, "stat": stat, "Path": Path}
exec(compile(ast.Module(body=functions, type_ignores=[]), "preflight-path-check", "exec"), namespace)
check = namespace["assert_no_symlink_components"]

with tempfile.TemporaryDirectory(prefix="myshop-preflight-path-") as temporary:
    root = Path(temporary).resolve()
    valid = root / "srv" / "myshop-friend"
    valid.mkdir(parents=True)
    assert check(valid, "valid dedicated path") == valid

    outside = root / "other-site"
    outside.mkdir()
    link = root / "srv" / "alias"
    link.symlink_to(outside, target_is_directory=True)
    try:
        check(link / "uploads", "symlinked upload path")
    except SystemExit as exc:
        assert "symlink component" in str(exc)
    else:
        raise AssertionError("symlinked data path was accepted")

    try:
        check(Path("relative/path"), "relative data path")
    except SystemExit as exc:
        assert "absolute path" in str(exc)
    else:
        raise AssertionError("relative data path was accepted")

assert 'str(configured_root) != "/srv/myshop-friend"' in body
assert 'script_dir = Path(sys.argv[6])' in body
assert 'verify_script = script_dir / "verify-offline-image-bundle.py"' in body
assert 'mode == "first-install"' in body and 'mode == "bootstrap-retry"' in body
assert 'if project_network_names != expected_networks' in body
assert 'docker", "port"' in body and '"ss", "-H", "-lnt"' in body
assert '(20, 10, 0)' in body and '(2, 20, 0)' in body
assert "network create" not in body and "volume rm" not in body and "system prune" not in body
print("shared preflight path normalization, symlink rejection, mode separation, Docker checks, and read-only invariants passed")
PY

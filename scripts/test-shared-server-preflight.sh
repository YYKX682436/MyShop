#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
python3 - "$repo_dir/scripts/preflight-shared-server.sh" <<'PY'
import ast
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
from pathlib import Path

script = Path(sys.argv[1]).read_text(encoding="utf-8")
start = script.index("<<'PY'\n") + len("<<'PY'\n")
end = script.rindex("\nPY")
body = script[start:end]
tree = ast.parse(body, filename="preflight-shared-server-embedded")
functions = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name in {"fail", "assert_no_symlink_components", "verify_recovery_records"}]
namespace = {"os": os, "stat": stat, "Path": Path, "hashlib": hashlib, "json": json, "re": re}
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

    runtime = root / "runtime"
    backups = root / "backups"
    (runtime / "candidates").mkdir(parents=True)
    backups.mkdir()
    active, failed = "a" * 40, "b" * 40
    marker = runtime / "candidates" / f"{failed}.failed.json"
    marker.write_text(json.dumps({"version": 1, "status": "FAILED", "source_sha": failed, "reason": "start"}))
    log = runtime / f"deploy-{failed}.log"
    log.write_text(f"CRITICAL_SCHEMA_ROLLBACK_BLOCKED source={failed} reason=start; database and recovery point preserved, application services stopped\n")
    dump = backups / f"myshop-friend-prod-predeploy-test-{failed}.dump"
    dump.write_bytes(b"verified recovery data")
    manifest = Path(str(dump) + ".manifest.json")
    manifest.write_text(json.dumps({
        "version": 1, "project": "myshop-friend-prod", "candidate_sha": failed,
        "source_active_sha": active, "filename": str(dump), "size": dump.stat().st_size,
        "database_identity": "myshop@db/myshop",
        "sha256": hashlib.sha256(dump.read_bytes()).hexdigest(), "validation": {"pg_restore_list": "passed"},
    }))
    verify = namespace["verify_recovery_records"]
    verify(runtime, backups, "myshop-friend-prod", active, failed, "myshop@db/myshop")
    for damage in ("backup", "log", "marker", "active", "database"):
        original_dump, original_log, original_marker = dump.read_bytes(), log.read_text(), marker.read_text()
        if damage == "backup": dump.write_bytes(b"corrupt recovery data")
        if damage == "log": log.write_text("rollback not verified\n")
        if damage == "marker": marker.write_text(json.dumps({"version": 1, "status": "FAILED", "source_sha": "c" * 40, "reason": "start"}))
        try:
            verify(runtime, backups, "myshop-friend-prod", failed if damage == "active" else active, failed,
                   "other@db/other" if damage == "database" else "myshop@db/myshop")
        except SystemExit:
            pass
        else:
            raise AssertionError(f"forward recovery accepted damaged {damage}")
        dump.write_bytes(original_dump); log.write_text(original_log); marker.write_text(original_marker)

assert 'str(configured_root) != "/srv/myshop-friend"' in body
assert 'script_dir = Path(sys.argv[6])' in body
assert 'MYSHOP_FORWARD_RECOVERY_BUNDLE_FILE' in body
assert 'edge_ip_range = ipaddress.ip_network(subnets[0].get("ip_range", ""), strict=True)' in body
assert 'if caddy_ip in edge_ip_range:' in body
assert 'configured subnet or dynamic IP range' in body
assert 'mode == "first-install"' in body and 'mode == "bootstrap-retry"' in body and 'mode == "forward-recovery"' in body
assert 'if project_network_names != expected_networks' in body
assert 'docker", "port"' in body and '"ss", "-H", "-lnt"' in body
assert '(20, 10, 0)' in body and '(2, 20, 0)' in body
assert "network create" not in body and "volume rm" not in body and "system prune" not in body
print("shared preflight path, forward-recovery evidence, mode separation, Docker checks, and read-only invariants passed")
PY

from __future__ import annotations

import importlib.util
import json
import stat
import tempfile
import unittest
from argparse import Namespace
from pathlib import Path
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("myshop_runtime_config_state", SCRIPTS / "runtime_config_state.py")
runtime = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(runtime)

SHA = "a" * 40
VALUES = {
    "SALES_CHANNEL_WORKER_BATCH_SIZE": "10",
    "SALES_CHANNEL_WORKER_ENABLED": "false",
    "SALES_CHANNEL_WORKER_INTERVAL_MS": "1000",
}


def manifest(changes: dict | None = None, source_sha: str = SHA) -> dict:
    return {
        "version": 1,
        "project": "myshop",
        "expected_active_source_sha": source_sha,
        "created_at": "2026-10-07T00:00:00Z",
        "changes": changes or {},
    }


def release_state() -> dict:
    return {
        "version": 1,
        "active": {
            "source_sha": SHA,
            "accepted_at": "2026-10-06T00:00:00Z",
            "images": {
                service: {
                    "ref": f"ghcr.io/yykx682436/myshop-{service}:{SHA}",
                    "digest": "sha256:" + ("1" if service == "api" else "2") * 64,
                }
                for service in ("api", "web")
            },
        },
        "rollback": [],
        "history": [],
    }


def docker_result(values: dict[str, str] = VALUES, image_ref: str | None = None) -> str:
    ref = image_ref or f"ghcr.io/yykx682436/myshop-api:{SHA}"
    return json.dumps({
        "Config": {"Image": ref, "Env": [f"{key}={value}" for key, value in values.items()]},
        "State": {"Status": "running"},
        "Image": "sha256:" + "3" * 64,
    })


class RuntimeConfigStateTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self) -> None:
        self.temp.cleanup()

    def write_manifest(self, data: dict, path: Path | None = None) -> Path:
        target = path or self.root / "runtime-config-manifest.json"
        target.write_text(json.dumps(data), encoding="utf-8")
        return target

    def write_release(self) -> Path:
        target = self.root / "deploy-state.json"
        target.write_text(json.dumps(release_state()), encoding="utf-8")
        return target

    def write_env(self, values: dict[str, str] = VALUES) -> Path:
        target = self.root / ".env"
        target.write_text(
            "DB_PASSWORD=do-not-print-this-secret\n"
            f"SALES_CHANNEL_WORKER_ENABLED={values['SALES_CHANNEL_WORKER_ENABLED']}\n"
            f"SALES_CHANNEL_WORKER_BATCH_SIZE={values['SALES_CHANNEL_WORKER_BATCH_SIZE']}\n"
            f"SALES_CHANNEL_WORKER_INTERVAL_MS={values['SALES_CHANNEL_WORKER_INTERVAL_MS']}\n",
            encoding="utf-8",
        )
        target.chmod(0o640)
        return target

    def write_state(self, path: Path, values: dict[str, str] = VALUES) -> dict:
        state, changed = runtime.promote_state(path, values, SHA)
        self.assertTrue(changed)
        return state

    def test_manifest_strict_schema_unknown_secret_and_duplicate_keys(self) -> None:
        valid = self.write_manifest(manifest())
        self.assertEqual({}, runtime.validate_manifest(valid)["changes"])
        bad_manifests = (
            {**manifest(), "unknown": "field"},
            manifest({"JWT_SECRET": {"from": "old", "to": "new"}}),
            manifest({"SALES_CHANNEL_WORKER_ENABLED": {"from": False, "to": "true"}}),
            manifest({"SALES_CHANNEL_WORKER_ENABLED": {"from": "false", "to": "TRUE"}}),
            manifest(source_sha="short"),
            {**manifest(), "version": True},
        )
        for value in bad_manifests:
            with self.subTest(value=value), self.assertRaises(runtime.RuntimeConfigError):
                runtime.validate_manifest(self.write_manifest(value))
        duplicate = self.root / "duplicate.json"
        duplicate.write_text(
            '{"version":1,"project":"myshop","expected_active_source_sha":"'
            + SHA
            + '","created_at":"2026-10-07T00:00:00Z","changes":{},'
            '"changes":{}}',
            encoding="utf-8",
        )
        with self.assertRaises(runtime.RuntimeConfigError) as raised:
            runtime.validate_manifest(duplicate)
        self.assertEqual("RUNTIME_CONFIG_MANIFEST_INVALID", raised.exception.code)

    def test_boolean_integer_types_and_bounds_are_strict(self) -> None:
        cases = (
            ("SALES_CHANNEL_WORKER_BATCH_SIZE", "0"),
            ("SALES_CHANNEL_WORKER_BATCH_SIZE", "101"),
            ("SALES_CHANNEL_WORKER_BATCH_SIZE", "01"),
            ("SALES_CHANNEL_WORKER_INTERVAL_MS", "99"),
            ("SALES_CHANNEL_WORKER_INTERVAL_MS", "60001"),
            ("SALES_CHANNEL_WORKER_INTERVAL_MS", 1000),
        )
        for key, value in cases:
            with self.subTest(key=key, value=value), self.assertRaises(runtime.RuntimeConfigError):
                runtime.validate_manifest(self.write_manifest(manifest({key: {"from": VALUES[key], "to": value}})))
        for value in ("TRUE", "1", "yes"):
            with self.subTest(value=value), self.assertRaises(runtime.RuntimeConfigError):
                runtime.validate_manifest(self.write_manifest(manifest({
                    "SALES_CHANNEL_WORKER_ENABLED": {"from": "false", "to": value}
                })))
        for value in ("1", "100"):
            manifest_value = self.write_manifest(manifest({
                "SALES_CHANNEL_WORKER_BATCH_SIZE": {"from": "10", "to": value}
            }))
            self.assertEqual(value, runtime.validate_manifest(manifest_value)["changes"]["SALES_CHANNEL_WORKER_BATCH_SIZE"]["to"])
        for value in ("100", "60000"):
            manifest_value = self.write_manifest(manifest({
                "SALES_CHANNEL_WORKER_INTERVAL_MS": {"from": "1000", "to": value}
            }))
            self.assertEqual(value, runtime.validate_manifest(manifest_value)["changes"]["SALES_CHANNEL_WORKER_INTERVAL_MS"]["to"])

    def test_env_duplicate_or_missing_managed_key_fails_closed(self) -> None:
        duplicate = (
            b"SALES_CHANNEL_WORKER_ENABLED=false\n"
            b"export SALES_CHANNEL_WORKER_ENABLED=false\n"
            b"SALES_CHANNEL_WORKER_BATCH_SIZE=10\n"
            b"SALES_CHANNEL_WORKER_INTERVAL_MS=1000\n"
        )
        with self.assertRaises(runtime.RuntimeConfigError):
            runtime.parse_env_bytes(duplicate)
        with self.assertRaises(runtime.RuntimeConfigError):
            runtime.parse_env_bytes(b"SALES_CHANNEL_WORKER_ENABLED=false\n")

    def test_source_sha_mismatch_rejected(self) -> None:
        env = self.write_env()
        args = Namespace(
            manifest=str(self.write_manifest(manifest(source_sha="b" * 40))),
            release_state=str(self.write_release()), runtime_state=str(self.root / "runtime-config-state.json"),
            service="api", container_id="api-1", docker="docker", env_file=str(env),
            candidate_env=str(self.root / "candidate.env"),
        )
        with patch.object(runtime.subprocess, "check_output", return_value=docker_result()):
            with self.assertRaises(runtime.RuntimeConfigError) as raised:
                runtime.prepare_plan(args)
        self.assertEqual("ACTIVE_RELEASE_MISMATCH", raised.exception.code)

    def test_baseline_env_container_drift_rejected(self) -> None:
        env = self.write_env()
        changed = dict(VALUES)
        changed["SALES_CHANNEL_WORKER_BATCH_SIZE"] = "11"
        args = Namespace(
            manifest=str(self.write_manifest(manifest())), release_state=str(self.write_release()),
            runtime_state=str(self.root / "runtime-config-state.json"), service="api",
            container_id="api-1", docker="docker", env_file=str(env), candidate_env=str(self.root / "candidate.env"),
        )
        with patch.object(runtime.subprocess, "check_output", return_value=docker_result(changed)):
            with self.assertRaises(runtime.RuntimeConfigError) as raised:
                runtime.prepare_plan(args)
        self.assertEqual("RUNTIME_CONFIG_BASELINE_DRIFT", raised.exception.code)
        self.assertFalse((self.root / "runtime-config-state.json").exists())

    def test_current_value_must_match_declared_from(self) -> None:
        env = self.write_env()
        state_path = self.root / "runtime-config-state.json"
        self.write_state(state_path)
        args = Namespace(
            manifest=str(self.write_manifest(manifest({
                "SALES_CHANNEL_WORKER_BATCH_SIZE": {"from": "9", "to": "1"}
            }))),
            release_state=str(self.write_release()), runtime_state=str(state_path), service="api",
            container_id="api-1", docker="docker", env_file=str(env), candidate_env=str(self.root / "candidate.env"),
        )
        with patch.object(runtime.subprocess, "check_output", return_value=docker_result()):
            with self.assertRaises(runtime.RuntimeConfigError) as raised:
                runtime.prepare_plan(args)
        self.assertEqual("RUNTIME_CONFIG_FROM_MISMATCH", raised.exception.code)

    def test_bootstrap_atomic_state_and_monotonic_revisions(self) -> None:
        path = self.root / "runtime-config-state.json"
        first = self.write_state(path)
        self.assertEqual(1, first["active"]["revision"])
        self.assertEqual(0o640, stat.S_IMODE(path.stat().st_mode))
        before = path.read_bytes()
        same, changed = runtime.promote_state(path, VALUES, SHA)
        self.assertFalse(changed)
        self.assertEqual(1, same["active"]["revision"])
        self.assertEqual(before, path.read_bytes())
        next_values = {**VALUES, "SALES_CHANNEL_WORKER_BATCH_SIZE": "1"}
        second, changed = runtime.promote_state(path, next_values, SHA)
        self.assertTrue(changed)
        self.assertEqual(2, second["active"]["revision"])
        self.assertEqual([1], [item["revision"] for item in second["history"]])
        self.assertEqual(runtime.config_digest(next_values), second["active"]["config_digest"])
        self.assertFalse(list(self.root.glob(".runtime-config-state.json.*")))

    def test_digest_is_canonical_and_state_rejects_digest_mismatch(self) -> None:
        self.assertEqual(runtime.config_digest(VALUES), runtime.config_digest(dict(reversed(list(VALUES.items())))))
        state, _ = runtime.promote_state(self.root / "state.json", VALUES, SHA)
        state["active"]["config_digest"] = "sha256:" + "0" * 64
        with self.assertRaises(runtime.RuntimeConfigError):
            runtime.validate_state(state)

    def test_candidate_env_is_private_and_never_echoes_full_env(self) -> None:
        env = self.write_env()
        runtime_state = self.root / "runtime-config-state.json"
        self.write_state(runtime_state)
        manifest_path = self.write_manifest(manifest({
            "SALES_CHANNEL_WORKER_BATCH_SIZE": {"from": "10", "to": "1"}
        }))
        candidate = self.root / "candidate.env"
        args = Namespace(
            manifest=str(manifest_path), release_state=str(self.write_release()), runtime_state=str(runtime_state),
            service="api", container_id="api-1", docker="docker", env_file=str(env), candidate_env=str(candidate),
        )
        with patch.object(runtime.subprocess, "check_output", return_value=docker_result()):
            plan = runtime.prepare_plan(args)
        self.assertNotIn("do-not-print-this-secret", json.dumps(plan))
        self.assertEqual(0o600, stat.S_IMODE(candidate.stat().st_mode))
        self.assertIn("DB_PASSWORD=do-not-print-this-secret", candidate.read_text(encoding="utf-8"))

    def test_release_runtime_config_drift_guard_checks_env_and_container(self) -> None:
        state_path = self.root / "runtime-config-state.json"
        self.write_state(state_path)
        env = self.write_env()
        changed = dict(VALUES)
        changed["SALES_CHANNEL_WORKER_ENABLED"] = "true"
        with patch.object(runtime.subprocess, "check_output", return_value=docker_result(changed)):
            with self.assertRaises(runtime.RuntimeConfigError) as raised:
                runtime.verify_state_baseline(state_path, env, "api-1", "docker")
        self.assertEqual("RUNTIME_CONFIG_DRIFT", raised.exception.code)
        deploy_source = (SCRIPTS / "deploy-production.sh").read_text(encoding="utf-8")
        self.assertIn('RUNTIME_CONFIG_TOOL=${MYSHOP_RUNTIME_CONFIG_TOOL:-$SCRIPT_DIR/runtime_config_state.py}', deploy_source)
        self.assertIn('verify-baseline --runtime-state "$RUNTIME_CONFIG_STATE"', deploy_source)
        self.assertLess(deploy_source.index("RUNTIME_CONFIG_DRIFT"), deploy_source.index('run_compose up -d'))

    def test_side_effect_evidence_is_atomic_and_contains_only_safe_counts(self) -> None:
        state_path = self.root / "runtime-config-state.json"
        self.write_state(state_path)
        manifest_path = self.write_manifest(manifest())
        before = json.dumps({"attempted_ensure_total": 0, "agent_operation_total": 0})
        after = json.dumps({"attempted_ensure_total": 1, "agent_operation_total": 1})
        evidence = self.root / "candidates" / "boundary.json"
        result = runtime.record_boundary(evidence, manifest_path, SHA, before, after, state_path)
        self.assertEqual("RUNTIME_CONFIG_SIDE_EFFECT_BOUNDARY_CROSSED", result["status"])
        self.assertEqual(0o640, stat.S_IMODE(evidence.stat().st_mode))
        self.assertNotIn("do-not-print-this-secret", evidence.read_text(encoding="utf-8"))
        self.assertFalse(list(evidence.parent.glob(".boundary.json.*")))

    def test_env_atomic_replacement_preserves_mode_owner_group(self) -> None:
        env = self.write_env()
        candidate = self.root / "candidate.env"
        candidate.write_text(
            "DB_PASSWORD=do-not-print-this-secret\nSALES_CHANNEL_WORKER_ENABLED=true\n"
            "SALES_CHANNEL_WORKER_BATCH_SIZE=1\nSALES_CHANNEL_WORKER_INTERVAL_MS=1000\n",
            encoding="utf-8",
        )
        candidate.chmod(0o600)
        before = env.stat()
        runtime.atomic_install_env(candidate, env)
        after = env.stat()
        self.assertEqual((before.st_uid, before.st_gid, stat.S_IMODE(before.st_mode)),
                         (after.st_uid, after.st_gid, stat.S_IMODE(after.st_mode)))
        values, _ = runtime.parse_env_bytes(env.read_bytes())
        self.assertEqual("true", values["SALES_CHANNEL_WORKER_ENABLED"])
        self.assertEqual("1", values["SALES_CHANNEL_WORKER_BATCH_SIZE"])


if __name__ == "__main__":
    unittest.main()

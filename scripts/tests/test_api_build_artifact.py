from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

VALIDATOR = Path(__file__).resolve().parents[1] / "validate-api-build-artifact.py"


class ApiBuildArtifactTests(unittest.TestCase):
    def make_artifact(self, root: Path, source_sha: str = "a" * 40) -> None:
        contract = json.dumps({"version": 1, "tables": {}}, sort_keys=True).encode()
        with zipfile.ZipFile(root / "app.jar", "w") as archive:
            archive.writestr("BOOT-INF/classes/db/production-schema-contract.json", contract)
        (root / "production-schema-contract.json").write_bytes(contract)
        jar_sha = hashlib.sha256((root / "app.jar").read_bytes()).hexdigest()
        contract_sha = hashlib.sha256(contract).hexdigest()
        (root / "api-build-manifest.json").write_text(json.dumps({
            "version": 1,
            "source_sha": source_sha,
            "jar_sha256": jar_sha,
            "schema_contract_sha256": contract_sha,
        }), encoding="utf-8")

    def run_validator(self, root: Path, sha: str = "a" * 40) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(VALIDATOR), str(root), sha], capture_output=True, text=True)

    def test_exact_jar_and_embedded_contract_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_artifact(root)
            result = self.run_validator(root)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn('"source_sha": "' + "a" * 40, result.stdout)

    def test_wrong_sha_and_changed_jar_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_artifact(root)
            self.assertNotEqual(self.run_validator(root, "b" * 40).returncode, 0)
            (root / "app.jar").write_bytes(b"changed")
            self.assertNotEqual(self.run_validator(root).returncode, 0)

    def test_separate_contract_must_match_jar_resource(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.make_artifact(root)
            (root / "production-schema-contract.json").write_text('{"version":1}', encoding="utf-8")
            result = self.run_validator(root)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("does not match", result.stderr)


if __name__ == "__main__":
    unittest.main()

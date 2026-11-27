from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from datetime import datetime, timezone
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]
REGISTRY = "ghcr.io/yykx682436"
SOURCE = "a" * 40
BASE = "b" * 40
API_DIGEST = "sha256:" + "1" * 64
WEB_DIGEST = "sha256:" + "2" * 64


def image(service: str, digest: str, source_sha: str) -> dict:
    return {"ref": f"{REGISTRY}/myshop-{service}@{digest}", "digest": digest, "source_sha": source_sha}


class ReleaseManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def write_manifest(self, name: str, value: dict) -> Path:
        path = self.root / name
        path.write_text(json.dumps(value), encoding="utf-8")
        return path

    def run_validator(self, path: Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, str(SCRIPTS / "validate-release-manifest.py"),
                               str(path), REGISTRY, "myshop"], text=True, capture_output=True)

    def run_creator(self, **values: str) -> tuple[subprocess.CompletedProcess[str], Path]:
        output = self.root / "release.json"
        env = {
            **os.environ,
            "SOURCE_SHA": SOURCE,
            "BASELINE_SHA": BASE,
            "ACCEPTED_SHA": BASE,
            "API_BUILD": "true",
            "API_DIGEST": API_DIGEST,
            "WEB_BUILD": "false",
            "WEB_DIGEST": "",
            "DATABASE_MIGRATION": "false",
            "BASELINE_FILE": str(self.root / "baseline.json"),
            "OUTPUT_FILE": str(output),
            **values,
        }
        result = subprocess.run([sys.executable, str(SCRIPTS / "create-release-manifest.py")],
                                env=env, text=True, capture_output=True)
        return result, output

    def test_manifest_tracks_mixed_image_source_shas(self):
        baseline = {
            "version": 2, "project": "myshop", "source_sha": BASE,
            "created_at": "2026-11-20T00:00:00Z", "built_services": ["web"],
            "database_migration": False,
            "images": {"api": image("api", API_DIGEST, BASE), "web": image("web", WEB_DIGEST, BASE)},
        }
        path = self.write_manifest("baseline.json", baseline)
        result, output = self.run_creator(BASELINE_FILE=str(path))
        self.assertEqual(result.returncode, 0, result.stderr)
        release = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(release["source_sha"], SOURCE)
        self.assertEqual(release["accepted_sha"], BASE)
        self.assertEqual(release["version"], 3)
        self.assertEqual(release["built_services"], ["api"])
        self.assertEqual(release["images"]["api"]["source_sha"], SOURCE)
        self.assertEqual(release["images"]["web"]["source_sha"], BASE)
        validation = self.run_validator(output)
        self.assertEqual(validation.returncode, 0, validation.stderr)

    def test_first_release_requires_both_service_images(self):
        result, _ = self.run_creator(BASELINE_SHA="", API_BUILD="true", WEB_BUILD="false")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("both service images", result.stderr)

    def test_mismatched_production_baseline_is_rejected(self):
        result, _ = self.run_creator(ACCEPTED_SHA="c" * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("baseline must equal", result.stderr)

    def test_v1_full_sha_manifest_remains_readable(self):
        old = {
            "version": 1, "project": "myshop", "source_sha": SOURCE,
            "created_at": "2026-11-20T00:00:00Z",
            "images": {service: {"ref": f"{REGISTRY}/myshop-{service}:{SOURCE}",
                                  "digest": API_DIGEST if service == "api" else WEB_DIGEST}
                       for service in ("api", "web")},
        }
        result = self.run_validator(self.write_manifest("legacy.json", old))
        self.assertEqual(result.returncode, 0, result.stderr)
        normalized = json.loads(result.stdout)
        self.assertEqual(normalized["images"]["api"]["source_sha"], SOURCE)
        self.assertTrue(normalized["database_migration"])

    def test_manifest_ref_digest_and_built_services_are_fail_closed(self):
        value = {
            "version": 2, "project": "myshop", "source_sha": SOURCE,
            "created_at": datetime.now(timezone.utc).isoformat(), "built_services": ["api"],
            "database_migration": False,
            "images": {"api": image("api", API_DIGEST, SOURCE), "web": image("web", WEB_DIGEST, BASE)},
        }
        self.assertEqual(self.run_validator(self.write_manifest("valid.json", value)).returncode, 0)
        value["images"]["web"]["ref"] = f"{REGISTRY}/myshop-web:{SOURCE}"
        result = self.run_validator(self.write_manifest("bad-ref.json", value))
        self.assertNotEqual(result.returncode, 0)
        value["images"]["web"] = image("web", WEB_DIGEST, BASE)
        value["built_services"] = ["api", "unknown"]
        result = self.run_validator(self.write_manifest("bad-scope.json", value))
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()

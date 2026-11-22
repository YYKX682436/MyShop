"""The importer may reuse infrastructure only after its identity is verified."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class ImportOfflineImagesTest(unittest.TestCase):
    def run_import(self, failed_records):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            scripts = root / "scripts"
            scripts.mkdir()
            shutil.copy(ROOT / "scripts/import-offline-image-bundle.sh", scripts)
            verifier = scripts / "verify-offline-image-bundle.py"
            verifier.write_text("""import os, pathlib, sys
if '--record-loaded-image-ids' in sys.argv:
    counter = pathlib.Path(os.environ['COUNTER'])
    count = int(counter.read_text()) if counter.exists() else 0
    counter.write_text(str(count + 1))
    if count < int(os.environ['FAILED_RECORDS']):
        raise SystemExit(1)
if '--verify-local' in sys.argv:
    pathlib.Path(os.environ['VERIFIED']).touch()
""")
            bin_dir = root / "bin"
            bin_dir.mkdir()
            for name, content in {
                "id": "#!/bin/sh\necho 0\n",
                "docker": "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \"$DOCKER_LOG\"\n",
                "install": "#!/usr/bin/env python3\nimport shutil,sys\nshutil.copyfile(sys.argv[-2],sys.argv[-1])\n",
            }.items():
                path = bin_dir / name
                path.write_text(content)
                path.chmod(0o755)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"source_sha": "a" * 40}))
            bundle = root / "bundle.json"
            services = ("api", "web", "postgres", "caddy", "node-exporter")
            bundle.write_text(json.dumps({"images": {name: {"archive": name + ".tar"} for name in services}}))
            archives = root / "archives"
            archives.mkdir()
            env = {**os.environ, "PATH": str(bin_dir) + os.pathsep + os.environ["PATH"],
                   "COUNTER": str(root / "counter"), "VERIFIED": str(root / "verified"),
                   "DOCKER_LOG": str(root / "docker.log"), "FAILED_RECORDS": str(failed_records)}
            result = subprocess.run(["bash", str(scripts / "import-offline-image-bundle.sh"),
                                     str(manifest), str(bundle), str(archives)], env=env,
                                    text=True, capture_output=True)
            loads = (root / "docker.log").read_text().splitlines()
            return result, [Path(line.split()[-1]).name for line in loads], (root / "verified").exists()

    def test_matching_infrastructure_is_reused(self):
        result, loads, verified = self.run_import(0)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(loads, ["api.tar", "web.tar"])
        self.assertTrue(verified)

    def test_unverified_infrastructure_is_loaded_and_checked_again(self):
        result, loads, verified = self.run_import(1)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(loads, ["api.tar", "web.tar", "postgres.tar", "caddy.tar", "node-exporter.tar"])
        self.assertTrue(verified)

    def test_mismatch_after_import_fails_closed(self):
        result, _, verified = self.run_import(2)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("runtime configuration or layers differ", result.stderr)
        self.assertFalse(verified)


if __name__ == "__main__":
    unittest.main()

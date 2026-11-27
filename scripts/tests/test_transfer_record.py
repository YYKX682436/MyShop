from __future__ import annotations

import hashlib
import io
import json
import os
import subprocess
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "create-transfer-record.py"
SHA = "a" * 40
DIGEST = "sha256:" + "b" * 64
TAG = f"ghcr.io/yykx682436/myshop-api:{SHA}"


class TransferRecordTests(unittest.TestCase):
    def test_inspect_only_defaults_do_not_change_export_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            config = {"os": "linux", "architecture": "amd64",
                      "config": {"User": "10001:10001", "Entrypoint": ["java", "-jar", "/app/app.jar"],
                                 "Labels": {"org.opencontainers.image.revision": SHA}},
                      "rootfs": {"diff_ids": ["sha256:" + "c" * 64]}}
            payload = json.dumps(config, sort_keys=True).encode()
            image_id = "sha256:" + hashlib.sha256(payload).hexdigest()
            archive = root / "api.tar"
            with tarfile.open(archive, "w:") as tar:
                for name, content in (("manifest.json", json.dumps([{
                    "Config": "config.json", "RepoTags": [TAG], "Layers": ["layer.tar"]}]).encode()),
                                      ("config.json", payload)):
                    item = tarfile.TarInfo(name)
                    item.size = len(content)
                    tar.addfile(item, io.BytesIO(content))
            inspected = {"Id": image_id, "Os": "linux", "Architecture": "amd64",
                         "Config": {**config["config"], "AttachStdin": False, "Hostname": ""},
                         "RootFS": {"Layers": config["rootfs"]["diff_ids"]},
                         "RepoDigests": [f"ghcr.io/yykx682436/myshop-api@{DIGEST}"]}
            metadata = root / "image.json"
            metadata.write_text(json.dumps([inspected]))
            fake_bin = root / "bin"
            fake_bin.mkdir()
            docker = fake_bin / "docker"
            docker.write_text("#!/usr/bin/env python3\nimport os,sys\nprint(open(os.environ['FAKE_IMAGE']).read())\n")
            docker.chmod(0o755)
            env = {**os.environ, "PATH": str(fake_bin) + os.pathsep + os.environ["PATH"],
                   "FAKE_IMAGE": str(metadata)}
            record = root / "record.json"
            command = [sys.executable, str(SCRIPT), "api", SHA, DIGEST, str(archive), str(record)]
            passed = subprocess.run(command, env=env, text=True, capture_output=True)
            self.assertEqual(passed.returncode, 0, passed.stderr)
            self.assertEqual(json.loads(record.read_text())["image_id"], image_id)
            inspected["Id"] = "sha256:" + "d" * 64
            metadata.write_text(json.dumps([inspected]))
            exported_variant = subprocess.run(command, env=env, text=True, capture_output=True)
            self.assertEqual(exported_variant.returncode, 0, exported_variant.stderr)
            self.assertEqual(json.loads(record.read_text())["image_id"], inspected["Id"])
            self.assertEqual(json.loads(record.read_text())["exported_config_digest"], image_id)
            inspected["Config"]["User"] = "root"
            metadata.write_text(json.dumps([inspected]))
            failed = subprocess.run(command, env=env, text=True, capture_output=True)
            self.assertNotEqual(failed.returncode, 0)
            self.assertIn("keys=['AttachStdin', 'Hostname', 'User']", failed.stderr)


if __name__ == "__main__":
    unittest.main()

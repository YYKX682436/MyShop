"""Emergency offline exports reuse validated registry digests without building."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
EXPORTER = ROOT / "scripts/export-offline-release.sh"
REGISTRY = "ghcr.io/yykx682436"
SOURCE_SHA = "a" * 40
API_SHA = "b" * 40
API_DIGEST = "sha256:" + "1" * 64
WEB_DIGEST = "sha256:" + "2" * 64
INFRA = {
    "postgres": ("mirror-cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685", "cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685"),
    "caddy": ("mirror-5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648", "5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648"),
    "node-exporter": ("mirror-1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0", "1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0"),
}


def docker_archive(path: Path, tag: str, marker: str) -> None:
    config = {
        "os": "linux", "architecture": "amd64", "config": {},
        "rootfs": {"type": "layers", "diff_ids": ["sha256:" + marker * 64]},
    }
    with tarfile.open(path, "w:") as archive:
        for name, payload in (
            ("manifest.json", json.dumps([{"Config": "config.json", "RepoTags": [tag], "Layers": ["layer/layer.tar"]}]).encode()),
            ("config.json", json.dumps(config, sort_keys=True).encode()),
            ("layer/layer.tar", b"fixture-layer"),
        ):
            info = tarfile.TarInfo(name)
            info.size = len(payload)
            archive.addfile(info, __import__("io").BytesIO(payload))


class OfflineExportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.fixtures = self.root / "fixtures"
        self.fixtures.mkdir()
        self.output = self.root / "offline"
        self.manifest = self.root / "release-manifest.json"
        payload = {
            "version": 2, "project": "myshop", "source_sha": SOURCE_SHA,
            "created_at": "2026-11-25T00:00:00Z", "built_services": ["web"],
            "database_migration": False,
            "images": {
                "api": {"ref": f"{REGISTRY}/myshop-api@{API_DIGEST}", "digest": API_DIGEST, "source_sha": API_SHA},
                "web": {"ref": f"{REGISTRY}/myshop-web@{WEB_DIGEST}", "digest": WEB_DIGEST, "source_sha": SOURCE_SHA},
            },
        }
        self.manifest.write_text(json.dumps(payload), encoding="utf-8")
        refs = {
            "api": f"{REGISTRY}/myshop-api:{API_SHA}",
            "web": f"{REGISTRY}/myshop-web:{SOURCE_SHA}",
            **{service: f"{REGISTRY}/myshop-{service}:{tag}" for service, (tag, _) in INFRA.items()},
        }
        for index, (service, tag) in enumerate(refs.items()):
            docker_archive(self.fixtures / f"{service}.tar", tag, hex(index + 1)[-1])
        self.docker_log = self.root / "docker.log"
        fake_docker = self.bin / "docker"
        fake_docker.write_text(r'''#!/usr/bin/env python3
import os, shutil, sys
from pathlib import Path
args=sys.argv[1:]
with open(os.environ['FAKE_DOCKER_LOG'], 'a', encoding='utf-8') as log:
    log.write(' '.join(args) + '\n')
if args[0] == 'pull':
    sys.exit(0)
if args[:2] == ['image', 'inspect']:
    ref=args[-1]
    if '@' not in ref: raise SystemExit('inspection must use a digest-pinned ref')
    print(ref)
    sys.exit(0)
if args[0] == 'tag':
    sys.exit(0)
if args[0] == 'save':
    target=Path(args[args.index('--output')+1])
    service=args[-1].split('/')[-1].split(':')[0].removeprefix('myshop-')
    shutil.copyfile(Path(os.environ['FAKE_FIXTURE_DIR']) / (service + '.tar'), target)
    sys.exit(0)
raise SystemExit('unexpected Docker command: ' + repr(args))
''', encoding="utf-8")
        fake_docker.chmod(0o755)

    def tearDown(self):
        self.temp.cleanup()

    def run_export(self, **extra):
        return subprocess.run(
            ["bash", str(EXPORTER), str(self.manifest), str(self.output)],
            env={**os.environ, "PATH": f"{self.bin}:{os.environ['PATH']}",
                 "FAKE_DOCKER_LOG": str(self.docker_log), "FAKE_FIXTURE_DIR": str(self.fixtures), **extra},
            text=True, capture_output=True, timeout=30,
        )

    def test_exports_exact_successful_digests_and_never_builds_or_pushes(self):
        result = self.run_export()
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        bundle = json.loads((self.output / "bundle.json").read_text(encoding="utf-8"))
        self.assertEqual(bundle["version"], 4)
        self.assertEqual(bundle["source_sha"], SOURCE_SHA)
        self.assertEqual(bundle["images"]["api"]["source_sha"], API_SHA)
        self.assertEqual(bundle["images"]["web"]["source_sha"], SOURCE_SHA)
        commands = self.docker_log.read_text(encoding="utf-8").splitlines()
        self.assertEqual(sum(line.startswith("pull ") for line in commands), 2)
        self.assertTrue(all("@sha256:" in line for line in commands if line.startswith("pull ")))
        self.assertEqual(sum(line.startswith("save ") for line in commands), 2)
        self.assertFalse(any(line.startswith(("build", "push")) for line in commands))
        self.assertEqual(len((self.output / "SHA256SUMS").read_text().splitlines()), 4)

    def test_registry_digest_mismatch_fails_before_export(self):
        fake = self.bin / "docker"
        fake.write_text(fake.read_text(encoding="utf-8").replace("print(ref)", "print(ref.replace('1' * 64, 'f' * 64))"), encoding="utf-8")
        result = self.run_export()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("RepoDigest differs", result.stderr)
        self.assertFalse((self.output / "api.tar").exists())


if __name__ == "__main__":
    unittest.main()

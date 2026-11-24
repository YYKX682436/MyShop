from __future__ import annotations

import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
FETCHER = REPO / "scripts/fetch-release-manifest.sh"
WORKFLOW = REPO / ".github/workflows/cicd.yml"
OFFLINE_WORKFLOW = REPO / ".github/workflows/export-offline-release.yml"
SOURCE_SHA = "a" * 40
BASE_SHA = "b" * 40
API_DIGEST = "sha256:" + "c" * 64
WEB_DIGEST = "sha256:" + "d" * 64
REGISTRY = "ghcr.io/yykx682436"


def manifest() -> dict:
    return {
        "version": 2,
        "project": "myshop",
        "source_sha": SOURCE_SHA,
        "created_at": "2026-11-24T00:00:00Z",
        "built_services": ["web"],
        "database_migration": False,
        "images": {
            "api": {"ref": f"{REGISTRY}/myshop-api@{API_DIGEST}", "digest": API_DIGEST, "source_sha": BASE_SHA},
            "web": {"ref": f"{REGISTRY}/myshop-web@{WEB_DIGEST}", "digest": WEB_DIGEST, "source_sha": SOURCE_SHA},
        },
    }


class ManifestTransportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.artifact = self.root / "artifact"
        self.artifact.mkdir()
        data = json.dumps(manifest(), indent=2, sort_keys=True).encode() + b"\n"
        (self.artifact / "release-manifest.json").write_bytes(data)
        checksum = hashlib.sha256(data).hexdigest()
        (self.artifact / "SHA256SUMS").write_text(f"{checksum}  release-manifest.json\n", encoding="utf-8")
        self.gh_log = self.root / "gh.log"
        self.make_fake_gh()

    def tearDown(self):
        self.temp.cleanup()

    def make_fake_gh(self):
        self.fake_gh = self.bin_dir / "gh"
        self.fake_gh.write_text("""#!/usr/bin/env python3
import json, os, shutil, sys
from pathlib import Path
args=sys.argv[1:]
with open(os.environ['FAKE_GH_LOG'],'a',encoding='utf-8') as f: f.write(' '.join(args)+'\\n')
if args[0]=='api':
    endpoint=args[1]
    if endpoint.endswith('/artifacts'):
        print(json.dumps({'artifacts':[{'name':'myshop-release-manifest-'+os.environ['FAKE_SHA'],'expired':os.environ.get('FAKE_EXPIRED')=='1'}]}))
    elif '/jobs?' in endpoint:
        print(json.dumps({'jobs':[{'name':'Publish immutable release manifest','conclusion':'success'}]}))
    else:
        print(json.dumps({'workflow_runs':[{'id':42,'head_sha':os.environ['FAKE_SHA'],'conclusion':'success'}]}))
elif args[0]=='run' and args[1]=='download':
    dest=Path(args[args.index('--dir')+1])
    shutil.copytree(os.environ['FAKE_ARTIFACT_DIR'],dest,dirs_exist_ok=True)
else:
    raise SystemExit(2)
""", encoding="utf-8")
        self.fake_gh.chmod(0o755)

    def run_fetch(self, sha: str = SOURCE_SHA, output: Path | None = None, **extra_env):
        output = output or self.root / "release-manifest.json"
        env = {
            **os.environ,
            "PATH": f"{self.bin_dir}:{os.environ['PATH']}",
            "FAKE_GH_LOG": str(self.gh_log),
            "FAKE_ARTIFACT_DIR": str(self.artifact),
            "FAKE_SHA": SOURCE_SHA,
            **extra_env,
        }
        return subprocess.run(["bash", str(FETCHER), sha, str(output)], env=env, text=True, capture_output=True), output

    def test_manifest_artifact_fetch_is_private_atomic_and_docker_free(self):
        output = self.root / "secure" / "release-manifest.json"
        output.parent.mkdir()
        output.write_text("old", encoding="utf-8")
        result, _ = self.run_fetch(output=output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(output.read_text(encoding="utf-8")), manifest())
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        calls = self.gh_log.read_text(encoding="utf-8")
        self.assertIn("run download 42", calls)
        self.assertNotIn("docker", calls)

    def test_invalid_sha_and_missing_artifact_do_not_replace_output(self):
        output = self.root / "preserved.json"
        output.write_text("preserve", encoding="utf-8")
        result, _ = self.run_fetch("a" * 39, output)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output.read_text(encoding="utf-8"), "preserve")
        result, _ = self.run_fetch(output=output, FAKE_EXPIRED="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output.read_text(encoding="utf-8"), "preserve")

    def test_checksum_mismatch_fails_closed(self):
        (self.artifact / "SHA256SUMS").write_text("0" * 64 + "  release-manifest.json\n", encoding="utf-8")
        output = self.root / "preserved.json"
        output.write_text("preserve", encoding="utf-8")
        result, _ = self.run_fetch(output=output)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output.read_text(encoding="utf-8"), "preserve")

    def test_release_artifact_is_small_manifest_and_workflow_is_manual(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("group: myshop-manual-release", workflow)
        self.assertIn("queue: max", workflow)
        self.assertIn("name: myshop-release-manifest-${{ github.sha }}", workflow)
        self.assertIn("retention-days: 90", workflow)
        self.assertNotIn("type=docker,dest=", workflow)
        self.assertNotIn("fetch-release-archives.py", workflow)
        self.assertNotIn("myshop-release-${{ github.sha }}", workflow)
        self.assertEqual(workflow.count('docker pull "$image@$digest"'), 2)
        self.assertIn('"$pulled_jar_sha" == "$API_JAR_SHA256"', workflow)
        self.assertIn('[[ "$pulled_revision" == "$SOURCE_SHA" ]]', workflow)

    def test_emergency_export_is_manual_digest_only_and_separate(self):
        workflow = OFFLINE_WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertIn("source_sha:", workflow)
        self.assertIn("packages: read", workflow)
        self.assertIn("scripts/export-offline-release.sh", workflow)
        self.assertIn("retention-days: 2", workflow)
        self.assertNotIn("docker build", workflow)
        self.assertNotIn("docker push", workflow)


if __name__ == "__main__":
    unittest.main()

from __future__ import annotations

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"
FETCHER = SCRIPTS / "fetch-release-manifest.sh"
VALIDATOR = SCRIPTS / "validate-release-manifest.py"
WORKFLOW = REPO / ".github/workflows/cicd.yml"
SOURCE_SHA = "a" * 40
DIGEST = "sha256:" + "b" * 64


def valid_manifest() -> dict:
    return {
        "version": 1,
        "project": "myshop",
        "source_sha": SOURCE_SHA,
        "created_at": "2026-10-06T00:00:00Z",
        "images": {
            service: {"ref": f"ghcr.io/yykx682436/myshop-{service}:{SOURCE_SHA}", "digest": DIGEST}
            for service in ("api", "web")
        },
    }


class MyShopReleaseManifestTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bin_dir = self.root / "bin"
        self.bin_dir.mkdir()
        self.docker_log = self.root / "docker.log"
        self.manifest_file = self.root / "carrier-manifest.json"
        self.fake_docker = self.bin_dir / "docker"
        self.fake_docker.write_text("""#!/usr/bin/env python3
import json, os, shutil, sys
args = sys.argv[1:]
with open(os.environ['FAKE_DOCKER_LOG'], 'a', encoding='utf-8') as log:
    log.write(' '.join(args) + '\\n')
if args[0] == 'pull':
    raise SystemExit(1 if args[1] == os.environ.get('FAKE_PULL_FAIL_REF') else 0)
if args[:2] == ['image', 'inspect']:
    fmt = args[3]
    if '.RepoDigests' in fmt:
        print(os.environ.get('FAKE_REPO_DIGESTS', json.dumps([
            'ghcr.io/yykx682436/myshop-release-manifest@' + os.environ['FAKE_CARRIER_DIGEST']
        ])))
    elif 'org.opencontainers.image.revision' in fmt:
        print(os.environ.get('FAKE_REVISION', os.environ['FAKE_SHA']))
    elif 'org.opencontainers.image.source' in fmt:
        print(os.environ.get('FAKE_SOURCE', 'https://github.com/YYKX682436/MyShop'))
    else:
        raise SystemExit(2)
elif args[0] == 'create':
    print('fake-container-id')
elif args[0] == 'cp':
    shutil.copyfile(os.environ['FAKE_CARRIER_MANIFEST'], args[2])
elif args[0] == 'rm':
    pass
else:
    raise SystemExit(2)
""", encoding="utf-8")
        self.fake_docker.chmod(0o755)
        self.write_manifest(valid_manifest())

    def tearDown(self):
        self.temp.cleanup()

    def write_manifest(self, value: dict | str):
        self.manifest_file.write_text(value if isinstance(value, str) else json.dumps(value), encoding="utf-8")

    def run_fetch(self, sha: str = SOURCE_SHA, digest: str = DIGEST, output: Path | None = None, **extra_env):
        output = output or self.root / "release-manifest.json"
        env = {
            **os.environ,
            "PATH": f"{self.bin_dir}:{os.environ['PATH']}",
            "MYSHOP_DOCKER_COMMAND": str(self.fake_docker),
            "FAKE_DOCKER_LOG": str(self.docker_log),
            "FAKE_CARRIER_MANIFEST": str(self.manifest_file),
            "FAKE_SHA": SOURCE_SHA,
            "FAKE_CARRIER_DIGEST": DIGEST,
            **extra_env,
        }
        result = subprocess.run(["bash", str(FETCHER), sha, digest, str(output)], env=env, text=True, capture_output=True)
        return result, output

    def test_valid_exact_sha_fetch_writes_atomic_private_output(self):
        output = self.root / "secure" / "release-manifest.json"
        output.parent.mkdir()
        output.write_text("old", encoding="utf-8")
        result, _ = self.run_fetch(output=output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(output.read_text(encoding="utf-8")), valid_manifest())
        self.assertEqual(output.stat().st_mode & 0o777, 0o600)
        log = self.docker_log.read_text(encoding="utf-8")
        pinned = f"ghcr.io/yykx682436/myshop-release-manifest@{DIGEST}"
        tag = f"ghcr.io/yykx682436/myshop-release-manifest:{SOURCE_SHA}"
        self.assertIn(f"pull {pinned}", log)
        self.assertIn(f"pull {tag}", log)
        self.assertIn("image inspect --format {{json .RepoDigests}} " + tag, log)
        self.assertIn(f"create --entrypoint /release-manifest.json {pinned}", log)
        inspect_lines = [line for line in log.splitlines() if line.startswith("image inspect --format ")]
        self.assertTrue(any("org.opencontainers.image.revision" in line and line.endswith(pinned)
                            for line in inspect_lines))
        self.assertTrue(any("org.opencontainers.image.source" in line and line.endswith(pinned)
                            for line in inspect_lines))
        self.assertIn("cp fake-container-id:/release-manifest.json", log)
        self.assertIn("rm -f fake-container-id", log)

    def test_invalid_sha_is_rejected_before_docker(self):
        result, _ = self.run_fetch("a" * 39)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.docker_log.exists())

    def test_missing_carrier_preserves_existing_file(self):
        output = self.root / "manifest.json"
        output.write_text("preserve", encoding="utf-8")
        pinned = f"ghcr.io/yykx682436/myshop-release-manifest@{DIGEST}"
        result, _ = self.run_fetch(output=output, FAKE_PULL_FAIL_REF=pinned)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output.read_text(encoding="utf-8"), "preserve")

    def test_malformed_digest_is_rejected_before_docker(self):
        result, _ = self.run_fetch(digest="sha256:bad")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("carrier registry digest", result.stderr)
        self.assertFalse(self.docker_log.exists())

    def test_unavailable_pinned_digest_is_rejected(self):
        pinned = f"ghcr.io/yykx682436/myshop-release-manifest@{DIGEST}"
        result, _ = self.run_fetch(FAKE_PULL_FAIL_REF=pinned)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("digest could not be pulled", result.stderr)

    def test_tag_digest_mismatch_fails_closed(self):
        wrong = "ghcr.io/yykx682436/myshop-release-manifest@sha256:" + "c" * 64
        result, _ = self.run_fetch(FAKE_REPO_DIGESTS=json.dumps([wrong]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("CARRIER_TAG_DIGEST_MISMATCH", result.stderr)
        self.assertNotIn("create --entrypoint", self.docker_log.read_text(encoding="utf-8"))

    def test_old_two_argument_interface_is_rejected(self):
        env = {
            **os.environ,
            "PATH": f"{self.bin_dir}:{os.environ['PATH']}",
            "MYSHOP_DOCKER_COMMAND": str(self.fake_docker),
            "FAKE_DOCKER_LOG": str(self.docker_log),
        }
        result = subprocess.run(["bash", str(FETCHER), SOURCE_SHA, str(self.root / "out.json")],
                                env=env, text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("<carrier_registry_digest>", result.stderr)
        self.assertFalse(self.docker_log.exists())

    def test_wrong_revision_or_source_label_fails(self):
        result, _ = self.run_fetch(FAKE_REVISION="c" * 40)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("revision label", result.stderr)
        result, _ = self.run_fetch(FAKE_SOURCE="https://example.invalid/repo")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source label", result.stderr)

    def test_malformed_manifest_does_not_replace_existing_file(self):
        self.write_manifest("{")
        output = self.root / "manifest.json"
        output.write_text("preserve", encoding="utf-8")
        result, _ = self.run_fetch(output=output)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(output.read_text(encoding="utf-8"), "preserve")

    def test_wrong_namespace_and_digest_mismatch_fail(self):
        value = valid_manifest()
        value["images"]["api"]["ref"] = f"ghcr.io/attacker/myshop-api:{SOURCE_SHA}"
        self.write_manifest(value)
        result, _ = self.run_fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("exact full-SHA ref", result.stderr)
        value = valid_manifest()
        value["images"]["web"]["digest"] = "sha256:bad"
        self.write_manifest(value)
        result, _ = self.run_fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("digest is invalid", result.stderr)

    def test_wrong_manifest_sha_or_unknown_fields_fail(self):
        value = valid_manifest()
        value["source_sha"] = "c" * 40
        self.write_manifest(value)
        result, _ = self.run_fetch()
        self.assertNotEqual(result.returncode, 0)
        value = valid_manifest()
        value["unexpected"] = True
        self.write_manifest(value)
        result, _ = self.run_fetch()
        self.assertNotEqual(result.returncode, 0)

    def test_workflow_publishes_only_ghcr_manifest_carrier(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("actions/upload-artifact", workflow)
        self.assertIn('DOCKER_BUILD_RECORD_UPLOAD: "false"', workflow)
        self.assertIn("myshop-release-manifest:${SOURCE_SHA}", workflow)
        self.assertIn("bash scripts/fetch-release-manifest.sh", workflow)
        self.assertIn('carrier_digest=$(printf', workflow)
        self.assertIn('[[ "$carrier_digest" =~ ^sha256:[0-9a-f]{64}$ ]]', workflow)
        self.assertIn('bash scripts/fetch-release-manifest.sh "$SOURCE_SHA" "$carrier_digest" "$FETCHED_MANIFEST"', workflow)
        self.assertIn('manifest_carrier_digest=%s', workflow)
        self.assertIn('manifest_carrier_ref=%s', workflow)
        self.assertIn("ghcr.io/yykx682436/myshop-api:${{ github.sha }}", workflow)
        self.assertIn("ghcr.io/yykx682436/myshop-web:${{ github.sha }}", workflow)

    def test_validator_rejects_nonapproved_registry_and_unknown_fields(self):
        manifest_path = self.root / "manifest.json"
        manifest_path.write_text(json.dumps(valid_manifest()), encoding="utf-8")
        result = subprocess.run(["python3", str(VALIDATOR), str(manifest_path), "registry.example/team", "myshop"],
                                text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        value = valid_manifest()
        value["images"]["api"]["unexpected"] = True
        manifest_path.write_text(json.dumps(value), encoding="utf-8")
        result = subprocess.run(["python3", str(VALIDATOR), str(manifest_path), "ghcr.io/yykx682436", "myshop"],
                                text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main()

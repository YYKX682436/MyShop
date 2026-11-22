from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

script = Path(__file__).resolve().parents[1] / "fetch-release-archives.py"
spec = importlib.util.spec_from_file_location("fetch_release_archives", script)
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
SHA = "a" * 40
DIGEST = "sha256:" + "b" * 64


def fixture_files() -> dict[str, bytes]:
    manifest = {"version": 1, "project": "myshop", "source_sha": SHA, "created_at": "2026-11-21T00:00:00Z",
                "images": {name: {"ref": f"ghcr.io/yykx682436/myshop-{name}:{SHA}", "digest": DIGEST}
                           for name in ("api", "web")}}
    files = {"api.tar": b"api", "web.tar": b"web", "release-manifest.json": json.dumps(manifest).encode(),
             "manifest-carrier-digest.txt": (DIGEST + "\n").encode()}
    files["SHA256SUMS"] = "".join(f"{hashlib.sha256(data).hexdigest()}  {name}\n" for name, data in files.items()).encode()
    return files


class ReleaseArchiveTests(unittest.TestCase):
    def test_only_successful_matching_run_with_unexpired_artifact_is_used(self):
        runs = {"workflow_runs": [{"head_sha": SHA, "id": 1, "conclusion": "failure"},
                                  {"head_sha": SHA, "id": 2, "conclusion": "success"}]}
        artifacts = {"artifacts": [{"name": f"myshop-release-{SHA}", "expired": False, "id": 3}]}
        with mock.patch.object(release, "gh_api", side_effect=[runs, artifacts]):
            item, run = release.find_artifact(SHA)
        self.assertEqual((item["id"], run), (3, 2))

    def test_matching_artifact_extracts_and_verifies(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            archive, output = root / "release.zip", root / "out"
            output.mkdir()
            with zipfile.ZipFile(archive, "w") as package:
                for filename, data in fixture_files().items():
                    package.writestr(filename, data)
            release.extract_and_verify(archive, output, SHA)
            self.assertEqual((output / "web.tar").read_bytes(), b"web")

    def test_archive_with_extra_path_or_wrong_checksum_is_rejected(self):
        for mode in ("extra", "tampered"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as name:
                root = Path(name)
                archive, output = root / "release.zip", root / "out"
                output.mkdir()
                files = fixture_files()
                if mode == "extra":
                    files["../outside"] = b"unsafe"
                else:
                    files["api.tar"] = b"tampered"
                with zipfile.ZipFile(archive, "w") as package:
                    for filename, data in files.items():
                        package.writestr(filename, data)
                with self.assertRaises(ValueError):
                    release.extract_and_verify(archive, output, SHA)
                self.assertFalse((root / "outside").exists())

    def test_full_response_cannot_be_mistaken_for_a_byte_range(self):
        with tempfile.TemporaryDirectory() as name:
            destination = Path(name) / "part"
            destination.write_bytes(b"a")
            with mock.patch.object(release.subprocess, "run", return_value=mock.Mock(returncode=0, stdout="200")):
                with self.assertRaises(ValueError):
                    release.download_part("https://example.invalid/artifact", destination, 0, 1)

    def test_expiring_download_url_is_refreshed(self):
        with mock.patch.object(release, "artifact_url", side_effect=["https://first.invalid", "https://second.invalid"]) as fetch, \
             mock.patch.object(release.time, "monotonic", side_effect=[0, 0, 239, 240, 240]):
            source = release.ArtifactURL(7)
            self.assertEqual(source.get(), "https://first.invalid")
            self.assertEqual(source.get(), "https://first.invalid")
            self.assertEqual(source.get(), "https://second.invalid")
            self.assertEqual(fetch.call_count, 2)


if __name__ == "__main__":
    unittest.main()

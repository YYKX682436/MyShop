#!/usr/bin/env python3
"""Record the exact tested image exported by its CI build job."""
from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import tarfile
from pathlib import Path

if len(sys.argv) != 6:
    raise SystemExit("usage: create-transfer-record.py <api|web> <source-sha> <registry-digest> <image-tar> <record-json>")
service, source_sha, digest, archive_text, record_text = sys.argv[1:]
if service not in ("api", "web") or not re.fullmatch(r"[0-9a-f]{40}", source_sha) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
    raise SystemExit("invalid service, source SHA, or registry digest")
archive = Path(archive_text)
tag = f"ghcr.io/yykx682436/myshop-{service}:{source_sha}"
image = json.loads(subprocess.check_output(["docker", "image", "inspect", tag], text=True))[0]
if image["Os"] != "linux" or image["Architecture"] != "amd64":
    raise SystemExit("image platform is not linux/amd64")
if image["Config"]["Labels"].get("org.opencontainers.image.revision") != source_sha:
    raise SystemExit("image source label differs from the release SHA")
if f"ghcr.io/yykx682436/myshop-{service}@{digest}" not in image.get("RepoDigests", []):
    raise SystemExit("image registry digest was not verified by CI")
with tarfile.open(archive, "r:") as tar:
    entries = json.load(tar.extractfile("manifest.json"))
    if len(entries) != 1 or entries[0].get("RepoTags") != [tag]:
        raise SystemExit("archive has an unexpected image tag")
    config_bytes = tar.extractfile(entries[0]["Config"]).read()
    config = json.loads(config_bytes)
    config_digest = "sha256:" + hashlib.sha256(config_bytes).hexdigest()
    if config_digest != image["Id"] or config.get("os") != "linux" or config.get("architecture") != "amd64":
        raise SystemExit("archive config differs from the verified linux/amd64 image")
sha = hashlib.sha256()
with archive.open("rb") as handle:
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        sha.update(chunk)
record = {
    "version": 1, "service": service, "source_sha": source_sha,
    "registry_digest": digest, "image_id": image["Id"],
    "tar_sha256": sha.hexdigest(), "archive_tag": tag,
    "rootfs_diff_ids": image["RootFS"]["Layers"],
}
Path(record_text).write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"TRANSFER_RECORD_CREATED service={service} sha={source_sha} digest={digest} tar_bytes={archive.stat().st_size}")

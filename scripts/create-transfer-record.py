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

def canonical_sha256(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


# Docker image inspect can add these legacy container defaults to Config. They
# do not affect the image's runtime settings and are absent from docker save.
INSPECT_ONLY_DEFAULTS = {
    "AttachStderr", "AttachStdin", "AttachStdout", "Domainname", "Hostname",
    "Image", "OpenStdin", "StdinOnce", "Tty",
}

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
    if config.get("os") != "linux" or config.get("architecture") != "amd64":
        raise SystemExit("archive platform differs from the verified linux/amd64 image")
    archive_config = config.get("config", {})
    inspected_config = image["Config"]
    differing = sorted(key for key in set(archive_config) | set(inspected_config)
                       if archive_config.get(key) != inspected_config.get(key))
    if any(key not in INSPECT_ONLY_DEFAULTS for key in differing):
        raise SystemExit(f"archive runtime config differs from the verified CI image; keys={differing} "
                         f"tar_config={config_digest} ci_image={image['Id']}")
    if config.get("rootfs", {}).get("diff_ids") != image["RootFS"]["Layers"]:
        raise SystemExit("archive rootfs layers differ from the verified CI image")
sha = hashlib.sha256()
with archive.open("rb") as handle:
    for chunk in iter(lambda: handle.read(1024 * 1024), b""):
        sha.update(chunk)
record = {
    "version": 1, "service": service, "source_sha": source_sha,
    "registry_digest": digest, "image_id": image["Id"],
    "exported_config_digest": config_digest,
    "runtime_config_digest": canonical_sha256(archive_config),
    "tar_sha256": sha.hexdigest(), "archive_tag": tag,
    "rootfs_diff_ids": image["RootFS"]["Layers"],
}
Path(record_text).write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(f"TRANSFER_RECORD_CREATED service={service} sha={source_sha} digest={digest} tar_bytes={archive.stat().st_size}")

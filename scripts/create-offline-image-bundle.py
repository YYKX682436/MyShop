#!/usr/bin/env python3
"""Create a root-only manifest for verified Docker-archive image transfers."""
from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import sys
import tarfile
import tempfile
from pathlib import Path, PurePosixPath

REGISTRY = "ghcr.io/yykx682436"
INFRA = {
    "postgres": (
        f"{REGISTRY}/myshop-postgres:mirror-cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
        "sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
    ),
    "caddy": (
        f"{REGISTRY}/myshop-caddy:mirror-5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648",
        "sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648",
    ),
    "node-exporter": (
        f"{REGISTRY}/myshop-node-exporter:mirror-1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0",
        "sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0",
    ),
}
SERVICES = ("api", "web", "postgres", "caddy", "node-exporter")
SHA = re.compile(r"^[0-9a-f]{40}$")


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"[myshop-offline-images] ERROR: {message}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_sha256(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


if len(sys.argv) != 4:
    raise SystemExit("usage: create-offline-image-bundle.py <release-manifest.json> <archive-directory> <bundle.json>")
manifest_path, archive_dir, output_path = map(Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
if manifest.get("version") != 1 or manifest.get("project") != "myshop":
    fail("release manifest version/project is unsupported")
sha = manifest.get("source_sha")
if not isinstance(sha, str) or not SHA.fullmatch(sha):
    fail("release manifest source_sha must be a full lowercase SHA")
archive_dir = archive_dir.resolve(strict=True)
images: dict[str, dict[str, str]] = {}
for service in SERVICES:
    archive_name = f"{service}.tar"
    archive = archive_dir / archive_name
    if archive.is_symlink() or not archive.is_file() or archive.resolve(strict=True).parent != archive_dir:
        fail(f"{service} Docker archive is missing, symlinked, or outside the archive directory")
    if service in ("api", "web"):
        release_image = manifest.get("images", {}).get(service, {})
        expected_ref = f"{REGISTRY}/myshop-{service}:{sha}"
        if release_image.get("ref") != expected_ref:
            fail(f"release manifest {service} ref is not the expected full-SHA image")
        ref, source_digest = release_image["ref"], release_image["digest"]
    else:
        ref, source_digest = INFRA[service]
    try:
        with tarfile.open(archive, "r:") as tar:
            members = {member.name: member for member in tar.getmembers() if member.isfile()}
            manifest_member = members.get("manifest.json")
            if manifest_member is None:
                fail(f"{service} Docker archive has no manifest.json")
            docker_manifest = json.loads(tar.extractfile(manifest_member).read())
            if not isinstance(docker_manifest, list) or len(docker_manifest) != 1:
                fail(f"{service} Docker archive must contain exactly one platform image")
            record = docker_manifest[0]
            if record.get("RepoTags") != [ref]:
                fail(f"{service} Docker archive tag does not match its approved ref")
            config_name = record.get("Config")
            if not isinstance(config_name, str) or PurePosixPath(config_name).is_absolute() or ".." in PurePosixPath(config_name).parts:
                fail(f"{service} Docker archive config path is unsafe")
            config_member = members.get(config_name)
            if config_member is None:
                fail(f"{service} Docker archive config is missing")
            config_bytes = tar.extractfile(config_member).read()
            config_digest = "sha256:" + hashlib.sha256(config_bytes).hexdigest()
            config = json.loads(config_bytes)
            if config.get("os") != "linux" or config.get("architecture") != "amd64":
                fail(f"{service} Docker archive platform is not linux/amd64")
            layers = record.get("Layers")
            if not isinstance(layers, list) or not layers:
                fail(f"{service} Docker archive contains no layers")
            for layer in layers:
                layer_path = PurePosixPath(str(layer))
                if layer_path.is_absolute() or ".." in layer_path.parts or str(layer) not in members:
                    fail(f"{service} Docker archive contains a missing or unsafe layer")
    except SystemExit:
        raise
    except Exception as exc:
        fail(f"{service} Docker archive is invalid: {exc}")
    images[service] = {
        "ref": ref,
        "source_digest": source_digest,
        "config_digest": config_digest,
        "runtime_config_digest": canonical_sha256(config.get("config", {})),
        "rootfs_diff_ids": config["rootfs"]["diff_ids"],
        "loaded_image_id": "",
        "archive": archive_name,
        "archive_sha256": sha256_file(archive),
    }

bundle = {"version": 2, "source_sha": sha, "platform": "linux/amd64", "images": images}
output_path.parent.mkdir(parents=True, exist_ok=True)
fd, temporary = tempfile.mkstemp(prefix=f".{output_path.name}.", dir=output_path.parent)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(bundle, handle, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(temporary, stat.S_IRUSR | stat.S_IWUSR)
    os.replace(temporary, output_path)
    directory_fd = os.open(output_path.parent, os.O_RDONLY)
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
print(f"OFFLINE_IMAGE_BUNDLE_CREATED source_sha={sha} platform=linux/amd64 services={len(SERVICES)}")

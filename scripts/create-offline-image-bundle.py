#!/usr/bin/env python3
"""Describe digest-pinned release images exported by the emergency workflow."""
from __future__ import annotations

import hashlib
import json
import os
import re
import stat
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path, PurePosixPath

REGISTRY = "ghcr.io/yykx682436"
INFRA = {
    "postgres": (
        f"{REGISTRY}/myshop-postgres:mirror-cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
        "sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
    ),
    "caddy": (
        f"{REGISTRY}/myshop-caddy:mirror-5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648",
        "sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648",
    ),
    "node-exporter": (
        f"{REGISTRY}/myshop-node-exporter:mirror-1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0@sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0",
        "sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0",
    ),
}
APP_SERVICES = ("api", "web")
INFRA_SERVICES = ("postgres", "caddy", "node-exporter")
SERVICES = (*APP_SERVICES, *INFRA_SERVICES)
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")


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


if len(sys.argv) not in (4, 5):
    raise SystemExit("usage: create-offline-image-bundle.py <release-manifest.json> <archive-directory> <bundle.json> [accepted-state.json]")
manifest_path, archive_dir, output_path = map(Path, sys.argv[1:4])
accepted_state_path = Path(sys.argv[4]) if len(sys.argv) == 5 else None
validator = Path(__file__).with_name("validate-release-manifest.py")
result = __import__("subprocess").run(
    [sys.executable, str(validator), str(manifest_path), REGISTRY, "myshop"],
    check=False, text=True, capture_output=True,
)
if result.returncode:
    fail(f"release manifest is invalid: {result.stderr.strip()}")
manifest = json.loads(result.stdout)
sha = manifest["source_sha"]
if not SHA.fullmatch(sha):
    fail("release manifest source_sha must be a full lowercase SHA")
archive_dir = archive_dir.resolve(strict=True)
images: dict[str, dict[str, object]] = {}
accepted = None
if accepted_state_path:
    if manifest.get("version") != 3:
        fail("transferred release requires a production-baselined manifest")
    accepted_state = json.loads(accepted_state_path.read_text(encoding="utf-8"))
    accepted = accepted_state.get("active")
    if not isinstance(accepted, dict) or accepted.get("source_sha") != manifest["accepted_sha"]:
        fail("production accepted SHA changed since CI dispatch")

for service in APP_SERVICES:
    archive_name = f"{service}.tar"
    archive_path = archive_dir / archive_name
    release_image = manifest["images"][service]
    source_ref = release_image["ref"]
    source_digest = release_image["digest"]
    image_source_sha = release_image["source_sha"]
    archive_ref = f"{REGISTRY}/myshop-{service}:{image_source_sha}"
    if not DIGEST.fullmatch(source_digest):
        fail(f"{service} source digest is invalid")
    previous = accepted["images"][service] if accepted else None
    if previous and previous["digest"] == source_digest:
        if archive_path.exists() or (archive_dir / f"{service}.record.json").exists():
            fail(f"unchanged {service} must not have a transferred archive")
        if previous.get("source_sha", accepted["source_sha"]) != image_source_sha:
            fail(f"reused {service} source SHA differs from accepted state")
        archive_ref = previous.get("runtime_ref", previous["ref"])
        inspected = json.loads(subprocess.check_output(["docker", "image", "inspect", archive_ref], text=True))[0]
        image_id = inspected["Id"]
        if image_id != previous.get("image_id") or inspected["Os"] != "linux" or inspected["Architecture"] != "amd64":
            fail(f"reused {service} image ID or platform differs from accepted state")
        if inspected.get("Config", {}).get("Labels", {}).get("org.opencontainers.image.revision") != image_source_sha:
            fail(f"reused {service} image source label differs from accepted state")
        images[service] = {
            "source_ref": source_ref, "source_digest": source_digest, "source_sha": image_source_sha,
            "archive_ref": archive_ref, "config_digest": image_id,
            "runtime_config_digest": canonical_sha256(inspected["Config"]),
            "rootfs_diff_ids": inspected["RootFS"]["Layers"], "loaded_image_id": image_id,
            "archive": "", "archive_sha256": "", "reuse_accepted": True,
        }
        continue
    if archive_path.is_symlink() or not archive_path.is_file() or archive_path.resolve(strict=True).parent != archive_dir:
        fail(f"{service} Docker archive is missing, symlinked, or outside the archive directory")
    try:
        with tarfile.open(archive_path, "r:") as tar:
            members = {member.name: member for member in tar.getmembers() if member.isfile()}
            manifest_member = members.get("manifest.json")
            if manifest_member is None:
                fail(f"{service} Docker archive has no manifest.json")
            docker_manifest = json.loads(tar.extractfile(manifest_member).read())
            if not isinstance(docker_manifest, list) or len(docker_manifest) != 1:
                fail(f"{service} Docker archive must contain exactly one platform image")
            record = docker_manifest[0]
            if record.get("RepoTags") != [archive_ref]:
                fail(f"{service} Docker archive tag does not match its source image")
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
    image = {
        "source_ref": source_ref,
        "source_digest": source_digest,
        "source_sha": image_source_sha,
        "archive_ref": archive_ref,
        "config_digest": config_digest,
        "runtime_config_digest": canonical_sha256(config.get("config", {})),
        "rootfs_diff_ids": config.get("rootfs", {}).get("diff_ids"),
        "loaded_image_id": "",
        "archive": archive_name,
        "archive_sha256": sha256_file(archive_path),
    }
    if accepted:
        record_path = archive_dir / f"{service}.record.json"
        if record_path.is_symlink() or not record_path.is_file():
            fail(f"{service} CI transfer record is missing or unsafe")
        transfer = json.loads(record_path.read_text(encoding="utf-8"))
        if transfer != {
            "version": 1, "service": service, "source_sha": image_source_sha,
            "registry_digest": source_digest, "image_id": config_digest,
            "tar_sha256": image["archive_sha256"], "archive_tag": archive_ref,
            "rootfs_diff_ids": image["rootfs_diff_ids"],
        }:
            fail(f"{service} tar, CI record, or manifest identity differs")
        if config.get("config", {}).get("Labels", {}).get("org.opencontainers.image.revision") != image_source_sha:
            fail(f"{service} archive source label differs from the manifest")
        image["reuse_accepted"] = False
    if not isinstance(image["rootfs_diff_ids"], list) or not image["rootfs_diff_ids"]:
        fail(f"{service} image has no root filesystem layer identities")
    images[service] = image

for service in INFRA_SERVICES:
    source_ref, source_digest = INFRA[service]
    images[service] = {
        "source_ref": source_ref,
        "source_digest": source_digest,
        "source_sha": "",
        "archive_ref": source_ref.split("@", 1)[0],
        "config_digest": "",
        "runtime_config_digest": "",
        "rootfs_diff_ids": [],
        "loaded_image_id": "",
        "archive": "",
        "archive_sha256": "",
    }
    if accepted:
        images[service]["reuse_accepted"] = False

bundle = {"version": 5 if accepted else 4, "source_sha": sha, "platform": "linux/amd64", "images": images}
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

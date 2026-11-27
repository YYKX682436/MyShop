#!/usr/bin/env python3
"""Validate digest-provenanced Docker archives before and after offline import."""
from __future__ import annotations

import argparse
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
LEGACY_INFRA_REPO_DIGESTS = {
    # The existing friend-prod host has these exact preinstalled mirror images.
    # Accept them for offline recovery after comparing the local RepoDigest.
    "postgres": "sha256:60a518396f28c1c42c0fb640a41bc3516943802f683e59f7fe741a7df3d86822",
    "caddy": "sha256:ee92234975200c8d302bf9b407ac32061eae9315c9cb6c7e3ddfd58c7a675ac6",
    "node-exporter": "sha256:daafcbdb24336cb32dbe5590325d1a0fc61db5dcf43e53b414c99bdc8fb75fdc",
}
APP_SERVICES = ("api", "web")
INFRA_SERVICES = tuple(INFRA)
SERVICES = (*APP_SERVICES, *INFRA_SERVICES)
SHA = re.compile(r"^[0-9a-f]{40}$")
DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
HEX = re.compile(r"^[0-9a-f]{64}$")


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"[myshop-offline-images] ERROR: {message}")


def read_json(path: Path, label: str) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"{label} is not valid JSON: {exc}")
    if not isinstance(value, dict):
        fail(f"{label} must be an object")
    return value


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def canonical_sha256(value: object) -> str:
    encoded = json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()


def validate_bundle(path: Path) -> dict:
    bundle = read_json(path, "image bundle")
    if set(bundle) != {"version", "source_sha", "platform", "images"} or bundle.get("version") not in (4, 5):
        fail("image bundle version or fields are unsupported")
    if not isinstance(bundle.get("source_sha"), str) or not SHA.fullmatch(bundle["source_sha"]):
        fail("source_sha must be a full lowercase SHA")
    if bundle.get("platform") != "linux/amd64":
        fail("image bundle platform must be linux/amd64")
    images = bundle.get("images")
    if not isinstance(images, dict) or set(images) != set(SERVICES):
        fail(f"images must contain exactly {SERVICES}")
    expected_fields = {
        "source_ref", "source_digest", "source_sha", "archive_ref", "config_digest",
        "runtime_config_digest", "rootfs_diff_ids", "loaded_image_id", "archive", "archive_sha256",
    }
    if bundle["version"] == 5:
        expected_fields.add("reuse_accepted")
    for service in SERVICES:
        item = images[service]
        if not isinstance(item, dict) or set(item) != expected_fields:
            fail(f"images.{service} fields are unsupported")
        reused = bundle["version"] == 5 and item["reuse_accepted"] is True
        if bundle["version"] == 5 and not isinstance(item["reuse_accepted"], bool):
            fail(f"images.{service}.reuse_accepted must be boolean")
        if reused and service not in APP_SERVICES:
            fail(f"images.{service} cannot reuse an accepted app image")
        if service in APP_SERVICES:
            image_sha = item.get("source_sha")
            if not isinstance(image_sha, str) or not SHA.fullmatch(image_sha):
                fail(f"images.{service}.source_sha is invalid")
            expected_ref = f"{REGISTRY}/myshop-{service}@{item['source_digest']}"
            expected_archive_ref = f"{REGISTRY}/myshop-{service}:{image_sha}"
        else:
            expected_ref, expected_digest = INFRA[service]
            if item.get("source_digest") != expected_digest:
                fail(f"images.{service} is not pinned to the approved infrastructure digest")
            expected_archive_ref = expected_ref.split("@", 1)[0]
            if item.get("source_sha") != "":
                fail(f"images.{service}.source_sha must be empty for infrastructure images")
        if item.get("source_ref") != expected_ref:
            fail(f"images.{service}.source_ref is not the approved digest ref")
        if not isinstance(item.get("source_digest"), str) or not DIGEST.fullmatch(item["source_digest"]):
            fail(f"images.{service}.source_digest is invalid")
        if item.get("archive_ref") != expected_archive_ref and not (reused and item.get("archive_ref") == expected_ref):
            fail(f"images.{service}.archive_ref is invalid")
        if service in APP_SERVICES:
            for field in ("config_digest", "runtime_config_digest"):
                if not isinstance(item.get(field), str) or not DIGEST.fullmatch(item[field]):
                    fail(f"images.{service}.{field} is invalid")
            diff_ids = item.get("rootfs_diff_ids")
            if not isinstance(diff_ids, list) or not diff_ids or any(not isinstance(value, str) or not DIGEST.fullmatch(value) for value in diff_ids):
                fail(f"images.{service}.rootfs_diff_ids is invalid")
            if reused:
                if item.get("archive") != "" or item.get("archive_sha256") != "" or item.get("loaded_image_id") != item.get("config_digest"):
                    fail(f"images.{service} accepted image identity is invalid")
            elif item.get("archive") != f"{service}.tar" or not isinstance(item.get("archive_sha256"), str) or not HEX.fullmatch(item["archive_sha256"]):
                fail(f"images.{service} archive identity is invalid")
        else:
            if (item.get("config_digest") != "" or item.get("runtime_config_digest") != ""
                    or item.get("rootfs_diff_ids") != [] or item.get("archive") != ""
                    or item.get("archive_sha256") != ""):
                fail(f"images.{service} must refer to a preinstalled digest-pinned infrastructure image")
        loaded_id = item.get("loaded_image_id")
        if loaded_id != "" and (not isinstance(loaded_id, str) or not DIGEST.fullmatch(loaded_id)):
            fail(f"images.{service}.loaded_image_id is invalid")
    return bundle


def verify_manifest(bundle: dict, manifest_path: Path) -> None:
    validator = Path(__file__).with_name("validate-release-manifest.py")
    result = subprocess.run([sys.executable, str(validator), str(manifest_path), REGISTRY, "myshop"],
                            check=False, text=True, capture_output=True)
    if result.returncode:
        fail(f"release manifest validation failed: {result.stderr.strip()}")
    manifest = json.loads(result.stdout)
    if bundle["source_sha"] != manifest["source_sha"]:
        fail("bundle source SHA differs from the release manifest")
    for service in ("api", "web"):
        item = bundle["images"][service]
        release = manifest["images"][service]
        if (item["source_ref"] != release["ref"] or item["source_digest"] != release["digest"]
                or item["source_sha"] != release["source_sha"]):
            fail(f"bundle {service} digest or provenance differs from the release manifest")


def verify_archives(bundle: dict, directory: Path) -> None:
    try:
        root = directory.resolve(strict=True)
    except OSError as exc:
        fail(f"archive directory is unavailable: {exc}")
    for service in APP_SERVICES:
        item = bundle["images"][service]
        if item.get("reuse_accepted") is True:
            if (directory / f"{service}.tar").exists():
                fail(f"{service} unexpectedly transferred an unchanged archive")
            continue
        archive = directory / item["archive"]
        if archive.is_symlink() or not archive.is_file() or archive.resolve(strict=True).parent != root:
            fail(f"{service} archive is missing, symlinked, or outside the transfer directory")
        if sha256_file(archive) != item["archive_sha256"]:
            fail(f"{service} archive SHA-256 mismatch")
        try:
            with tarfile.open(archive, "r:") as tar:
                members = {member.name: member for member in tar.getmembers() if member.isfile()}
                manifest_member = members.get("manifest.json")
                if manifest_member is None:
                    fail(f"{service} Docker archive has no manifest.json")
                docker_manifest = json.loads(tar.extractfile(manifest_member).read())
                if not isinstance(docker_manifest, list) or len(docker_manifest) != 1:
                    fail(f"{service} Docker archive must contain one platform image")
                record = docker_manifest[0]
                if record.get("RepoTags") != [item["archive_ref"]]:
                    fail(f"{service} archive tag differs from the verified source image")
                config_path = record.get("Config")
                if (not isinstance(config_path, str) or PurePosixPath(config_path).is_absolute()
                        or ".." in PurePosixPath(config_path).parts or config_path not in members):
                    fail(f"{service} archive config path is missing or unsafe")
                config_bytes = tar.extractfile(members[config_path]).read()
                if "sha256:" + hashlib.sha256(config_bytes).hexdigest() != item["config_digest"]:
                    fail(f"{service} archive config digest mismatch")
                config = json.loads(config_bytes)
                if config.get("os") != "linux" or config.get("architecture") != "amd64":
                    fail(f"{service} archive platform is not linux/amd64")
                if canonical_sha256(config.get("config", {})) != item["runtime_config_digest"]:
                    fail(f"{service} archive runtime config differs from the verified image")
                if config.get("rootfs", {}).get("diff_ids") != item["rootfs_diff_ids"]:
                    fail(f"{service} archive layers differ from the verified image")
                layers = record.get("Layers")
                if not isinstance(layers, list) or not layers:
                    fail(f"{service} archive has no layers")
                for layer in layers:
                    path = PurePosixPath(str(layer))
                    if path.is_absolute() or ".." in path.parts or str(layer) not in members:
                        fail(f"{service} archive contains an unsafe or missing layer")
        except SystemExit:
            raise
        except Exception as exc:
            fail(f"{service} archive is invalid: {exc}")


def verify_local_images(bundle: dict, bundle_path: Path, allow_unrecorded: bool = False) -> dict[str, str]:
    try:
        info = bundle_path.stat()
    except OSError as exc:
        fail(f"image bundle cannot be stat'ed: {exc}")
    if os.geteuid() == 0 and (info.st_uid != 0 or info.st_mode & 0o077):
        fail("server image bundle must be root-owned with mode 0600 or stricter")
    observed: dict[str, str] = {}
    for service in SERVICES:
        item = bundle["images"][service]
        result = subprocess.run(["docker", "image", "inspect", "--format", "{{json .}}", item["archive_ref"]],
                                check=False, text=True, capture_output=True)
        if result.returncode:
            fail(f"{service} image is not loaded under its verified archive tag")
        image = json.loads(result.stdout)
        image_id = image.get("Id")
        if not isinstance(image_id, str) or not DIGEST.fullmatch(image_id):
            fail(f"{service} loaded image ID is invalid")
        expected_id = item["loaded_image_id"]
        if expected_id and image_id != expected_id:
            fail(f"{service} loaded image ID differs from the bundle")
        if not expected_id and not allow_unrecorded:
            fail(f"{service} loaded image ID has not been recorded")
        if image.get("Os") != "linux" or image.get("Architecture") != "amd64":
            fail(f"{service} loaded platform is not linux/amd64")
        if item["archive_ref"] not in (image.get("RepoTags") or []) and item["archive_ref"] not in (image.get("RepoDigests") or []):
            fail(f"{service} loaded image tag differs from the bundle")
        if service in APP_SERVICES:
            if canonical_sha256(image.get("Config", {})) != item["runtime_config_digest"]:
                fail(f"{service} loaded runtime config differs from the bundle")
            if image.get("RootFS", {}).get("Layers") != item["rootfs_diff_ids"]:
                fail(f"{service} loaded rootfs layers differ from the bundle")
        else:
            repo = item["source_ref"].split("@", 1)[0].rsplit(":", 1)[0]
            repo_digests = image.get("RepoDigests") or []
            accepted_repo_digests = {
                f"{repo}@{item['source_digest']}",
                f"{repo}@{LEGACY_INFRA_REPO_DIGESTS[service]}",
            }
            if not accepted_repo_digests.intersection(repo_digests):
                fail(f"{service} preinstalled RepoDigest differs from its pinned infrastructure digest")
        observed[service] = image_id
    return observed


def record_loaded_image_ids(bundle: dict, bundle_path: Path) -> None:
    if os.geteuid() != 0:
        fail("recording loaded image IDs requires root")
    info = bundle_path.stat()
    if info.st_uid != 0 or info.st_mode & 0o077:
        fail("server image bundle must be root-owned with mode 0600 or stricter")
    for service, image_id in verify_local_images(bundle, bundle_path, allow_unrecorded=True).items():
        bundle["images"][service]["loaded_image_id"] = image_id
    fd, name = tempfile.mkstemp(prefix=f".{bundle_path.name}.", dir=bundle_path.parent)
    try:
        os.fchown(fd, 0, 0)
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(json.dumps(bundle, indent=2, sort_keys=True) + "\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(name, bundle_path)
        directory_fd = os.open(bundle_path.parent, os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if os.path.exists(name):
            os.unlink(name)


parser = argparse.ArgumentParser()
parser.add_argument("bundle")
parser.add_argument("--manifest")
parser.add_argument("--verify-archives")
parser.add_argument("--verify-local", action="store_true")
parser.add_argument("--record-loaded-image-ids", action="store_true")
parser.add_argument("--expected-source-sha")
parser.add_argument("--get-config-digest", choices=SERVICES)
parser.add_argument("--get-loaded-image-id", choices=SERVICES)
parser.add_argument("--get-runtime-ref", choices=SERVICES)
args = parser.parse_args()
bundle_path = Path(args.bundle)
bundle = validate_bundle(bundle_path)
if args.expected_source_sha and bundle["source_sha"] != args.expected_source_sha:
    fail("bundle source SHA differs from the configured release source SHA")
if args.manifest:
    verify_manifest(bundle, Path(args.manifest))
if args.verify_archives:
    verify_archives(bundle, Path(args.verify_archives))
if args.verify_local:
    verify_local_images(bundle, bundle_path)
if args.record_loaded_image_ids:
    record_loaded_image_ids(bundle, bundle_path)
if args.get_config_digest:
    print(bundle["images"][args.get_config_digest]["config_digest"])
elif args.get_loaded_image_id:
    image_id = bundle["images"][args.get_loaded_image_id]["loaded_image_id"]
    if not isinstance(image_id, str) or not DIGEST.fullmatch(image_id):
        fail(f"{args.get_loaded_image_id} loaded image ID has not been recorded")
    print(image_id)
elif args.get_runtime_ref:
    print(bundle["images"][args.get_runtime_ref]["archive_ref"])
elif not (args.manifest or args.verify_archives or args.verify_local or args.record_loaded_image_ids or args.expected_source_sha):
    parser.error("select a bundle validation or inspection operation")
else:
    print(f"offline-image-bundle=valid source_sha={bundle['source_sha']} platform={bundle['platform']}")

#!/usr/bin/env python3
"""Validate a digest-pinned MyShop image transfer bundle."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tarfile
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


def verify_manifest(bundle: dict, manifest_path: Path) -> None:
    validator = Path(__file__).with_name("validate-release-manifest.py")
    result = subprocess.run(
        [sys.executable, str(validator), str(manifest_path), REGISTRY, "myshop"],
        check=False,
        text=True,
        capture_output=True,
    )
    if result.returncode:
        fail(f"release manifest validation failed: {result.stderr.strip()}")
    try:
        manifest = json.loads(result.stdout)
    except Exception as exc:
        fail(f"validated release manifest output is invalid: {exc}")
    if bundle["source_sha"] != manifest["source_sha"]:
        fail("bundle source SHA differs from the release manifest")
    for service in ("api", "web"):
        item = bundle["images"][service]
        release_image = manifest["images"][service]
        if item["ref"] != release_image["ref"] or item["source_digest"] != release_image["digest"]:
            fail(f"bundle {service} source ref/digest differs from the release manifest")


def validate_bundle(path: Path) -> dict:
    bundle = read_json(path, "image bundle")
    if set(bundle) != {"version", "source_sha", "platform", "images"} or bundle.get("version") != 1:
        fail("image bundle version or fields are unsupported")
    sha = bundle.get("source_sha")
    if not isinstance(sha, str) or not SHA.fullmatch(sha):
        fail("source_sha must be a full lowercase SHA")
    if bundle.get("platform") != "linux/amd64":
        fail("image bundle platform must be linux/amd64")
    images = bundle.get("images")
    if not isinstance(images, dict) or set(images) != set(SERVICES):
        fail(f"images must contain exactly {SERVICES}")
    for service in SERVICES:
        item = images[service]
        if not isinstance(item, dict) or set(item) != {"ref", "source_digest", "config_digest", "archive", "archive_sha256"}:
            fail(f"images.{service} fields are unsupported")
        if service in ("api", "web"):
            expected_ref = f"{REGISTRY}/myshop-{service}:{sha}"
            if item["ref"] != expected_ref:
                fail(f"images.{service}.ref must equal the full-SHA release ref")
            if not isinstance(item["source_digest"], str) or not DIGEST.fullmatch(item["source_digest"]):
                fail(f"images.{service}.source_digest is invalid")
        else:
            expected_ref, expected_digest = INFRA[service]
            if item["ref"] != expected_ref or item["source_digest"] != expected_digest:
                fail(f"images.{service} is not the approved mirror ref and source digest")
        if not isinstance(item["config_digest"], str) or not DIGEST.fullmatch(item["config_digest"]):
            fail(f"images.{service}.config_digest is invalid")
        if item["archive"] != f"{service}.tar":
            fail(f"images.{service}.archive must be {service}.tar")
        if not isinstance(item["archive_sha256"], str) or not HEX.fullmatch(item["archive_sha256"]):
            fail(f"images.{service}.archive_sha256 is invalid")
    return bundle


def verify_archives(bundle: dict, directory: Path) -> None:
    try:
        root = directory.resolve(strict=True)
    except OSError as exc:
        fail(f"archive directory is unavailable: {exc}")
    for service in SERVICES:
        item = bundle["images"][service]
        archive = directory / item["archive"]
        if archive.is_symlink() or not archive.is_file() or archive.resolve(strict=True).parent != root:
            fail(f"{service} archive is missing, symlinked, or outside the transfer directory")
        digest = sha256_file(archive)
        if digest != item["archive_sha256"]:
            fail(f"{service} archive SHA-256 mismatch")
        try:
            with tarfile.open(archive, "r:") as tar:
                members = {member.name: member for member in tar.getmembers() if member.isfile()}
                manifest_member = members.get("manifest.json")
                if manifest_member is None:
                    fail(f"{service} Docker archive has no manifest.json")
                manifest_bytes = tar.extractfile(manifest_member).read()
                docker_manifest = json.loads(manifest_bytes)
                if not isinstance(docker_manifest, list) or len(docker_manifest) != 1:
                    fail(f"{service} Docker archive must contain exactly one platform image")
                record = docker_manifest[0]
                if record.get("RepoTags") != [item["ref"]]:
                    fail(f"{service} Docker archive RepoTags differ from the pinned image ref")
                config_name = record.get("Config")
                if not isinstance(config_name, str) or PurePosixPath(config_name).is_absolute() or ".." in PurePosixPath(config_name).parts:
                    fail(f"{service} Docker archive config path is unsafe")
                config_member = members.get(config_name)
                if config_member is None:
                    fail(f"{service} Docker archive config is missing")
                config_bytes = tar.extractfile(config_member).read()
                if "sha256:" + hashlib.sha256(config_bytes).hexdigest() != item["config_digest"]:
                    fail(f"{service} Docker archive config digest mismatch")
                config = json.loads(config_bytes)
                if config.get("os") != "linux" or config.get("architecture") != "amd64":
                    fail(f"{service} Docker archive platform is not linux/amd64")
                layers = record.get("Layers")
                if not isinstance(layers, list) or not layers:
                    fail(f"{service} Docker archive has no layers")
                for layer in layers:
                    layer_path = PurePosixPath(str(layer))
                    if layer_path.is_absolute() or ".." in layer_path.parts or str(layer) not in members:
                        fail(f"{service} Docker archive contains a missing or unsafe layer")
        except SystemExit:
            raise
        except Exception as exc:
            fail(f"{service} Docker archive is invalid: {exc}")


def verify_local_images(bundle: dict) -> None:
    try:
        info = os.stat(bundle_path)
    except OSError as exc:
        fail(f"image bundle cannot be stat'ed: {exc}")
    if os.geteuid() == 0 and (info.st_uid != 0 or info.st_mode & 0o077):
        fail("server image bundle must be root-owned with mode 0600 or stricter")
    for service in SERVICES:
        item = bundle["images"][service]
        result = subprocess.run(
            ["docker", "image", "inspect", "--format", "{{json .}}", item["ref"]],
            check=False,
            text=True,
            capture_output=True,
        )
        if result.returncode:
            fail(f"{service} image is not loaded under its expected ref")
        try:
            image = json.loads(result.stdout)
        except Exception as exc:
            fail(f"{service} Docker inspect output is invalid: {exc}")
        if image.get("Id") != item["config_digest"]:
            fail(f"{service} loaded image ID differs from the checked archive config digest")
        if image.get("Os") != "linux" or image.get("Architecture") != "amd64":
            fail(f"{service} loaded image platform is not linux/amd64")
        if item["ref"] not in (image.get("RepoTags") or []):
            fail(f"{service} loaded image is not tagged with its expected immutable ref")


parser = argparse.ArgumentParser()
parser.add_argument("bundle")
parser.add_argument("--manifest")
parser.add_argument("--verify-archives")
parser.add_argument("--verify-local", action="store_true")
parser.add_argument("--expected-source-sha")
parser.add_argument("--get-config-digest", choices=SERVICES)
args = parser.parse_args()
bundle_path = Path(args.bundle)
bundle = validate_bundle(bundle_path)
if args.expected_source_sha and bundle["source_sha"] != args.expected_source_sha:
    fail("bundle source SHA differs from the configured application image SHA")
if args.manifest:
    verify_manifest(bundle, Path(args.manifest))
if args.verify_archives:
    verify_archives(bundle, Path(args.verify_archives))
if args.verify_local:
    verify_local_images(bundle)
if args.get_config_digest:
    print(bundle["images"][args.get_config_digest]["config_digest"])
elif not (args.manifest or args.verify_archives or args.verify_local or args.expected_source_sha):
    parser.error("select --manifest, --verify-archives, --verify-local, --expected-source-sha, or --get-config-digest")
else:
    print(f"offline-image-bundle=valid source_sha={bundle['source_sha']} platform={bundle['platform']}")

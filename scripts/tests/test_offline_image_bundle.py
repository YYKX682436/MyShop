from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = "ghcr.io/yykx682436"
APP_SERVICES = ("api", "web")
SERVICES = (*APP_SERVICES, "postgres", "caddy", "node-exporter")
SHA = "a" * 40
BASE_SHA = "b" * 40
DIGESTS = {
    "api": "sha256:" + "1" * 64,
    "web": "sha256:" + "2" * 64,
    "postgres": "sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
    "caddy": "sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648",
    "node-exporter": "sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0",
}
SOURCE_SHAS = {"api": SHA, "web": BASE_SHA, "postgres": "", "caddy": "", "node-exporter": ""}
CONFIG_IDS: dict[str, str] = {}
CONFIGS: dict[str, dict] = {}
LOADED_IDS = {service: "sha256:" + character * 64 for service, character in zip(
    ("api", "web", "postgres", "caddy", "node-exporter"), "abcde"
)}


def source_ref(service: str) -> str:
    if service in {"api", "web"}:
        return f"{REGISTRY}/myshop-{service}@{DIGESTS[service]}"
    return {
        "postgres": f"{REGISTRY}/myshop-postgres:mirror-cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685@{DIGESTS[service]}",
        "caddy": f"{REGISTRY}/myshop-caddy:mirror-5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648@{DIGESTS[service]}",
        "node-exporter": f"{REGISTRY}/myshop-node-exporter:mirror-1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0@{DIGESTS[service]}",
    }[service]


def archive_ref(service: str) -> str:
    if service in {"api", "web"}:
        return f"{REGISTRY}/myshop-{service}:{SOURCE_SHAS[service]}"
    return source_ref(service).split("@", 1)[0]


def make_archive(path: Path, service: str) -> str:
    layer = b"layer-fixture"
    diff_id = "sha256:" + hashlib.sha256(layer).hexdigest()
    config = {
        "architecture": "amd64", "os": "linux",
        "config": {"Env": ["PATH=/usr/bin"], "Labels": {"org.opencontainers.image.revision": SOURCE_SHAS[service] or "infra"}},
        "rootfs": {"type": "layers", "diff_ids": [diff_id]},
    }
    CONFIGS[service] = config
    config_bytes = json.dumps(config, sort_keys=True, separators=(",", ":")).encode()
    config_digest = "sha256:" + hashlib.sha256(config_bytes).hexdigest()
    CONFIG_IDS[service] = config_digest
    config_name = config_digest.split(":", 1)[1] + ".json"
    manifest = [{"Config": config_name, "RepoTags": [archive_ref(service)], "Layers": ["layer/layer.tar"]}]
    with tarfile.open(path, "w:") as outer:
        for name, data in (("manifest.json", json.dumps(manifest).encode()),
                           (config_name, config_bytes), ("layer/layer.tar", layer)):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            outer.addfile(info, __import__("io").BytesIO(data))
    return config_digest


def run(*args: str, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(args, check=False, text=True, capture_output=True, env=env)


with tempfile.TemporaryDirectory(prefix="myshop-offline-bundle-test-") as temp:
    root = Path(temp)
    archive_dir = root / "archives"
    archive_dir.mkdir()
    release = {
        "version": 2, "project": "myshop", "source_sha": SHA,
        "created_at": "2026-11-17T19:35:33Z", "built_services": ["api"],
        "database_migration": False,
        "images": {
            service: {"ref": source_ref(service), "digest": DIGESTS[service], "source_sha": SOURCE_SHAS[service]}
            for service in ("api", "web")
        },
    }
    release_path = root / "release-manifest.json"
    release_path.write_text(json.dumps(release), encoding="utf-8")
    for service in APP_SERVICES:
        make_archive(archive_dir / f"{service}.tar", service)

    bundle_path = root / "image-bundle.json"
    created = run(sys.executable, str(ROOT / "scripts/create-offline-image-bundle.py"),
                  str(release_path), str(archive_dir), str(bundle_path))
    assert created.returncode == 0, created.stderr
    bundle_data = json.loads(bundle_path.read_text(encoding="utf-8"))
    for service in SERVICES:
        bundle_data["images"][service]["loaded_image_id"] = LOADED_IDS[service]
    bundle_path.write_text(json.dumps(bundle_data), encoding="utf-8")
    verify = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                 str(bundle_path), "--manifest", str(release_path), "--verify-archives", str(archive_dir))
    assert verify.returncode == 0, verify.stderr

    metadata = {
        archive_ref(service): {
            "Id": LOADED_IDS[service], "Os": "linux", "Architecture": "amd64",
            "RepoTags": [archive_ref(service)], "Config": CONFIGS[service]["config"],
            "RootFS": {"Layers": CONFIGS[service]["rootfs"]["diff_ids"]},
        }
            for service in APP_SERVICES
    }
    for service in ("postgres", "caddy", "node-exporter"):
        repo = source_ref(service).split("@", 1)[0].rsplit(":", 1)[0]
        metadata[archive_ref(service)] = {
            "Id": LOADED_IDS[service], "Os": "linux", "Architecture": "amd64",
            "RepoTags": [archive_ref(service)], "RepoDigests": [f"{repo}@{DIGESTS[service]}"],
            "Config": {}, "RootFS": {"Layers": []},
        }
    metadata_path = root / "fake-images.json"
    metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
    fake_bin = root / "bin"
    fake_bin.mkdir()
    fake_docker = fake_bin / "docker"
    fake_docker.write_text(
        "#!/usr/bin/env python3\n"
        "import json, os, sys\n"
        "images=json.load(open(os.environ['FAKE_IMAGE_METADATA']))\n"
        "ref=sys.argv[-1]\n"
        "image=images.get(ref)\n"
        "if image is None: raise SystemExit('missing fake image')\n"
        "if os.environ.get('FAKE_BAD_REF') == ref: image['Config']['Env']=['PATH=/tampered']\n"
        "if os.environ.get('FAKE_MISSING_INFRA_DIGEST') == ref: image['RepoDigests']=[]\n"
        "print(json.dumps(image if '--format' in sys.argv else [image]))\n", encoding="utf-8")
    fake_docker.chmod(0o755)
    local_env = os.environ.copy()
    local_env["PATH"] = str(fake_bin) + os.pathsep + local_env.get("PATH", "")
    local_env["FAKE_IMAGE_METADATA"] = str(metadata_path)
    unrecorded = json.loads(bundle_path.read_text(encoding="utf-8"))
    unrecorded["images"]["api"]["loaded_image_id"] = ""
    unrecorded_path = root / "unrecorded-image-bundle.json"
    unrecorded_path.write_text(json.dumps(unrecorded), encoding="utf-8")
    unrecorded_check = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                           str(unrecorded_path), "--verify-local", env=local_env)
    assert unrecorded_check.returncode != 0 and "has not been recorded" in unrecorded_check.stderr
    recorded_bundle = json.loads(bundle_path.read_text(encoding="utf-8"))
    assert recorded_bundle["version"] == 4
    for service in SERVICES:
        assert recorded_bundle["images"][service]["loaded_image_id"] == LOADED_IDS[service]
    for service in APP_SERVICES:
        assert recorded_bundle["images"][service]["loaded_image_id"] != CONFIG_IDS[service]
    local_check = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                      str(bundle_path), "--manifest", str(release_path), "--verify-local", env=local_env)
    assert local_check.returncode == 0, local_check.stderr
    legacy_repo_digests = {
        "postgres": "sha256:60a518396f28c1c42c0fb640a41bc3516943802f683e59f7fe741a7df3d86822",
        "caddy": "sha256:ee92234975200c8d302bf9b407ac32061eae9315c9cb6c7e3ddfd58c7a675ac6",
        "node-exporter": "sha256:daafcbdb24336cb32dbe5590325d1a0fc61db5dcf43e53b414c99bdc8fb75fdc",
    }
    for service, local_digest in legacy_repo_digests.items():
        repo = source_ref(service).split("@", 1)[0].rsplit(":", 1)[0]
        metadata[archive_ref(service)]["RepoDigests"] = [f"{repo}@{local_digest}"]
    metadata_path.write_text(json.dumps(metadata), encoding="utf-8")
    legacy_local_check = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                             str(bundle_path), "--manifest", str(release_path), "--verify-local", env=local_env)
    assert legacy_local_check.returncode == 0, legacy_local_check.stderr
    loaded_id = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                    str(bundle_path), "--get-loaded-image-id", "api")
    assert loaded_id.returncode == 0 and loaded_id.stdout.strip() == LOADED_IDS["api"]
    runtime_ref = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                      str(bundle_path), "--get-runtime-ref", "web")
    assert runtime_ref.returncode == 0 and runtime_ref.stdout.strip() == archive_ref("web")

    bad_local_env = local_env.copy()
    bad_local_env["FAKE_BAD_REF"] = archive_ref("web")
    bad_runtime = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                      str(bundle_path), "--verify-local", env=bad_local_env)
    assert bad_runtime.returncode != 0 and "runtime config differs" in bad_runtime.stderr

    bad_infra_env = local_env.copy()
    bad_infra_env["FAKE_MISSING_INFRA_DIGEST"] = archive_ref("postgres")
    bad_infra = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                    str(bundle_path), "--verify-local", env=bad_infra_env)
    assert bad_infra.returncode != 0 and "preinstalled RepoDigest differs" in bad_infra.stderr

    # A new API release carries only api.tar; the accepted Web is verified by
    # its existing local tag, image ID, config, and rootfs layers.
    partial_dir = root / "partial-transfer"
    partial_dir.mkdir()
    (partial_dir / "api.tar").write_bytes((archive_dir / "api.tar").read_bytes())
    partial_release = {**release, "version": 3, "accepted_sha": BASE_SHA}
    partial_release_path = root / "partial-release.json"
    partial_release_path.write_text(json.dumps(partial_release), encoding="utf-8")
    accepted_state = {"version": 1, "active": {"source_sha": BASE_SHA, "images": {
        "api": {"digest": "sha256:" + "0" * 64, "source_sha": BASE_SHA,
                "ref": f"{REGISTRY}/myshop-api@sha256:{'0' * 64}", "image_id": LOADED_IDS["api"],
                "runtime_ref": archive_ref("api")},
        "web": {"digest": DIGESTS["web"], "source_sha": BASE_SHA,
                "ref": source_ref("web"), "image_id": LOADED_IDS["web"],
                "runtime_ref": archive_ref("web")},
    }}}
    accepted_path = root / "accepted.json"
    accepted_path.write_text(json.dumps(accepted_state), encoding="utf-8")
    transfer = {"version": 1, "service": "api", "source_sha": SHA,
                "registry_digest": DIGESTS["api"], "image_id": CONFIG_IDS["api"],
                "exported_config_digest": CONFIG_IDS["api"],
                "runtime_config_digest": "sha256:" + hashlib.sha256(json.dumps(
                    CONFIGS["api"]["config"], sort_keys=True, separators=(",", ":"),
                    ensure_ascii=False).encode()).hexdigest(),
                "tar_sha256": hashlib.sha256((partial_dir / "api.tar").read_bytes()).hexdigest(),
                "archive_tag": archive_ref("api"),
                "rootfs_diff_ids": CONFIGS["api"]["rootfs"]["diff_ids"]}
    (partial_dir / "api.record.json").write_text(json.dumps(transfer), encoding="utf-8")
    partial_bundle = root / "partial-bundle.json"
    created_partial = run(sys.executable, str(ROOT / "scripts/create-offline-image-bundle.py"),
                          str(partial_release_path), str(partial_dir), str(partial_bundle), str(accepted_path),
                          env=local_env)
    assert created_partial.returncode == 0, created_partial.stderr
    partial_data = json.loads(partial_bundle.read_text())
    assert partial_data["version"] == 5 and partial_data["images"]["web"]["reuse_accepted"] is True
    assert partial_data["images"]["api"]["reuse_accepted"] is False
    for service in SERVICES:
        partial_data["images"][service]["loaded_image_id"] = LOADED_IDS[service]
    partial_bundle.write_text(json.dumps(partial_data), encoding="utf-8")
    verified_partial = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                           str(partial_bundle), "--manifest", str(partial_release_path),
                           "--verify-archives", str(partial_dir), "--verify-local", env=local_env)
    assert verified_partial.returncode == 0, verified_partial.stderr
    (partial_dir / "web.tar").write_bytes(b"unexpected")
    rejected_extra = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                         str(partial_bundle), "--verify-archives", str(partial_dir), env=local_env)
    assert rejected_extra.returncode != 0 and "unexpectedly transferred" in rejected_extra.stderr

    tampered_archive = archive_dir / "api.tar"
    tampered_archive.write_bytes(tampered_archive.read_bytes() + b"corrupt")
    tamper_check = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                       str(bundle_path), "--manifest", str(release_path), "--verify-archives", str(archive_dir))
    assert tamper_check.returncode != 0 and "SHA-256 mismatch" in tamper_check.stderr

    wrong_source = json.loads(bundle_path.read_text(encoding="utf-8"))
    wrong_source["images"]["caddy"]["source_digest"] = "sha256:" + "9" * 64
    wrong_source_path = root / "wrong-source.json"
    wrong_source_path.write_text(json.dumps(wrong_source), encoding="utf-8")
    source_check = run(sys.executable, str(ROOT / "scripts/verify-offline-image-bundle.py"),
                       str(wrong_source_path), "--manifest", str(release_path))
    assert source_check.returncode != 0 and "approved infrastructure digest" in source_check.stderr

    wrapper_root = root / "runtime"
    wrapper_root.mkdir()
    compose_file = wrapper_root / "docker-compose.shared-server.yml"
    compose_file.write_text("name: myshop-friend-prod\nservices: {}\n", encoding="utf-8")
    env_file = wrapper_root / ".env"
    env_file.write_text(
        "MYSHOP_COMPOSE_PROJECT_NAME=myshop-friend-prod\n"
        f"MYSHOP_COMPOSE_FILE={compose_file}\n"
        f"MYSHOP_API_IMAGE={archive_ref('api')}\n"
        f"MYSHOP_WEB_IMAGE={archive_ref('web')}\n"
        "MYSHOP_OFFLINE_IMAGES=1\n", encoding="utf-8")
    wrapper_bin = root / "wrapper-bin"
    wrapper_bin.mkdir()
    wrapper_docker = wrapper_bin / "docker"
    wrapper_docker.write_text(
        "#!/usr/bin/env bash\nset -eu\n"
        "if [[ \"$1 $2\" == 'compose version' ]]; then printf 'Docker Compose version v2.30.0\\n'; exit 0; fi\n"
        "printf '%s\\n' \"$*\" >>\"$FAKE_DOCKER_LOG\"\n", encoding="utf-8")
    wrapper_docker.chmod(0o755)
    wrapper_env = os.environ.copy()
    wrapper_env.update({"PATH": str(wrapper_bin) + os.pathsep + os.environ.get("PATH", ""),
                        "MYSHOP_RUNTIME_ROOT": str(wrapper_root), "MYSHOP_ENV_FILE": str(env_file),
                        "MYSHOP_COMPOSE_FILE": str(compose_file), "MYSHOP_COMPOSE_PROJECT_NAME": "myshop-friend-prod",
                        "MYSHOP_APPROVED_REGISTRY": REGISTRY, "FAKE_DOCKER_LOG": str(root / "wrapper-docker.log")})
    up = run("bash", str(ROOT / "scripts/myshop-compose.sh"), "--env-file", str(env_file), "up", "-d", env=wrapper_env)
    assert up.returncode == 0, up.stderr
    wrapper_log = (root / "wrapper-docker.log").read_text(encoding="utf-8")
    assert "--pull never" in wrapper_log
    pull = run("bash", str(ROOT / "scripts/myshop-compose.sh"), "--env-file", str(env_file), "pull", env=wrapper_env)
    assert pull.returncode != 0 and "refuses Docker Compose pull" in pull.stderr
    assert len((root / "wrapper-docker.log").read_text(encoding="utf-8").splitlines()) == 1

print("offline image bundle digest, provenance, platform, archive, runtime, layer, and image-ID checks passed")

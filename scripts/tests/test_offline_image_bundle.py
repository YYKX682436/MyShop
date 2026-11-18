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
SHA = "78e991d2372a6c76ccdf6d5e1ac50e70a83b6339"
DIGESTS = {
    "api": "sha256:" + "1" * 64,
    "web": "sha256:" + "2" * 64,
    "postgres": "sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
    "caddy": "sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648",
    "node-exporter": "sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0",
}
CONFIG_IDS: dict[str, str] = {}


def image_ref(service: str) -> str:
    if service in {"api", "web"}:
        return f"{REGISTRY}/myshop-{service}:{SHA}"
    digest = DIGESTS[service].split(":", 1)[1]
    return f"{REGISTRY}/myshop-{service}:mirror-{digest}"


def make_archive(path: Path, service: str) -> str:
    layer = b"layer-fixture"
    diff_id = "sha256:" + hashlib.sha256(layer).hexdigest()
    config = {"architecture": "amd64", "os": "linux", "rootfs": {"type": "layers", "diff_ids": [diff_id]}}
    config_bytes = json.dumps(config, sort_keys=True, separators=(",", ":")).encode()
    config_digest = "sha256:" + hashlib.sha256(config_bytes).hexdigest()
    CONFIG_IDS[service] = config_digest
    config_name = config_digest.split(":", 1)[1] + ".json"
    manifest = [{"Config": config_name, "RepoTags": [image_ref(service)], "Layers": ["layer/layer.tar"]}]
    with tarfile.open(path, "w:") as outer:
        for name, data in (
            ("manifest.json", json.dumps(manifest).encode()),
            (config_name, config_bytes),
            ("layer/layer.tar", layer),
        ):
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
        "version": 1,
        "project": "myshop",
        "source_sha": SHA,
        "created_at": "2026-11-17T19:35:33Z",
        "images": {
            service: {
                "ref": image_ref(service),
                "digest": DIGESTS[service],
            }
            for service in ("api", "web")
        },
    }
    release_path = root / "release-manifest.json"
    release_path.write_text(json.dumps(release), encoding="utf-8")
    for service in ("api", "web", "postgres", "caddy", "node-exporter"):
        make_archive(archive_dir / f"{service}.tar", service)

    bundle_path = root / "image-bundle.json"
    created = run(
        sys.executable,
        str(ROOT / "scripts/create-offline-image-bundle.py"),
        str(release_path),
        str(archive_dir),
        str(bundle_path),
    )
    assert created.returncode == 0, created.stderr
    verify = run(
        sys.executable,
        str(ROOT / "scripts/verify-offline-image-bundle.py"),
        str(bundle_path),
        "--manifest",
        str(release_path),
        "--verify-archives",
        str(archive_dir),
    )
    assert verify.returncode == 0, verify.stderr

    metadata = {image_ref(service): CONFIG_IDS[service] for service in CONFIG_IDS}
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
        "image_id=images.get(ref, 'missing')\n"
        "if os.environ.get('FAKE_BAD_REF') == ref: image_id='sha256:'+'f'*64\n"
        "print(json.dumps({'Id':image_id,'Os':'linux','Architecture':'amd64','RepoTags':[ref]}))\n",
        encoding="utf-8",
    )
    fake_docker.chmod(0o755)
    local_env = os.environ.copy()
    local_env["PATH"] = str(fake_bin) + os.pathsep + local_env.get("PATH", "")
    local_env["FAKE_IMAGE_METADATA"] = str(metadata_path)
    local_check = run(
        sys.executable,
        str(ROOT / "scripts/verify-offline-image-bundle.py"),
        str(bundle_path),
        "--manifest",
        str(release_path),
        "--verify-local",
        env=local_env,
    )
    assert local_check.returncode == 0, local_check.stderr

    tampered_archive = archive_dir / "api.tar"
    tampered_archive.write_bytes(tampered_archive.read_bytes() + b"corrupt")
    tamper_check = run(
        sys.executable,
        str(ROOT / "scripts/verify-offline-image-bundle.py"),
        str(bundle_path),
        "--manifest",
        str(release_path),
        "--verify-archives",
        str(archive_dir),
    )
    assert tamper_check.returncode != 0 and "SHA-256 mismatch" in tamper_check.stderr

    wrong_source = json.loads(bundle_path.read_text(encoding="utf-8"))
    wrong_source["images"]["caddy"]["source_digest"] = "sha256:" + "9" * 64
    wrong_source_path = root / "wrong-source.json"
    wrong_source_path.write_text(json.dumps(wrong_source), encoding="utf-8")
    source_check = run(
        sys.executable,
        str(ROOT / "scripts/verify-offline-image-bundle.py"),
        str(wrong_source_path),
        "--manifest",
        str(release_path),
    )
    assert source_check.returncode != 0 and "approved mirror" in source_check.stderr

    wrapper_root = root / "runtime"
    wrapper_root.mkdir()
    compose_file = wrapper_root / "docker-compose.shared-server.yml"
    compose_file.write_text("name: myshop-friend-prod\nservices: {}\n", encoding="utf-8")
    env_file = wrapper_root / ".env"
    env_file.write_text(
        "MYSHOP_COMPOSE_PROJECT_NAME=myshop-friend-prod\n"
        f"MYSHOP_COMPOSE_FILE={compose_file}\n"
        f"MYSHOP_API_IMAGE={image_ref('api')}\n"
        f"MYSHOP_WEB_IMAGE={image_ref('web')}\n"
        "MYSHOP_OFFLINE_IMAGES=1\n",
        encoding="utf-8",
    )
    wrapper_bin = root / "wrapper-bin"
    wrapper_bin.mkdir()
    wrapper_docker = wrapper_bin / "docker"
    wrapper_docker.write_text(
        "#!/usr/bin/env bash\n"
        "set -eu\n"
        "if [[ \"$1 $2\" == 'compose version' ]]; then printf 'Docker Compose version v2.30.0\\n'; exit 0; fi\n"
        "printf '%s\\n' \"$*\" >>\"$FAKE_DOCKER_LOG\"\n",
        encoding="utf-8",
    )
    wrapper_docker.chmod(0o755)
    wrapper_env = os.environ.copy()
    wrapper_env.update(
        {
            "PATH": str(wrapper_bin) + os.pathsep + os.environ.get("PATH", ""),
            "MYSHOP_RUNTIME_ROOT": str(wrapper_root),
            "MYSHOP_ENV_FILE": str(env_file),
            "MYSHOP_COMPOSE_FILE": str(compose_file),
            "MYSHOP_COMPOSE_PROJECT_NAME": "myshop-friend-prod",
            "MYSHOP_APPROVED_REGISTRY": REGISTRY,
            "FAKE_DOCKER_LOG": str(root / "wrapper-docker.log"),
        }
    )
    up = run("bash", str(ROOT / "scripts/myshop-compose.sh"), "--env-file", str(env_file), "up", "-d", env=wrapper_env)
    assert up.returncode == 0, up.stderr
    wrapper_log = (root / "wrapper-docker.log").read_text(encoding="utf-8")
    assert "--pull never" in wrapper_log
    pull = run("bash", str(ROOT / "scripts/myshop-compose.sh"), "--env-file", str(env_file), "pull", env=wrapper_env)
    assert pull.returncode != 0 and "refuses Docker Compose pull" in pull.stderr
    assert len((root / "wrapper-docker.log").read_text(encoding="utf-8").splitlines()) == 1

print("offline image bundle source, platform, archive integrity, and loaded-image identity checks passed")

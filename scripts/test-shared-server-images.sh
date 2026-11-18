#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
python3 - "$repo_dir" <<'PY'
import re
import sys
from pathlib import Path

root = Path(sys.argv[1])
compose = (root / "docker-compose.shared-server.yml").read_text(encoding="utf-8")
workflow = (root / ".github/workflows/cicd.yml").read_text(encoding="utf-8")
legacy = (root / "docker-compose.prod.yml").read_text(encoding="utf-8")

expected = {
    "postgres": ("mirror-cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685", "cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685"),
    "caddy": ("mirror-5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648", "5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648"),
    "node-exporter": ("mirror-1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0", "1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0"),
}
for name, (tag, digest) in expected.items():
    ref = f"ghcr.io/yykx682436/myshop-{name}:{tag}@sha256:{digest}"
    if ref not in compose:
        raise SystemExit(f"shared Compose is missing exact mirrored ref for {name}")
if re.search(r"image:\s*(?:postgres:|caddy:|quay\.io/)", compose):
    raise SystemExit("shared Compose still depends on a non-GHCR base registry")

for source, digest in (
    ("docker.io/library/postgres", expected["postgres"][1]),
    ("docker.io/library/caddy", expected["caddy"][1]),
    ("quay.io/prometheus/node-exporter", expected["node-exporter"][1]),
):
    if f"{source}|sha256:{digest}|" not in workflow:
        raise SystemExit(f"workflow source pin missing: {source}")
for invariant in (
    "skopeo copy --all --preserve-digests",
    '[[ "$source_digest" == "$expected" ]]',
    '[[ "$target_digest" == "$source_digest" ]]',
    "github.ref == 'refs/heads/codex/shared-server-prep'",
    "packages: write",
):
    if invariant not in workflow:
        raise SystemExit(f"mirror workflow invariant missing: {invariant}")
for source in (
    "postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
    "quay.io/prometheus/node-exporter:v1.12.1@sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0",
    "caddy:2-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648",
):
    if source not in legacy:
        raise SystemExit("legacy production Compose base image was changed")
print("shared base image mirror references are digest-pinned; original production Compose remains unchanged")
PY

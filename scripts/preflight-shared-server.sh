#!/usr/bin/env bash
set -Eeuo pipefail

die() { printf '[myshop-shared-preflight] ERROR: %s\n' "$*" >&2; exit 1; }
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
REPO_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd -P)
PROJECT_ROOT=${MYSHOP_RUNTIME_ROOT:-$REPO_ROOT}
PROJECT_NAME=${MYSHOP_COMPOSE_PROJECT_NAME:-myshop-friend-prod}
COMPOSE_FILE=${MYSHOP_COMPOSE_FILE:-$PROJECT_ROOT/docker-compose.shared-server.yml}
ENV_FILE=${MYSHOP_ENV_FILE:-$PROJECT_ROOT/.env}
MODE=first-install
while (( $# )); do
  case "$1" in
    --mode)
      (( $# >= 2 )) || die '--mode requires first-install or upgrade'
      MODE=$2
      shift 2
      ;;
      *) die "unknown argument: $1" ;;
  esac
done
[[ "$MODE" == first-install || "$MODE" == bootstrap-retry || "$MODE" == upgrade ]] || die '--mode must be first-install, bootstrap-retry, or upgrade'

command -v docker >/dev/null 2>&1 || die 'docker is unavailable'
docker compose version >/dev/null 2>&1 || die 'Docker Compose v2 is unavailable'
[[ -f "$COMPOSE_FILE" ]] || die "shared-host Compose file is missing: $COMPOSE_FILE"
[[ -f "$ENV_FILE" && ! -L "$ENV_FILE" ]] || die 'private environment file is missing or is a symlink'
[[ "$PROJECT_NAME" =~ ^[a-z0-9][a-z0-9_-]*$ ]] || die 'MYSHOP_COMPOSE_PROJECT_NAME is invalid'

tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT
MYSHOP_COMPOSE_PROJECT_NAME="$PROJECT_NAME" COMPOSE_PROJECT_NAME="$PROJECT_NAME" \
  docker compose --project-directory "$PROJECT_ROOT" --file "$COMPOSE_FILE" \
  --env-file "$ENV_FILE" config --format json >"$tmp_dir/compose.json" \
  || die 'Compose configuration could not be rendered'

python3 - "$tmp_dir/compose.json" "$ENV_FILE" "$PROJECT_NAME" "$MODE" "$PROJECT_ROOT" "$SCRIPT_DIR" <<'PY'
from __future__ import annotations

import ipaddress
import json
import os
import re
import shutil
import socket
import stat
import subprocess
import sys
from pathlib import Path
from urllib.parse import urlsplit

config_path = Path(sys.argv[1])
env_path = Path(sys.argv[2])
expected_project = sys.argv[3]
mode = sys.argv[4]
runtime_root = Path(sys.argv[5])
script_dir = Path(sys.argv[6])


def fail(message: str) -> "NoReturn":
    raise SystemExit(f"[myshop-shared-preflight] ERROR: {message}")


def run(*args: str) -> str:
    result = subprocess.run(args, check=True, text=True, capture_output=True)
    return result.stdout.strip()


def version_tuple(raw: str, label: str) -> tuple[int, ...]:
    match = re.search(r"(?:^|[^0-9])v?(\d+)\.(\d+)(?:\.(\d+))?", raw)
    if not match:
        fail(f"could not parse {label} version")
    return tuple(int(part or 0) for part in match.groups())


def assert_no_symlink_components(path: Path, label: str) -> Path:
    if not path.is_absolute():
        fail(f"{label} must be an absolute path")
    normalized = Path(os.path.normpath(str(path)))
    cursor = Path(normalized.anchor)
    for part in normalized.parts[1:]:
        cursor /= part
        try:
            info = cursor.lstat()
        except FileNotFoundError:
            fail(f"{label} component does not exist: {cursor}")
        if stat.S_ISLNK(info.st_mode):
            fail(f"{label} contains a symlink component: {cursor}")
    resolved = normalized.resolve(strict=True)
    if resolved != normalized:
        fail(f"{label} resolves outside its configured path: {resolved}")
    return resolved


if os.geteuid() != 0:
    fail("run as root on the target host so Docker and private-file checks are authoritative")
env_stat = env_path.stat()
if not stat.S_ISREG(env_stat.st_mode) or env_stat.st_uid != 0 or stat.S_IMODE(env_stat.st_mode) & 0o077:
    fail("environment file must be a regular root-owned file with mode 0600 or stricter")

try:
    engine_version = run("docker", "version", "--format", "{{.Server.Version}}")
    compose_version = run("docker", "compose", "version", "--short")
except Exception as exc:
    fail(f"Docker Engine/Compose version check failed: {exc}")
engine_tuple = version_tuple(engine_version, "Docker Engine")
compose_tuple = version_tuple(compose_version, "Docker Compose")
if engine_tuple < (20, 10, 0):
    fail(f"Docker Engine {engine_version} is below the supported 20.10.0 minimum; do not auto-upgrade here")
if compose_tuple < (2, 20, 0):
    fail(f"Docker Compose {compose_version} is below the supported 2.20.0 minimum; do not auto-upgrade here")

try:
    config = json.loads(config_path.read_text(encoding="utf-8"))
except Exception as exc:
    fail(f"rendered Compose configuration is invalid: {exc}")
if config.get("name") != expected_project:
    fail("rendered Compose project name does not match MYSHOP_COMPOSE_PROJECT_NAME")
if not expected_project.startswith("myshop-friend-"):
    fail("shared-host project name must use the dedicated myshop-friend-* namespace")
services = config.get("services", {})
if set(services) != {"db", "api", "web", "node-exporter", "caddy"}:
    fail("rendered service set differs from the shared-host design")
if any("build" in service for service in services.values()):
    fail("shared-host Compose must use prebuilt images only")

image_pattern = re.compile(r"^ghcr\.io/yykx682436/myshop-(api|web):([0-9a-f]{40})$")
app_shas = set()
for service_name in ("api", "web"):
    image = str(services[service_name].get("image", ""))
    match = image_pattern.fullmatch(image)
    if not match or match.group(1) != service_name:
        fail(f"{service_name} image must use an immutable GHCR full-SHA tag")
    app_shas.add(match.group(2))
if len(app_shas) != 1:
    fail("API and Web images must use the same release SHA")
expected_digest_refs = {
    "db": "ghcr.io/yykx682436/myshop-postgres:mirror-cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
    "caddy": "ghcr.io/yykx682436/myshop-caddy:mirror-5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648",
    "node-exporter": "ghcr.io/yykx682436/myshop-node-exporter:mirror-1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0@sha256:1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0",
}
offline_tag_refs = {
    "db": "ghcr.io/yykx682436/myshop-postgres:mirror-cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685",
    "caddy": "ghcr.io/yykx682436/myshop-caddy:mirror-5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648",
    "node-exporter": "ghcr.io/yykx682436/myshop-node-exporter:mirror-1b4e4438faca4dd7e001dd445d161a4a2091b0fededa84093b3a8dfeae1f1be0",
}
ports = []
for service_name, service in services.items():
    for port in service.get("ports", []) or []:
        ports.append((service_name, port))
if len(ports) != 1 or ports[0][0] != "caddy":
    fail("Caddy must be the only service with a published host port")
port = ports[0][1]
if str(port.get("host_ip", "")) != "127.0.0.1" or int(port.get("target", 0)) != 80 or port.get("protocol", "tcp") != "tcp":
    fail("the only host port must bind 127.0.0.1 to Caddy HTTP port 80")
published = int(port.get("published", "0"))
if published < 1024 or published > 65535:
    fail("MYSHOP_WEB_PORT must be an unprivileged TCP port")

edge = config.get("networks", {}).get("edge", {})
subnets = edge.get("ipam", {}).get("config", []) or []
if len(subnets) != 1 or not subnets[0].get("subnet"):
    fail("edge network must have one audited subnet")
try:
    edge_network = ipaddress.ip_network(subnets[0]["subnet"], strict=True)
    caddy_ip = ipaddress.ip_address(services["caddy"].get("networks", {}).get("edge", {}).get("ipv4_address", ""))
except ValueError as exc:
    fail(f"edge subnet or Caddy address is invalid: {exc}")
if caddy_ip not in edge_network or caddy_ip in (edge_network.network_address, edge_network.broadcast_address, edge_network.network_address + 1):
    fail("Caddy address must be a usable host address other than the Docker gateway")
for network_name in ("database", "metrics"):
    if not config.get("networks", {}).get(network_name, {}).get("internal", False):
        fail(f"{network_name} network must be internal")
db_networks = services["db"].get("networks", {})
api_networks = services["api"].get("networks", {})
exporter_networks = services["node-exporter"].get("networks", {})
if "edge" in db_networks or "database" not in api_networks or "metrics" not in api_networks:
    fail("PostgreSQL must stay off the edge network and API must join its private networks")
if "edge" in exporter_networks or set(exporter_networks) != {"metrics"}:
    fail("node-exporter must stay on its private metrics network")

api_env = services["api"].get("environment", {}) or {}
if str(api_env.get("RATE_LIMIT_TRUSTED_PROXIES", "")) != str(caddy_ip):
    fail("RATE_LIMIT_TRUSTED_PROXIES must equal the fixed Caddy address")
if str(api_env.get("AUTH_COOKIE_SECURE", "")).lower() != "true":
    fail("AUTH_COOKIE_SECURE must be true")
public_url = str(api_env.get("PUBLIC_SITE_URL", ""))
parsed_url = urlsplit(public_url)
if (parsed_url.scheme != "https" or parsed_url.netloc not in {"mc.mc520.top", "myshop-candidate.mc520.top"}
        or parsed_url.path or parsed_url.query or parsed_url.fragment):
    fail("PUBLIC_SITE_URL must be the canonical production or temporary candidate HTTPS origin")
db_password = str(api_env.get("DB_PASSWORD", ""))
jwt_secret = str(api_env.get("JWT_SECRET", ""))
if len(db_password) < 24 or len(jwt_secret) < 32:
    fail("DB_PASSWORD and JWT_SECRET must be non-placeholder secrets of at least 24 and 32 characters")
caddy_env = services["caddy"].get("environment", {}) or {}
if str(caddy_env.get("BAOTA_NGINX_PROXY_SOURCE_CONFIRMED", "")) != "1":
    fail("set MYSHOP_BAOTA_PROXY_SOURCE_CONFIRMED=1 only after observing Caddy's actual Baota proxy peer address")
proxy_cidr_text = str(caddy_env.get("BAOTA_NGINX_PROXY_CIDR", ""))
try:
    proxy_cidr = ipaddress.ip_network(proxy_cidr_text, strict=True)
except ValueError:
    fail("BAOTA_NGINX_PROXY_CIDR must be the audited Baota-to-Caddy source as a /32 or /128 host prefix")
if proxy_cidr.prefixlen != proxy_cidr.max_prefixlen:
    fail("BAOTA_NGINX_PROXY_CIDR must trust exactly one audited host address (/32 or /128)")

data_root_raw = env_path.read_text(encoding="utf-8")
env_values: dict[str, str] = {}
for line in data_root_raw.splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    env_values[key] = value.strip().strip("\"'")
offline_images = env_values.get("MYSHOP_OFFLINE_IMAGES", "0")
if offline_images not in {"0", "1"}:
    fail("MYSHOP_OFFLINE_IMAGES must be 0 or 1")
for service_name, image in ((name, str(services[name].get("image", ""))) for name in expected_digest_refs):
    if image == expected_digest_refs[service_name]:
        continue
    if offline_images != "1" or image != offline_tag_refs[service_name]:
        fail(f"{service_name} infrastructure image is not the approved digest-pinned image or offline-import tag")
if offline_images == "1":
    bundle_path = Path(env_values.get("MYSHOP_OFFLINE_IMAGE_BUNDLE_FILE", ""))
    if not bundle_path.is_file() or bundle_path.is_symlink():
        fail("offline image mode requires a regular, non-symlink image bundle file")
    manifest_path = Path(env_values.get("MYSHOP_RELEASE_MANIFEST_FILE", ""))
    if not manifest_path.is_file() or manifest_path.is_symlink():
        fail("offline image mode requires a regular, non-symlink release manifest file")
    verify_script = script_dir / "verify-offline-image-bundle.py"
    try:
        subprocess.run([
            sys.executable, str(verify_script), str(bundle_path), "--manifest", str(manifest_path),
            "--verify-local", "--expected-source-sha", next(iter(app_shas), ""),
        ], check=True)
    except Exception as exc:
        fail(f"offline image bundle verification failed: {exc}")
configured_root = Path(env_values.get("MYSHOP_DATA_ROOT", ""))
if str(configured_root) != "/srv/myshop-friend":
    fail("MYSHOP_DATA_ROOT must be exactly /srv/myshop-friend")
data_root = assert_no_symlink_components(configured_root, "MYSHOP_DATA_ROOT")
if not data_root.is_dir():
    fail("MYSHOP_DATA_ROOT must already exist as a directory")
runtime_root = assert_no_symlink_components(runtime_root, "MYSHOP_RUNTIME_ROOT")
assert_no_symlink_components(env_path, "MYSHOP_ENV_FILE")

bind_targets = {"/app/uploads": "uploads", "/app/private-uploads": "private-uploads", "/app/ipdb": "ipdb"}
api_mounts = [m for m in (services["api"].get("volumes", []) or []) if m.get("target") in bind_targets]
if len(api_mounts) != len(bind_targets):
    fail("API must have exactly the three dedicated persistence bind mounts")
for mount in api_mounts:
    target = mount["target"]
    suffix = bind_targets[target]
    source = assert_no_symlink_components(Path(str(mount.get("source", ""))), f"volume source for {target}")
    expected_source = data_root / suffix
    if source != expected_source or not source.is_dir():
        fail(f"{target} must resolve to the existing dedicated path {expected_source}")
    if mount.get("bind", {}).get("create_host_path") is not False:
        fail(f"{target} must not be created implicitly by Compose")

db_mounts = services["db"].get("volumes", []) or []
if len(db_mounts) != 1 or db_mounts[0].get("type") != "volume" or db_mounts[0].get("source") != "postgres_data":
    fail("PostgreSQL must use only its dedicated postgres_data named volume")
volume_config = config.get("volumes", {}).get("postgres_data", {})
if volume_config.get("external", False) or volume_config.get("name", f"{expected_project}_postgres_data") != f"{expected_project}_postgres_data":
    fail("PostgreSQL volume name must be isolated under the shared-host project")
for network_name in ("edge", "database", "metrics"):
    configured = config.get("networks", {}).get(network_name, {})
    if configured.get("name", f"{expected_project}_{network_name}") != f"{expected_project}_{network_name}":
        fail(f"Compose network {network_name} must be isolated under the shared-host project")

def overlap_message(candidate: ipaddress._BaseNetwork, other: ipaddress._BaseNetwork, label: str) -> str | None:
    return f"edge subnet {candidate} overlaps {label} {other}" if candidate.version == other.version and candidate.overlaps(other) else None

expected_networks = {f"{expected_project}_edge", f"{expected_project}_database", f"{expected_project}_metrics"}
project_network_names = set(run("docker", "network", "ls", "--filter", f"label=com.docker.compose.project={expected_project}", "--format", "{{.Name}}").splitlines())
project_volumes = set(run("docker", "volume", "ls", "--filter", f"label=com.docker.compose.project={expected_project}", "--format", "{{.Name}}").splitlines())
project_container_rows = run(
    "docker", "ps", "-a", "--filter", f"label=com.docker.compose.project={expected_project}",
    "--format", "{{.Label \"com.docker.compose.service\"}}|{{.ID}}|{{.Names}}|{{.Image}}|{{.State}}"
).splitlines()
all_container_names = set(run("docker", "ps", "-a", "--format", "{{.Names}}").splitlines())
all_volume_names = set(run("docker", "volume", "ls", "-q").splitlines())
all_network_names = set(run("docker", "network", "ls", "--format", "{{.Name}}").splitlines())
expected_volume = f"{expected_project}_postgres_data"
expected_services = {"db", "api", "web", "node-exporter", "caddy"}
if mode == "first-install":
    if project_network_names or project_volumes or project_container_rows:
        fail(f"first-install requires an empty {expected_project} project; use bootstrap-retry after a failed first acceptance")
    for name in {expected_volume, *expected_networks}:
        if name in all_volume_names or name in all_network_names:
            fail(f"unlabelled Docker resource already uses the reserved MyShop name: {name}")
    reserved_containers = {f"{expected_project}-{service}-1" for service in expected_services}
    if reserved_containers & all_container_names:
        fail("an unlabelled container already uses a reserved shared-server service name")
elif mode == "bootstrap-retry":
    state_path = Path(env_values.get("MYSHOP_DEPLOY_STATE", str(runtime_root / "deploy-state.json")))
    if state_path.exists():
        fail("bootstrap-retry is only valid while no accepted deployment state exists")
    candidates_dir = runtime_root / "candidates"
    if not candidates_dir.is_dir() or candidates_dir.is_symlink():
        fail("bootstrap-retry requires the existing MyShop failed-candidate directory")
    failed_shas: set[str] = set()
    for marker in candidates_dir.glob("*.failed.json"):
        try:
            record = json.loads(marker.read_text(encoding="utf-8"))
            if record.get("status") == "FAILED" and re.fullmatch(r"[0-9a-f]{40}", str(record.get("source_sha", ""))):
                failed_shas.add(record["source_sha"])
        except Exception:
            continue
    if not failed_shas:
        fail("bootstrap-retry requires at least one recorded failed candidate")
    if not project_volumes.issubset({expected_volume}) or expected_volume not in project_volumes:
        fail("bootstrap-retry requires only the existing MyShop postgres_data volume")
    if not project_network_names.issubset(expected_networks) or f"{expected_project}_database" not in project_network_names:
        fail("bootstrap-retry requires only the expected MyShop project networks, including database")
    if expected_volume not in all_volume_names or (all_network_names & expected_networks) != project_network_names:
        fail("bootstrap-retry found a reserved volume or network without the expected Compose project label")
    rows_by_service: dict[str, list[tuple[str, str, str, str]]] = {}
    labelled_container_names: set[str] = set()
    for row in project_container_rows:
        parts = row.split("|", 4)
        if len(parts) != 5 or parts[0] not in expected_services:
            fail("bootstrap-retry found a container outside the expected MyShop service set")
        rows_by_service.setdefault(parts[0], []).append((parts[1], parts[2], parts[3], parts[4]))
        labelled_container_names.add(parts[2])
    reserved_containers = {f"{expected_project}-{service}-1" for service in expected_services}
    if (reserved_containers & all_container_names) - labelled_container_names:
        fail("bootstrap-retry found a reserved service container without the MyShop project label")
    if any(len(rows) != 1 for rows in rows_by_service.values()):
        fail("bootstrap-retry found duplicate containers for a MyShop service")
    if "db" not in rows_by_service or rows_by_service["db"][0][3] != "running":
        fail("bootstrap-retry requires the MyShop database container to be running")
    for service in ("api", "web"):
        for _container_id, _name, image, state in rows_by_service.get(service, []):
            if state != "exited":
                fail(f"bootstrap-retry requires the previous failed {service} candidate to be stopped")
            match = image_pattern.fullmatch(image)
            if not match or match.group(2) not in failed_shas:
                fail(f"bootstrap-retry {service} container is not recorded as a failed MyShop candidate")
    if any(state != "exited" for _container_id, _name, _image, state in rows_by_service.get("caddy", [])):
        fail("bootstrap-retry requires the failed candidate Caddy container to be stopped")
else:
    if project_network_names != expected_networks or project_volumes != {expected_volume}:
        fail("upgrade mode requires exactly the expected MyShop project networks and database volume")
    rows_by_service: dict[str, list[tuple[str, str, str, str]]] = {}
    for row in project_container_rows:
        parts = row.split("|", 4)
        if len(parts) != 5:
            fail("could not identify an existing MyShop project container")
        rows_by_service.setdefault(parts[0], []).append((parts[1], parts[2], parts[3], parts[4]))
    if set(rows_by_service) != expected_services or any(len(rows) != 1 for rows in rows_by_service.values()):
        fail("upgrade mode requires exactly one existing container for each expected MyShop service")
    state_path = Path(env_values.get("MYSHOP_DEPLOY_STATE", str(runtime_root / "deploy-state.json")))
    state_path = assert_no_symlink_components(state_path, "MYSHOP_DEPLOY_STATE")
    try:
        state = json.loads(state_path.read_text(encoding="utf-8"))
        active = state["active"]
        active_sha = active["source_sha"]
        if state.get("version") != 1 or not re.fullmatch(r"[0-9a-f]{40}", active_sha):
            raise ValueError("invalid accepted state")
        for service in ("api", "web"):
            expected_ref = active["images"][service]["ref"]
            if rows_by_service[service][0][2] != expected_ref:
                fail(f"running {service} image does not match the accepted deployment state")
    except Exception as exc:
        fail(f"upgrade mode requires a valid accepted deployment state: {exc}")

network_ids = run("docker", "network", "ls", "-q").splitlines()
for network_id in network_ids:
    try:
        rows = json.loads(run("docker", "network", "inspect", network_id))
    except Exception as exc:
        fail(f"could not inspect an existing Docker network: {exc}")
    for item in rows:
        name = item.get("Name", "unknown")
        if mode in {"upgrade", "bootstrap-retry"} and name in expected_networks:
            if name == f"{expected_project}_edge":
                actual = [entry.get("Subnet") for entry in item.get("IPAM", {}).get("Config", []) or [] if entry.get("Subnet")]
                if str(edge_network) not in [str(ipaddress.ip_network(value, strict=False)) for value in actual]:
                    fail("existing MyShop edge network differs from the configured audited subnet")
            continue
        for subnet_config in item.get("IPAM", {}).get("Config", []) or []:
            raw_subnet = subnet_config.get("Subnet")
            if not raw_subnet:
                continue
            try:
                existing = ipaddress.ip_network(raw_subnet, strict=False)
            except ValueError:
                continue
            conflict = overlap_message(edge_network, existing, f"Docker network {name}")
            if conflict:
                fail(conflict)

try:
    routes = json.loads(run("ip", "-j", "route", "show"))
except Exception as exc:
    fail(f"could not read host routes to check subnet conflicts: {exc}")
for route in routes:
    destination = route.get("dst")
    if not destination or destination == "default":
        continue
    try:
        existing = ipaddress.ip_network(destination, strict=False)
    except ValueError:
        continue
    if mode in {"upgrade", "bootstrap-retry"} and existing == edge_network:
        continue
    conflict = overlap_message(edge_network, existing, "host route")
    if conflict:
        fail(conflict)

try:
    listeners = run("ss", "-H", "-lnt", f"sport = :{published}").splitlines()
except Exception as exc:
    fail(f"could not inspect listeners on the candidate port: {exc}")
if mode in {"first-install", "bootstrap-retry"}:
    if listeners:
        fail(f"loopback candidate port {published} already has a TCP listener")
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
            probe.bind(("127.0.0.1", published))
    except OSError:
        fail(f"loopback port {published} is already reserved")
else:
    service_rows = rows_by_service["caddy"]
    caddy_id = service_rows[0][0]
    if run("docker", "port", caddy_id, "80/tcp").splitlines() != [f"127.0.0.1:{published}"]:
        fail("running Caddy is not published exclusively on the configured IPv4 loopback port")
    for line in listeners:
        fields = line.split()
        if len(fields) < 4 or fields[3] != f"127.0.0.1:{published}":
            fail("the candidate port has a listener beyond the expected loopback-only binding")

disk = shutil.disk_usage(data_root)
print(f"preflight=passed mode={mode} project={expected_project} domain={parsed_url.hostname} loopback_port={published}")
print(f"docker_engine={engine_version} docker_compose={compose_version}")
print(f"edge_subnet={edge_network} caddy_ip={caddy_ip} baota_proxy_cidr={proxy_cidr} data_root={data_root}")
print(f"data_disk_total_bytes={disk.total} data_disk_free_bytes={disk.free}")
print("compose=images-only host-ports=loopback-only database=private named-volume=project-scoped")
PY

#!/usr/bin/env bash
set -euo pipefail

# Fetches only the official ip2region release XDB files. The application never
# downloads data at startup; this script is an explicit operator action.
official_repo="lionsoul2014/ip2region"
github_api="https://api.github.com/repos/${official_repo}"
raw_base="https://raw.githubusercontent.com/${official_repo}"
target_dir="${IP_GEO_HOST_PATH:-/data/myshop/ipdb}"
runtime_root="${MYSHOP_RUNTIME_ROOT:-/opt/myshop/runtime}"
env_file="${MYSHOP_ENV_FILE:-/opt/myshop/.env}"
binding_version="${IP2REGION_JAVA_VERSION:-3.3.7}"
restart_api="${RESTART_API:-true}"
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_dir="$(CDPATH= cd -- "${script_dir}/.." && pwd)"
compose_wrapper="${MYSHOP_COMPOSE_WRAPPER:-${repo_dir}/scripts/myshop-compose.sh}"
approved_registry="${MYSHOP_APPROVED_REGISTRY:-ghcr.io/yykx682436}"
lock_file="${HOST_DEPLOY_LOCK_FILE:-/run/myshop-deploy.lock}"

if ! command -v curl >/dev/null 2>&1 || ! command -v python3 >/dev/null 2>&1 \
  || ! command -v sha256sum >/dev/null 2>&1 || ! command -v docker >/dev/null 2>&1; then
  echo "curl, python3, sha256sum, and docker are required" >&2
  exit 1
fi
if [[ ! -f "${repo_dir}/ops/Ip2RegionXdbVerifier.java" ]]; then
  echo "missing the official-binding XDB verifier source" >&2
  exit 1
fi
if [[ "${restart_api}" != "true" && "${restart_api}" != "false" ]]; then
  echo "RESTART_API must be true or false" >&2
  exit 1
fi
if [[ "${approved_registry}" != "ghcr.io/yykx682436" ]]; then
  echo "MYSHOP_APPROVED_REGISTRY must be ghcr.io/yykx682436" >&2
  exit 1
fi
# The data replacement and restart must not overlap a deployment or host
# maintenance run. Keep the same lock through preflight, download and restart.
if [[ "${HOST_DEPLOY_LOCK_HELD:-0}" != "1" ]]; then
  mkdir -p -- "$(dirname -- "${lock_file}")"
  exec 9>"${lock_file}"
  flock -n 9 || { echo "deployment/maintenance lock is held" >&2; exit 1; }
fi
if [[ "${restart_api}" == "true" ]]; then
  if [[ ! -x "${compose_wrapper}" || ! -f "${runtime_root}/docker-compose.prod.yml" || ! -f "${env_file}" ]]; then
    echo "API restart requires the installed production wrapper, runtime Compose, and env file" >&2
    exit 1
  fi
  # Validate the real installed stack before downloading or replacing either
  # database. The process loads XDB data at startup, so a skipped restart must
  # never be reported as a successful default update.
  MYSHOP_RUNTIME_ROOT="${runtime_root}" MYSHOP_ENV_FILE="${env_file}" \
    MYSHOP_APPROVED_REGISTRY="${approved_registry}" \
    "${compose_wrapper}" config --quiet
fi

mkdir -p "${target_dir}"
tmp_dir="$(mktemp -d "${target_dir%/}/.ip2region-update.XXXXXX")"
cleanup() {
  rm -rf -- "${tmp_dir}"
}
trap cleanup EXIT

release_json="$(curl --fail --silent --show-error --location --retry 3 \
  --connect-timeout 10 --max-time 60 \
  -H 'Accept: application/vnd.github+json' \
  -H 'User-Agent: myshop-ip2region-updater' \
  "${github_api}/releases/latest")"
upstream_version="$(printf '%s' "${release_json}" | python3 -c '
import json, sys
value = json.load(sys.stdin).get("tag_name")
if not value or "/" in value or ".." in value:
    raise SystemExit("latest release has no safe tag")
print(value)
')"
upstream_commit="$(curl --fail --silent --show-error --location --retry 3 \
  --connect-timeout 10 --max-time 60 \
  -H 'Accept: application/vnd.github+json' \
  -H 'User-Agent: myshop-ip2region-updater' \
  "${github_api}/commits/${upstream_version}" \
  | python3 -c 'import json, sys; print(json.load(sys.stdin)["sha"])')"
if [[ ! "${upstream_commit}" =~ ^[0-9a-fA-F]{40}$ ]]; then
  echo "official release commit could not be verified" >&2
  exit 1
fi

ipv4_tmp="${tmp_dir}/ip2region_v4.xdb"
ipv6_tmp="${tmp_dir}/ip2region_v6.xdb"
curl --fail --silent --show-error --location --retry 3 --connect-timeout 10 --max-time 180 \
  -H 'User-Agent: myshop-ip2region-updater' \
  "${raw_base}/${upstream_version}/data/ip2region_v4.xdb" -o "${ipv4_tmp}"
curl --fail --silent --show-error --location --retry 3 --connect-timeout 10 --max-time 180 \
  -H 'User-Agent: myshop-ip2region-updater' \
  "${raw_base}/${upstream_version}/data/ip2region_v6.xdb" -o "${ipv6_tmp}"
test -s "${ipv4_tmp}"
test -s "${ipv6_tmp}"

# Compile/run the official Java binding against both downloaded files in a
# disposable JDK container. This keeps the production host free of Java/Maven
# state while ensuring the files are structurally queryable before replacement.
docker run --rm \
  -e "IP2REGION_JAVA_VERSION=${binding_version}" \
  -v "${tmp_dir}:/work:ro" \
  -v "${repo_dir}/ops:/verify:ro" \
  maven:3.9.11-eclipse-temurin-25 \
  sh -ceu '
    jar_path="/root/.m2/repository/org/lionsoul/ip2region/${IP2REGION_JAVA_VERSION}/ip2region-${IP2REGION_JAVA_VERSION}.jar"
    mvn -q dependency:get -Dartifact="org.lionsoul:ip2region:${IP2REGION_JAVA_VERSION}" -Dtransitive=false
    test -f "$jar_path"
    mkdir -p /tmp/ip2region-verifier-classes
    javac -cp "$jar_path" -d /tmp/ip2region-verifier-classes /verify/Ip2RegionXdbVerifier.java
    java -cp "/tmp/ip2region-verifier-classes:$jar_path" Ip2RegionXdbVerifier \
      /work/ip2region_v4.xdb /work/ip2region_v6.xdb
  '

ipv4_sha256="$(sha256sum "${ipv4_tmp}" | awk '{print $1}')"
ipv6_sha256="$(sha256sum "${ipv6_tmp}" | awk '{print $1}')"
updated_at="$(date --iso-8601=seconds)"

# The temporary directory is inside the target, so each rename stays on one
# filesystem and uses the target directory's write permission. A failed
# download/validation exits before either old file is touched. The manifest is
# replaced last and is itself atomic.
chmod 0644 "${ipv4_tmp}" "${ipv6_tmp}"
mv -f -- "${ipv4_tmp}" "${target_dir}/ip2region_v4.xdb"
mv -f -- "${ipv6_tmp}" "${target_dir}/ip2region_v6.xdb"
manifest_tmp="${tmp_dir}/manifest"
cat >"${manifest_tmp}" <<EOF
updated_at=${updated_at}
upstream_repository=https://github.com/${official_repo}
upstream_version=${upstream_version}
upstream_commit=${upstream_commit}
ipv4_sha256=${ipv4_sha256}
ipv6_sha256=${ipv6_sha256}
EOF
mv -f -- "${manifest_tmp}" "${target_dir}/manifest"

if [[ "${restart_api}" == "true" ]]; then
  MYSHOP_RUNTIME_ROOT="${runtime_root}" MYSHOP_ENV_FILE="${env_file}" \
    MYSHOP_APPROVED_REGISTRY="${approved_registry}" \
    "${compose_wrapper}" restart api
fi

echo "ip2region updated: version=${upstream_version} commit=${upstream_commit}"
echo "IPv4 SHA256=${ipv4_sha256}"
echo "IPv6 SHA256=${ipv6_sha256}"

#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
python3 - "$repo_dir/ops/shared-server/audit-readonly.sh" <<'PY'
import os
import subprocess
import sys
import tempfile
from pathlib import Path

audit = Path(sys.argv[1])
script = r'''#!/usr/bin/env bash
if [[ "$1" == -v ]]; then echo 'nginx version: nginx/1.30.4' >&2; exit 0; fi
if [[ "$1" == -T ]]; then
  [[ "${NGINX_CONFIG_FAIL:-0}" != 1 ]] || exit 1
  cat <<'CONFIG'
# configuration file /www/server/nginx/conf/vhost/test.conf:
server {
  listen 443 ssl;
  server_name shop.example;
  proxy_pass http://user:never-print-this@127.0.0.1:8080;
  ssl_certificate /www/server/panel/vhost/cert/shop/fullchain.pem;
  ssl_certificate_key /www/server/panel/vhost/cert/shop/never-print-this.key;
  auth_basic_user_file /www/server/nginx/conf/never-print-this.htpasswd;
}
CONFIG
  exit 0
fi
exit 2
'''

with tempfile.TemporaryDirectory(prefix="myshop-audit-test-") as temporary:
    fake_bin = Path(temporary) / "bin"
    fake_bin.mkdir()
    nginx = fake_bin / "nginx"
    nginx.write_text(script, encoding="utf-8")
    nginx.chmod(0o755)
    base_env = os.environ.copy()
    base_env["PATH"] = f"{fake_bin}:{base_env['PATH']}"
    base_env["LC_ALL"] = "C"

    complete = subprocess.run(["bash", str(audit)], text=True, capture_output=True, env=base_env, check=True)
    output = complete.stdout + complete.stderr
    assert "nginx_binary=" in output and "nginx_config_check=complete" in output
    assert "server_name shop.example" in output and "proxy_pass http://[redacted]@127.0.0.1:8080" in output
    assert "never-print-this" not in output

    failed_env = base_env.copy()
    failed_env["NGINX_CONFIG_FAIL"] = "1"
    incomplete = subprocess.run(["bash", str(audit)], text=True, capture_output=True, env=failed_env, check=True)
    failed_output = incomplete.stdout + incomplete.stderr
    assert "nginx_config_check=INCOMPLETE reason=config-dump-failed-or-unreadable" in failed_output
    assert "never-print-this" not in failed_output

text = audit.read_text(encoding="utf-8")
assert "/www/server/nginx/sbin/nginx" in text
print("Baota Nginx path fallback, config-failure reporting, and secret redaction passed")
PY

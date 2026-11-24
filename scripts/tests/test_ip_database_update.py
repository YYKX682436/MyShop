"""IP database updates must use the installed stack and shared deployment lock."""
import fcntl
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class IpDatabaseUpdateTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'bin').mkdir()
        (self.root / 'runtime').mkdir()
        (self.root / 'runtime/docker-compose.prod.yml').write_text(
            (ROOT / 'docker-compose.prod.yml').read_text())
        self.env_file = self.root / 'production.env'
        self.env_file.write_text(
            'MYSHOP_API_IMAGE=ghcr.io/yykx682436/myshop-api:' + 'a' * 40 + '\n'
            'MYSHOP_WEB_IMAGE=ghcr.io/yykx682436/myshop-web:' + 'a' * 40 + '\n')
        self.log = self.root / 'commands'
        self.lock = self.root / 'deployment.lock'
        # macOS does not ship the util-linux `flock` command. Keep this shell
        # contract test portable while preserving the kernel lock behavior.
        self.script('flock', '''#!/usr/bin/env python3
import fcntl, sys
try:
    fcntl.flock(int(sys.argv[-1]), fcntl.LOCK_EX | fcntl.LOCK_NB)
except BlockingIOError:
    raise SystemExit(1)
''')
        self.script('docker', '#!/bin/sh\nprintf "docker %s\\n" "$*" >> "$TEST_COMMAND_LOG"\nexit 0\n')
        # The download is intentionally stopped; no network or live data is touched.
        self.script('curl', '#!/bin/sh\nprintf "curl\\n" >> "$TEST_COMMAND_LOG"\nexit 42\n')
        self.env = os.environ.copy()
        for key in ('MYSHOP_APPROVED_REGISTRY', 'MYSHOP_API_IMAGE', 'MYSHOP_WEB_IMAGE', 'HOST_DEPLOY_LOCK_HELD'):
            self.env.pop(key, None)
        self.env.update(
            PATH=str(self.root / 'bin') + os.pathsep + self.env['PATH'],
            TEST_COMMAND_LOG=str(self.log),
            MYSHOP_RUNTIME_ROOT=str(self.root / 'runtime'),
            MYSHOP_ENV_FILE=str(self.env_file),
            MYSHOP_COMPOSE_WRAPPER=str(ROOT / 'scripts/myshop-compose.sh'),
            HOST_DEPLOY_LOCK_FILE=str(self.lock),
            IP_GEO_HOST_PATH=str(self.root / 'ipdb'),
            RESTART_API='true',
        )

    def script(self, name, content):
        path = self.root / 'bin' / name
        path.write_text(content)
        path.chmod(0o755)

    def run_updater(self):
        return subprocess.run(['bash', str(ROOT / 'ops/update-ip2region.sh')],
                              env=self.env, text=True, capture_output=True, timeout=10)

    def test_restart_preflight_uses_installed_runtime_and_default_registry(self):
        result = self.run_updater()
        self.assertEqual(42, result.returncode, result.stderr)
        events = self.log.read_text()
        self.assertIn('--file ' + str(self.root / 'runtime/docker-compose.prod.yml'), events)
        self.assertIn('--env-file ' + str(self.env_file), events)
        self.assertIn('config --quiet', events)
        self.assertTrue(events.endswith('curl\n'))

    def test_conflicting_registry_stops_before_download(self):
        self.env['MYSHOP_APPROVED_REGISTRY'] = 'ghcr.io/other'
        result = self.run_updater()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('MYSHOP_APPROVED_REGISTRY must be ghcr.io/yykx682436', result.stderr)
        self.assertFalse(self.log.exists())

    def test_active_deployment_blocks_update_before_any_external_command(self):
        with self.lock.open('w') as holder:
            fcntl.flock(holder, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_updater()
        self.assertNotEqual(0, result.returncode)
        self.assertIn('deployment/maintenance lock is held', result.stderr)
        self.assertFalse(self.log.exists())


if __name__ == '__main__':
    unittest.main()

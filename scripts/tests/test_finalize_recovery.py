"""Exercise the actual finalizer with isolated paths and fake deploy tools."""
import hashlib
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SHA = 'a' * 40
BASE = 'b' * 40
REGISTRY = 'ghcr.io/yykx682436'


class FinalizerRecoveryTests(unittest.TestCase):
    def test_config_install_and_postcommit_recovery_do_not_redeploy(self):
        with tempfile.TemporaryDirectory(prefix='myshop-finalizer-test-') as name:
            temp = Path(name)
            friend, data, upload, etc, run = [temp / part for part in ('friend', 'data', 'upload', 'etc', 'run')]
            runtime = friend / 'runtime'
            release = data / 'releases' / SHA
            candidate = release / 'candidate'
            stage = upload / 'staging' / SHA
            for directory in (runtime, friend / 'scripts', friend / 'ops/shared-server', candidate / 'scripts',
                              candidate / 'ops/shared-server', stage, upload / 'basis', etc, run):
                directory.mkdir(parents=True, exist_ok=True)
            state = runtime / 'deploy-state.json'
            env_file = etc / 'myshop.env'
            env_file.write_text('PRIVATE_KEY=leave-alone\nMYSHOP_OFFLINE_IMAGES=0\n')
            (runtime / 'docker-compose.shared-server.yml').write_text('old compose\n')
            (runtime / 'Caddyfile.shared-server').write_text('old caddy\n')
            (candidate / 'docker-compose.shared-server.yml').write_text('candidate compose\n')
            (candidate / 'Caddyfile.shared-server').write_text('candidate caddy\n')
            (friend / 'ops/shared-server/check-storage-health.sh').write_text('old health\n')
            (candidate / 'ops/shared-server/check-storage-health.sh').write_text('candidate health\n')
            old = {'api': {'digest': 'sha256:' + '1' * 64}, 'web': {'digest': 'sha256:' + '2' * 64}}
            state.write_text(json.dumps({'version': 1, 'active': {'source_sha': BASE, 'images': old}}))
            manifest = {'version': 3, 'project': 'myshop', 'source_sha': SHA, 'accepted_sha': BASE,
                        'built_services': ['api'], 'images': {
                            'api': {'digest': 'sha256:' + '3' * 64, 'source_sha': SHA},
                            'web': {'digest': old['web']['digest'], 'source_sha': BASE}}}
            (release / 'release-manifest.json').write_text(json.dumps(manifest))
            (release / 'release.bundle').write_bytes(b'fixture')
            payload = b'verified candidate api'
            record = {'version': 1, 'service': 'api', 'source_sha': SHA,
                      'registry_digest': manifest['images']['api']['digest'], 'image_id': 'sha256:' + '4' * 64,
                      'exported_config_digest': 'sha256:' + '5' * 64, 'runtime_config_digest': 'sha256:' + '6' * 64,
                      'tar_sha256': hashlib.sha256(payload).hexdigest(),
                      'archive_tag': f'{REGISTRY}/myshop-api:{SHA}', 'rootfs_diff_ids': ['sha256:' + '7' * 64]}
            (release / 'trusted-api.record.json').write_text(json.dumps(record))
            (stage / 'api.record.json').write_text(json.dumps(record))
            (stage / 'api.tar').write_bytes(payload)
            script_names = ('create-offline-image-bundle.py', 'verify-offline-image-bundle.py',
                            'import-offline-image-bundle.sh', 'validate-release-manifest.py',
                            'verify-transfer-record.py', 'write-deploy-state.sh', 'deploy-production.sh',
                            'finalize-transferred-release.sh', 'myshop-compose.sh', 'preflight-shared-server.sh',
                            'check-production-schema.sh', 'create-predeploy-backup.sh', 'cleanup-docker-retention.sh')
            for script in script_names:
                (friend / 'scripts' / script).write_text('old helper\n')
                (candidate / 'scripts' / script).write_text('#!/bin/bash\nexit 0\n')
            (candidate / 'scripts/verify-transfer-record.py').write_bytes(
                (ROOT / 'scripts/verify-transfer-record.py').read_bytes())
            (candidate / 'scripts/verify-offline-image-bundle.py').write_text('import sys\nsys.exit(0)\n')
            (candidate / 'scripts/create-offline-image-bundle.py').write_text(
                'import json,sys\njson.dump({"images":{"api":{"archive_ref":"api-ref"},'
                '"web":{"archive_ref":"web-ref"}}},open(sys.argv[3],"w"))\n')
            (candidate / 'scripts/deploy-production.sh').write_text(
                '#!/bin/bash\n[[ "${FAIL_BEFORE:-0}" != 1 ]] || exit 1\npython3 -c \'import json,os; p=os.environ["TEST_STATE"]; '
                's=json.load(open(p));s["active"]["source_sha"]=os.environ["TEST_SHA"];json.dump(s,open(p,"w"))\'\n'
                'echo deployed >> "$TEST_ROOT/deploy-count"\n')
            script = (ROOT / 'scripts/finalize-transferred-release.sh').read_text()
            for source, target in (('/opt/myshop-friend', friend), ('/srv/myshop-friend', data),
                                   ('/srv/myshop-upload', upload), ('/etc/myshop-friend', etc),
                                   ('/run/myshop-friend-deploy.lock', run / 'deploy.lock')):
                script = script.replace(source, str(target))
            finalizer = temp / 'finalize.sh'
            finalizer.write_text(script)
            fake_bin = temp / 'bin'
            fake_bin.mkdir()
            for executable, content in {
                'id': '#!/bin/sh\necho 0\n', 'flock': '#!/bin/sh\nexit 0\n',
                'git': '#!/bin/sh\nexit 0\n',
                'install': """#!/usr/bin/env python3
import os,shutil,sys
from pathlib import Path
a=sys.argv[1:]; opts=[];i=0;directory=False
while i<len(a) and a[i].startswith('-'):
 if a[i]=='-d': directory=True;i+=1
 elif a[i] in ('-o','-g','-m'): i+=2
 else: i+=1
paths=a[i:]
if directory:
 for p in paths: Path(p).mkdir(parents=True,exist_ok=True)
else:
 src,dst=paths
 if os.environ.get('FAIL_BASIS')=='1' and '/basis/.api.tar.' in dst: raise SystemExit(1)
 Path(dst).parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst)
""",
            }.items():
                path = fake_bin / executable
                path.write_text(content)
                path.chmod(0o755)
            env = {**os.environ, 'PATH': str(fake_bin) + os.pathsep + os.environ['PATH'],
                   'TEST_ROOT': str(temp), 'TEST_STATE': str(state), 'TEST_SHA': SHA}
            first = subprocess.run(['bash', str(finalizer), SHA, BASE], env={**env, 'FAIL_BASIS': '1'},
                                   text=True, capture_output=True)
            self.assertNotEqual(first.returncode, 0, first.stdout + first.stderr)
            self.assertEqual(json.loads(state.read_text())['active']['source_sha'], SHA)
            self.assertEqual((temp / 'deploy-count').read_text().splitlines(), ['deployed'])
            self.assertEqual((runtime / 'docker-compose.shared-server.yml').read_text(), 'candidate compose\n')
            self.assertEqual((runtime / 'Caddyfile.shared-server').read_text(), 'candidate caddy\n')
            self.assertEqual((release / 'previous/docker-compose.shared-server.yml').read_text(), 'old compose\n')
            second = subprocess.run(['bash', str(finalizer), SHA, BASE], env=env, text=True, capture_output=True)
            self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
            self.assertIn('RECOVER_ACCEPTED_FINALIZATION', second.stdout)
            self.assertEqual((temp / 'deploy-count').read_text().splitlines(), ['deployed'])
            self.assertEqual((upload / 'basis/api.tar').read_bytes(), payload)
            self.assertIn('PRIVATE_KEY=leave-alone', env_file.read_text())
            self.assertTrue((release / 'release-complete').exists())
            state.write_text(json.dumps({'version': 1, 'active': {'source_sha': BASE, 'images': old}}))
            (runtime / 'docker-compose.shared-server.yml').write_text('old compose\n')
            (runtime / 'Caddyfile.shared-server').write_text('old caddy\n')
            stage.mkdir(parents=True)
            (stage / 'api.tar').write_bytes(payload)
            (stage / 'api.record.json').write_text(json.dumps(record))
            (release / 'release.bundle').write_bytes(b'fixture')
            (release / 'release-complete').unlink()
            failed = subprocess.run(['bash', str(finalizer), SHA, BASE], env={**env, 'FAIL_BEFORE': '1'},
                                    text=True, capture_output=True)
            self.assertNotEqual(failed.returncode, 0)
            self.assertEqual(json.loads(state.read_text())['active']['source_sha'], BASE)
            self.assertEqual((runtime / 'docker-compose.shared-server.yml').read_text(), 'old compose\n')
            self.assertEqual((runtime / 'Caddyfile.shared-server').read_text(), 'old caddy\n')
            self.assertEqual((friend / 'scripts/deploy-production.sh').read_text(), 'old helper\n')


if __name__ == '__main__':
    unittest.main()

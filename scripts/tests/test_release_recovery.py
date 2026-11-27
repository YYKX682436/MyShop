from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REGISTRY = 'ghcr.io/yykx682436'
RUN = 42


def command(*args, cwd=None, env=None):
    return subprocess.run(args, cwd=cwd, env=env, text=True, capture_output=True)


class ReleaseRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='myshop-release-recovery-test-')
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.repo.mkdir()
        shutil.copytree(ROOT / 'scripts', self.repo / 'scripts', ignore=shutil.ignore_patterns('__pycache__'))
        for path in ('docker-compose.shared-server.yml', 'Caddyfile.shared-server',
                     'ops/shared-server/check-storage-health.sh'):
            target = self.repo / path
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / path, target)
        def git(*args):
            result = command('git', *args, cwd=self.repo)
            self.assertEqual(result.returncode, 0, result.stderr)
            return result.stdout.strip()
        git('init', '-b', 'main')
        git('config', 'user.email', 'release-test@example.invalid')
        git('config', 'user.name', 'Release Test')
        git('add', '.')
        git('commit', '-m', 'accepted')
        self.base = git('rev-parse', 'HEAD')
        (self.repo / 'scripts' / 'release-production.sh').write_text(
            (self.repo / 'scripts' / 'release-production.sh').read_text() + '\n# candidate fixture\n')
        git('add', '.')
        git('commit', '-m', 'candidate')
        self.sha = git('rev-parse', 'HEAD')
        bare = self.root / 'origin.git'
        result = command('git', 'init', '--bare', str(bare))
        self.assertEqual(result.returncode, 0, result.stderr)
        git('remote', 'add', 'origin', str(bare))
        git('push', '-u', 'origin', 'main')
        self.server = self.root / 'server'
        self.server.mkdir()
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.log = self.root / 'calls.log'
        self.ci = self.root / 'ci'
        self.ci.mkdir()
        self.state_path = self.root / 'state.json'
        old = {name: {'ref': f'{REGISTRY}/myshop-{name}@sha256:{digit * 64}',
                      'digest': 'sha256:' + digit * 64, 'source_sha': self.base}
               for name, digit in (('api', '1'), ('web', '2'))}
        self.state_path.write_text(json.dumps({'version': 1, 'active': {'source_sha': self.base, 'images': old}}))
        baseline = {'version': 2, 'project': 'myshop', 'source_sha': self.base,
                    'created_at': '2026-11-27T00:00:00Z', 'built_services': ['api', 'web'],
                    'database_migration': False, 'images': old}
        self.baseline_path = self.root / 'baseline.json'
        self.baseline_path.write_text(json.dumps(baseline))
        images = {name: {'ref': f'{REGISTRY}/myshop-{name}@sha256:{digit * 64}',
                         'digest': 'sha256:' + digit * 64, 'source_sha': self.sha}
                  for name, digit in (('api', '3'), ('web', '4'))}
        release = {'version': 3, 'project': 'myshop', 'source_sha': self.sha, 'accepted_sha': self.base,
                   'created_at': '2026-11-27T00:01:00Z', 'built_services': ['api', 'web'],
                   'database_migration': False, 'images': images}
        manifest_dir = self.ci / f'myshop-release-manifest-{self.sha}'
        manifest_dir.mkdir()
        content = (json.dumps(release) + '\n').encode()
        (manifest_dir / 'release-manifest.json').write_bytes(content)
        (manifest_dir / 'SHA256SUMS').write_text(hashlib.sha256(content).hexdigest() + '  release-manifest.json\n')
        for name in ('api', 'web'):
            payload = (name * 1024).encode()
            record = {'version': 1, 'service': name, 'source_sha': self.sha,
                      'registry_digest': images[name]['digest'], 'image_id': 'sha256:' + '5' * 64,
                      'exported_config_digest': 'sha256:' + '6' * 64, 'runtime_config_digest': 'sha256:' + '7' * 64,
                      'tar_sha256': hashlib.sha256(payload).hexdigest(),
                      'archive_tag': f'{REGISTRY}/myshop-{name}:{self.sha}',
                      'rootfs_diff_ids': ['sha256:' + '8' * 64]}
            artifact = self.ci / f'myshop-transfer-{name}-{self.sha}'
            artifact.mkdir()
            (artifact / f'{name}.tar').write_bytes(payload)
            (artifact / f'{name}.record.json').write_text(json.dumps(record))
            small = self.ci / f'myshop-transfer-record-{name}-{self.sha}'
            small.mkdir()
            (small / f'{name}.record.json').write_text(json.dumps(record))
        self.fixture = self.root / 'fixture.json'
        self.fixture.write_text(json.dumps({'sha': self.sha, 'base': self.base, 'run': RUN}))
        self.write_fakes()

    def tearDown(self):
        self.temp.cleanup()

    def write_fakes(self):
        gh = self.bin / 'gh'
        gh.write_text("""#!/usr/bin/env python3
import json,os,shutil,sys
from pathlib import Path
root=Path(os.environ['TEST_ROOT']); fixture=json.load(open(root/'fixture.json')); args=sys.argv[1:]
with (root/'calls.log').open('a') as f: f.write('gh '+' '.join(args)+'\\n')
run={'databaseId':42,'id':42,'status':'completed','conclusion':'success','headSha':fixture['sha'],'head_sha':fixture['sha'],'headBranch':'main','event':'workflow_dispatch'}
jobs={'jobs':[{'name':'Publish immutable release manifest','conclusion':'success','steps':[]}]}
if args[:2]==['run','list']:
    if os.environ.get('RUN_IN_PROGRESS')=='1': run['status']='in_progress';run['conclusion']=''
    if os.environ.get('FAILED_PUSH')=='1': run['conclusion']='failure'
    print(json.dumps([run]))
elif args[:2]==['run','view']:
    if os.environ.get('FAILED_PUSH')=='1': jobs={'jobs':[{'name':'API build and tests','conclusion':'failure','steps':[{'name':'Push API image and verify registry digest','conclusion':'success'}]}]}
    print(json.dumps(jobs))
elif args[:2]==['run','watch']: pass
elif args[0]=='api':
    endpoint=args[1]
    if endpoint.endswith('/git/ref/heads/main'):
        print(json.dumps({'object':{'sha':fixture['sha']}}))
    elif endpoint.endswith('/artifacts'):
        name='myshop-release-manifest-'+fixture['sha']
        print(json.dumps({'artifacts':[{'name':name,'expired':os.environ.get('EXPIRE_MANIFEST')=='1'}]}))
    elif '/jobs?' in endpoint: print(json.dumps(jobs))
    else: print(json.dumps({'workflow_runs':[run]}))
elif args[:2]==['run','download']:
    name=args[args.index('--name')+1]; dest=Path(args[args.index('--dir')+1]); source=root/'ci'/name
    if os.environ.get('FAIL_DOWNLOAD') and name=='myshop-transfer-'+os.environ['FAIL_DOWNLOAD']+'-'+fixture['sha']: raise SystemExit(1)
    if os.environ.get('EXPIRE_COMPONENT') and name.startswith('myshop-transfer-'): raise SystemExit(1)
    if not source.is_dir(): raise SystemExit(1)
    shutil.copytree(source,dest,dirs_exist_ok=True)
elif args[:2]==['workflow','run']:
    raise SystemExit('unexpected dispatch')
else: raise SystemExit('unexpected gh '+repr(args))
""")
        gh.chmod(0o755)
        ssh = self.bin / 'ssh'
        ssh.write_text("""#!/usr/bin/env python3
import json,os,shlex,subprocess,sys
from pathlib import Path
root=Path(os.environ['TEST_ROOT']); server=root/'server'; fixture=json.load(open(root/'fixture.json')); args=sys.argv[1:]
with (root/'calls.log').open('a') as f:f.write('ssh '+' '.join(args)+'\\n')
def mapped(s):
    return s.replace('/srv/myshop-friend/releases/',str(server/'releases')+'/').replace('/srv/myshop-upload/staging/',str(server/'staging')+'/')
state=json.load(open(root/'state.json')); active=state['active']['source_sha']
if args[1:3]==['bash','-s']:
    sys.stdin.read()
    if active==fixture['sha'] and (server/'postcommit-failed').exists():
        (server/'postcommit-failed').unlink()
        with (root/'calls.log').open('a') as f:f.write('recovered-finalization\\n')
    raise SystemExit(0)
if len(args)>=7 and args[1:3]==['python3','-']:
    import hashlib
    sys.stdin.read()
    release=Path(mapped(args[3]));operation=args[6];service=args[7] if len(args)>7 else ''
    proof=release/'ci-proof.json'; manifest=release/'release-manifest.json'
    if operation=='write-manifest':
        proof.write_text(json.dumps({'run':args[5],'manifest':hashlib.sha256(manifest.read_bytes()).hexdigest(),'records':{}}) if not proof.exists() else proof.read_text())
    elif operation=='read-manifest':
        if not proof.exists() or json.loads(proof.read_text())['manifest']!=hashlib.sha256(manifest.read_bytes()).hexdigest(): raise SystemExit(1)
        sys.stdout.buffer.write(manifest.read_bytes())
    elif operation=='write-record':
        data=json.loads(proof.read_text());data['records'][service]=hashlib.sha256((release/('trusted-'+service+'.record.json')).read_bytes()).hexdigest();proof.write_text(json.dumps(data))
    elif operation=='read-record':
        record=release/('trusted-'+service+'.record.json')
        if not proof.exists() or not record.exists() or json.loads(proof.read_text())['records'].get(service)!=hashlib.sha256(record.read_bytes()).hexdigest(): raise SystemExit(1)
        sys.stdout.buffer.write(record.read_bytes())
    raise SystemExit(0)
if len(args)==3 and args[1:3]==['python3','-']:
    sys.stdin.read()
    print(json.dumps({'state':state,'manifest':(root/'baseline.json').read_text()})); raise SystemExit(0)
if len(args)==4 and args[1:3]==['python3','-']:
    sys.stdin.read();(server/'staging'/args[3]).mkdir(parents=True,exist_ok=True);raise SystemExit(0)
cmd=' '.join(args[1:])
if cmd.startswith('flock -n /run/myshop-friend-deploy.lock'):
    raise SystemExit(0)
if cmd.startswith('python3 -c'):
    print(active); raise SystemExit(0)
if cmd.startswith('install -d'):
    for x in shlex.split(mapped(cmd))[6:]: Path(x).mkdir(parents=True,exist_ok=True)
    raise SystemExit(0)
if cmd.startswith('chown root:root'):
    raise SystemExit(0)
if cmd.startswith('umask 077; cat >'):
    temp=shlex.split(mapped(cmd))[4].rstrip(';');path=Path(temp.replace('/.trusted-','/trusted-').replace('.tmp','.record.json'))
    path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(sys.stdin.buffer.read());raise SystemExit(0)
if cmd.startswith('python3 '):
    raise SystemExit(subprocess.run(shlex.split(mapped(cmd)),stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode)
if cmd.startswith('bash '):
    if os.environ.get('FAIL_FINALIZER_BEFORE')=='1': raise SystemExit(1)
    with (root/'calls.log').open('a') as f:f.write('deploy-once\\n')
    state['active']['source_sha']=fixture['sha'];(root/'state.json').write_text(json.dumps(state))
    if os.environ.get('FAIL_POSTCOMMIT')=='1':
        (server/'postcommit-failed').touch();raise SystemExit(1)
    raise SystemExit(0)
raise SystemExit('unexpected ssh '+repr(args))
""")
        ssh.chmod(0o755)
        rsync = self.bin / 'rsync'
        rsync.write_text("""#!/usr/bin/env python3
import os,shutil,sys
from pathlib import Path
root=Path(os.environ['TEST_ROOT']);args=sys.argv[1:]
with (root/'calls.log').open('a') as f:f.write('rsync '+' '.join(args)+'\\n')
dest=args[-1].split(':',1)[1].replace('/srv/myshop-friend/releases/',str(root/'server'/'releases')+'/').replace('/srv/myshop-upload/staging/',str(root/'server'/'staging')+'/')
sources=[Path(x) for x in args[:-1] if not x.startswith('-')]
target=Path(dest)
for src in sources:
    if os.environ.get('FAIL_COMPONENT') and src.name==os.environ['FAIL_COMPONENT']+'.tar':
        part=target/'.rsync-partial';part.mkdir(parents=True,exist_ok=True);(part/src.name).write_bytes(src.read_bytes()[:10]);raise SystemExit(23)
    if src.is_dir(): shutil.copytree(src,target,dirs_exist_ok=True)
    else:
        out=target/src.name if dest.endswith('/') or target.is_dir() else target
        out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,out)
""")
        rsync.chmod(0o755)

    def release(self, **options):
        env = {**os.environ, 'PATH': str(self.bin) + os.pathsep + os.environ['PATH'],
               'TEST_ROOT': str(self.root), 'MYSHOP_RELEASE_LOCK_HELD': '1',
               'MYSHOP_RSYNC_BIN': str(self.bin / 'rsync'), **options}
        return command('bash', 'scripts/release-production.sh', cwd=self.repo, env=env)

    def calls(self):
        return self.log.read_text() if self.log.exists() else ''

    def test_ci_success_upload_failure_resumes_same_artifact(self):
        first = self.release(FAIL_COMPONENT='api')
        self.assertNotEqual(first.returncode, 0, first.stdout + first.stderr)
        self.assertTrue((self.server / 'staging' / self.sha / '.rsync-partial' / 'api.tar').exists())
        candidate = self.server / 'releases' / self.sha / 'candidate'
        for name in ('docker-compose.shared-server.yml', 'Caddyfile.shared-server',
                     'scripts/deploy-production.sh', 'ops/shared-server/check-storage-health.sh'):
            expected = command('git', 'show', f'{self.sha}:{name}', cwd=self.repo)
            self.assertEqual(expected.returncode, 0, expected.stderr)
            self.assertEqual((candidate / name).read_text(), expected.stdout)
        self.assertFalse((candidate / '.env').exists())
        second = self.release()
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertIn('LOCAL_ARTIFACT_REUSED service=api', second.stdout)
        calls = self.calls()
        self.assertEqual(calls.count('run download 42 --repo YYKX682436/MyShop --name myshop-transfer-api-'), 1)
        self.assertNotIn('gh workflow run', calls)
        self.assertEqual(calls.count('deploy-once'), 1)

    def test_uploaded_api_is_not_retransferred_after_web_failure(self):
        first = self.release(FAIL_COMPONENT='web')
        self.assertNotEqual(first.returncode, 0, first.stdout + first.stderr)
        second = self.release()
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertIn('STAGED_TRANSFER_REUSED service=api', second.stdout)
        self.assertIn('LOCAL_ARTIFACT_REUSED service=web', second.stdout)
        self.assertEqual(self.calls().count('run download 42 --repo YYKX682436/MyShop --name myshop-transfer-api-'), 1)

    def test_interrupted_download_can_retry_same_ci_artifact(self):
        first = self.release(FAIL_DOWNLOAD='api')
        self.assertNotEqual(first.returncode, 0)
        second = self.release()
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertEqual(self.calls().count('run download 42 --repo YYKX682436/MyShop --name myshop-transfer-api-'), 2)
        self.assertNotIn('gh workflow run', self.calls())

    def test_expired_artifact_is_not_needed_when_trusted_download_is_cached(self):
        first = self.release(FAIL_COMPONENT='api')
        self.assertNotEqual(first.returncode, 0)
        # Web still needs its artifact; pre-populate its independently trusted
        # record and archive as if it had already been downloaded as well.
        cache = self.repo / '.git/myshop-release' / self.sha / str(RUN)
        source = self.ci / f'myshop-transfer-web-{self.sha}'
        shutil.copytree(source, cache / 'web')
        shutil.copy2(source / 'web.record.json', cache / 'trusted-web.record.json')
        second = self.release(EXPIRE_COMPONENT='1')
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertIn('LOCAL_ARTIFACT_REUSED service=api', second.stdout)
        self.assertIn('LOCAL_ARTIFACT_REUSED service=web', second.stdout)

    def test_server_proof_recovers_transfers_after_local_cache_and_artifacts_expire(self):
        first = self.release(FAIL_FINALIZER_BEFORE='1')
        self.assertNotEqual(first.returncode, 0)
        shutil.rmtree(self.repo / '.git/myshop-release' / self.sha)
        second = self.release(EXPIRE_MANIFEST='1', EXPIRE_COMPONENT='1')
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertIn('SERVER_CI_MANIFEST_REUSED', second.stdout)
        self.assertIn('SERVER_CI_RECORD_REUSED service=api', second.stdout)
        self.assertIn('STAGED_TRANSFER_REUSED service=api', second.stdout)
        self.assertIn('STAGED_TRANSFER_REUSED service=web', second.stdout)
        self.assertNotIn('gh workflow run', self.calls())

    def test_expired_artifact_stops_without_dispatch_or_state_change(self):
        result = self.release(EXPIRE_COMPONENT='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('unavailable or expired', result.stderr)
        self.assertEqual(json.loads(self.state_path.read_text())['active']['source_sha'], self.base)
        self.assertNotIn('gh workflow run', self.calls())

    def test_expired_manifest_stops_without_publishing_again(self):
        result = self.release(EXPIRE_MANIFEST='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('manifest is unavailable or expired', result.stderr)
        self.assertNotIn('gh workflow run', self.calls())

    def test_saved_accepted_manifest_works_without_historical_artifact(self):
        baseline = command('python3', 'scripts/release-baseline.py', str(self.baseline_path), str(self.state_path), cwd=self.repo)
        self.assertEqual(baseline.returncode, 0, baseline.stderr)
        env = {**os.environ, 'PATH': str(self.bin) + os.pathsep + os.environ['PATH'], 'TEST_ROOT': str(self.root),
               'GITHUB_SHA': self.sha, 'GITHUB_REPOSITORY': 'YYKX682436/MyShop', 'GITHUB_RUN_ID': '42',
               'GITHUB_REF': 'refs/heads/main', 'MYSHOP_ACCEPTED_SHA': self.base,
               'MYSHOP_ACCEPTED_MANIFEST': baseline.stdout, 'EXPIRE_MANIFEST': '1'}
        result = command('python3', 'scripts/ci-release-scope.py', cwd=self.repo, env=env)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn('api_image=false', result.stdout)
        self.assertIn('web_image=false', result.stdout)
        self.assertIn('validated production manifest', result.stdout)
        env['MYSHOP_ACCEPTED_MANIFEST'] = ''
        cold = command('python3', 'scripts/ci-release-scope.py', cwd=self.repo, env=env)
        self.assertEqual(cold.returncode, 0, cold.stderr)
        self.assertIn('api_image=true', cold.stdout)
        self.assertIn('web_image=true', cold.stdout)

    def test_changed_accepted_baseline_is_not_silently_rebuilt(self):
        path = self.ci / f'myshop-release-manifest-{self.sha}'
        manifest = json.loads((path / 'release-manifest.json').read_text())
        manifest['accepted_sha'] = 'f' * 40
        content = (json.dumps(manifest) + '\n').encode()
        (path / 'release-manifest.json').write_bytes(content)
        (path / 'SHA256SUMS').write_text(hashlib.sha256(content).hexdigest() + '  release-manifest.json\n')
        result = self.release()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('different production baseline', result.stderr)
        self.assertNotIn('gh workflow run', self.calls())

    def test_failed_run_that_pushed_image_does_not_overwrite_same_sha(self):
        result = self.release(FAILED_PUSH='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('attempted to publish this SHA', result.stderr)
        self.assertNotIn('gh workflow run', self.calls())

    def test_in_progress_run_is_waited_for_without_dispatch(self):
        result = self.release(RUN_IN_PROGRESS='1')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('CI_REUSED run=42 status=running', result.stdout)
        self.assertIn('gh run watch 42', self.calls())
        self.assertNotIn('gh workflow run', self.calls())

    def test_forged_staging_record_and_tar_are_not_trusted(self):
        self.release(FAIL_COMPONENT='web')
        stage = self.server / 'staging' / self.sha
        payload = b'forged data'
        record = json.loads((stage / 'api.record.json').read_text())
        record['tar_sha256'] = hashlib.sha256(payload).hexdigest()
        (stage / 'api.tar').write_bytes(payload)
        (stage / 'api.record.json').write_text(json.dumps(record))
        result = self.release()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertNotIn('STAGED_TRANSFER_REUSED service=api', result.stdout)
        self.assertIn('LOCAL_ARTIFACT_REUSED service=api', result.stdout)

    def test_postcommit_failure_recovers_without_redeploy(self):
        first = self.release(FAIL_POSTCOMMIT='1')
        self.assertNotEqual(first.returncode, 0)
        second = self.release()
        self.assertEqual(second.returncode, 0, second.stdout + second.stderr)
        self.assertIn('ALREADY_ACCEPTED', second.stdout)
        self.assertEqual(self.calls().count('deploy-once'), 1)
        self.assertEqual(self.calls().count('recovered-finalization'), 1)


class ReleaseLockTests(unittest.TestCase):
    def test_server_lock_excludes_a_second_release_until_first_exits(self):
        with tempfile.TemporaryDirectory(prefix='myshop-release-lock-test-') as name:
            root = Path(name)
            scripts = root / 'scripts'
            scripts.mkdir()
            shutil.copy2(ROOT / 'scripts/with-release-lock.py', scripts)
            (scripts / 'release-production.sh').write_text(
                '#!/bin/bash\necho started >> "$TEST_ROOT/started"\nsleep 3\n')
            fake_bin = root / 'bin'
            fake_bin.mkdir()
            ssh = fake_bin / 'ssh'
            ssh.write_text("""#!/usr/bin/env python3
import fcntl,os,sys
f=open(os.path.join(os.environ['TEST_ROOT'],'server.lock'),'w')
try: fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
except BlockingIOError: raise SystemExit(1)
print('MYSHOP_RELEASE_LOCKED',flush=True)
sys.stdin.read()
""")
            ssh.chmod(0o755)
            env = {**os.environ, 'TEST_ROOT': str(root), 'PATH': str(fake_bin) + os.pathsep + os.environ['PATH']}
            first = subprocess.Popen(['python3', str(scripts / 'with-release-lock.py')], env=env,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                for _ in range(50):
                    if (root / 'started').exists():
                        break
                    time.sleep(0.05)
                self.assertTrue((root / 'started').exists())
                second = command('python3', str(scripts / 'with-release-lock.py'), env=env)
                self.assertNotEqual(second.returncode, 0)
                self.assertIn('another release holds', second.stderr)
                output, error = first.communicate(timeout=10)
                self.assertEqual(first.returncode, 0, output + error)
                self.assertEqual((root / 'started').read_text().splitlines(), ['started'])
            finally:
                if first.poll() is None:
                    first.kill()
                    first.wait()


if __name__ == '__main__':
    unittest.main()

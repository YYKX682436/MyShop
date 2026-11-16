"""Exercise the greenfield transaction with isolated fake host services."""
import hashlib
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
SHA = "a" * 40
REGISTRY = "ghcr.io/yykx682436"
FAKE = r'''#!/usr/bin/env python3
import json, os, signal, sys, time
from pathlib import Path

name = Path(sys.argv[0]).name
args = sys.argv[1:]
root = Path(os.environ['FAKE_ROOT'])
state_file = root / 'fake.json'
s = json.loads(state_file.read_text())
def save():
    state_file.write_text(json.dumps(s))
def log(message):
    s['events'].append(message)
    save()
def fail(message):
    print(message, file=sys.stderr)
    sys.exit(1)
def container(service):
    return s['containers'].get(service)

if name == 'flock':
    import fcntl
    deadline = time.monotonic() + (float(args[args.index('-w') + 1]) if '-w' in args else 0)
    while True:
        try:
            fcntl.flock(int(args[-1]), fcntl.LOCK_EX | fcntl.LOCK_NB)
            break
        except BlockingIOError:
            if time.monotonic() >= deadline:
                sys.exit(1)
            time.sleep(0.05)
    sys.exit(0)
if name == 'sleep':
    time.sleep(0.05)
    sys.exit(0)
if name == 'findmnt':
    print(os.environ['DATA_MOUNTPOINT'])
    sys.exit(0)
if name == 'systemctl':
    if os.getenv('ACTIVE_TIMER') and args[-1] == 'storage-lifecycle.timer':
        sys.exit(0)
    sys.exit(0 if args[-1] in ('docker.service', 'containerd.service') else 3)
if name == 'containerd':
    print("root = '/data/containerd'")
    sys.exit(0)
if name == 'stat':
    print(Path(args[-1]).stat().st_size)
    sys.exit(0)
if name == 'chmod':
    for path in args[2:] if args[1:2] == ['--'] else args[1:]:
        os.chmod(path, int(args[0], 8))
    sys.exit(0)
if name == 'storage':
    log('storage')
    if os.getenv('FAIL_STORAGE') == str(s['events'].count('storage')):
        fail('storage failed')
    sys.exit(0)
if name == 'schema':
    log('schema')
    if not container('api') or not container('api')['running'] or not list((root/'backups').glob('*.dump')):
        fail('schema before running API or backup')
    if os.getenv('INTERRUPT_SCHEMA'):
        os.kill(os.getppid(), signal.SIGTERM)
        sys.exit(143)
    if os.getenv('KILL_SCHEMA'):
        os.kill(os.getppid(), signal.SIGKILL)
        sys.exit(137)
    if os.getenv('FAIL_SCHEMA'):
        fail('schema failed')
    print('MYSHOP_SCHEMA_CONTRACT_OK migrations=42')
    sys.exit(0)
if name == 'curl':
    if os.getenv('FAIL_SMOKE'):
        fail('curl failed')
    url = args[-1]
    origin = 'https://mc.mc520.top'
    output = {
        origin + '/': '<html><link rel="canonical" href="' + origin + '/"></html>',
        origin + '/api/site/config': '{"success":true}',
        origin + '/robots.txt': 'Sitemap: ' + origin + '/sitemap.xml',
        origin + '/sitemap.xml': '<urlset><url><loc>' + origin + '</loc></url></urlset>',
    }
    print(output[url])
    sys.exit(0)
if name == 'compose':
    if args[:1] == ['--env-file']:
        args = args[2:]
    action = args[0]
    log('compose:' + ':'.join(args))
    if action == 'config':
        print(json.dumps({'name':'myshop','services':{'db':{'volumes':[{'type':'volume','source':'postgres_data','target':'/var/lib/postgresql/data'}]}},'volumes':{'postgres_data':{'name':'myshop_postgres_data'}}}))
    elif action == 'pull':
        pass
    elif action == 'create':
        service = args[-1]
        dependencies = {'api':['db'], 'web':['db','api'], 'caddy':['db','api','web']}.get(service, [])
        for item in dependencies + [service]:
            if item not in s['containers']:
                ref = os.getenv('MYSHOP_' + item.upper() + '_IMAGE', 'infra:' + item)
                s['containers'][item] = {'id':'cid-' + item,'running':False,'restart':'unless-stopped','ref':ref}
                if item == 'db':
                    s['volumes'].append('myshop_postgres_data')
        save()
    elif action == 'start':
        service = args[-1]
        c = container(service)
        if not c or c['restart'] != 'no':
            fail('unsafe restart policy on start')
        c['running'] = True
        save()
    elif action == 'stop':
        for service in args[1:]:
            if container(service):
                container(service)['running'] = False
        save()
    else:
        fail('unknown compose action')
    sys.exit(0)
if name == 'docker':
    action = args[0]
    if action == 'info':
        print('/data/docker')
    elif action == 'ps':
        all_containers = '-aq' in args
        service = next((a.split('=',2)[-1] for a in args if a.startswith('label=com.docker.compose.service=')), None)
        for key,c in s['containers'].items():
            if (not service or service == key) and (all_containers or c['running']):
                print(c['id'])
    elif action == 'volume':
        print('\n'.join(s['volumes']))
    elif action == 'network':
        print('\n'.join(s.get('networks', [])))
    elif action == 'pull':
        log('pull:' + args[1])
    elif action == 'image' and args[1] == 'inspect':
        ref = args[-1]
        service = 'api' if '/myshop-api:' in ref else 'web'
        if '{{.Id}}' in args:
            print('image-' + service)
        else:
            digit = '1' if service == 'api' else '2'
            if os.getenv('FAIL_DIGEST') == service:
                digit = '9'
            print(ref.rsplit(':',1)[0] + '@sha256:' + digit*64)
    elif action == 'inspect':
        fmt, ident = args[2], args[3]
        service = ident.removeprefix('cid-')
        c = container(service)
        if not c: fail('unknown container')
        if 'RestartPolicy.Name' in fmt:
            print(c['restart'])
        elif '.State.Health' in fmt:
            print('healthy' if c['running'] else 'created')
        elif '.Config.Image' in fmt:
            print(c['ref'])
        elif '.Image' in fmt:
            print('image-' + service)
        else: fail('unknown inspect')
    elif action == 'update':
        service = args[-1].removeprefix('cid-')
        container(service)['restart'] = args[1].split('=',1)[1]
        log('restart:' + service + ':' + container(service)['restart'])
    elif action == 'exec':
        if 'pg_restore' in args:
            if sys.stdin.buffer.read() != b'FAKE-PG-DUMP': fail('invalid dump')
        elif 'pg_dump' in args[-1]:
            log('pg_dump')
            sys.stdout.buffer.write(b'FAKE-PG-DUMP')
        elif 'POSTGRES_HOSTNAME' in args[-1]:
            print('myshop@db/myshop')
        else:
            print('f' if os.getenv('NONEMPTY_DB') else 't')
    else:
        fail('unknown docker action: ' + repr(args))
    sys.exit(0)
fail('unknown fake command ' + name)
'''


class GreenfieldBootstrapTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.runtime = self.root / "runtime"
        self.runtime.mkdir()
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.backups = self.root / "backups"
        self.backups.mkdir()
        fake = self.bin / "fake"
        fake.write_text(FAKE, encoding="utf-8")
        fake.chmod(0o755)
        for name in ("docker", "compose", "flock", "sleep", "findmnt", "systemctl", "containerd", "storage", "schema", "curl", "stat", "chmod"):
            (self.bin / name).symlink_to(fake)
        backup_helper = self.root / "create-predeploy-backup.sh"
        shutil.copyfile(SCRIPTS / "create-predeploy-backup.sh", backup_helper)
        backup_helper.chmod(0o755)
        self.fake_state = self.root / "fake.json"
        self.fake_state.write_text(json.dumps({"containers": {}, "volumes": [], "events": []}))
        self.env_file = self.root / ".env"
        self.env_file.write_text("SITE_DOMAIN=mc.mc520.top\nPUBLIC_SITE_URL=https://mc.mc520.top\n")
        self.env_file.chmod(0o600)
        self.manifest = self.root / "manifest.json"
        self.manifest.write_text(json.dumps({
            "version": 1, "project": "myshop", "source_sha": SHA,
            "created_at": "2026-11-17T00:00:00Z",
            "images": {service: {"ref": f"{REGISTRY}/myshop-{service}:{SHA}",
                                 "digest": "sha256:" + digit * 64}
                       for service, digit in (("api", "1"), ("web", "2"))},
        }))
        self.env = os.environ.copy()
        self.env.update({
            "PATH": str(self.bin) + os.pathsep + self.env["PATH"],
            "FAKE_ROOT": str(self.root),
            "DATA_MOUNTPOINT": str(self.root),
            "MYSHOP_RUNTIME_ROOT": str(self.runtime),
            "MYSHOP_ENV_FILE": str(self.env_file),
            "MYSHOP_APPROVED_REGISTRY": REGISTRY,
            "HOST_DEPLOY_LOCK_FILE": str(self.root / "lock"),
            "MYSHOP_COMPOSE_WRAPPER": str(self.bin / "compose"),
            "MYSHOP_SCHEMA_SCRIPT": str(self.bin / "schema"),
            "MYSHOP_BACKUP_SCRIPT": str(backup_helper),
            "HOST_STORAGE_HEALTH_SCRIPT": str(self.bin / "storage"),
            "MYSHOP_BACKUP_DIR": str(self.backups),
        })

    def tearDown(self):
        self.tmp.cleanup()

    def run_bootstrap(self, **extra):
        return subprocess.run(
            ["bash", str(SCRIPTS / "bootstrap-greenfield.sh"), str(self.manifest)],
            env={**self.env, **extra}, text=True, capture_output=True, timeout=30,
        )

    def state(self):
        return json.loads(self.fake_state.read_text())

    def test_initial_install_and_next_state_promotion(self):
        result = self.run_bootstrap()
        self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
        state = json.loads((self.runtime / "deploy-state.json").read_text())
        self.assertEqual(state["active"]["source_sha"], SHA)
        self.assertEqual(state["rollback"], [])
        dump = next(self.backups.glob("*.dump"))
        metadata = json.loads(Path(str(dump) + ".manifest.json").read_text())
        self.assertEqual(metadata["source_active_sha"], "bootstrap")
        self.assertEqual(metadata["sha256"], hashlib.sha256(dump.read_bytes()).hexdigest())
        self.assertEqual(metadata["size"], dump.stat().st_size)
        self.assertEqual(metadata["validation"]["pg_restore_list"], "passed")
        fake = self.state()
        self.assertTrue(all(c["running"] and c["restart"] == "unless-stopped" for c in fake["containers"].values()))
        self.assertLess(fake["events"].index("pg_dump"), fake["events"].index("compose:start:api"))
        self.assertLess(fake["events"].index("schema"), fake["events"].index("compose:start:caddy"))
        # The existing writer accepts the initial state as the baseline of a
        # later normal deployment and derives its one rollback record.
        next_manifest = json.loads(self.manifest.read_text())
        next_manifest["source_sha"] = "b" * 40
        for item in next_manifest["images"].values():
            item["ref"] = item["ref"].replace(SHA, "b" * 40)
        self.manifest.write_text(json.dumps(next_manifest))
        subprocess.run(["bash", str(SCRIPTS / "write-deploy-state.sh"),
                        str(self.runtime / "deploy-state.json"), str(self.manifest)], check=True, capture_output=True)
        promoted = json.loads((self.runtime / "deploy-state.json").read_text())
        self.assertEqual(promoted["active"]["source_sha"], "b" * 40)
        self.assertEqual([x["source_sha"] for x in promoted["rollback"]], [SHA])

    def test_interrupted_after_migration_stops_public_services_and_refuses_retry(self):
        result = self.run_bootstrap(INTERRUPT_SCHEMA="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.runtime / "deploy-state.json").exists())
        fake = self.state()
        self.assertTrue(fake["containers"]["db"]["running"])
        self.assertFalse(fake["containers"]["api"]["running"])
        self.assertFalse(fake["containers"]["web"]["running"])
        self.assertNotIn("caddy", fake["containers"])
        self.assertTrue(all(c["restart"] == "no" for c in fake["containers"].values()))
        self.assertTrue(list(self.backups.glob("*.dump")))
        self.assertTrue(list((self.runtime / "candidates").glob("*.json")))
        retry = self.run_bootstrap()
        self.assertNotEqual(retry.returncode, 0)
        self.assertIn("existing MyShop containers", retry.stderr)

    def test_existing_state_even_if_malformed_is_never_overwritten(self):
        path = self.runtime / "deploy-state.json"
        subprocess.run(["bash", str(SCRIPTS / "write-deploy-state.sh"), str(path), str(self.manifest)],
                       check=True, capture_output=True)
        for content in (path.read_text(), "not valid JSON"):
            path.write_text(content)
            result = self.run_bootstrap()
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("deployment state exists", result.stderr)
            self.assertEqual(path.read_text(), content)
            self.assertEqual(self.state()["events"], [])

    def test_failed_public_smoke_stops_caddy_and_keeps_backup(self):
        result = self.run_bootstrap(FAIL_SMOKE="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.runtime / "deploy-state.json").exists())
        fake = self.state()
        for service in ("caddy", "api", "web"):
            self.assertFalse(fake["containers"][service]["running"])
            self.assertEqual(fake["containers"][service]["restart"], "no")
        self.assertTrue(fake["containers"]["db"]["running"])
        self.assertTrue(list(self.backups.glob("*.dump")))

    def test_watchdog_stops_candidate_if_operator_is_killed(self):
        import time
        result = self.run_bootstrap(KILL_SCHEMA="1")
        self.assertNotEqual(result.returncode, 0)
        for _ in range(400):
            try:
                fake = self.state()
                if fake["containers"].get("api", {}).get("running") is False:
                    break
            except json.JSONDecodeError:
                pass
            time.sleep(0.05)
        else:
            logs = list((self.runtime / "candidates").glob("*.greenfield-watchdog.log"))
            self.fail("watchdog did not stop candidate services: " +
                      (logs[0].read_text() if logs else "no watchdog log"))
        self.assertFalse(fake["containers"]["web"]["running"])
        self.assertTrue(fake["containers"]["db"]["running"])
        self.assertFalse((self.runtime / "deploy-state.json").exists())
        self.assertTrue(list(self.backups.glob("*.dump")))
        self.assertTrue(list((self.runtime / "candidates").glob("*.greenfield-watchdog.log")))

    def test_unknown_volume_or_digest_failure_never_starts_database(self):
        self.fake_state.write_text(json.dumps({"containers": {}, "volumes": ["myshop_postgres_data"], "events": []}))
        result = self.run_bootstrap()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("existing MyShop volumes", result.stderr)
        self.fake_state.write_text(json.dumps({"containers": {}, "volumes": [], "events": []}))
        result = self.run_bootstrap(FAIL_DIGEST="api")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.state()["containers"], {})
        self.assertFalse((self.runtime / "deploy-state.json").exists())

    def test_unsafe_environment_or_active_maintenance_is_rejected(self):
        self.env_file.chmod(0o644)
        result = self.run_bootstrap()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("environment file must be owned", result.stderr)
        self.env_file.chmod(0o600)
        result = self.run_bootstrap(ACTIVE_TIMER="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("storage-lifecycle.timer must be stopped", result.stderr)
        self.assertEqual(self.state()["containers"], {})

    def test_shared_lock_blocks_bootstrap_before_any_host_changes(self):
        with (self.root / "lock").open("w") as holder:
            fcntl.flock(holder, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_bootstrap()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("another deployment or storage lifecycle operation", result.stderr)
        self.assertEqual(self.state()["events"], [])

    def test_nonempty_database_and_post_start_storage_failure_are_not_accepted(self):
        result = self.run_bootstrap(NONEMPTY_DB="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(list(self.backups.glob("*.dump")))
        self.assertFalse((self.runtime / "deploy-state.json").exists())
        # A separate isolated installation tests the second storage gate.
        self.tearDown()
        self.setUp()
        result = self.run_bootstrap(FAIL_STORAGE="2")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.runtime / "deploy-state.json").exists())
        self.assertTrue(list(self.backups.glob("*.dump")))
        self.assertFalse(self.state()["containers"]["caddy"]["running"])


if __name__ == "__main__":
    unittest.main()

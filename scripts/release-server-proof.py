#!/usr/bin/env python3
"""Persist and recover CI evidence in a root-only release directory."""
import hashlib
import json
import os
import re
import stat
import sys
import tempfile
from pathlib import Path

if len(sys.argv) not in (5, 6):
    raise SystemExit('usage: release-server-proof.py <release-dir> <sha> <run-id> <operation> [service]')
root = Path(sys.argv[1])
sha, run_id, operation = sys.argv[2:5]
service = sys.argv[5] if len(sys.argv) == 6 else ''
if (not re.fullmatch(r'[0-9a-f]{40}', sha) or not run_id.isdigit()
        or root.name != sha or operation not in ('write-manifest', 'read-manifest', 'write-record', 'read-record')
        or (operation.endswith('record') and service not in ('api', 'web'))):
    raise SystemExit('invalid CI proof request')
if root.is_symlink() or not root.is_dir():
    raise SystemExit('unsafe CI proof directory')
info = root.stat()
if info.st_uid != 0 or info.st_mode & 0o077:
    raise SystemExit('CI proof directory must be private and root-owned')


def read_private(path):
    if path.is_symlink() or not path.is_file():
        raise SystemExit('CI evidence file is missing or unsafe')
    info = path.stat()
    if info.st_uid != 0 or info.st_mode & 0o077:
        raise SystemExit('CI evidence file must be private and root-owned')
    return path.read_bytes()


def digest(content):
    return hashlib.sha256(content).hexdigest()


proof_path = root / 'ci-proof.json'
manifest_path = root / 'release-manifest.json'
manifest = read_private(manifest_path)
manifest_hash = digest(manifest)
if proof_path.exists():
    proof = json.loads(read_private(proof_path))
    if (proof.get('version') != 1 or proof.get('source_sha') != sha or str(proof.get('run_id')) != run_id
            or proof.get('manifest_sha256') != manifest_hash or not isinstance(proof.get('records'), dict)):
        raise SystemExit('saved CI proof does not match the selected run or manifest')
elif operation == 'write-manifest':
    proof = {'version': 1, 'source_sha': sha, 'run_id': run_id,
             'manifest_sha256': manifest_hash, 'records': {}}
else:
    raise SystemExit('CI proof is missing')

if operation.endswith('record'):
    record = read_private(root / f'trusted-{service}.record.json')
    if operation == 'write-record':
        existing = proof['records'].get(service)
        if existing and existing != digest(record):
            raise SystemExit('refusing to replace a previously trusted CI record')
        proof['records'][service] = digest(record)
    elif proof['records'].get(service) != digest(record):
        raise SystemExit('saved CI record does not match the proof')

if operation.startswith('write-'):
    fd, name = tempfile.mkstemp(prefix='.ci-proof.', dir=root)
    try:
        os.fchmod(fd, stat.S_IRUSR | stat.S_IWUSR)
        with os.fdopen(fd, 'w', encoding='utf-8') as file:
            json.dump(proof, file, sort_keys=True)
            file.write('\n'); file.flush(); os.fsync(file.fileno())
        os.replace(name, proof_path)
    finally:
        if os.path.exists(name): os.unlink(name)
elif operation == 'read-manifest':
    sys.stdout.buffer.write(manifest)
else:
    sys.stdout.buffer.write(record)

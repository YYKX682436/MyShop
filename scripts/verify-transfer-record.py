#!/usr/bin/env python3
"""Validate a CI-provided transfer record and optionally its archive or staging copy."""
import argparse
import hashlib
import json
import re
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('manifest')
parser.add_argument('service', choices=('api', 'web'))
parser.add_argument('record')
parser.add_argument('--archive')
parser.add_argument('--match-record')
args = parser.parse_args()
manifest = json.loads(Path(args.manifest).read_text(encoding='utf-8'))
path = Path(args.record)
if path.is_symlink() or not path.is_file():
    raise SystemExit('trusted CI record is missing or unsafe')
record = json.loads(path.read_text(encoding='utf-8'))
service = args.service
image = manifest['images'][service]
fields = {'version', 'service', 'source_sha', 'registry_digest', 'image_id',
          'exported_config_digest', 'runtime_config_digest', 'tar_sha256', 'archive_tag', 'rootfs_diff_ids'}
digest = re.compile(r'^sha256:[0-9a-f]{64}$')
if not isinstance(record, dict) or set(record) != fields or record['version'] != 1:
    raise SystemExit('CI transfer record fields are invalid')
if (service not in manifest['built_services'] or record['service'] != service
        or record['source_sha'] != image['source_sha'] or record['source_sha'] != manifest['source_sha']
        or record['registry_digest'] != image['digest']
        or record['archive_tag'] != f"ghcr.io/yykx682436/myshop-{service}:{image['source_sha']}"):
    raise SystemExit('CI transfer record does not match release manifest identity')
if (any(not isinstance(record[key], str) or not digest.fullmatch(record[key])
        for key in ('image_id', 'exported_config_digest', 'runtime_config_digest'))
        or not isinstance(record['tar_sha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', record['tar_sha256'])
        or not isinstance(record['rootfs_diff_ids'], list) or not record['rootfs_diff_ids']
        or any(not isinstance(value, str) or not digest.fullmatch(value) for value in record['rootfs_diff_ids'])):
    raise SystemExit('CI transfer record content identity is invalid')
if args.match_record:
    copy = Path(args.match_record)
    if copy.is_symlink() or not copy.is_file() or json.loads(copy.read_text(encoding='utf-8')) != record:
        raise SystemExit('staging record differs from the trusted CI record')
if args.archive:
    archive = Path(args.archive)
    if archive.is_symlink() or not archive.is_file():
        raise SystemExit('image archive is missing or unsafe')
    checksum = hashlib.sha256()
    with archive.open('rb') as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b''):
            checksum.update(chunk)
    if checksum.hexdigest() != record['tar_sha256']:
        raise SystemExit('archive SHA-256 differs from trusted CI record')
print(f'TRANSFER_RECORD_VERIFIED service={service} source_sha={record["source_sha"]}')

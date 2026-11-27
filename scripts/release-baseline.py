#!/usr/bin/env python3
"""Validate a saved production manifest against the observed accepted state."""
import json
import subprocess
import sys
from pathlib import Path

if len(sys.argv) != 3:
    raise SystemExit('usage: release-baseline.py <saved-manifest> <accepted-state>')
manifest_path, state_path = map(Path, sys.argv[1:])
scripts = Path(__file__).resolve().parent
if manifest_path.is_symlink() or not manifest_path.is_file():
    raise SystemExit('saved accepted manifest is unavailable')
result = subprocess.run([sys.executable, str(scripts / 'validate-release-manifest.py'),
                         str(manifest_path), 'ghcr.io/yykx682436', 'myshop'],
                        check=True, text=True, capture_output=True)
manifest = json.loads(result.stdout)
state = json.loads(state_path.read_text(encoding='utf-8'))
active = state['active']
if state.get('version') != 1 or manifest['source_sha'] != active['source_sha']:
    raise SystemExit('saved manifest does not match production accepted SHA')
for service in ('api', 'web'):
    image = active['images'][service]
    expected = manifest['images'][service]
    if any(image.get(key, active['source_sha'] if key == 'source_sha' else None) != expected[key]
           for key in ('ref', 'digest', 'source_sha')):
        raise SystemExit(f'saved manifest does not match accepted {service} identity')
subprocess.run([sys.executable, str(scripts / 'validate-release-ancestry.py'),
                str(manifest_path), active['source_sha']], check=True, capture_output=True)
print(json.dumps(manifest, sort_keys=True, separators=(',', ':')))

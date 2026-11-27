#!/usr/bin/env python3
"""Select an existing run for this exact SHA without republishing an image tag."""
import json
import subprocess
import sys

if len(sys.argv) != 2:
    raise SystemExit('usage: select-release-run.py <candidate-sha>')
sha = sys.argv[1]
repo = 'YYKX682436/MyShop'


def gh(*args):
    return json.loads(subprocess.check_output(['gh', *args], text=True, timeout=60))


runs = gh('run', 'list', '--repo', repo, '--workflow', 'cicd.yml', '--commit', sha, '--limit', '100',
          '--json', 'databaseId,status,conclusion,headSha,headBranch,event')
runs = [r for r in runs if r.get('headSha') == sha and r.get('headBranch') == 'main'
        and r.get('event') == 'workflow_dispatch']
for run in runs:
    if run.get('status') == 'completed' and run.get('conclusion') == 'success':
        jobs = gh('run', 'view', str(run['databaseId']), '--repo', repo, '--json', 'jobs')['jobs']
        if any(j['name'] == 'Publish immutable release manifest' and j['conclusion'] == 'success' for j in jobs):
            print(f"{run['databaseId']}|completed")
            raise SystemExit(0)
for run in runs:
    if run.get('status') not in ('completed',):
        print(f"{run['databaseId']}|running")
        raise SystemExit(0)
for run in runs:
    jobs = gh('run', 'view', str(run['databaseId']), '--repo', repo, '--json', 'jobs')['jobs']
    for job in jobs:
        if any(step.get('name') in ('Push API image and verify registry digest', 'Push Web image and verify registry digest')
               and step.get('conclusion') not in ('skipped', None, '') for step in job.get('steps', [])):
            raise SystemExit(f"[myshop-release] run {run['databaseId']} attempted to publish this SHA but has no reusable manifest; "
                             'preserve immutable image identity and use a new reviewed commit')
print('new')

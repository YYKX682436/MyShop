#!/usr/bin/env python3
"""Hold a server-side release lock while the local release entry is running."""
import os
import select
import signal
import subprocess
import sys
import time
from pathlib import Path

script = Path(__file__).resolve().with_name('release-production.sh')
remote = subprocess.Popen(
    ['ssh', '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', '-o', 'ServerAliveInterval=15',
     '-o', 'ServerAliveCountMax=3', 'myshop-friend-prod',
     "flock -n /run/myshop-friend-release.lock sh -c 'echo MYSHOP_RELEASE_LOCKED; cat >/dev/null'"],
    stdin=subprocess.PIPE, stdout=subprocess.PIPE,
)
child = None
try:
    ready, _, _ = select.select([remote.stdout], [], [], 20)
    if not ready or remote.stdout.readline().decode().strip() != 'MYSHOP_RELEASE_LOCKED':
        raise SystemExit('[myshop-release] another release holds the server release lock, or SSH failed')
    env = {**os.environ, 'MYSHOP_RELEASE_LOCK_HELD': '1'}
    child = subprocess.Popen(['bash', str(script)], env=env, start_new_session=True)
    while child.poll() is None:
        if remote.poll() is not None:
            os.killpg(child.pid, signal.SIGTERM)
            child.wait()
            raise SystemExit('[myshop-release] server release lock connection was lost; staged data retained')
        time.sleep(1)
    raise SystemExit(child.returncode)
except KeyboardInterrupt:
    if child and child.poll() is None:
        os.killpg(child.pid, signal.SIGTERM)
        child.wait()
    raise SystemExit(130)
finally:
    if remote.stdin:
        remote.stdin.close()
    try:
        remote.wait(timeout=5)
    except subprocess.TimeoutExpired:
        remote.terminate()
        remote.wait()

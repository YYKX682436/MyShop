#!/usr/bin/env python3
"""Build and sign locally with the existing JKS; credentials never enter Gradle or the repository."""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
repo = root.parents[1]
sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
key_dir = Path.home() / 'Downloads/Android App正式签名密钥'
keystore = Path(os.environ.get('MYSHOP_KEYSTORE', str(key_dir / 'MUCHEN.jks')))
credential_file = Path(os.environ.get('MYSHOP_CREDENTIAL_FILE', str(key_dir / '签名密钥密码.env')))
if not keystore.is_file():
    sys.exit('Existing release JKS not found; no key will be generated.')
env = os.environ.copy()
env['ANDROID_HOME'] = str(sdk)
# This release consumes Java 21 AARs; use a real JDK 21 for Gradle and signing tools.
jdk = subprocess.check_output(['/usr/libexec/java_home', '-v', '21'], text=True).strip()
env['JAVA_HOME'] = jdk
env['PATH'] = jdk + '/bin:' + env.get('PATH', '')
passwords = [env['MYSHOP_STORE_PASSWORD']] if env.get('MYSHOP_STORE_PASSWORD') else credential_file.read_text().splitlines()
identity = None
for password in passwords:
    env['MYSHOP_STORE_PASSWORD'] = password.strip()
    result = subprocess.run(['keytool', '-J-Duser.language=en', '-J-Duser.country=US', '-list', '-v',
        '-keystore', str(keystore), '-storetype', 'JKS', '-storepass:env', 'MYSHOP_STORE_PASSWORD'],
        env=env, text=True, capture_output=True)
    if result.returncode == 0:
        aliases = re.findall(r'^Alias name: (.+)$', result.stdout, re.MULTILINE)
        if len(aliases) != 1:
            sys.exit('Expected exactly one existing signing alias; inspect local JKS configuration.')
        identity = aliases[0]
        break
if identity is None:
    sys.exit('Local credentials do not unlock the existing JKS.')
env['MYSHOP_KEY_PASSWORD'] = os.environ.get('MYSHOP_KEY_PASSWORD', env['MYSHOP_STORE_PASSWORD'])
subprocess.run([str(root / 'gradlew'), '-p', str(root), ':app:assembleRelease', ':app:lintRelease', '--console=plain', *sys.argv[1:]], env=env, check=True)
unsigned = root / 'app/build/outputs/apk/release/app-release-unsigned.apk'
config = (root / 'app/build.gradle.kts').read_text()
version_name = re.search(r'versionName\s*=\s*"([^"]+)"', config).group(1)
version_code = re.search(r'versionCode\s*=\s*(\d+)', config).group(1)
output = repo / f'dist/android/aikayun-{version_name}-{version_code}-shell-release.apk'
output.parent.mkdir(parents=True, exist_ok=True)
tools = sdk / 'build-tools/36.0.0'
subprocess.run([str(tools / 'apksigner'), 'sign', '--ks', str(keystore), '--ks-type', 'JKS',
    '--ks-key-alias', identity, '--ks-pass', 'env:MYSHOP_STORE_PASSWORD', '--key-pass', 'env:MYSHOP_KEY_PASSWORD',
    '--out', str(output), str(unsigned)], env=env, check=True)
subprocess.run([str(tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(output)], check=True)
subprocess.run([str(tools / 'zipalign'), '-c', '-P', '16', '4', str(output)], check=True)
# v4 sidecar is unnecessary for the single distributable APK.
output.with_suffix('.apk.idsig').unlink(missing_ok=True)
print(f'APK: {output}')
print(f'SHA-256: {hashlib.sha256(output.read_bytes()).hexdigest()}')

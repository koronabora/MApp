"""Create a stable release signing identity OUTSIDE the repository, once."""
import os
from pathlib import Path
import secrets
import subprocess

root = Path.home() / '.config' / 'mapp'
root.mkdir(parents=True, exist_ok=True, mode=0o700)
properties = root / 'signing.properties'
keystore = root / 'release.p12'
if properties.exists() or keystore.exists():
    raise SystemExit('Signing identity already exists; refusing to overwrite it')
os.umask(0o077)
password = secrets.token_hex(32)
environment = os.environ | {'MAPP_STORE_PASS': password}
subprocess.run(['keytool', '-genkeypair', '-keystore', str(keystore), '-storetype', 'PKCS12',
                '-storepass:env', 'MAPP_STORE_PASS', '-keypass:env', 'MAPP_STORE_PASS',
                '-alias', 'mapp', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
                '-dname', 'CN=MApp private application'], env=environment, check=True)
properties.write_text(f'storeFile={keystore}\nstorePassword={password}\nkeyAlias=mapp\nkeyPassword={password}\n')
print('Created external signing identity:', root)

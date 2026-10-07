#!/usr/bin/env python3
"""Create the owner's long-lived private APK key outside the repository."""

import os
from pathlib import Path
import secrets
import shlex
import subprocess
import sys


signing_dir = Path.home() / "Library" / "Application Support" / "NextNotif" / "Signing"
keystore = signing_dir / "nextnotif-private-release.p12"
env_file = signing_dir / "signing.env"
alias = "nextnotif-release"
keytool = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/keytool")

if keystore.exists() or env_file.exists():
    sys.exit("Signing material already exists; refusing to replace the release identity")
if not keytool.is_file():
    sys.exit("JDK 17 keytool is unavailable")

signing_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
signing_dir.chmod(0o700)
password = secrets.token_urlsafe(48)
child_env = os.environ.copy()
child_env["NEXTNOTIF_NEW_KEY_PASSWORD"] = password

try:
    subprocess.run(
        [
            str(keytool), "-genkeypair", "-storetype", "PKCS12",
            "-keystore", str(keystore), "-alias", alias,
            "-keyalg", "RSA", "-keysize", "4096", "-sigalg", "SHA256withRSA",
            "-validity", "10000", "-dname", "CN=NextNotif Private Release",
            "-storepass:env", "NEXTNOTIF_NEW_KEY_PASSWORD",
            "-keypass:env", "NEXTNOTIF_NEW_KEY_PASSWORD",
        ],
        env=child_env,
        check=True,
    )
    keystore.chmod(0o600)
    values = {
        "NEXTNOTIF_SIGNING_STORE_FILE": str(keystore),
        "NEXTNOTIF_SIGNING_STORE_PASSWORD": password,
        "NEXTNOTIF_SIGNING_KEY_ALIAS": alias,
        "NEXTNOTIF_SIGNING_KEY_PASSWORD": password,
    }
    fd = os.open(env_file, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as file:
        for name, value in values.items():
            file.write(f"export {name}={shlex.quote(value)}\n")
except Exception:
    # A failed first attempt must not leave a partial signing identity.
    if env_file.exists():
        env_file.unlink()
    if keystore.exists():
        keystore.unlink()
    raise

print(f"Created private signing key: {keystore}")
print(f"Created owner-only signing environment file: {env_file}")
print("Back up the keystore and its password separately before distributing an APK.")

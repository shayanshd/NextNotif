#!/usr/bin/env python3
"""Read an installed APK signature over forwarded ADB without changing the phone."""

import re
import subprocess
import sys
import tempfile
from pathlib import Path


if len(sys.argv) != 2:
    sys.exit("Usage: compare-installed-signers.py DEVICE_SERIAL")

repo = Path(__file__).resolve().parent.parent
sdk = Path.home() / "Library/Android/sdk"
adb = str(sdk / "platform-tools/adb")
apksigner = str(sdk / "build-tools/34.0.0/apksigner")
local_apk = repo / "android/app/build/outputs/apk/release/app-release.apk"
serial = sys.argv[1]


def run(args):
    return subprocess.run(args, text=True, capture_output=True, timeout=45, check=True).stdout


def certificate(apk):
    output = run([apksigner, "verify", "--print-certs", str(apk)])
    match = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]{64})", output)
    if not match:
        raise RuntimeError("APK signer certificate not found")
    return match.group(1)


paths = run([adb, "-H", "127.0.0.1", "-P", "5038", "-s", serial,
             "shell", "pm", "path", "com.nextnotif.app"]).splitlines()
base = next((line.removeprefix("package:") for line in paths if line.startswith("package:") and line.endswith(".apk")), None)
if not base:
    sys.exit("NextNotif base APK not found on this phone")

with tempfile.TemporaryDirectory(prefix="nextnotif-signature-") as folder:
    pulled = Path(folder) / "installed.apk"
    run([adb, "-H", "127.0.0.1", "-P", "5038", "-s", serial,
         "pull", base, str(pulled)])
    installed = certificate(pulled)

candidate = certificate(local_apk)
print(f"device={serial}")
print(f"installed signer SHA-256={installed}")
print(f"candidate signer SHA-256={candidate}")
print(f"in-place update signature match={installed == candidate}")

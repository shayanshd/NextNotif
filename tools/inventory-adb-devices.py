#!/usr/bin/env python3
"""Read-only inventory of devices on a forwarded ADB server."""

import re
import subprocess
from pathlib import Path


ADB = str(Path.home() / "Library/Android/sdk/platform-tools/adb")
SERVER = [ADB, "-H", "127.0.0.1", "-P", "5038"]
PACKAGE = "com.nextnotif.app"


def adb(*args):
    return subprocess.run(
        [*SERVER, *args], text=True, capture_output=True, timeout=12, check=True,
    ).stdout.strip()


lines = adb("devices", "-l").splitlines()[1:]
for line in lines:
    fields = line.split()
    if len(fields) < 2 or fields[1] != "device":
        continue
    serial = fields[0]
    model = next((item.removeprefix("model:") for item in fields if item.startswith("model:")), "unknown")
    sdk = adb("-s", serial, "shell", "getprop", "ro.build.version.sdk")
    release = adb("-s", serial, "shell", "getprop", "ro.build.version.release")
    package_path = adb("-s", serial, "shell", "pm", "path", PACKAGE)
    package_dump = adb("-s", serial, "shell", "dumpsys", "package", PACKAGE) if package_path else ""
    version_code = re.search(r"\bversionCode=(\d+)", package_dump)
    version_name = re.search(r"\bversionName=([^\s]+)", package_dump)
    location = "system" if "/system/" in package_path else "data" if "/data/" in package_path else "unknown"
    print(f"{serial} model={model} Android={release} API={sdk} "
          f"NextNotif={'installed ' + version_code.group(1) + '/' + (version_name.group(1) if version_name else '?') + ' (' + location + ')' if version_code else 'not installed'}")

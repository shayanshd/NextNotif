#!/usr/bin/env python3
"""Reject the CI-only Firebase client config before signing a private APK.

This is a local shape/placeholder check, not proof that FCM credentials work.
Never print client configuration values in build logs.
"""

import json
import sys
from pathlib import Path


def valid(path: Path) -> bool:
    try:
        config = json.loads(path.read_text(encoding="utf-8"))
        project = config["project_info"]
        project_id = project["project_id"]
        project_number = project["project_number"]
        if (not isinstance(project_id, str) or not project_id or
                "placeholder" in project_id.lower() or
                not isinstance(project_number, str) or not project_number.isdigit()):
            return False
        for client in config["client"]:
            info = client["client_info"]
            if info["android_client_info"]["package_name"] != "com.nextnotif.app":
                continue
            app_id = info["mobilesdk_app_id"]
            keys = client.get("api_key", [])
            return (isinstance(app_id, str) and app_id.startswith(f"1:{project_number}:android:") and
                    any(isinstance(key.get("current_key"), str) and
                        key["current_key"] and not key["current_key"].startswith("NOT_A_REAL_")
                        for key in keys))
    except (OSError, ValueError, KeyError, TypeError, IndexError):
        return False
    return False


if __name__ == "__main__":
    if len(sys.argv) != 2 or not valid(Path(sys.argv[1])):
        print("Private APK requires a non-placeholder Firebase Android client config", file=sys.stderr)
        sys.exit(2)

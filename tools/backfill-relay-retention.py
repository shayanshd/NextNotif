#!/usr/bin/env python3
"""Enumerate stored production Durable Objects and arm their retention alarms.

Dry-run by default. Outputs counts only; no object IDs or stored content.
Requires a Cloudflare token with Workers Scripts Read (or Write), and a
separate NEXTNOTIF_MAINTENANCE_TOKEN Secret on the deployed Worker for --apply.
"""

import argparse
import json
import os
import re
import time
import urllib.error
import urllib.parse
import urllib.request


API_ROOT = "https://api.cloudflare.com/client/v4"
RELAY_URL = "https://relay.amberdogeorgia.com/__maintenance/touch"


def request_json(url, headers, payload=None):
    request = urllib.request.Request(
        url,
        data=None if payload is None else json.dumps(payload).encode("utf-8"),
        headers=headers,
        method="GET" if payload is None else "POST",
    )
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=25) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code == 429 and attempt < 3:
                try:
                    delay = min(max(int(error.headers.get("Retry-After", "2")), 1), 30)
                except ValueError:
                    delay = 2
                time.sleep(delay)
                continue
            raise RuntimeError(f"Request failed with HTTP {error.code}") from None
        except urllib.error.URLError:
            if attempt < 3:
                time.sleep(2 * (attempt + 1))
                continue
            raise RuntimeError("Request failed due to network error") from None
    raise RuntimeError("Request retry limit reached")


def objects(account, namespace, api_token):
    if not re.fullmatch(r"[a-fA-F0-9]{32}", account) or not re.fullmatch(r"[a-fA-F0-9]{32}", namespace):
        raise RuntimeError("Account and namespace IDs must each be 32 hex characters")
    root = f"{API_ROOT}/accounts/{account}/workers/durable_objects/namespaces/{namespace}/objects"
    cursor = None
    seen_cursors = set()
    seen_ids = set()
    while True:
        query = {"limit": 1000}
        if cursor:
            query["cursor"] = cursor
        url = root + "?" + urllib.parse.urlencode(query)
        page = request_json(url, {"Authorization": f"Bearer {api_token}"})
        if page.get("success") is not True or not isinstance(page.get("result"), list):
            raise RuntimeError("Cloudflare object listing failed")
        for item in page["result"]:
            object_id = item.get("id")
            if not isinstance(object_id, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", object_id):
                raise RuntimeError("Cloudflare listing returned an invalid object ID")
            if object_id in seen_ids:
                continue
            seen_ids.add(object_id)
            if item.get("hasStoredData") is True:
                yield object_id
        info = page.get("result_info") or {}
        next_cursor = info.get("cursor") or (info.get("cursors") or {}).get("after")
        if not next_cursor:
            if isinstance(info.get("total_count"), int) and len(seen_ids) < info["total_count"]:
                raise RuntimeError("Cloudflare listing ended before all objects were returned")
            return
        if next_cursor in seen_cursors:
            raise RuntimeError("Cloudflare listing repeated a cursor")
        seen_cursors.add(next_cursor)
        cursor = next_cursor


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Touch stored objects; default is count-only dry-run")
    args = parser.parse_args()
    account = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "")
    namespace = os.environ.get("NEXTNOTIF_PAIRING_NAMESPACE_ID", "")
    api_token = os.environ.get("CLOUDFLARE_API_TOKEN", "")
    maintenance_token = os.environ.get("NEXTNOTIF_MAINTENANCE_TOKEN", "")
    if not account or not namespace or not api_token:
        parser.error("Set CLOUDFLARE_ACCOUNT_ID, NEXTNOTIF_PAIRING_NAMESPACE_ID, and CLOUDFLARE_API_TOKEN")
    if args.apply and len(maintenance_token) < 32:
        parser.error("--apply requires NEXTNOTIF_MAINTENANCE_TOKEN (at least 32 characters)")
    total = touched = 0
    for object_id in objects(account, namespace, api_token):
        total += 1
        if args.apply:
            result = request_json(RELAY_URL, {
                "X-NextNotif-Maintenance-Token": maintenance_token,
                "Content-Type": "application/json",
            }, {"object_id": object_id})
            if result.get("ok") is not True:
                raise RuntimeError("Maintenance touch failed")
            touched += 1
            time.sleep(0.1)
    print(f"Stored pairing objects: {total}; retention maintenance applied: {touched}")


if __name__ == "__main__":
    main()

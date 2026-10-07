#!/usr/bin/env python3
"""Disposable, content-free authorization probe for the staging Worker only."""

import asyncio
import inspect
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

try:
    import websockets
except ModuleNotFoundError:
    websockets = None


BASE = "https://nextnotif-relay-staging.shayanshad.workers.dev"
WS_BASE = BASE.replace("https://", "wss://", 1)
RECOVERY = Path("/private/tmp/nextnotif-staging-security-recovery.json")
WS_HEADERS = (
    "additional_headers"
    if websockets and "additional_headers" in inspect.signature(websockets.connect).parameters
    else "extra_headers"
)


def request(path, method="POST", headers=None, body=None):
    payload = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        BASE + path,
        data=payload,
        headers={"Content-Type": "application/json", "User-Agent": "curl/8.7.1", **(headers or {})},
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as response:
            status, content = response.status, response.read()
            content_type = response.headers.get("Content-Type", "")
    except urllib.error.HTTPError as error:
        status, content = error.code, error.read()
        content_type = error.headers.get("Content-Type", "")
    except urllib.error.URLError as error:
        parts = path.strip("/").split("/")
        operation = "pair-status" if parts[0] == "pair" and len(parts) > 2 else parts[1] if parts[0] == "pair" else parts[0]
        raise RuntimeError(f"network error on {operation}: {type(error.reason).__name__}") from error
    if content and "application/json" not in content_type:
        raise RuntimeError(f"non-JSON HTTP {status} ({content_type})")
    return status, json.loads(content) if content else {}


def identity(code, device):
    return {
        "X-NextNotif-Code": code,
        "X-NextNotif-Device-Id": device["device_id"],
        "X-NextNotif-Token": device["device_token"],
    }


def expect_status(label, actual, expected):
    if actual != expected:
        raise RuntimeError(f"{label}: HTTP {actual}, expected {expected}")


def save_recovery(code, owner):
    descriptor = os.open(RECOVERY, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as handle:
        json.dump({"code": code, "device_id": owner["device_id"], "device_token": owner["device_token"]}, handle)
        handle.flush()
        os.fsync(handle.fileno())


def cleanup(code, owner):
    status, _ = request("/pair/secure-delete", headers=identity(code, owner), body={})
    expect_status("owner cleanup", status, 200)
    RECOVERY.unlink(missing_ok=True)


def recover_previous():
    if not RECOVERY.exists():
        return False
    with RECOVERY.open() as handle:
        saved = json.load(handle)
    cleanup(saved["code"], saved)
    print("PASS: recovered and deleted previous disposable staging pairing")
    return True


async def denied_websocket(uri, headers):
    try:
        async with websockets.connect(
            uri, **{WS_HEADERS: headers}, user_agent_header="curl/8.7.1", open_timeout=10,
        ):
            raise RuntimeError("unauthorized WebSocket upgrade accepted")
    except websockets.exceptions.InvalidStatus:
        pass


async def check_websocket_revocation(code, owner, peer):
    uri = f"{WS_BASE}/ws/receiver/{code}"
    await denied_websocket(uri, {})
    await denied_websocket(uri, identity(code, owner))
    async with websockets.connect(
        uri, **{WS_HEADERS: identity(code, peer)}, user_agent_header="curl/8.7.1", open_timeout=10,
    ) as socket:
        await socket.send(json.dumps({"type": "hello", "device_name": "staging security probe"}))
        challenge = json.loads(await asyncio.wait_for(socket.recv(), 8))
        if challenge.get("type") != "handshake" or not challenge.get("token"):
            raise RuntimeError("WebSocket handshake challenge missing")
        await socket.send(json.dumps({"type": "auth", "token": challenge["token"], "code": code}))
        accepted = json.loads(await asyncio.wait_for(socket.recv(), 8))
        if accepted.get("type") != "auth_ok":
            raise RuntimeError("WebSocket authentication failed")
        status, _ = await asyncio.to_thread(
            request, "/pair/secure-delete", headers=identity(code, owner), body={},
        )
        expect_status("owner deletion", status, 200)
        try:
            async with asyncio.timeout(8):
                while True:
                    await socket.recv()
        except websockets.exceptions.ConnectionClosed:
            pass
        except TimeoutError as error:
            raise RuntimeError("deleted pairing WebSocket remained open") from error
    await denied_websocket(uri, identity(code, peer))
    await denied_websocket(f"{WS_BASE}/ws/sender/{code}", identity(code, owner))


def main(with_websocket=False):
    if recover_previous():
        return
    if with_websocket and websockets is None:
        raise RuntimeError("WebSocket package unavailable; use server/.venv/bin/python")
    code = None
    owner = None
    deleted = False
    try:
        status, created = request("/pair/secure-create", body={"role": "sender"})
        expect_status("create", status, 200)
        code = created["code"]
        owner = created
        save_recovery(code, owner)
        invite = created["invite"]
        status, peer = request(
            "/pair/secure-join",
            headers={"X-NextNotif-Code": code},
            body={"role": "receiver", "secret": invite["secret"]},
        )
        expect_status("join", status, 200)
        status, _ = request(
            "/pair/secure-join",
            headers={"X-NextNotif-Code": code},
            body={"role": "receiver", "secret": invite["secret"]},
        )
        expect_status("invite replay", status, 401)

        status, _ = request(f"/pair/{code}/status", method="GET")
        expect_status("code-only status", status, 401)
        status, _ = request("/send", headers={"X-NextNotif-Code": code}, body={"type": "relay_test", "data": {}})
        expect_status("code-only send", status, 401)
        status, _ = request("/fetch", headers=identity(code, owner), body={})
        expect_status("sender fetch", status, 401)
        status, _ = request("/send", headers=identity(code, peer), body={"type": "relay_test", "data": {}})
        expect_status("receiver send", status, 401)
        status, _ = request(f"/pair/{code}/status", method="GET", headers=identity(code, peer))
        expect_status("receiver status", status, 200)

        status, _ = request(
            "/send", headers=identity(code, owner),
            body={"type": "relay_test", "data": {"message": "staging security probe"}},
        )
        expect_status("authorized send", status, 200)
        status, snapshot = request("/fetch", headers=identity(code, peer), body={})
        expect_status("authorized fetch", status, 200)
        events = snapshot.get("events", [])
        if len(events) != 1 or events[0].get("type") != "relay_test":
            raise RuntimeError("synthetic queue did not contain exactly one test event")

        status, _ = request("/pair/secure-delete", headers=identity(code, peer), body={})
        expect_status("peer deletion", status, 401)
        if with_websocket:
            asyncio.run(check_websocket_revocation(code, owner, peer))
            RECOVERY.unlink(missing_ok=True)
        else:
            cleanup(code, owner)
        deleted = True
        for label, device, operation, method in (
            ("former sender status", owner, f"/pair/{code}/status", "GET"),
            ("former receiver status", peer, f"/pair/{code}/status", "GET"),
            ("former sender send", owner, "/send", "POST"),
            ("former receiver fetch", peer, "/fetch", "POST"),
        ):
            status, _ = request(operation, method=method, headers=identity(code, device), body=None if method == "GET" else {})
            expect_status(label, status, 401)
        checked = "active WebSocket closure, " if with_websocket else ""
        print(f"PASS: one-use invite, role isolation, code-only denial, synthetic queue, owner deletion, {checked}former-device denial")
    finally:
        if code and owner and not deleted:
            try:
                cleanup(code, owner)
            except Exception:
                print("Cleanup incomplete: owner credential retained in private recovery file", file=sys.stderr)


if __name__ == "__main__":
    try:
        main(with_websocket="--websocket" in sys.argv[1:])
    except Exception as error:
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", file=sys.stderr)
        raise SystemExit(1)

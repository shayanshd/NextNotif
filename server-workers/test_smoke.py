"""Smoke tests for the NextNotif Cloudflare Worker relay.

Point NEXTNOTIF_WORKER_URL at a running worker and run:

    python test_smoke.py                      # default http://localhost:8787
    NEXTNOTIF_WORKER_URL=https://nextnotif-relay.<account>.workers.dev python test_smoke.py

Without NEXTNOTIF_WORKER_URL the suite auto-starts a local `npx wrangler dev`
when nothing is listening on the default port (and needs node for the FCM
checks either way).

Requires the server venv (httpx + websockets): server/.venv/bin/python test_smoke.py

Every websocket check performs the handshake: connect, send {"type":"hello"},
recv {"type":"handshake","token":...}, send {"type":"auth","token":...,"code":...}.
"""

import asyncio
import base64
import inspect
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import websockets

BASE = os.environ.get("NEXTNOTIF_WORKER_URL", "http://localhost:8787").rstrip("/")
LOCAL_TARGET = BASE == "http://localhost:8787"
if BASE.startswith("http://"):
    WS_BASE = BASE.replace("http://", "ws://")
else:
    WS_BASE = BASE.replace("https://", "wss://")
UA = {"User-Agent": "nextnotif-smoke-test/1.0"}
AUTO_CODE = "424242"
HERE = os.path.dirname(os.path.abspath(__file__))
FCM_TOKEN = "test-fcm-token-" + "f6e5d4c3b2a1" * 6

# websockets >= 14 uses additional_headers; the legacy client used extra_headers.
_CONNECT_PARAMS = inspect.signature(websockets.connect).parameters
_HDR_KWARG = "additional_headers" if "additional_headers" in _CONNECT_PARAMS else "extra_headers"


def ws_connect(url, headers=None):
    if headers is None:
        return websockets.connect(url)
    return websockets.connect(url, **{_HDR_KWARG: headers})


async def handshake(ws, code, token=None, device_token=None, device_name=None, fcm_token=None):
    """Send hello, recv the server handshake, answer auth, consume auth_ok.

    Returns the device token issued (or kept) by the worker.
    """
    hello = {"type": "hello"}
    if device_name is not None:
        hello["device_name"] = device_name
    if fcm_token is not None:
        hello["fcm_token"] = fcm_token
    await ws.send(json.dumps(hello))
    raw = await asyncio.wait_for(ws.recv(), timeout=5)
    hs = json.loads(raw)
    assert hs.get("type") == "handshake" and isinstance(hs.get("token"), str) and hs["token"], hs
    auth = {"type": "auth", "token": token if token is not None else hs["token"], "code": code}
    if device_token is not None:
        auth["device_token"] = device_token
    if fcm_token is not None:
        auth["fcm_token"] = fcm_token
    await ws.send(json.dumps(auth))
    raw = await asyncio.wait_for(ws.recv(), timeout=5)
    ok = json.loads(raw)
    assert (
        ok.get("type") == "auth_ok"
        and isinstance(ok.get("device_token"), str)
        and ok["device_token"]
    ), ok
    return ok["device_token"]


async def connect_authed(path, code, headers=None, device_token=None, device_name=None, fcm_token=None):
    ws = await ws_connect(f"{WS_BASE}{path}", headers)
    dt = await handshake(ws, code, device_token=device_token, device_name=device_name, fcm_token=fcm_token)
    return ws, dt


async def wait_disconnected(code, role="sender", timeout: float = 5.0) -> bool:
    key = f"{role}_connected"
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        body = await get_json(f"/pair/{code}/status")
        if body.get(key) is False:
            return True
        await asyncio.sleep(0.05)
    return False


async def run_check(name, fn, *args):
    try:
        return True, await fn(*args)
    except Exception as exc:
        print(f"FAIL: {name}: {exc}")
        return False, None


async def http_get(path: str):
    req = urllib.request.Request(BASE + path, headers=UA)
    loop = asyncio.get_running_loop()

    def _do():
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    return await loop.run_in_executor(None, _do)


async def http_post(path: str):
    req = urllib.request.Request(BASE + path, data=b"", method="POST", headers=UA)
    loop = asyncio.get_running_loop()

    def _do():
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read()

    return await loop.run_in_executor(None, _do)


async def http_post_json(path: str, payload: str, headers=None):
    hdrs = dict(UA)
    if headers:
        hdrs.update(headers)
    req = urllib.request.Request(BASE + path, data=payload.encode(), method="POST", headers=hdrs)
    loop = asyncio.get_running_loop()

    def _do():
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, resp.read()
        except urllib.error.HTTPError as e:
            return e.code, e.read()

    return await loop.run_in_executor(None, _do)


async def wait_ready(timeout: float = 30.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            status, _ = await http_get("/")
            if status == 200:
                return True
        except Exception:
            pass
        await asyncio.sleep(0.5)
    return False


async def get_json(path):
    status, body = await http_get(path)
    if status != 200:
        raise AssertionError(f"GET {path} -> {status}")
    return json.loads(body)


async def fresh_code():
    status, body = await http_post("/pair/create")
    assert status == 200, f"POST /pair/create -> {status}"
    return json.loads(body)["code"]


async def expect_auth_reject(ws, code, token, label, send_code=None):
    """Send an auth message and assert the socket is closed with 1008 'auth failed'."""
    await ws.send(
        json.dumps(
            {"type": "auth", "token": token, "code": send_code if send_code is not None else code}
        )
    )
    try:
        await asyncio.wait_for(ws.recv(), timeout=5)
    except websockets.exceptions.ConnectionClosed as e:
        assert e.rcvd is not None and e.rcvd.code == 1008, e.rcvd
        assert e.rcvd.reason == "auth failed", e.rcvd.reason
        print(f"[ok] {label} closed with 1008")
        return
    await ws.close()
    raise AssertionError(f"socket stayed open after {label} auth")


async def check_health():
    """GET / answers with the relay banner."""
    status, body = await http_get("/")
    assert status == 200 and "NextNotif" in body.decode(), body
    print("[ok] health endpoint")


async def check_create():
    """POST /pair/create returns a fresh 6-digit code with clean initial status."""
    status, body = await http_post("/pair/create")
    assert status == 200, f"POST /pair/create -> {status}"
    code = json.loads(body)["code"]
    assert len(code) == 6 and code.isdigit(), f"bad code {code!r}"
    status = await get_json(f"/pair/{code}/status")
    assert status == {
        "exists": True,
        "sender_connected": False,
        "receiver_connected": False,
        "sender_name": None,
        "receiver_name": None,
        "sender_has_fcm": False,
        "receiver_has_fcm": False,
    }, status
    print(f"[ok] pairing created: {code}")
    return code


async def check_both_connect(code):
    """Sender and receiver connect (handshaken); status reports both sides connected."""
    sender, _ = await connect_authed(f"/ws/sender/{code}", code, device_name="SmokeSender 1")
    receiver, _ = await connect_authed(f"/ws/receiver/{code}", code, device_name="SmokeReceiver 2")
    status = await get_json(f"/pair/{code}/status")
    assert status == {
        "exists": True,
        "sender_connected": True,
        "receiver_connected": True,
        "sender_name": "SmokeSender 1",
        "receiver_name": "SmokeReceiver 2",
        "sender_has_fcm": False,
        "receiver_has_fcm": False,
    }, status
    print("[ok] status shows both connected + device names")
    return sender, receiver


async def check_sms_relay(sender, receiver):
    """SMS event from sender reaches receiver tagged with from=sender."""
    await sender.send(json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "hello relay", "ts": 1}}))
    got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
    assert got["type"] == "sms" and got["from"] == "sender" and got["data"]["body"] == "hello relay", got
    print("[ok] SMS relayed sender -> receiver")


async def check_call_relay(sender, receiver):
    """Call event from sender reaches receiver with its data intact."""
    await sender.send(json.dumps({"type": "call", "data": {"number": "+15552223333", "state": "RINGING", "ts": 2}}))
    got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
    assert got["type"] == "call" and got["from"] == "sender" and got["data"]["state"] == "RINGING", got
    print("[ok] call relayed sender -> receiver")


async def check_reverse_relay(sender, receiver):
    """Message from receiver is relayed back to sender tagged from=receiver."""
    await receiver.send(json.dumps({"type": "sms", "data": {"from": "+15559998888", "body": "ping", "ts": 3}}))
    got = json.loads(await asyncio.wait_for(sender.recv(), timeout=5))
    assert got["from"] == "receiver" and got["data"]["body"] == "ping", got
    print("[ok] reverse relay receiver -> sender")


async def check_binary_audio_relay(sender, receiver):
    """Ephemeral PCM frames relay byte-for-byte in both directions."""
    downstream = b"\x00\x01\xfe\xff" * 80
    upstream = b"\x10\x00\xf0\xff" * 80
    await sender.send(downstream)
    assert await asyncio.wait_for(receiver.recv(), timeout=5) == downstream
    await receiver.send(upstream)
    assert await asyncio.wait_for(sender.recv(), timeout=5) == upstream
    print("[ok] binary call audio relayed bidirectionally")


async def check_auth_duplicate_ignored(sender, receiver):
    """A second auth message from an authenticated socket is ignored, not relayed."""
    await sender.send(json.dumps({"type": "auth", "token": "stale", "code": "000000"}))
    await sender.send(json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "after dup auth", "ts": 4}}))
    got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
    assert got["type"] == "sms" and got["data"]["body"] == "after dup auth", got
    print("[ok] duplicate auth from authenticated socket ignored")


async def check_takeover():
    """A second sender while one is connected takes over the slot: it is
    accepted (101) and the first holder is closed with 1000 'replaced'.
    This is what lets a phone recover when its previous connection is a
    zombie the edge has not reaped yet."""
    code = await fresh_code()
    first, _ = await connect_authed(f"/ws/sender/{code}", code)
    second = await ws_connect(f"{WS_BASE}/ws/sender/{code}")
    try:
        try:
            raw = await asyncio.wait_for(first.recv(), timeout=5)
            raise AssertionError(f"first holder stayed open: {raw!r}")
        except websockets.exceptions.ConnectionClosed as e:
            assert e.rcvd is not None and e.rcvd.code == 1000, e.rcvd
            assert e.rcvd.reason == "replaced", e.rcvd.reason
        await handshake(second, code)
        status = await get_json(f"/pair/{code}/status")
        assert status == {
            "exists": True,
            "sender_connected": True,
            "receiver_connected": False,
            "sender_name": None,
            "receiver_name": None,
            "sender_has_fcm": False,
            "receiver_has_fcm": False,
        }, status
        print("[ok] duplicate sender takes over, old holder closed 1000 'replaced'")
    finally:
        await second.close()
        try:
            await first.close()
        except Exception:
            pass


async def check_takeover_name():
    """Takeover keeps the new holder's device name: the old socket's late
    close (still listed for the role) used to wipe names[sender] and
    pending[sender], silently dropping the new holder's auth."""
    code = await fresh_code()
    first, _ = await connect_authed(f"/ws/sender/{code}", code, device_name="TakeoverOld")
    second, _ = await connect_authed(f"/ws/sender/{code}", code, device_name="TakeoverNew")
    try:
        try:
            raw = await asyncio.wait_for(first.recv(), timeout=5)
            raise AssertionError(f"first holder stayed open: {raw!r}")
        except websockets.exceptions.ConnectionClosed as e:
            assert e.rcvd is not None and e.rcvd.code == 1000, e.rcvd
            assert e.rcvd.reason == "replaced", e.rcvd.reason
        body = None
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            body = await get_json(f"/pair/{code}/status")
            if body.get("sender_name") == "TakeoverNew":
                break
            await asyncio.sleep(0.2)
        assert body is not None and body.get("sender_name") == "TakeoverNew", body
        # The old socket's late close lands shortly after; the name must survive it.
        await asyncio.sleep(1)
        body = await get_json(f"/pair/{code}/status")
        assert body.get("sender_name") == "TakeoverNew", f"late close wiped the new name: {body}"
        assert body.get("sender_connected") is True, body
        print("[ok] takeover keeps the new holder's name")
    finally:
        await second.close()
        try:
            await first.close()
        except Exception:
            pass


async def check_disconnect(code, sender, receiver):
    """Closing the sender socket clears sender_connected while receiver stays up."""
    await sender.close()
    body = None
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        body = await get_json(f"/pair/{code}/status")
        if body == {
            "exists": True,
            "sender_connected": False,
            "receiver_connected": True,
            "sender_name": None,
            "receiver_name": "SmokeReceiver 2",
            "sender_has_fcm": False,
            "receiver_has_fcm": False,
        }:
            print("[ok] disconnect updates status")
            return
        await asyncio.sleep(0.1)
    raise AssertionError(f"unexpected status after disconnect: {body}")


async def check_reconnect(code, receiver):
    """A fresh sender can reconnect (handshaken) and relay to the still-connected receiver."""
    sender, _ = await connect_authed(f"/ws/sender/{code}", code)
    try:
        await sender.send(json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "after reconnect", "ts": 4}}))
        got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got["type"] == "sms" and got["data"]["body"] == "after reconnect", got
    finally:
        await sender.close()
        await receiver.close()
    print("[ok] reconnect relays sender -> receiver")


async def check_device_token_flow(code):
    """First connect issues a device token; a reconnect presenting it keeps it,
    and a bogus one is replaced with a fresh valid one."""
    ws, dt = await connect_authed(f"/ws/sender/{code}", code)
    assert len(dt) >= 32, f"device token looks too short: {dt!r}"
    await ws.close()
    assert await wait_disconnected(code, "sender"), "sender slot not freed"

    ws, dt2 = await connect_authed(f"/ws/sender/{code}", code, device_token=dt)
    assert dt2 == dt, "a valid presented device token should be kept"
    await ws.close()
    assert await wait_disconnected(code, "sender"), "sender slot not freed (2)"

    ws, dt3 = await connect_authed(f"/ws/sender/{code}", code, device_token="stale-token")
    assert dt3 != dt and len(dt3) >= 32, "bogus token should be replaced by a fresh one"
    await ws.close()
    assert await wait_disconnected(code, "sender"), "sender slot not freed (3)"
    print(f"[ok] device token issued ({len(dt)} chars), kept on reconnect, replaced when stale")


async def check_bogus_role(code):
    """A websocket with an unknown role is rejected."""
    try:
        bad = await websockets.connect(f"{WS_BASE}/ws/bogus/{code}")
    except websockets.exceptions.InvalidStatus:
        print("[ok] bogus role rejected")
        return
    await bad.close()
    raise AssertionError("bogus role was accepted")


async def check_auto_create():
    """A typed 6-digit code with no pairing is auto-created on connect."""
    ws, _ = await connect_authed(f"/ws/sender/{AUTO_CODE}", AUTO_CODE)
    try:
        status = await get_json(f"/pair/{AUTO_CODE}/status")
        assert status["exists"] is True, status
    finally:
        await ws.close()
    print("[ok] typed code auto-creates pairing")


async def check_auth_wrong_token():
    """Auth with a wrong token closes the socket with 1008 'auth failed'."""
    code = await fresh_code()
    ws = await ws_connect(f"{WS_BASE}/ws/sender/{code}")
    await ws.send(json.dumps({"type": "hello"}))
    raw = await asyncio.wait_for(ws.recv(), timeout=5)
    hs = json.loads(raw)
    assert hs.get("type") == "handshake", hs
    await expect_auth_reject(ws, code, "definitely-wrong-token", "wrong-token")


async def check_auth_wrong_code():
    """Auth with the right token but a wrong code closes with 1008 'auth failed'."""
    code = await fresh_code()
    ws = await ws_connect(f"{WS_BASE}/ws/sender/{code}")
    await ws.send(json.dumps({"type": "hello"}))
    raw = await asyncio.wait_for(ws.recv(), timeout=5)
    hs = json.loads(raw)
    await expect_auth_reject(ws, code, hs["token"], "wrong-code", send_code="000000")


async def check_auth_timeout():
    """A socket that never authenticates is closed within ~7s with 1008 'auth timeout'."""
    code = await fresh_code()
    ws = await ws_connect(f"{WS_BASE}/ws/sender/{code}")
    await ws.send(json.dumps({"type": "hello"}))
    await asyncio.wait_for(ws.recv(), timeout=5)  # consume the handshake
    start = time.monotonic()
    try:
        await asyncio.wait_for(ws.recv(), timeout=8)
    except websockets.exceptions.ConnectionClosed as e:
        elapsed = time.monotonic() - start
        assert e.rcvd is not None and e.rcvd.code == 1008, e.rcvd
        assert e.rcvd.reason == "auth timeout", e.rcvd.reason
        assert 4.5 <= elapsed < 7.0, f"auth timeout took {elapsed:.2f}s"
        print(f"[ok] no-auth socket closed with 1008 'auth timeout' after {elapsed:.2f}s")
        return
    await ws.close()
    raise AssertionError("socket stayed open past the auth window")


async def check_header_code():
    """Connect via X-NextNotif-Code header with /ws/<role> (no code in path) works."""
    code = await fresh_code()
    ws = await ws_connect(f"{WS_BASE}/ws/sender", {"X-NextNotif-Code": code})
    try:
        await handshake(ws, code)
        status = await get_json(f"/pair/{code}/status")
        assert status == {
            "exists": True,
            "sender_connected": True,
            "receiver_connected": False,
            "sender_name": None,
            "receiver_name": None,
            "sender_has_fcm": False,
            "receiver_has_fcm": False,
        }, status
        print("[ok] header code with /ws/<role> path works")
    finally:
        await ws.close()


async def check_send_uplink():
    """POST /send: queued while receiver offline, replayed in order on the
    receiver's connect, delivered live afterwards. Unknown code -> 404,
    malformed body -> 400, header form (no code in path) works."""
    code = await fresh_code()

    # Receiver offline: events are queued.
    status, body = await http_post_json(
        f"/send/{code}",
        json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "queued one", "ts": 1}}),
    )
    assert status == 200 and json.loads(body) == {"delivered": False, "queued": 1}, (status, body)
    status, body = await http_post_json(
        f"/send/{code}",
        json.dumps({"type": "call", "data": {"number": "+15552223333", "state": "RINGING", "ts": 2}}),
    )
    assert status == 200 and json.loads(body) == {"delivered": False, "queued": 2}, (status, body)

    # Receiver connects: catch-up replays the queue in order, then live delivery.
    receiver, _ = await connect_authed(f"/ws/receiver/{code}", code)
    try:
        got1 = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got1["type"] == "sms" and got1["from"] == "sender" and got1["data"]["body"] == "queued one", got1
        got2 = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got2["type"] == "call" and got2["from"] == "sender" and got2["data"]["state"] == "RINGING", got2

        status, body = await http_post_json(
            f"/send/{code}",
            json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "live one", "ts": 3}}),
        )
        assert status == 200 and json.loads(body) == {"delivered": True, "queued": 0}, (status, body)
        got3 = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got3["data"]["body"] == "live one", got3
        print("[ok] /send queues while offline, catch-up in order, live after connect")
    finally:
        await receiver.close()

    # Unknown pairing -> 404, malformed body -> 400.
    status, _ = await http_post_json("/send/999999", json.dumps({"type": "sms", "data": {}}))
    assert status == 404, f"unknown code -> {status}"
    status, _ = await http_post_json(f"/send/{code}", "this is not json")
    assert status == 400, f"bad body -> {status}"

    # Header form: POST /send with the code in X-NextNotif-Code.
    receiver2, _ = await connect_authed(f"/ws/receiver/{code}", code)
    try:
        status, body = await http_post_json(
            "/send",
            json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "header form", "ts": 4}}),
            headers={"X-NextNotif-Code": code},
        )
        assert status == 200 and json.loads(body) == {"delivered": True, "queued": 0}, (status, body)
        got = json.loads(await asyncio.wait_for(receiver2.recv(), timeout=5))
        assert got["data"]["body"] == "header form", got
        print("[ok] /send 404/400 handling and header form")
    finally:
        await receiver2.close()


async def check_fetch_ack():
    """Fetch is repeatable; exact-ID ACK is authenticated and safe to retry."""
    code = await fresh_code()
    receiver, token = await connect_authed(f"/ws/receiver/{code}", code)
    await receiver.close()
    assert await wait_disconnected(code, "receiver"), "receiver slot not freed"
    headers = {"X-NextNotif-Code": code, "X-NextNotif-Token": token}

    async def send(label):
        status, body = await http_post_json(
            f"/send/{code}", json.dumps({"type": "sms", "data": {"body": label}}),
        )
        assert status == 200 and json.loads(body)["delivered"] is False, (status, body)

    async def fetch():
        status, body = await http_post_json("/fetch", "", headers)
        assert status == 200, (status, body)
        return json.loads(body)["events"]

    async def ack(ids):
        status, body = await http_post_json("/ack", json.dumps({"event_ids": ids}), headers)
        assert status == 200, (status, body)
        return json.loads(body)

    await send("fetched one")
    await send("fetched two")
    events = await fetch()
    assert [e["data"]["body"] for e in events] == ["fetched one", "fetched two"], events
    ids = [e["event_id"] for e in events]
    assert all(ids) and len(set(ids)) == 2, ids
    assert await fetch() == events, "second fetch changed or deleted events"
    status, body = await http_post_json(
        f"/fetch/{code}", "", {"Authorization": f"Bearer {token}"},
    )
    assert status == 200 and json.loads(body)["events"] == events, (status, body)

    # A different pairing's otherwise valid credential must not authorize this one.
    other_code = await fresh_code()
    other, other_token = await connect_authed(f"/ws/receiver/{other_code}", other_code)
    await other.close()
    for operation in ("fetch", "ack"):
        for invalid_headers in ({}, {"X-NextNotif-Token": "wrong-token"},
                                {"Authorization": f"Bearer {other_token}"}):
            status, _ = await http_post_json(
                f"/{operation}/{code}", json.dumps({"event_ids": ids}), invalid_headers,
            )
            assert status == 401, (operation, status)
    assert await fetch() == events, "unauthorized operation modified the queue"

    for invalid in ({}, {"event_ids": None}, {"event_ids": "not-list"},
                    {"event_ids": [1]}, {"event_ids": [""]}, {"event_ids": [" \t"]},
                    {"event_ids": ["unknown"] * 101}):
        status, _ = await http_post_json("/ack", json.dumps(invalid), headers)
        assert status == 400, (invalid, status)
    status, _ = await http_post_json("/ack", "not-json", headers)
    assert status == 400, status
    assert await ack([]) == {"acknowledged": 0}
    assert await ack(["unknown"] * 100) == {"acknowledged": 0}
    assert await ack([f" {ids[0]} "]) == {"acknowledged": 0}, "ACK did not use exact IDs"
    assert await fetch() == events, "invalid or empty ACK modified the queue"

    # One event arrives after the fetch and another races the ACK itself.
    # Neither ID was in the fetched snapshot; both must remain in either order.
    await send("after fetch")
    _, result = await asyncio.gather(send("concurrent with ack"), ack(ids + [ids[0]]))
    assert result == {"acknowledged": 2}, result
    assert await ack(ids) == {"acknowledged": 0}, "ACK retry was not idempotent"
    remaining = await fetch()
    assert [e["data"]["body"] for e in remaining] == ["after fetch", "concurrent with ack"], remaining
    remaining_ids = [e["event_id"] for e in remaining]
    status, body = await http_post_json(
        f"/ack/{code}", json.dumps({"event_ids": remaining_ids}),
        {"Authorization": f"Bearer {token}"},
    )
    assert status == 200 and json.loads(body) == {"acknowledged": 2}, (status, body)
    assert await fetch() == [], "acknowledged events remained"
    print("[ok] fetch repeats; authenticated exact-ID ACK retries and preserves concurrent events")


async def check_persistence(code, auto_code):
    """Codes still report exists=true after all sockets are closed."""
    deadline = time.monotonic() + 5
    body = None
    while time.monotonic() < deadline:
        body = await get_json(f"/pair/{code}/status")
        if body == {
            "exists": True,
            "sender_connected": False,
            "receiver_connected": False,
            "sender_name": None,
            "receiver_name": None,
            "sender_has_fcm": False,
            "receiver_has_fcm": False,
        }:
            break
        await asyncio.sleep(0.1)
    assert body == {
        "exists": True,
        "sender_connected": False,
        "receiver_connected": False,
        "sender_name": None,
        "receiver_name": None,
        "sender_has_fcm": False,
        "receiver_has_fcm": False,
    }, body
    status = await get_json(f"/pair/{auto_code}/status")
    assert status["exists"] is True, status
    print(f"[ok] pairings persisted for {code} and {auto_code}")


# ---------------------------------------------------------------------------
# FCM wake fallback: a dedicated local `wrangler dev` with the service
# account + stubbed Google endpoints bound as vars, so the full path
# (RS256 assertion -> OAuth -> messages:send) is exercised against the real
# worker code without touching the real Firebase project.
# ---------------------------------------------------------------------------


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class FcmStub:
    """Local stand-in for the Google OAuth token endpoint + FCM API that
    records every request it receives (also to a file, for post-mortem)."""

    def __init__(self) -> None:
        self.token_calls: list = []
        self.messages: list = []
        self._lock = threading.Lock()
        self._log_path = os.path.join(tempfile.gettempdir(), f"nextnotif-fcm-stub-{free_port()}.log")
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, fmt, *args):
                with outer._lock:
                    with open(outer._log_path, "a", encoding="utf-8") as f:
                        f.write(f"{time.strftime('%H:%M:%S')} {self.path} {fmt % args}\n")

            def _send(self, code: int, body: dict) -> None:
                data = json.dumps(body).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def do_POST(self):
                length = int(self.headers.get("Content-Length", 0))
                raw = self.rfile.read(length)
                if self.path == "/token":
                    form = urllib.parse.parse_qs(raw.decode())
                    with outer._lock:
                        outer.token_calls.append(
                            {
                                "grant_type": form.get("grant_type", [""])[0],
                                "assertion": form.get("assertion", [""])[0],
                            }
                        )
                    self._send(
                        200, {"access_token": "stub-access-token", "expires_in": 3600, "token_type": "Bearer"}
                    )
                elif self.path.endswith("/messages:send"):
                    with outer._lock:
                        outer.messages.append({"auth": self.headers.get("Authorization"), "body": json.loads(raw)})
                    self._send(200, {"name": "projects/stub/messages/1"})
                else:
                    self._send(404, {"error": f"unknown path {self.path}"})

        port = free_port()
        self.server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.base = f"http://127.0.0.1:{port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def stop(self) -> None:
        self.server.shutdown()
        self.server.server_close()


SA_PATH = os.path.join(HERE, "fcm-test-sa.json")


def make_service_account():
    """Generate a throwaway RSA service account; returns (sa dict, public key)."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()
    sa = {
        "type": "service_account",
        "project_id": "nextnotif-test",
        "client_email": "wake@nextnotif-test.iam.gserviceaccount.com",
        "private_key_id": "testkey1",
        "private_key": pem,
    }
    with open(SA_PATH, "w", encoding="utf-8") as f:
        json.dump(sa, f)
    return sa, key.public_key()


def _b64url_decode(segment: str) -> bytes:
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))


def verify_jwt(assertion: str, public_key) -> dict:
    """Verify the RS256 service-account assertion and return its payload."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding

    h64, p64, s64 = assertion.split(".")
    signing_input = f"{h64}.{p64}".encode("ascii")
    public_key.verify(_b64url_decode(s64), signing_input, padding.PKCS1v15(), hashes.SHA256())
    return json.loads(_b64url_decode(p64))


class WorkerDev:
    """A local `npx wrangler dev` process for this project (optionally with
    the FCM service account + stub endpoints bound as vars).

    The FCM vars go through a temporary config file: the CLI `--var` form
    truncates values at the first ':' (the service-account JSON breaks)."""

    FCM_CONFIG = "wrangler.fcm-test.jsonc"

    def __init__(self, port: int) -> None:
        self.port = port
        self.base = f"http://127.0.0.1:{port}"
        self.ws_base = f"ws://127.0.0.1:{port}"
        self.proc = None

    def start(self, sa_json=None, stub_base=None) -> bool:
        cmd = ["npx", "wrangler", "dev", "--port", str(self.port), "--ip", "127.0.0.1"]
        if sa_json is not None and stub_base is not None:
            cfg = {
                "$schema": "node_modules/wrangler/config-schema.json",
                "name": "nextnotif-relay",
                "main": "src/worker.js",
                "compatibility_date": "2025-06-01",
                "vars": {
                    "FCM_SERVICE_ACCOUNT": json.dumps(sa_json, separators=(",", ":")),
                    "FCM_SEND_URL": stub_base,
                    "FCM_TOKEN_URL": f"{stub_base}/token",
                },
                "migrations": [{"tag": "v1", "new_classes": ["RelayPairing"]}],
                "durable_objects": {
                    "bindings": [{"name": "PAIRING", "class_name": "RelayPairing"}]
                },
            }
            with open(os.path.join(HERE, self.FCM_CONFIG), "w", encoding="utf-8") as f:
                json.dump(cfg, f, indent=2)
            cmd += ["--config", self.FCM_CONFIG]
        try:
            log_path = os.path.join(tempfile.gettempdir(), f"nextnotif-fcm-dev-{self.port}.log")
            self._logf = open(log_path, "w", encoding="utf-8")
            self.proc = subprocess.Popen(cmd, cwd=HERE, stdout=self._logf, stderr=subprocess.STDOUT)
        except (OSError, FileNotFoundError):
            return False
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            if self.proc.poll() is not None:
                return False
            try:
                with urllib.request.urlopen(self.base + "/", timeout=2) as resp:
                    if resp.status == 200:
                        return True
            except Exception:
                pass
            time.sleep(1)
        return False

    def stop(self) -> None:
        if self.proc is None:
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.proc = None
        if getattr(self, "_logf", None) is not None:
            try:
                self._logf.close()
            except Exception:
                pass
        try:
            os.remove(os.path.join(HERE, self.FCM_CONFIG))
        except FileNotFoundError:
            pass


async def poll(condition, timeout: float = 5.0, interval: float = 0.05):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return True
        await asyncio.sleep(interval)
    return False


def _http_post(base: str, path: str, payload=None, headers=None):
    data = payload.encode() if payload is not None else b""
    hdrs = dict(UA)
    if headers:
        hdrs.update(headers)
    if payload is not None:
        hdrs.setdefault("Content-Type", "application/json")
    req = urllib.request.Request(base + path, data=data, method="POST", headers=hdrs)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, resp.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def _http_get_json(base: str, path: str):
    req = urllib.request.Request(base + path, headers=UA)
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read())


async def fcm_status(dev, code):
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, _http_get_json, dev.base, f"/pair/{code}/status")


async def fcm_wait_status(dev, code, want: dict, timeout: float = 5.0):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        body = await fcm_status(dev, code)
        if all(body.get(k) == v for k, v in want.items()):
            return True
        await asyncio.sleep(0.1)
    return False


async def fcm_fresh_code(dev):
    loop = asyncio.get_running_loop()
    status, body = await loop.run_in_executor(None, _http_post, dev.base, "/pair/create")
    assert status == 200, f"POST /pair/create -> {status}"
    return json.loads(body)["code"]


async def fc_connect(base, path, code, fcm_token=None, device_name=None, delivery_mode=None):
    """Handshake against an explicit worker base (the FCM checks run against
    their own local dev instance, not the main BASE)."""
    ws = await websockets.connect(f"{base.ws_base}{path}")
    hello = {"type": "hello"}
    if device_name is not None:
        hello["device_name"] = device_name
    if fcm_token is not None:
        hello["fcm_token"] = fcm_token
    await ws.send(json.dumps(hello))
    raw = await asyncio.wait_for(ws.recv(), timeout=5)
    hs = json.loads(raw)
    assert hs.get("type") == "handshake", hs
    auth = {"type": "auth", "token": hs["token"], "code": code}
    if delivery_mode is not None:
        auth["delivery_mode"] = delivery_mode
    if fcm_token is not None:
        auth["fcm_token"] = fcm_token
    await ws.send(json.dumps(auth))
    raw = await asyncio.wait_for(ws.recv(), timeout=5)
    ok = json.loads(raw)
    assert ok.get("type") == "auth_ok", ok
    return ws


async def check_fcm_register(dev, fcm_token):
    """fcm_token on auth is stored per role and reported by /status."""
    code = await fcm_fresh_code(dev)
    ws = await fc_connect(dev, f"/ws/receiver/{code}", code, fcm_token=fcm_token)
    await ws.close()
    assert await fcm_wait_status(dev, code, {"receiver_connected": False}), "receiver slot not freed"
    status = await fcm_status(dev, code)
    assert status["receiver_has_fcm"] is True, status
    assert status["sender_has_fcm"] is False, status
    print(f"[ok] fcm token registered via auth, visible in status (code {code})")
    return code


async def check_fcm_wake_offline(base, stub, public_key, code, fcm_token):
    """POST /send while the receiver is offline: queued + one FCM message
    with the right token/payload and a valid RS256 OAuth assertion."""
    before_msgs = len(stub.messages)
    loop = asyncio.get_running_loop()
    status, body = await loop.run_in_executor(
        None,
        _http_post,
        base.base,
        f"/send/{code}",
        json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "wake me up", "ts": 100}}),
    )
    assert status == 200 and json.loads(body) == {"delivered": False, "queued": 1}, (status, body)

    assert await poll(lambda: len(stub.messages) > before_msgs, timeout=10), (
        f"no FCM wake sent for offline receiver (stub {stub.base}, "
        f"messages so far: {len(stub.messages)}, dev log: see temp dir)"
    )
    msg = stub.messages[-1]
    assert msg["auth"] == "Bearer stub-access-token", msg["auth"]
    m = msg["body"]["message"]
    assert m["token"] == fcm_token, m
    assert "notification" not in m, m
    assert m["data"] == {"nn": "1", "code": code, "wake": "1"}, m
    assert m["android"]["priority"] == "high", m

    call = stub.token_calls[-1]
    assert call["grant_type"] == "urn:ietf:params:oauth:grant-type:jwt-bearer", call
    payload = verify_jwt(call["assertion"], public_key)
    assert payload["scope"] == "https://www.googleapis.com/auth/firebase.messaging", payload
    assert payload["aud"] == f"{stub.base}/token", payload
    assert payload["exp"] > payload["iat"], payload
    print("[ok] offline receiver: event queued + FCM wake with valid RS256 assertion")


async def check_fcm_rate_limit(base, stub, code):
    """A second queued event inside the 30 s cooldown does not re-wake."""
    before_msgs = len(stub.messages)
    loop = asyncio.get_running_loop()
    status, body = await loop.run_in_executor(
        None,
        _http_post,
        base.base,
        f"/send/{code}",
        json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "second while cool", "ts": 101}}),
    )
    assert json.loads(body) == {"delivered": False, "queued": 2}, (status, body)
    await asyncio.sleep(1.5)
    assert len(stub.messages) == before_msgs, "cooldown was not enforced"
    print("[ok] wake rate-limited per pairing")


async def check_fcm_on_demand(base, stub, fcm_token):
    """On-demand registration creates an FCM-only pairing, authenticates queue
    drain, and wakes for every event instead of applying the WS cooldown."""
    code = str(100000 + time.time_ns() % 900000)
    assert (await fcm_status(base, code))["exists"] is False
    loop = asyncio.get_running_loop()
    status, body = await loop.run_in_executor(
        None,
        _http_post,
        base.base,
        "/fcm-register",
        json.dumps({"fcm_token": fcm_token, "device_name": "Sleeping receiver"}),
        {"X-NextNotif-Code": code},
    )
    assert status == 200, (status, body)
    device_token = json.loads(body)["device_token"]
    status_body = await fcm_status(base, code)
    assert status_body["exists"] is True and status_body["receiver_has_fcm"] is True, status_body
    before = len(stub.messages)
    for ts in (201, 202):
        status, body = await loop.run_in_executor(
            None,
            _http_post,
            base.base,
            f"/send/{code}",
            json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": f"push {ts}", "ts": ts}}),
        )
        assert status == 200, (status, body)
    assert await poll(lambda: len(stub.messages) >= before + 2, timeout=10), "on-demand wake was cooled down"
    wakes = [m["body"]["message"]["data"] for m in stub.messages[-2:]]
    assert all(w == {"nn": "1", "code": code, "wake": "1"} for w in wakes), wakes

    status, _ = await loop.run_in_executor(None, _http_post, base.base, f"/drain/{code}")
    assert status == 401, status
    status, body = await loop.run_in_executor(
        None,
        _http_post,
        base.base,
        "/drain",
        None,
        {"X-NextNotif-Code": code, "X-NextNotif-Token": device_token},
    )
    assert status == 200, (status, body)
    events = json.loads(body)["events"]
    assert [e["data"]["body"] for e in events] == ["push 201", "push 202"], events
    event_ids = [e["event_id"] for e in events]
    assert all(event_ids) and len(set(event_ids)) == 2, event_ids
    status, body = await loop.run_in_executor(
        None,
        _http_post,
        base.base,
        f"/drain/{code}",
        None,
        {"X-NextNotif-Token": device_token},
    )
    assert status == 200 and json.loads(body)["events"] == [], (status, body)
    print("[ok] FCM on-demand: every event wakes, authenticated drain clears queue")


async def check_fcm_temporary_call_socket(base, stub, fcm_token):
    """Temporary call sockets preserve durable fetch/ack and ephemeral media."""
    code = await fcm_fresh_code(base)
    loop = asyncio.get_running_loop()

    async def post(path, payload=None, headers=None):
        return await loop.run_in_executor(None, _http_post, base.base, path, payload, headers)

    status, body = await post(
        f"/fcm-register/{code}", json.dumps({"fcm_token": fcm_token}),
    )
    assert status == 200, (status, body)
    token = json.loads(body)["device_token"]
    headers = {"X-NextNotif-Token": token}
    before_wakes = len(stub.messages)
    status, body = await post(
        f"/send/{code}", json.dumps({"type": "sms", "data": {"body": "before call socket"}}),
    )
    assert status == 200 and json.loads(body)["queued"] == 1, (status, body)
    sender = await fc_connect(base, f"/ws/sender/{code}", code)
    receiver = None
    try:
        receiver = await fc_connect(base, f"/ws/receiver/{code}", code, delivery_mode="fcm")
        status, body = await post(f"/fetch/{code}", None, headers)
        assert status == 200, (status, body)
        backlog = json.loads(body)["events"]
        assert [e["data"]["body"] for e in backlog] == ["before call socket"], backlog
        status, body = await post(
            f"/send/{code}", json.dumps({"type": "sms", "data": {"body": "during call socket"}}),
        )
        assert status == 200 and json.loads(body) == {"delivered": False, "queued": 2}, (status, body)
        for typ in ("call", "relay_test"):
            await sender.send(json.dumps({"type": typ, "data": {"body": f"queued {typ}"}}))
        # Ordered WS input means this arrives only after the durable events.
        await sender.send(json.dumps({"type": "call_control", "data": {"action": "answer"}}))
        got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got["type"] == "call_control" and got["data"]["action"] == "answer", got
        await receiver.send(json.dumps({"type": "call_control", "data": {"action": "end"}}))
        got = json.loads(await asyncio.wait_for(sender.recv(), timeout=5))
        assert got["type"] == "call_control" and got["data"]["action"] == "end", got
        await check_binary_audio_relay(sender, receiver)
        assert await poll(lambda: len(stub.messages) >= before_wakes + 4, timeout=10), "durable FCM wake suppressed by call socket"
        status, body = await post(f"/fetch/{code}", None, headers)
        assert status == 200, (status, body)
        events = json.loads(body)["events"]
        assert [e["type"] for e in events] == ["sms", "sms", "call", "relay_test"], events
        assert events[0] == backlog[0], "temporary socket changed the queued backlog"
        status, body = await post(
            f"/ack/{code}", json.dumps({"event_ids": [e["event_id"] for e in events]}), headers,
        )
        assert status == 200 and json.loads(body) == {"acknowledged": 4}, (status, body)
        # Drop call control if its peer is gone: it must never become a stale
        # durable command on the receiver's later reconnect.
        await receiver.close()
        receiver = None
        assert await fcm_wait_status(base, code, {"receiver_connected": False})
        await sender.send(json.dumps({"type": "call_control", "data": {"action": "answer"}}))
        await sender.send(json.dumps({"type": "relay_test", "data": {"body": "after peer gone"}}))
        assert await poll(lambda: len(stub.messages) >= before_wakes + 5, timeout=10)
        status, body = await post(f"/fetch/{code}", None, headers)
        assert status == 200, (status, body)
        assert [e["type"] for e in json.loads(body)["events"]] == ["relay_test"], body
        print("[ok] temporary FCM socket retains backlog, queues durable events, and relays only live control/audio")
    finally:
        if receiver is not None:
            await receiver.close()
        await sender.close()


async def check_fcm_live_no_wake(base, stub, code, fcm_token):
    """With the receiver online, queued events replay in order on connect and
    live delivery works — no FCM message is involved."""
    before_msgs = len(stub.messages)
    ws = await fc_connect(base, f"/ws/receiver/{code}", code, fcm_token=fcm_token)
    try:
        bodies = []
        for _ in range(2):
            got = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
            assert got["type"] == "sms" and got["from"] == "sender", got
            bodies.append(got["data"]["body"])
        assert bodies == ["wake me up", "second while cool"], bodies

        loop = asyncio.get_running_loop()
        status, body = await loop.run_in_executor(
            None,
            _http_post,
            base.base,
            f"/send/{code}",
            json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "live one", "ts": 102}}),
        )
        assert json.loads(body) == {"delivered": True, "queued": 0}, (status, body)
        got = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
        assert got["data"]["body"] == "live one", got
    finally:
        await ws.close()
    await asyncio.sleep(0.5)
    assert len(stub.messages) == before_msgs, "FCM wake sent while the receiver was online"
    print("[ok] online receiver: queue replayed in order, live delivery, no FCM wake")


async def check_fcm_ws_path(base, stub, fcm_token):
    """The WS relay path also queues + wakes when the receiver is offline
    (previously such events were silently dropped)."""
    code = await fcm_fresh_code(base)
    ws = await fc_connect(base, f"/ws/receiver/{code}", code, fcm_token=fcm_token)
    await ws.close()
    assert await fcm_wait_status(base, code, {"receiver_connected": False}), "receiver slot not freed"

    before_msgs = len(stub.messages)
    sender = await fc_connect(base, f"/ws/sender/{code}", code)
    try:
        await sender.send(
            json.dumps({"type": "sms", "data": {"from": "+15554445555", "body": "via ws", "ts": 103}})
        )
        assert await poll(lambda: len(stub.messages) > before_msgs, timeout=10), (
            "WS-path event did not wake the receiver"
        )
    finally:
        await sender.close()

    ws = await fc_connect(base, f"/ws/receiver/{code}", code, fcm_token=fcm_token)
    try:
        got = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
        assert got["type"] == "sms" and got["data"]["body"] == "via ws", got
    finally:
        await ws.close()
    print("[ok] WS relay path: offline event queued + wake sent + replayed on reconnect")


async def main() -> int:
    print(f"target: {BASE}")
    local_dev = None
    if not await wait_ready(timeout=15.0):
        if not LOCAL_TARGET:
            print(f"FAIL: worker at {BASE} did not become ready")
            return 1
        print(f"[info] no worker at {BASE} — starting a local wrangler dev for the suite")
        local_dev = WorkerDev(8787)
        if not local_dev.start():
            print("FAIL: cannot reach the worker and cannot start a local wrangler dev (node available?)")
            return 1
        print(f"[ok] local wrangler dev started on port {local_dev.port}")

    failures = 0
    code = None
    sender = None
    receiver = None

    try:
        ok, _ = await run_check("health", check_health)
        failures += 0 if ok else 1

        ok, code = await run_check("pairing create", check_create)
        failures += 0 if ok else 1

        if code is not None:
            ok, pair = await run_check("both connect", check_both_connect, code)
            failures += 0 if ok else 1
            if ok:
                sender, receiver = pair
            if sender is not None and receiver is not None:
                for name, fn in (
                    ("sms relay", check_sms_relay),
                    ("call relay", check_call_relay),
                    ("reverse relay", check_reverse_relay),
                    ("binary audio relay", check_binary_audio_relay),
                    ("duplicate auth ignored", check_auth_duplicate_ignored),
                ):
                    ok, _ = await run_check(name, fn, sender, receiver)
                    failures += 0 if ok else 1
                ok, _ = await run_check("slot takeover", check_takeover)
                failures += 0 if ok else 1
                ok, _ = await run_check("takeover keeps new holder name", check_takeover_name)
                failures += 0 if ok else 1
                ok, _ = await run_check("disconnect status", check_disconnect, code, sender, receiver)
                failures += 0 if ok else 1
                sender = None
                if receiver is not None and failures == 0:
                    ok, _ = await run_check("reconnect", check_reconnect, code, receiver)
                    failures += 0 if ok else 1
                sender = None
                receiver = None

            ok, _ = await run_check("device token flow", check_device_token_flow, code)
            failures += 0 if ok else 1

        ok, _ = await run_check("bogus role", check_bogus_role, code if code is not None else "123456")
        failures += 0 if ok else 1

        ok, _ = await run_check("auto create", check_auto_create)
        failures += 0 if ok else 1

        ok, _ = await run_check("auth wrong token", check_auth_wrong_token)
        failures += 0 if ok else 1

        ok, _ = await run_check("auth wrong code", check_auth_wrong_code)
        failures += 0 if ok else 1

        ok, _ = await run_check("auth timeout", check_auth_timeout)
        failures += 0 if ok else 1

        ok, _ = await run_check("header code", check_header_code)
        failures += 0 if ok else 1

        ok, _ = await run_check("send uplink", check_send_uplink)
        failures += 0 if ok else 1

        ok, _ = await run_check("fetch and ack", check_fetch_ack)
        failures += 0 if ok else 1

        if code is None:
            print("FAIL: persistence: no created code to verify")
            failures += 1
        else:
            ok, _ = await run_check("persistence", check_persistence, code, AUTO_CODE)
            failures += 0 if ok else 1

        # FCM wake fallback: dedicated local dev worker + stubbed Google.
        fcm_stub = None
        fcm_dev = None
        fcm_pub = None
        fcm_code = None
        try:
            # All main-suite checks are done: stop the shared local dev first.
            # Two concurrent wrangler dev processes share .wrangler/state
            # (sqlite) and can lock each other's DO storage mid-request.
            if local_dev is not None:
                local_dev.stop()
                local_dev = None
                await asyncio.sleep(1.0)
            fcm_stub = FcmStub()
            sa, fcm_pub = make_service_account()
            fcm_dev = WorkerDev(free_port())
            if not fcm_dev.start(sa, fcm_stub.base):
                print("SKIP: FCM checks (local wrangler dev with FCM bindings failed to start)")
            else:
                ok, fcm_code = await run_check("fcm register", check_fcm_register, fcm_dev, FCM_TOKEN)
                failures += 0 if ok else 1
                if fcm_code is not None:
                    for name, fn in (
                        ("fcm wake offline", lambda: check_fcm_wake_offline(fcm_dev, fcm_stub, fcm_pub, fcm_code, FCM_TOKEN)),
                        ("fcm rate limit", lambda: check_fcm_rate_limit(fcm_dev, fcm_stub, fcm_code)),
                        ("fcm live no wake", lambda: check_fcm_live_no_wake(fcm_dev, fcm_stub, fcm_code, FCM_TOKEN)),
                    ):
                        ok, _ = await run_check(name, fn)
                        failures += 0 if ok else 1
                ok, _ = await run_check("fcm ws path", check_fcm_ws_path, fcm_dev, fcm_stub, FCM_TOKEN)
                failures += 0 if ok else 1
                ok, _ = await run_check("fcm on demand", check_fcm_on_demand, fcm_dev, fcm_stub, FCM_TOKEN)
                failures += 0 if ok else 1
                ok, _ = await run_check("fcm temporary call socket", check_fcm_temporary_call_socket, fcm_dev, fcm_stub, FCM_TOKEN)
                failures += 0 if ok else 1
        finally:
            if fcm_dev is not None:
                fcm_dev.stop()
            if fcm_stub is not None:
                fcm_stub.stop()
            try:
                os.remove(SA_PATH)
            except FileNotFoundError:
                pass
    except Exception as exc:
        print(f"FAIL: unexpected error: {exc!r}")
        failures += 1
    finally:
        for ws in (sender, receiver):
            if ws is not None:
                try:
                    await ws.close()
                except Exception:
                    pass
        if local_dev is not None:
            local_dev.stop()

    if failures:
        print(f"\n{failures} check(s) FAILED")
        return 1
    print("\nALL WORKER TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

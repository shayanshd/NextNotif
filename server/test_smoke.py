"""In-process smoke tests for the NextNotif relay server, including persistence across restart.

Every websocket check performs the handshake: connect, send {"type":"hello"},
recv {"type":"handshake","token":...}, send {"type":"auth","token":...,"code":...}.
"""

import asyncio
import base64
import inspect
import json
import os
import socket
import sys
import threading
import tempfile
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import httpx
import uvicorn
import websockets

import fcm

HOST = "127.0.0.1"
HERE = os.path.dirname(os.path.abspath(__file__))
PAIRINGS_FILE = os.path.join(HERE, "pairings.json")
AUTO_CODE = "424242"
# Well-formed enough to pass server-side validation (>= 20 chars, no blanks).
FCM_TOKEN = "test-fcm-token-" + "a1b2c3d4e5f6" * 6

FCM_STUB = None
FCM_PUBLIC_KEY = None
FCM_ENV_BACKUP = None

# websockets >= 14 uses additional_headers; the legacy client used extra_headers.
_CONNECT_PARAMS = inspect.signature(websockets.connect).parameters
_HDR_KWARG = "additional_headers" if "additional_headers" in _CONNECT_PARAMS else "extra_headers"


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind((HOST, 0))
        return s.getsockname()[1]


def remove_pairings_file() -> None:
    try:
        os.remove(PAIRINGS_FILE)
    except FileNotFoundError:
        pass


def fresh_app():
    sys.modules.pop("main", None)
    import main

    return main.app


class RelayServer:
    def __init__(self, app, port: int) -> None:
        self.port = port
        self.base = f"http://{HOST}:{port}"
        self.ws_base = f"ws://{HOST}:{port}"
        self._server = uvicorn.Server(uvicorn.Config(app, host=HOST, port=port, log_level="warning"))
        self._thread = None

    def start(self) -> None:
        self._thread = threading.Thread(target=self._server.run, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        if self._thread is None:
            return
        self._server.should_exit = True
        self._thread.join(timeout=15)
        self._thread = None


def ws_connect(url, headers=None):
    if headers is None:
        return websockets.connect(url)
    return websockets.connect(url, **{_HDR_KWARG: headers})


async def handshake(ws, code, token=None, device_token=None, device_name=None, fcm_token=None, delivery_mode=None, device_id=None):
    """Send hello, recv the server handshake, answer auth, and consume auth_ok.

    Returns the device token issued (or kept) by the server.
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
    if device_id is not None:
        auth["device_id"] = device_id
    if fcm_token is not None:
        auth["fcm_token"] = fcm_token
    if delivery_mode is not None:
        auth["delivery_mode"] = delivery_mode
    await ws.send(json.dumps(auth))
    raw = await asyncio.wait_for(ws.recv(), timeout=5)
    ok = json.loads(raw)
    assert (
        ok.get("type") == "auth_ok"
        and isinstance(ok.get("device_token"), str)
        and ok["device_token"]
    ), ok
    return ok["device_token"]


async def authed_connect(h, path, code, device_token=None, device_name=None, fcm_token=None, delivery_mode=None, device_id=None):
    ws = await ws_connect(f"{h.ws_base}{path}")
    dt = await handshake(ws, code, device_token=device_token, device_name=device_name, fcm_token=fcm_token, delivery_mode=delivery_mode, device_id=device_id)
    return ws, dt


async def wait_disconnected(h, code, role="sender", timeout: float = 5.0) -> bool:
    key = f"{role}_connected"
    deadline = time.monotonic() + timeout
    async with httpx.AsyncClient() as client:
        while time.monotonic() < deadline:
            body = (await client.get(f"{h.base}/pair/{code}/status")).json()
            if body.get(key) is False:
                return True
            await asyncio.sleep(0.05)
    return False


async def wait_ready(base: str, timeout: float = 20.0) -> bool:
    deadline = time.monotonic() + timeout
    async with httpx.AsyncClient() as client:
        while time.monotonic() < deadline:
            try:
                if (await client.get(base + "/")).status_code == 200:
                    return True
            except httpx.HTTPError:
                pass
            await asyncio.sleep(0.05)
    return False


async def run_check(name, fn, *args):
    try:
        return True, await fn(*args)
    except Exception as exc:
        print(f"FAIL: {name}: {exc}")
        return False, None


async def fresh_code(h):
    async with httpx.AsyncClient() as client:
        r = await client.post(f"{h.base}/pair/create")
        r.raise_for_status()
        return r.json()["code"]


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


async def check_create(h):
    """POST /pair/create returns a fresh 6-digit code with clean initial status."""
    async with httpx.AsyncClient() as client:
        r = await client.post(f"{h.base}/pair/create")
        r.raise_for_status()
        code = r.json()["code"]
        assert len(code) == 6 and code.isdigit(), f"bad code {code!r}"
        r = await client.get(f"{h.base}/pair/{code}/status")
        assert r.json() == {
            "exists": True,
            "sender_connected": False,
            "receiver_connected": False,
            "sender_name": None,
            "receiver_name": None,
            "sender_has_fcm": False,
            "receiver_has_fcm": False,
        }
    print(f"[ok] pairing created: {code}")
    return code


async def check_both_connect(h, code):
    """Sender and receiver connect (handshaken); status reports both sides connected."""
    sender, _ = await authed_connect(h, f"/ws/sender/{code}", code, device_name="SmokeSender 1")
    receiver, _ = await authed_connect(h, f"/ws/receiver/{code}", code, device_name="SmokeReceiver 2")
    async with httpx.AsyncClient() as client:
        r = await client.get(f"{h.base}/pair/{code}/status")
        assert r.json() == {
            "exists": True,
            "sender_connected": True,
            "receiver_connected": True,
            "sender_name": "SmokeSender 1",
            "receiver_name": "SmokeReceiver 2",
            "sender_has_fcm": False,
            "receiver_has_fcm": False,
        }
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


async def check_disconnect(h, code, sender):
    """Closing the sender socket clears sender_connected while receiver stays up."""
    await sender.close()
    async with httpx.AsyncClient() as client:
        deadline = time.monotonic() + 5
        body = None
        while time.monotonic() < deadline:
            body = (await client.get(f"{h.base}/pair/{code}/status")).json()
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


async def check_bogus_role(h, code):
    """A websocket with an unknown role is rejected."""
    try:
        bad = await websockets.connect(f"{h.ws_base}/ws/bogus/{code}")
    except Exception:
        print("[ok] bogus role rejected")
        return
    try:
        await bad.recv()
    except Exception:
        print("[ok] bogus role rejected")
        return
    await bad.close()
    raise AssertionError("bogus role was accepted")


async def check_auto_create(h):
    """A typed 6-digit code with no pairing is auto-created on connect."""
    ws, _ = await authed_connect(h, f"/ws/sender/{AUTO_CODE}", AUTO_CODE)
    try:
        async with httpx.AsyncClient() as client:
            r = await client.get(f"{h.base}/pair/{AUTO_CODE}/status")
            assert r.json()["exists"] is True
    finally:
        await ws.close()
    print("[ok] typed code auto-creates pairing")


async def check_auth_wrong_token(h):
    """Auth with a wrong token closes the socket with 1008 'auth failed'."""
    code = await fresh_code(h)
    ws = await ws_connect(f"{h.ws_base}/ws/sender/{code}")
    await ws.send(json.dumps({"type": "hello"}))
    raw = await asyncio.wait_for(ws.recv(), timeout=5)
    hs = json.loads(raw)
    assert hs.get("type") == "handshake", hs
    await expect_auth_reject(ws, code, "definitely-wrong-token", "wrong-token")


async def check_auth_wrong_code(h):
    """Auth with the right token but a wrong code closes with 1008 'auth failed'."""
    code = await fresh_code(h)
    ws = await ws_connect(f"{h.ws_base}/ws/sender/{code}")
    await ws.send(json.dumps({"type": "hello"}))
    raw = await asyncio.wait_for(ws.recv(), timeout=5)
    hs = json.loads(raw)
    await expect_auth_reject(ws, code, hs["token"], "wrong-code", send_code="000000")


async def check_auth_timeout(h):
    """A socket that never authenticates is closed within ~7s with 1008 'auth timeout'."""
    code = await fresh_code(h)
    ws = await ws_connect(f"{h.ws_base}/ws/sender/{code}")
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


async def check_header_code(h):
    """Connect via X-NextNotif-Code header with /ws/<role> (no code in path) works."""
    code = await fresh_code(h)
    ws = await ws_connect(f"{h.ws_base}/ws/sender", {"X-NextNotif-Code": code})
    try:
        await handshake(ws, code)
        async with httpx.AsyncClient() as client:
            body = (await client.get(f"{h.base}/pair/{code}/status")).json()
        assert body == {
            "exists": True,
            "sender_connected": True,
            "receiver_connected": False,
            "sender_name": None,
            "receiver_name": None,
            "sender_has_fcm": False,
            "receiver_has_fcm": False,
        }, body
        print("[ok] header code with /ws/<role> path works")
    finally:
        await ws.close()


async def check_send_uplink(h):
    """POST /send: queued while the receiver is offline, replayed in order on
    its connect, delivered live afterwards. Unknown code -> 404, malformed
    body -> 400, and the header form works. Leaves one event queued to verify
    queue persistence across a restart."""
    code = await fresh_code(h)

    async with httpx.AsyncClient() as client:
        r = await client.post(
            f"{h.base}/send/{code}",
            json={"type": "sms", "data": {"from": "+15550001111", "body": "queued one", "ts": 1}},
        )
        assert r.status_code == 200 and r.json() == {"delivered": False, "queued": 1}, (r.status_code, r.text)
        r = await client.post(
            f"{h.base}/send/{code}",
            json={"type": "call", "data": {"number": "+15552223333", "state": "RINGING", "ts": 2}},
        )
        assert r.status_code == 200 and r.json() == {"delivered": False, "queued": 2}, (r.status_code, r.text)

    receiver, _ = await authed_connect(h, f"/ws/receiver/{code}", code)
    try:
        got1 = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got1["type"] == "sms" and got1["from"] == "sender" and got1["data"]["body"] == "queued one", got1
        got2 = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got2["type"] == "call" and got2["from"] == "sender" and got2["data"]["state"] == "RINGING", got2

        async with httpx.AsyncClient() as client:
            r = await client.post(
                f"{h.base}/send/{code}",
                json={"type": "sms", "data": {"from": "+15550001111", "body": "live one", "ts": 3}},
            )
        assert r.status_code == 200 and r.json() == {"delivered": True, "queued": 0}, (r.status_code, r.text)
        got3 = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got3["data"]["body"] == "live one", got3
    finally:
        await receiver.close()

    async with httpx.AsyncClient() as client:
        r = await client.post(f"{h.base}/send/999999", json={"type": "sms", "data": {}})
        assert r.status_code == 404, f"unknown code -> {r.status_code}"
        r = await client.post(f"{h.base}/send/{code}", content=b"this is not json")
        assert r.status_code == 400, f"malformed body -> {r.status_code}"
        # Header form, receiver offline again: the event must be queued.
        r = await client.post(
            f"{h.base}/send",
            json={"type": "sms", "data": {"from": "+15550001111", "body": "header form", "ts": 4}},
            headers={"X-NextNotif-Code": code},
        )
        assert r.status_code == 200 and r.json() == {"delivered": False, "queued": 1}, (r.status_code, r.text)

    print("[ok] /send queues while offline, catch-up in order, live, 404/400, header form")
    return code


async def check_restart_queue(h, code):
    """Events queued before a restart are persisted and delivered to the
    receiver on its next connect after the restart."""
    receiver, _ = await authed_connect(h, f"/ws/receiver/{code}", code)
    try:
        got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got["type"] == "sms" and got["data"]["body"] == "header form", got
    finally:
        await receiver.close()
    print("[ok] restart: queued event persisted and delivered on reconnect")


async def check_device_token_flow(h, code):
    """First connect issues a device token; a reconnect presenting it keeps it,
    and a bogus one is replaced with a fresh valid one."""
    ws, dt = await authed_connect(h, f"/ws/sender/{code}", code)
    assert len(dt) >= 32, f"device token looks too short: {dt!r}"
    await ws.close()
    assert await wait_disconnected(h, code, "sender"), "sender slot not freed"

    ws, dt2 = await authed_connect(h, f"/ws/sender/{code}", code, device_token=dt)
    assert dt2 == dt, "a valid presented device token should be kept"
    await ws.close()
    assert await wait_disconnected(h, code, "sender"), "sender slot not freed (2)"

    ws, dt3 = await authed_connect(h, f"/ws/sender/{code}", code, device_token="stale-token")
    assert dt3 != dt and len(dt3) >= 32, "bogus token should be replaced by a fresh one"
    await ws.close()
    assert await wait_disconnected(h, code, "sender"), "sender slot not freed (3)"
    print(f"[ok] device token issued ({len(dt)} chars), kept on reconnect, replaced when stale")
    return dt


async def check_persistence_file(created):
    """pairings.json exists, uses the {code: {tokens: [...]}} format, and holds
    the created and auto-created codes."""
    assert os.path.exists(PAIRINGS_FILE), f"missing {PAIRINGS_FILE}"
    with open(PAIRINGS_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)
    assert created in data, f"{created} not persisted"
    assert AUTO_CODE in data, f"{AUTO_CODE} not persisted"
    entry = data[created]
    assert isinstance(entry, dict) and isinstance(entry.get("tokens"), list) and entry["tokens"], (
        f"unexpected persistence format for {created}: {entry!r}"
    )
    print(f"[ok] pairings.json persisted {created} and {AUTO_CODE} with device tokens")


async def check_restart_token(h, code, device_token):
    """A persisted device token is still accepted (and kept) after a restart."""
    ws, dt2 = await authed_connect(h, f"/ws/sender/{code}", code, device_token=device_token)
    assert dt2 == device_token, "persisted device token should still be valid after restart"
    await ws.close()
    print("[ok] restart: persisted device token accepted")


async def check_restart_status(h, code):
    """After a fresh import, a previously created code still reports exists=true."""
    async with httpx.AsyncClient() as client:
        r = await client.get(f"{h.base}/pair/{code}/status")
        body = r.json()
    assert body.get("exists") is True, body
    assert body.get("sender_connected") is False, body
    assert body.get("receiver_connected") is False, body
    print(f"[ok] restart: /pair/{code}/status still exists")


# ---------------------------------------------------------------------------
# FCM wake fallback: stubbed Google endpoints + a generated service account,
# so the full path (RS256 assertion -> OAuth -> messages:send) is exercised
# without touching the real Firebase project.
# ---------------------------------------------------------------------------


class FcmStub:
    """Local stand-in for the Google OAuth token endpoint + FCM API that
    records every request it receives. The token endpoint always answers
    "stub-access-token"; the send endpoint always 200s."""

    def __init__(self) -> None:
        self.token_calls: list = []
        self.messages: list = []
        self._lock = threading.Lock()
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

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
                    self._send(200, {"access_token": "stub-access-token", "expires_in": 3600, "token_type": "Bearer"})
                elif self.path.endswith("/messages:send"):
                    with outer._lock:
                        outer.messages.append(
                            {"auth": self.headers.get("Authorization"), "body": json.loads(raw)}
                        )
                    self._send(200, {"name": "projects/stub/messages/1"})
                else:
                    self._send(404, {"error": f"unknown path {self.path}"})

        port = free_port()
        self.server = ThreadingHTTPServer((HOST, port), Handler)
        self.base = f"http://{HOST}:{port}"
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
        "token_uri": "https://oauth2.googleapis.com/token",
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


async def poll(condition, timeout: float = 5.0, interval: float = 0.05):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return True
        await asyncio.sleep(interval)
    return False


def current_pairing(code: str):
    import main

    return main.registry.pairings.get(code)


async def check_fcm_register(h, fcm_token):
    """fcm_token on auth is stored per role and reported by /status."""
    code = await fresh_code(h)
    receiver, _ = await authed_connect(h, f"/ws/receiver/{code}", code, fcm_token=fcm_token)
    try:
        async with httpx.AsyncClient() as client:
            body = (await client.get(f"{h.base}/pair/{code}/status")).json()
        assert body["receiver_has_fcm"] is True, body
        assert body["sender_has_fcm"] is False, body
    finally:
        await receiver.close()
        assert await wait_disconnected(h, code, "receiver"), "receiver slot not freed"
    print(f"[ok] fcm token registered via auth, visible in status (code {code})")
    return code


async def check_fcm_wake_offline(h, code, stub, public_key, fcm_token):
    """POST /send while the receiver is offline: the event is queued AND one
    FCM message goes out with the right token/payload and a valid RS256
    OAuth assertion."""
    before_msgs = len(stub.messages)
    async with httpx.AsyncClient() as client:
        r = await client.post(
            f"{h.base}/send/{code}",
            json={"type": "sms", "data": {"from": "+15550001111", "body": "wake me up", "ts": 100}},
        )
        assert r.status_code == 200 and r.json() == {"delivered": False, "queued": 1}, (r.status_code, r.text)

    assert await poll(lambda: len(stub.messages) > before_msgs), "no FCM wake sent for offline receiver"
    msg = stub.messages[-1]
    assert msg["auth"] == "Bearer stub-access-token", msg["auth"]
    body = msg["body"]["message"]
    assert body["token"] == fcm_token, body
    assert "notification" not in body, body
    assert body["data"] == {"nn": "1", "code": code, "wake": "1"}, body
    assert body["android"]["priority"] == "high", body

    # The OAuth assertion must be a valid RS256 JWT for the FCM scope, signed
    # by the (generated) service-account key.
    call = stub.token_calls[-1]
    assert call["grant_type"] == "urn:ietf:params:oauth:grant-type:jwt-bearer", call
    payload = verify_jwt(call["assertion"], public_key)
    assert payload["scope"] == "https://www.googleapis.com/auth/firebase.messaging", payload
    assert payload["aud"] == f"{stub.base}/token", payload
    assert payload["exp"] > payload["iat"], payload
    print("[ok] offline receiver: event queued + FCM wake with valid RS256 assertion")


async def check_fcm_rate_limit(h, code, stub):
    """A second queued event inside the 30 s cooldown does not re-wake;
    clearing the cooldown allows the next wake."""
    before_msgs = len(stub.messages)
    async with httpx.AsyncClient() as client:
        r = await client.post(
            f"{h.base}/send/{code}",
            json={"type": "sms", "data": {"from": "+15550001111", "body": "second while cool", "ts": 101}},
        )
        assert r.json() == {"delivered": False, "queued": 2}, r.text
    await asyncio.sleep(1.0)
    assert len(stub.messages) == before_msgs, "cooldown was not enforced"

    p = current_pairing(code)
    p.last_wake.clear()
    async with httpx.AsyncClient() as client:
        r = await client.post(
            f"{h.base}/send/{code}",
            json={"type": "sms", "data": {"from": "+15550001111", "body": "third after cooldown", "ts": 102}},
        )
        assert r.json() == {"delivered": False, "queued": 3}, r.text
    assert await poll(lambda: len(stub.messages) > before_msgs), "wake after cooldown did not fire"
    print("[ok] wake rate-limited per pairing, fires again after the cooldown")


async def check_fcm_on_demand(h, stub, fcm_token):
    """An FCM-only receiver creates its pairing over HTTPS, wakes for every
    event, and drains the durable queue with its issued device token."""
    code = str(100000 + time.time_ns() % 900000)
    async with httpx.AsyncClient() as client:
        initial = await client.get(f"{h.base}/pair/{code}/status")
        assert initial.json()["exists"] is False, initial.text
        r = await client.post(
            f"{h.base}/fcm-register",
            headers={"X-NextNotif-Code": code},
            json={"fcm_token": fcm_token, "device_name": "Sleeping receiver"},
        )
        assert r.status_code == 200, r.text
        device_token = r.json()["device_token"]
        status = await client.get(f"{h.base}/pair/{code}/status")
        assert status.json()["exists"] is True and status.json()["receiver_has_fcm"] is True
        before = len(stub.messages)
        for ts in (201, 202):
            r = await client.post(
                f"{h.base}/send/{code}",
                json={"type": "sms", "data": {"from": "+15550001111", "body": f"push {ts}", "ts": ts}},
            )
            assert r.status_code == 200, r.text
        assert await poll(lambda: len(stub.messages) >= before + 2), "on-demand wake was cooled down"
        wakes = [m["body"]["message"]["data"] for m in stub.messages[-2:]]
        assert all(w == {"nn": "1", "code": code, "wake": "1"} for w in wakes), wakes

        denied = await client.post(f"{h.base}/drain/{code}")
        assert denied.status_code == 401, denied.text
        drained = await client.post(
            f"{h.base}/drain",
            headers={"X-NextNotif-Code": code, "X-NextNotif-Token": device_token},
        )
        assert drained.status_code == 200, drained.text
        events = drained.json()["events"]
        assert [e["data"]["body"] for e in events] == ["push 201", "push 202"], events
        event_ids = [e["event_id"] for e in events]
        assert all(event_ids) and len(set(event_ids)) == 2, event_ids
        empty = await client.post(
            f"{h.base}/drain/{code}", headers={"X-NextNotif-Token": device_token}
        )
        assert empty.status_code == 200 and empty.json()["events"] == [], empty.text
    print("[ok] FCM on-demand: every event wakes, authenticated drain clears queue")


async def check_secure_endpoint_guards(h):
    import main
    from unittest.mock import patch
    from secure_pairing_store import SecurePairingStore
    code = await fresh_code(h)
    previous_store = main.registry.secure_store
    with tempfile.TemporaryDirectory(prefix="nextnotif-secure-smoke-") as directory:
        store = SecurePairingStore(os.path.join(directory, "secure.sqlite"))
        owner, invite = store.create(code, "sender", 1000)
        receiver = store.join(code, invite.secret, "receiver", 1001)
        main.registry.secure_store = store
        def headers(grant):
            return {"X-NextNotif-Device-Id": grant.device_id,
                    "X-NextNotif-Token": grant.device_token}
        try:
            async with httpx.AsyncClient() as client:
                for path, payload, forbidden in (
                    ("send", {"type": "sms", "data": {"body": "secure"}}, receiver),
                    ("fcm-register", {"fcm_token": FCM_TOKEN}, owner),
                    ("fetch", {}, owner), ("ack", {"event_ids": []}, owner), ("drain", {}, owner),
                ):
                    for presented in ({}, headers(forbidden),
                                      {**headers(owner), "X-NextNotif-Token": "wrong"}):
                        response = await client.post(f"{h.base}/{path}/{code}", headers=presented, json=payload)
                        assert response.status_code == 401, (path, response.status_code, response.text)
                denied = await client.get(f"{h.base}/pair/{code}/status")
                assert denied.status_code == 401, denied.text
                registered = await client.post(f"{h.base}/fcm-register/{code}", headers=headers(receiver),
                                               json={"fcm_token": FCM_TOKEN, "device_token": "do-not-mint"})
                assert registered.status_code == 200 and registered.json()["device_token"] == receiver.device_token
                sent = await client.post(f"{h.base}/send/{code}", headers=headers(owner),
                                         json={"type": "sms", "data": {"body": "secure backlog"}})
                assert sent.status_code == 200, sent.text
                fetched = await client.post(f"{h.base}/fetch/{code}", headers=headers(receiver))
                assert fetched.status_code == 200 and len(fetched.json()["events"]) == 1, fetched.text
                for grant in (owner, receiver):
                    assert (await client.get(f"{h.base}/pair/{code}/status", headers=headers(grant))).status_code == 200
                main.registry.save()
                restored = main.Registry(main.PAIRINGS_FILE)
                assert restored.get(code).security_mode == "invite_v1"
                main.registry.secure_store = None
                unavailable = await client.post(f"{h.base}/fetch/{code}", headers=headers(receiver))
                assert unavailable.status_code == 401, unavailable.text
                main.registry.secure_store = store
            for grant in (None, owner):
                pending = await ws_connect(f"{h.ws_base}/ws/receiver/{code}")
                try:
                    await pending.send(json.dumps({"type": "hello", "device_name": "intruder"}))
                    challenge = json.loads(await pending.recv())
                    auth = {"type": "auth", "token": challenge["token"], "code": code}
                    if grant:
                        auth.update(device_id=grant.device_id, device_token=grant.device_token)
                    await pending.send(json.dumps(auth))
                    await asyncio.wait_for(pending.wait_closed(), timeout=5)
                    assert pending.close_code == 1008, pending.close_code
                    assert current_pairing(code).receiver_name != "intruder"
                finally:
                    await pending.close()
            authenticated, token = await authed_connect(h, f"/ws/receiver/{code}", code,
                device_token=receiver.device_token, device_id=receiver.device_id, delivery_mode="fcm")
            assert token == receiver.device_token and current_pairing(code).tokens == []
            async with httpx.AsyncClient() as client:
                for presented in ({}, headers(receiver)):
                    denied = await client.post(f"{h.base}/pair/{code}/revoke", headers=presented,
                                               json={"device_id": receiver.device_id})
                    assert denied.status_code == 401, denied.text
                    assert len(current_pairing(code).queue) == 1
                self_removal = await client.post(f"{h.base}/pair/{code}/revoke", headers=headers(owner),
                                                 json={"device_id": owner.device_id})
                assert self_removal.status_code == 400, self_removal.text
                with patch.object(main.registry, "save", side_effect=OSError("simulated disk failure")):
                    revoked = await client.post(f"{h.base}/pair/{code}/revoke", headers=headers(owner),
                                                 json={"device_id": receiver.device_id})
                assert revoked.status_code == 503, revoked.text
                rejected = await client.post(f"{h.base}/fetch/{code}", headers=headers(receiver))
                assert rejected.status_code == 401, rejected.text
                retried = await client.post(f"{h.base}/pair/{code}/revoke", headers=headers(owner),
                                            json={"device_id": receiver.device_id})
                assert retried.status_code == 200, retried.text
            await asyncio.wait_for(authenticated.wait_closed(), timeout=5)
            assert authenticated.close_code == 1008, authenticated.close_code
            assert current_pairing(code).receiver is None
            assert current_pairing(code).queue == [] and "receiver" not in current_pairing(code).fcm
            assert main.Registry(main.PAIRINGS_FILE).get(code).queue == []
        finally:
            main.registry.secure_store = previous_store
            main.registry.pairings.pop(code, None)
            main.registry.save()
    print("[ok] secure records enforce role credentials across HTTP/WS and never mint legacy fallback tokens")


async def check_secure_pair_deletion(h):
    import main
    from unittest.mock import patch
    from secure_pairing_store import SecurePairingStore
    from secure_pairing import AuthorizationError
    code = await fresh_code(h)
    original_registry = main.registry
    previous_store = original_registry.secure_store
    sockets = []
    with tempfile.TemporaryDirectory(prefix="nextnotif-delete-smoke-") as directory:
        store = SecurePairingStore(os.path.join(directory, "secure.sqlite"))
        owner, invite = store.create(code, "sender", 1000)
        receiver = store.join(code, invite.secret, "receiver", 1001)
        original_registry.secure_store = store
        def headers(grant):
            return {"X-NextNotif-Device-Id": grant.device_id, "X-NextNotif-Token": grant.device_token}
        try:
            sender_socket, _ = await authed_connect(h, f"/ws/sender/{code}", code,
                device_id=owner.device_id, device_token=owner.device_token)
            receiver_socket, _ = await authed_connect(h, f"/ws/receiver/{code}", code,
                device_id=receiver.device_id, device_token=receiver.device_token, delivery_mode="fcm")
            sockets = [sender_socket, receiver_socket]
            async with httpx.AsyncClient() as client:
                await client.post(f"{h.base}/fcm-register/{code}", headers=headers(receiver), json={"fcm_token": FCM_TOKEN})
                await client.post(f"{h.base}/send/{code}", headers=headers(owner),
                                  json={"type": "sms", "data": {"body": "delete this backlog"}})
                assert len(current_pairing(code).queue) == 1
                for presented in ({}, headers(receiver)):
                    denied = await client.delete(f"{h.base}/pair/{code}", headers=presented)
                    assert denied.status_code == 401, denied.text
                with patch.object(original_registry, "save", side_effect=OSError("reservation write failed")):
                    failed = await client.delete(f"{h.base}/pair/{code}", headers=headers(owner))
                    assert failed.status_code == 503, failed.text
                assert len(current_pairing(code).queue) == 1 and code not in original_registry.blocked_records
                with patch.object(store, "purge_reserved", side_effect=OSError("credential cleanup failed")):
                    pending = await client.delete(f"{h.base}/pair/{code}", headers=headers(owner))
                    assert pending.status_code == 503, pending.text
                for socket in sockets:
                    await asyncio.wait_for(socket.wait_closed(), timeout=5)
                    assert socket.close_code == 1008, socket.close_code
                assert original_registry.get_or_create(code) is None
                assert original_registry.get(code) is None
                restored = main.Registry(main.PAIRINGS_FILE)
                restored.secure_store = store
                assert restored.get_or_create(code) is None
                assert restored.blocked_records[code]["cleanup_pending"] is True
                with patch.object(main, "registry", restored):
                    completed = await client.delete(f"{h.base}/pair/{code}", headers=headers(owner))
                    assert completed.status_code == 200, completed.text
                    repeat = await client.delete(f"{h.base}/pair/{code}", headers=headers(owner))
                    assert repeat.status_code == 200, repeat.text
                    forbidden = await client.delete(f"{h.base}/pair/{code}", headers=headers(receiver))
                    assert forbidden.status_code == 401, forbidden.text
                    registration = await client.post(f"{h.base}/fcm-register/{code}", json={"fcm_token": FCM_TOKEN})
                    assert registration.status_code == 401, registration.text
                assert not store.exists(code)
                credential_rejected = False
                try:
                    store.authorize(code, owner.device_id, owner.device_token, "sender")
                except AuthorizationError:
                    credential_rejected = True
                assert credential_rejected, "deleted credential still valid"
                persisted = main.Registry(main.PAIRINGS_FILE)
                assert persisted.get_or_create(code) is None
                assert persisted.blocked_records[code]["cleanup_pending"] is False
        finally:
            for socket in sockets:
                await socket.close()
            original_registry.secure_store = previous_store
            original_registry.blocked_records.pop(code, None)
            original_registry.pairings.pop(code, None)
            original_registry.save()
    print("[ok] owner-only pair deletion closes both peers, purges credentials/data, and retries safely after restart")


async def check_pending_peer_isolation(h):
    code = await fresh_code(h)
    sender, _ = await authed_connect(h, f"/ws/sender/{code}", code)
    pending = await websockets.connect(f"{h.ws_base}/ws/receiver/{code}")
    try:
        await pending.send(json.dumps({"type": "hello", "device_name": "unauthenticated",
                                       "fcm_token": FCM_TOKEN}))
        handshake = json.loads(await asyncio.wait_for(pending.recv(), timeout=5))
        assert handshake["type"] == "handshake", handshake
        async with httpx.AsyncClient() as client:
            status = (await client.get(f"{h.base}/pair/{code}/status")).json()
            assert not status["receiver_connected"] and status["receiver_name"] is None, status
            assert not status["receiver_has_fcm"], status
            sent = await client.post(f"{h.base}/send/{code}",
                                     json={"type": "sms", "data": {"body": "protected backlog"}})
            assert sent.json() == {"delivered": False, "queued": 1}, sent.text
        await pending.send(json.dumps({"type": "auth", "token": "wrong", "code": code}))
        await asyncio.wait_for(pending.wait_closed(), timeout=5)
        assert pending.close_code == 1008, pending.close_code
        assert current_pairing(code).receiver is None
        assert len(current_pairing(code).queue) == 1
        receiver, _ = await authed_connect(h, f"/ws/receiver/{code}", code)
        try:
            event = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
            assert event["data"]["body"] == "protected backlog", event
        finally:
            await receiver.close()
    finally:
        await pending.close()
        await sender.close()
    print("[ok] pending socket cannot receive backlog, publish metadata, or impersonate a connected peer")


async def check_queue_fetch_ack(h):
    """A lost fetch/ACK response cannot lose events or remove newer arrivals."""
    code = await fresh_code(h)
    async with httpx.AsyncClient() as client:
        registered = await client.post(
            f"{h.base}/fcm-register/{code}", json={"fcm_token": FCM_TOKEN}
        )
        assert registered.status_code == 200, registered.text
        token = registered.json()["device_token"]
        headers = {"X-NextNotif-Code": code, "X-NextNotif-Token": token}
        for ts in (301, 302):
            sent = await client.post(
                f"{h.base}/send/{code}",
                json={"type": "sms", "data": {"body": f"durable {ts}", "ts": ts}},
            )
            assert sent.status_code == 200, sent.text
        denied_fetch = await client.post(f"{h.base}/fetch/{code}")
        assert denied_fetch.status_code == 401, denied_fetch.text
        first = await client.post(f"{h.base}/fetch", headers=headers)
        assert first.status_code == 200, first.text
        events = first.json()["events"]
        assert [event["data"]["ts"] for event in events] == [301, 302], events
        repeated = await client.post(
            f"{h.base}/fetch/{code}", headers={"Authorization": f"Bearer {token}"}
        )
        assert repeated.status_code == 200 and repeated.json()["events"] == events, repeated.text
        event_ids = [event["event_id"] for event in events]
        denied_ack = await client.post(
            f"{h.base}/ack/{code}",
            headers={"X-NextNotif-Token": "wrong-token"}, json={"event_ids": event_ids},
        )
        assert denied_ack.status_code == 401, denied_ack.text
        for invalid in (None, "not-a-list", [" "], [123], ["id"] * 101):
            response = await client.post(
                f"{h.base}/ack", headers=headers, json={"event_ids": invalid}
            )
            assert response.status_code == 400, response.text
        unchanged = await client.post(f"{h.base}/fetch", headers=headers)
        assert unchanged.json()["events"] == events, unchanged.text
        # A new event arriving after the snapshot must survive ACK of the old IDs.
        sent = await client.post(
            f"{h.base}/send/{code}", json={"type": "sms", "data": {"body": "newer", "ts": 303}}
        )
        assert sent.status_code == 200, sent.text
        ack = await client.post(f"{h.base}/ack", headers=headers, json={"event_ids": event_ids})
        assert ack.status_code == 200 and ack.json() == {"acknowledged": 2}, ack.text
        retry = await client.post(
            f"{h.base}/ack/{code}", headers={"Authorization": f"Bearer {token}"},
            json={"event_ids": event_ids},
        )
        assert retry.status_code == 200 and retry.json() == {"acknowledged": 0}, retry.text
        remaining = await client.post(f"{h.base}/fetch", headers=headers)
        newer = remaining.json()["events"]
        assert len(newer) == 1 and newer[0]["data"]["ts"] == 303, newer
        final_ack = await client.post(
            f"{h.base}/ack", headers=headers, json={"event_ids": [newer[0]["event_id"]]}
        )
        assert final_ack.json() == {"acknowledged": 1}, final_ack.text
        empty = await client.post(f"{h.base}/fetch", headers=headers)
        assert empty.json() == {"events": []}, empty.text
    print("[ok] queue fetch/ACK: repeatable snapshot, authenticated bounded ACK, newer events retained")


async def check_fcm_temporary_socket(h, stub):
    """A call-only receiver socket preserves durable FCM inbox delivery."""
    code = await fresh_code(h)
    async with httpx.AsyncClient() as client:
        registered = await client.post(
            f"{h.base}/fcm-register/{code}", json={"fcm_token": FCM_TOKEN}
        )
        assert registered.status_code == 200, registered.text
        token = registered.json()["device_token"]
        headers = {"X-NextNotif-Token": token}
        queued = await client.post(
            f"{h.base}/send/{code}", json={"type": "sms", "data": {"body": "backlog"}}
        )
        assert queued.json()["queued"] == 1, queued.text
        sender, _ = await authed_connect(h, f"/ws/sender/{code}", code)
        receiver, _ = await authed_connect(
            h, f"/ws/receiver/{code}", code, device_token=token, delivery_mode="fcm"
        )
        try:
            assert current_pairing(code).delivery["receiver"] == "fcm"
            before = len(stub.messages)
            sent = await client.post(
                f"{h.base}/send/{code}", json={"type": "sms", "data": {"body": "during call"}}
            )
            assert sent.json() == {"delivered": False, "queued": 2}, sent.text
            for event_type in ("call", "relay_test"):
                await sender.send(json.dumps({"type": event_type, "data": {"state": "RINGING"}}))
            assert await poll(lambda: len(current_pairing(code).queue) == 4)
            assert await poll(lambda: len(stub.messages) >= before + 3), "durable events did not wake FCM"
            snapshot = await client.post(f"{h.base}/fetch/{code}", headers=headers)
            events = snapshot.json()["events"]
            assert [event["type"] for event in events] == ["sms", "sms", "call", "relay_test"], events
            assert events[0]["data"]["body"] == "backlog", events
            # First socket delivery must be control, not destructive backlog replay.
            await sender.send(json.dumps({"type": "call_control", "data": {"action": "connected"}}))
            downstream = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
            assert downstream["type"] == "call_control", downstream
            await receiver.send(json.dumps({"type": "call_control", "data": {"action": "answer"}}))
            upstream = json.loads(await asyncio.wait_for(sender.recv(), timeout=5))
            assert upstream["type"] == "call_control", upstream
            await sender.send(b"downlink")
            assert await asyncio.wait_for(receiver.recv(), timeout=5) == b"downlink"
            await receiver.send(b"uplink")
            assert await asyncio.wait_for(sender.recv(), timeout=5) == b"uplink"
            retained = await client.post(f"{h.base}/fetch/{code}", headers=headers)
            assert retained.json()["events"] == events, retained.text
        finally:
            await receiver.close()
            await sender.close()
    print("[ok] temporary FCM socket: backlog retained, durable events queue+wake, controls/audio route live")


async def check_fcm_live_no_wake(h, code, stub, fcm_token):
    """With the receiver online, events deliver live over the socket and the
    queue replays in order on connect — no FCM message is involved."""
    before_msgs = len(stub.messages)
    receiver, _ = await authed_connect(h, f"/ws/receiver/{code}", code, fcm_token=fcm_token)
    try:
        bodies = []
        for _ in range(3):
            got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
            assert got["type"] == "sms" and got["from"] == "sender", got
            bodies.append(got["data"]["body"])
        assert bodies == ["wake me up", "second while cool", "third after cooldown"], bodies

        async with httpx.AsyncClient() as client:
            r = await client.post(
                f"{h.base}/send/{code}",
                json={"type": "sms", "data": {"from": "+15550001111", "body": "live one", "ts": 103}},
            )
        assert r.json() == {"delivered": True, "queued": 0}, r.text
        got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got["data"]["body"] == "live one", got
    finally:
        await receiver.close()
        assert await wait_disconnected(h, code, "receiver")
    await asyncio.sleep(0.5)
    assert len(stub.messages) == before_msgs, "FCM wake sent while the receiver was online"
    print("[ok] online receiver: queue replayed in order, live delivery, no FCM wake")


async def check_fcm_ws_path(h, fcm_token):
    """The WS relay path also queues + wakes when the receiver is offline
    (previously such events were silently dropped)."""
    import main

    code = await fresh_code(h)
    # Register the receiver's wake token, then drop the receiver.
    receiver, _ = await authed_connect(h, f"/ws/receiver/{code}", code, fcm_token=fcm_token)
    await receiver.close()
    assert await wait_disconnected(h, code, "receiver"), "receiver slot not freed"

    main.registry.pairings[code].last_wake.clear()
    before_msgs = len(FCM_STUB.messages)
    sender, _ = await authed_connect(h, f"/ws/sender/{code}", code)
    try:
        await sender.send(json.dumps({"type": "sms", "data": {"from": "+15554445555", "body": "via ws", "ts": 104}}))
        assert await poll(lambda: len(FCM_STUB.messages) > before_msgs), "WS-path event did not wake the receiver"
        p = current_pairing(code)
        assert len(p.queue) == 1, f"WS-path event not queued: {p.queue!r}"
    finally:
        await sender.close()

    # The queued WS-path event is still delivered on the receiver's reconnect.
    receiver, _ = await authed_connect(h, f"/ws/receiver/{code}", code, fcm_token=fcm_token)
    try:
        got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
        assert got["type"] == "sms" and got["data"]["body"] == "via ws", got
    finally:
        await receiver.close()
    print("[ok] WS relay path: offline event queued + wake sent + replayed on reconnect")


async def check_fcm_no_config(h):
    """With a registered wake token but no usable service account, events
    still queue and the server does not crash or call out — the wake is
    simply skipped (logged)."""
    global FCM_ENV_BACKUP
    code = await fresh_code(h)
    receiver, _ = await authed_connect(h, f"/ws/receiver/{code}", code, fcm_token=FCM_TOKEN)
    await receiver.close()
    assert await wait_disconnected(h, code, "receiver"), "receiver slot not freed"

    FCM_ENV_BACKUP = os.environ.get("NEXTNOTIF_FCM_SERVICE_ACCOUNT")
    os.environ["NEXTNOTIF_FCM_SERVICE_ACCOUNT"] = os.path.join(HERE, "no-such-sa.json")
    fcm.reset_cache()
    try:
        before_msgs = len(FCM_STUB.messages)
        async with httpx.AsyncClient() as client:
            r = await client.post(
                f"{h.base}/send/{code}",
                json={"type": "sms", "data": {"from": "+15550001111", "body": "no fcm", "ts": 105}},
            )
            assert r.status_code == 200 and r.json() == {"delivered": False, "queued": 1}, (r.status_code, r.text)
        await asyncio.sleep(1.0)
        assert len(FCM_STUB.messages) == before_msgs, "FCM called without a service account"
    finally:
        if FCM_ENV_BACKUP is None:
            os.environ.pop("NEXTNOTIF_FCM_SERVICE_ACCOUNT", None)
        else:
            os.environ["NEXTNOTIF_FCM_SERVICE_ACCOUNT"] = FCM_ENV_BACKUP
        fcm.reset_cache()
    print("[ok] no service account: queue works, wake skipped without failure")


async def check_fcm_restart(h, code, fcm_token):
    """The persisted FCM token survives a restart: an offline event still
    wakes the receiver without anyone reconnecting first."""
    before_msgs = len(FCM_STUB.messages)
    current_pairing(code).last_wake.clear()
    async with httpx.AsyncClient() as client:
        r = await client.post(
            f"{h.base}/send/{code}",
            json={"type": "sms", "data": {"from": "+15550001111", "body": "after restart", "ts": 106}},
        )
        assert r.json() == {"delivered": False, "queued": 1}, r.text
    assert await poll(lambda: len(FCM_STUB.messages) > before_msgs), "persisted fcm token not used after restart"
    assert FCM_STUB.messages[-1]["body"]["message"]["token"] == fcm_token
    async with httpx.AsyncClient() as client:
        body = (await client.get(f"{h.base}/pair/{code}/status")).json()
    assert body["receiver_has_fcm"] is True, body
    print("[ok] restart: persisted fcm token still wakes the receiver")


async def main() -> int:
    remove_pairings_file()

    global FCM_STUB, FCM_PUBLIC_KEY
    try:
        FCM_STUB = FcmStub()
        _sa, FCM_PUBLIC_KEY = make_service_account()
        os.environ["NEXTNOTIF_FCM_SERVICE_ACCOUNT"] = SA_PATH
        os.environ["NEXTNOTIF_FCM_TOKEN_URL"] = f"{FCM_STUB.base}/token"
        os.environ["NEXTNOTIF_FCM_BASE"] = FCM_STUB.base
    except Exception as exc:
        print(f"FAIL: FCM test setup: {exc!r}")
        return 1
    fcm.reset_cache()

    failures = 0
    code = None
    sender = None
    receiver = None
    device_token = None
    send_code = None
    fcm_code = None
    server1 = None
    server2 = None

    try:
        server1 = RelayServer(fresh_app(), free_port())
        server1.start()
        if not await wait_ready(server1.base):
            print("FAIL: server 1 did not become ready")
            return 1
        print(f"[ok] server 1 started on port {server1.port}")

        ok, code = await run_check("pairing create", check_create, server1)
        failures += 0 if ok else 1

        if code is not None:
            ok, pair = await run_check("both connect", check_both_connect, server1, code)
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
                ok, _ = await run_check("disconnect status", check_disconnect, server1, code, sender)
                failures += 0 if ok else 1
                sender = None
                if receiver is not None:
                    await receiver.close()
                    receiver = None

                ok, device_token = await run_check("device token flow", check_device_token_flow, server1, code)
                failures += 0 if ok else 1

        ok, _ = await run_check("bogus role", check_bogus_role, server1, code if code is not None else "123456")
        failures += 0 if ok else 1

        ok, _ = await run_check("auto create", check_auto_create, server1)
        failures += 0 if ok else 1

        ok, _ = await run_check("auth wrong token", check_auth_wrong_token, server1)
        failures += 0 if ok else 1

        ok, _ = await run_check("auth wrong code", check_auth_wrong_code, server1)
        failures += 0 if ok else 1

        ok, _ = await run_check("auth timeout", check_auth_timeout, server1)
        failures += 0 if ok else 1
        ok_pending, _ = await run_check("pending peer isolation", check_pending_peer_isolation, server1)
        failures += 0 if ok_pending else 1
        ok_secure, _ = await run_check("secure endpoint guards", check_secure_endpoint_guards, server1)
        failures += 0 if ok_secure else 1
        ok_delete, _ = await run_check("secure pair deletion", check_secure_pair_deletion, server1)
        failures += 0 if ok_delete else 1

        ok, _ = await run_check("header code", check_header_code, server1)
        failures += 0 if ok else 1

        ok, send_code = await run_check("send uplink", check_send_uplink, server1)
        failures += 0 if ok else 1

        if code is None:
            print("FAIL: persistence file: no created code to verify")
            failures += 1
        else:
            ok, _ = await run_check("persistence file", check_persistence_file, code)
            failures += 0 if ok else 1

        ok, fcm_code = await run_check("fcm register", check_fcm_register, server1, FCM_TOKEN)
        failures += 0 if ok else 1

        if fcm_code is not None:
            ok, _ = await run_check(
                "fcm wake offline", check_fcm_wake_offline, server1, fcm_code, FCM_STUB, FCM_PUBLIC_KEY, FCM_TOKEN
            )
            failures += 0 if ok else 1
            ok, _ = await run_check("fcm rate limit", check_fcm_rate_limit, server1, fcm_code, FCM_STUB)
            failures += 0 if ok else 1
            ok, _ = await run_check(
                "fcm live no wake", check_fcm_live_no_wake, server1, fcm_code, FCM_STUB, FCM_TOKEN
            )
            failures += 0 if ok else 1
        ok, _ = await run_check("fcm ws path", check_fcm_ws_path, server1, FCM_TOKEN)
        failures += 0 if ok else 1
        ok, _ = await run_check("fcm on demand", check_fcm_on_demand, server1, FCM_STUB, FCM_TOKEN)
        failures += 0 if ok else 1
        ok, _ = await run_check("queue fetch ack", check_queue_fetch_ack, server1)
        failures += 0 if ok else 1
        ok, _ = await run_check("fcm temporary socket", check_fcm_temporary_socket, server1, FCM_STUB)
        failures += 0 if ok else 1
        ok, _ = await run_check("fcm no config", check_fcm_no_config, server1)
        failures += 0 if ok else 1

        server1.stop()

        server2 = RelayServer(fresh_app(), free_port())
        server2.start()
        if not await wait_ready(server2.base):
            print("FAIL: server 2 did not become ready")
            failures += 1
        else:
            print(f"[ok] server 2 started on port {server2.port}")
            if code is None:
                print("FAIL: restart status: no created code to verify")
                failures += 1
            else:
                ok, _ = await run_check("restart status", check_restart_status, server2, code)
                failures += 0 if ok else 1
                if device_token is None:
                    print("FAIL: restart token: no device token from earlier flow to verify")
                    failures += 1
                else:
                    ok, _ = await run_check("restart token", check_restart_token, server2, code, device_token)
                    failures += 0 if ok else 1
                if send_code is None:
                    print("FAIL: restart queue: no queued event from earlier flow to verify")
                    failures += 1
                else:
                    ok, _ = await run_check("restart queue", check_restart_queue, server2, send_code)
                    failures += 0 if ok else 1
                if fcm_code is None:
                    print("FAIL: restart fcm: no fcm-registered code from earlier flow to verify")
                    failures += 1
                else:
                    ok, _ = await run_check("restart fcm", check_fcm_restart, server2, fcm_code, FCM_TOKEN)
                    failures += 0 if ok else 1
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
        if server1 is not None:
            server1.stop()
        if server2 is not None:
            server2.stop()
        if FCM_STUB is not None:
            FCM_STUB.stop()
        for var in ("NEXTNOTIF_FCM_SERVICE_ACCOUNT", "NEXTNOTIF_FCM_TOKEN_URL", "NEXTNOTIF_FCM_BASE"):
            os.environ.pop(var, None)
        try:
            os.remove(SA_PATH)
        except FileNotFoundError:
            pass
        fcm.reset_cache()
        remove_pairings_file()

    if failures:
        print(f"\n{failures} check(s) FAILED")
        return 1
    print("\nALL SERVER TESTS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

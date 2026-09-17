import asyncio
import json
import os
import random
import secrets
import string
import tempfile
from typing import Dict, Optional

from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse

import fcm
from fcm import wake as fcm_wake

app = FastAPI(title="NextNotif Relay")

PAIRINGS_FILE = os.path.join(os.path.dirname(__file__), "pairings.json")
CODE_HEADER = "x-nextnotif-code"
TOKEN_HEADER = "x-nextnotif-token"
AUTH_TIMEOUT_SECONDS = 5.0
MAX_DEVICE_TOKENS = 4
MAX_QUEUED = 50


class Pairing:
    def __init__(
        self,
        code: str,
        tokens: Optional[list] = None,
        queue: Optional[list] = None,
    ) -> None:
        self.code = code
        self.sender: Optional[WebSocket] = None
        self.receiver: Optional[WebSocket] = None
        # Last-seen device names (e.g. "Xiaomi 23049PCD8G"), advertised by each
        # peer in its hello message. In-memory only: they re-advertise on every
        # connect, and they are cleared with the slot on disconnect.
        self.sender_name: Optional[str] = None
        self.receiver_name: Optional[str] = None
        # High-entropy per-device credentials issued at first auth. The 6-digit
        # code stays the bootstrap/pairing key; the token is the relay credential.
        self.tokens: list = list(tokens or [])
        # Sender events uplinked while the receiver was offline, as ready-to-send
        # wire strings, oldest first. Flushed in order on the receiver's (re)connect.
        self.queue: list = [item for item in (queue or []) if isinstance(item, str)]
        # FCM wake tokens per role (registered via hello/auth or an
        # authenticated fcm_token message). Persisted; used to wake a dead
        # receiver so it reconnects and drains the queue.
        self.fcm: dict = {}
        # Last FCM wake per role (epoch seconds) — rate limit, in-memory only.
        self.last_wake: dict = {}


class Registry:
    def __init__(self, path: str) -> None:
        self.path = path
        self.pairings: Dict[str, Pairing] = {}
        self.lock = asyncio.Lock()
        self.load()

    def gen_code(self) -> str:
        return "".join(random.choices(string.digits, k=6))

    def load(self) -> None:
        try:
            with open(self.path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except (OSError, ValueError):
            return
        if not isinstance(data, dict):
            return
        for code in data:
            code = str(code)
            if not (code.isdigit() and len(code) == 6):
                continue
            value = data[code]
            if isinstance(value, dict):
                raw_tokens = value.get("tokens")
                tokens = [t for t in raw_tokens if isinstance(t, str)] if isinstance(raw_tokens, list) else []
                raw_queue = value.get("queue")
                queue = [item for item in raw_queue if isinstance(item, str)] if isinstance(raw_queue, list) else []
                raw_fcm = value.get("fcm")
                fcm_tokens = (
                    {r: t for r, t in raw_fcm.items() if r in ("sender", "receiver") and fcm.valid_token(t)}
                    if isinstance(raw_fcm, dict)
                    else {}
                )
            else:
                tokens = []  # legacy format: {code: true}
                queue = []
                fcm_tokens = {}
            self.pairings.setdefault(code, Pairing(code, tokens, queue)).fcm.update(fcm_tokens)

    def save(self) -> None:
        try:
            directory = os.path.dirname(self.path) or "."
            fd, tmp = tempfile.mkstemp(prefix=".pairings-", suffix=".tmp", dir=directory)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    json.dump(
                        {
                            code: {
                                "tokens": p.tokens,
                                **({"queue": p.queue} if p.queue else {}),
                                **({"fcm": p.fcm} if p.fcm else {}),
                            }
                            for code, p in self.pairings.items()
                        },
                        f,
                    )
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp, self.path)
            except BaseException:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
                raise
        except Exception:
            pass

    async def create_pairing(self) -> Pairing:
        async with self.lock:
            for _ in range(20):
                code = self.gen_code()
                if code not in self.pairings:
                    p = Pairing(code)
                    self.pairings[code] = p
                    self.save()
                    return p
            raise RuntimeError("Could not generate unique pairing code")

    def get_or_create(self, code: str) -> Optional[Pairing]:
        if not code.isdigit() or len(code) != 6:
            return None
        existing = self.pairings.get(code)
        if existing is not None:
            return existing
        p = Pairing(code)
        self.pairings[code] = p
        self.save()
        return p

    def get(self, code: str) -> Optional[Pairing]:
        return self.pairings.get(code)


registry = Registry(PAIRINGS_FILE)


def _valid_code(value: Optional[str]) -> bool:
    return value is not None and value.isdigit() and len(value) == 6


def _effective_code(ws: WebSocket, path_code: Optional[str]) -> Optional[str]:
    header_code = ws.headers.get(CODE_HEADER)
    if _valid_code(header_code):
        return header_code
    if _valid_code(path_code):
        return path_code
    return None


def _safe_json(raw: str):
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def _store_fcm_token(pairing: Pairing, role: str, value) -> bool:
    """Record an FCM wake token for this pairing/role. True when changed."""
    if not fcm.valid_token(value):
        return False
    if pairing.fcm.get(role) == value:
        return False
    pairing.fcm[role] = value
    return True


@app.get("/")
async def root() -> HTMLResponse:
    return HTMLResponse(
        "<h3>NextNotif relay running</h3>"
        "<p>Endpoints: <code>/ws/{role}</code> with an <code>X-NextNotif-Code</code> "
        "header (or legacy <code>/ws/{role}/{code}</code>), <code>POST /pair/create</code>, "
        "<code>POST /send/{code}</code> (or <code>POST /send</code> + header) — the sender's "
        "fire-and-forget uplink; events are relayed to the receiver's socket or queued for "
        "its next connect.</p>"
        "<p>Websockets complete a one-time handshake: the client sends "
        "<code>{&quot;type&quot;:&quot;hello&quot;}</code>, the server replies "
        "<code>{&quot;type&quot;:&quot;handshake&quot;,&quot;token&quot;:...}</code>, and the "
        "client must then send "
        "<code>{&quot;type&quot;:&quot;auth&quot;,&quot;token&quot;:...,&quot;code&quot;:...}</code> "
         "within 5 seconds. The server answers with "
         "<code>{&quot;type&quot;:&quot;auth_ok&quot;,&quot;device_token&quot;:...}</code>; clients should "
         "store the device token and include it as "
         "<code>device_token</code> in future auth messages.</p>"
         "<p>Events for an offline receiver are queued and replayed on its "
         "next connect. Clients may register an FCM wake token (in hello or "
         "auth as <code>fcm_token</code>, or while connected as "
         "<code>{&quot;type&quot;:&quot;fcm_token&quot;,&quot;data&quot;:{&quot;token&quot;:...}}</code>) "
         "so the relay can wake the receiver when an event is queued.</p>"
    )


async def _ws_session(ws: WebSocket, role: str, path_code: Optional[str]) -> None:
    if role not in ("sender", "receiver"):
        await ws.close(code=1008)
        return

    code = _effective_code(ws, path_code)
    if code is None:
        await ws.close(code=1008)
        return

    pairing = registry.get_or_create(code)
    if pairing is None:
        await ws.close(code=1008)
        return

    if role == "sender" and pairing.sender is not None:
        await ws.close(code=1008)
        return
    if role == "receiver" and pairing.receiver is not None:
        await ws.close(code=1008)
        return

    await ws.accept()
    if role == "sender":
        pairing.sender = ws
    else:
        pairing.receiver = ws

    token = secrets.token_urlsafe(16)
    try:
        # Client opens with {"type":"hello"}; then we issue the one-time token.
        try:
            first = await asyncio.wait_for(ws.receive_text(), timeout=AUTH_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            await ws.close(code=1008, reason="auth timeout")
            return
        first_msg = _safe_json(first)
        if not (isinstance(first_msg, dict) and first_msg.get("type") == "hello"):
            await ws.close(code=1008, reason="auth failed")
            return

        # Optional device name ("Samsung SM-A520F") for the partner's UI.
        name = first_msg.get("device_name")
        if isinstance(name, str):
            name = name.strip()[:64] or None
            if role == "sender":
                pairing.sender_name = name
            else:
                pairing.receiver_name = name

        # Optional FCM wake token for kill-recovery of this peer.
        pending_fcm_token = first_msg.get("fcm_token")

        await ws.send_text(json.dumps({"type": "handshake", "token": token}))

        try:
            second = await asyncio.wait_for(ws.receive_text(), timeout=AUTH_TIMEOUT_SECONDS)
        except asyncio.TimeoutError:
            await ws.close(code=1008, reason="auth timeout")
            return
        auth = _safe_json(second)
        if not (
            isinstance(auth, dict)
            and auth.get("type") == "auth"
            and auth.get("token") == token
            and auth.get("code") == code
        ):
            await ws.close(code=1008, reason="auth failed")
            return

        # Device tokens: a presented token that belongs to this pairing is kept;
        # anything else (first connect, stale token, legacy client) gets a fresh
        # one. Code-only auth thus stays a bootstrap path, while ongoing relays
        # ride on the high-entropy token.
        presented = auth.get("device_token")
        if isinstance(presented, str) and presented in pairing.tokens:
            issued = presented
        else:
            issued = secrets.token_urlsafe(24)
            pairing.tokens.append(issued)
            if len(pairing.tokens) > MAX_DEVICE_TOKENS:
                pairing.tokens = pairing.tokens[-MAX_DEVICE_TOKENS:]
            registry.save()
        # The auth message may carry a fresher FCM wake token than hello did.
        if _store_fcm_token(pairing, role, auth.get("fcm_token") if fcm.valid_token(auth.get("fcm_token")) else pending_fcm_token):
            registry.save()
        await ws.send_text(json.dumps({"type": "auth_ok", "device_token": issued}))

        if role == "receiver":
            # Catch-up: events the sender uplinked while this receiver was
            # offline arrive now, in order, before any live relay.
            queued, pairing.queue = pairing.queue, []
            for out in queued:
                try:
                    await ws.send_text(out)
                except Exception:
                    break

        while True:
            msg = await ws.receive_text()
            try:
                data = json.loads(msg)
            except json.JSONDecodeError:
                continue
            if not isinstance(data, dict) or data.get("type") == "auth":
                continue

            # FCM token refresh while connected (rotation is rare, but a
            # reinstall / OS change rotates it without a re-pairing).
            if data.get("type") == "fcm_token":
                inner = data.get("data")
                if _store_fcm_token(pairing, role, inner.get("token") if isinstance(inner, dict) else None):
                    registry.save()
                continue

            payload = json.dumps(
                {"type": data.get("type", "unknown"), "from": role, "data": data.get("data")}
            )

            target = pairing.receiver if role == "sender" else pairing.sender
            delivered = False
            if target is not None:
                try:
                    await target.send_text(payload)
                    delivered = True
                except Exception:
                    pass  # stale socket: fall through to the queue
            if not delivered:
                # Receiver offline: hold the event for catch-up and wake the
                # phone via FCM so the catch-up doesn't wait for a reboot.
                pairing.queue.append(payload)
                if len(pairing.queue) > MAX_QUEUED:
                    pairing.queue = pairing.queue[-MAX_QUEUED:]
                registry.save()
                if role == "sender":
                    # Fire-and-forget: the wake must not delay the sender's
                    # response (a stalled Google egress would).
                    asyncio.get_running_loop().run_in_executor(
                        None, fcm_wake, pairing, "receiver", data.get("type", "unknown"), data.get("data")
                    )
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        if role == "sender" and pairing.sender is ws:
            pairing.sender = None
            pairing.sender_name = None
        elif role == "receiver" and pairing.receiver is ws:
            pairing.receiver = None
            pairing.receiver_name = None


@app.websocket("/ws/{role}")
async def ws_endpoint_header(ws: WebSocket, role: str) -> None:
    await _ws_session(ws, role, None)


@app.websocket("/ws/{role}/{code}")
async def ws_endpoint_path(ws: WebSocket, role: str, code: str) -> None:
    await _ws_session(ws, role, code)


@app.post("/pair/create")
async def pair_create() -> Dict[str, str]:
    p = await registry.create_pairing()
    return {"code": p.code}


async def _handle_send(request: Request, code: Optional[str]):
    """Fire-and-forget sender uplink.

    Auth is the 6-digit pairing code (TLS-protected in transit, the same trust
    anchor as the WS bootstrap). Delivers to the receiver's open socket, or
    queues for catch-up when the receiver is offline.
    """
    if not _valid_code(code):
        return JSONResponse({"error": "missing pairing code"}, status_code=400)
    p = registry.get(code)
    if p is None:
        return JSONResponse({"error": "unknown pairing"}, status_code=404)

    presented = request.headers.get(TOKEN_HEADER)
    if presented is not None and presented not in p.tokens:
        return JSONResponse({"error": "unknown device token"}, status_code=401)

    body = _safe_json((await request.body()).decode("utf-8", "replace"))
    if not isinstance(body, dict) or not isinstance(body.get("type"), str) or not body.get("type"):
        return JSONResponse({"error": "invalid body"}, status_code=400)

    out = json.dumps({"type": body["type"], "from": "sender", "data": body.get("data")})

    target = p.receiver
    delivered = False
    if target is not None:
        try:
            await target.send_text(out)
            delivered = True
        except Exception:
            pass  # stale socket: fall through to the queue

    if not delivered:
        p.queue.append(out)
        if len(p.queue) > MAX_QUEUED:
            p.queue = p.queue[-MAX_QUEUED:]
        registry.save()
        # Fire-and-forget: the wake must not delay the sender's response.
        asyncio.get_running_loop().run_in_executor(
            None, fcm_wake, p, "receiver", body["type"], body.get("data")
        )
        return {"delivered": False, "queued": len(p.queue)}
    return {"delivered": True, "queued": 0}


@app.post("/send")
async def send_header(request: Request):
    return await _handle_send(request, request.headers.get(CODE_HEADER))


@app.post("/send/{code}")
async def send_path(code: str, request: Request):
    header_code = request.headers.get(CODE_HEADER)
    return await _handle_send(request, header_code if _valid_code(header_code) else code)


@app.get("/pair/{code}/status")
async def pair_status(code: str) -> Dict[str, object]:
    p = registry.get(code)
    if p is None:
        return {"exists": False}
    return {
        "exists": True,
        "sender_connected": p.sender is not None,
        "receiver_connected": p.receiver is not None,
        "sender_name": p.sender_name,
        "receiver_name": p.receiver_name,
        "sender_has_fcm": p.fcm.get("sender") is not None,
        "receiver_has_fcm": p.fcm.get("receiver") is not None,
    }


if __name__ == "__main__":
    import uvicorn

    # Pterodactyl (e.g. Waifly) injects SERVER_PORT; PORT is a common fallback.
    # Local dev with no env vars keeps the original 8000.
    port = int(os.environ.get("SERVER_PORT") or os.environ.get("PORT") or 8000)
    uvicorn.run(app, host="0.0.0.0", port=port)

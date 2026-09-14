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
import sms_mailbox
from fcm import wake as fcm_wake
from secure_pairing import AuthorizationError, MODE as SECURE_MODE, authorize_deletion
from secure_pairing_store import SecurePairingStore

app = FastAPI(title="NextNotif Relay")

PAIRINGS_FILE = os.path.join(os.path.dirname(__file__), "pairings.json")
CODE_HEADER = "x-nextnotif-code"
TOKEN_HEADER = "x-nextnotif-token"
AUTH_TIMEOUT_SECONDS = 5.0
MAX_DEVICE_TOKENS = 4
MAX_QUEUED = 50
FCM_DURABLE_TYPES = {"sms", "call", "relay_test"}


class Pairing:
    def __init__(
        self,
        code: str,
        tokens: Optional[list] = None,
        queue: Optional[list] = None,
    ) -> None:
        self.code = code
        self.security_mode = "legacy"
        self.device_ids = {}
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
        self.token_roles: dict = {}
        self.sms_commands: list = []
        self.sms_sim_options = None
        # Sender events uplinked while the receiver was offline, as ready-to-send
        # wire strings, oldest first. Flushed in order on the receiver's (re)connect.
        self.queue: list = [item for item in (queue or []) if isinstance(item, str)]
        # FCM wake tokens per role (registered via hello/auth or an
        # authenticated fcm_token message). Persisted; used to wake a dead
        # receiver so it reconnects and drains the queue.
        self.fcm: dict = {}
        # Receiver delivery mode: "ws" keeps a socket, "fcm" sleeps and
        # drains through a short authenticated HTTP request after each push.
        self.delivery: dict = {}
        # Last FCM wake per role (epoch seconds) — rate limit, in-memory only.
        self.last_wake: dict = {}


class Registry:
    def __init__(self, path: str) -> None:
        self.path = path
        self.pairings: Dict[str, Pairing] = {}
        self.blocked_records = {}
        self.secure_store = None
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
            mode = value.get("security_mode", "legacy") if isinstance(value, dict) else "legacy"
            if mode not in ("legacy", SECURE_MODE):
                self.blocked_records[code] = value
                continue
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
                raw_delivery = value.get("delivery")
                delivery = (
                    {r: m for r, m in raw_delivery.items() if r in ("sender", "receiver") and m in ("ws", "fcm")}
                    if isinstance(raw_delivery, dict)
                    else {}
                )
            else:
                tokens = []  # legacy format: {code: true}
                queue = []
                fcm_tokens = {}
                delivery = {}
            pairing = self.pairings.setdefault(code, Pairing(code, tokens, queue))
            pairing.security_mode = mode
            if isinstance(value, dict):
                pairing.token_roles = value.get("token_roles", {})
                pairing.sms_commands = value.get("sms_commands", [])
                pairing.sms_sim_options = value.get("sms_sim_options")
            pairing.fcm.update(fcm_tokens)
            pairing.delivery.update(delivery)

    def save(self, strict=False) -> bool:
        try:
            directory = os.path.dirname(self.path) or "."
            fd, tmp = tempfile.mkstemp(prefix=".pairings-", suffix=".tmp", dir=directory)
            try:
                with os.fdopen(fd, "w", encoding="utf-8") as f:
                    json.dump(
                        {**self.blocked_records, **{
                            code: {
                                "tokens": p.tokens,
                                "token_roles": p.token_roles,
                                "sms_commands": p.sms_commands,
                                "sms_sim_options": p.sms_sim_options,
                                "security_mode": p.security_mode,
                                **({"queue": p.queue} if p.queue else {}),
                                **({"fcm": p.fcm} if p.fcm else {}),
                                **({"delivery": p.delivery} if p.delivery else {}),
                            }
                            for code, p in self.pairings.items()
                        }},
                        f,
                    )
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp, self.path)
                if strict:
                    directory_fd = os.open(directory, os.O_RDONLY)
                    try:
                        os.fsync(directory_fd)
                    finally:
                        os.close(directory_fd)
                return True
            except BaseException:
                try:
                    os.unlink(tmp)
                except OSError:
                    pass
                raise
        except Exception:
            if strict:
                raise
            return False

    async def create_pairing(self) -> Pairing:
        async with self.lock:
            for _ in range(20):
                code = self.gen_code()
                if (code not in self.pairings and code not in self.blocked_records
                        and not (self.secure_store and self.secure_store.exists(code))):
                    p = Pairing(code)
                    self.pairings[code] = p
                    self.save()
                    return p
            raise RuntimeError("Could not generate unique pairing code")

    def get_or_create(self, code: str) -> Optional[Pairing]:
        if code in self.blocked_records:
            return None
        if not code.isdigit() or len(code) != 6:
            return None
        existing = self.pairings.get(code)
        if existing is not None:
            return self.get(code)
        p = Pairing(code)
        if self.secure_store and self.secure_store.exists(code):
            p.security_mode = SECURE_MODE
        self.pairings[code] = p
        self.save()
        return p

    def get(self, code: str) -> Optional[Pairing]:
        if code in self.blocked_records:
            return None
        p = self.pairings.get(code)
        if p and self.secure_store and self.secure_store.exists(code):
            p.security_mode = SECURE_MODE
        return p


registry = Registry(PAIRINGS_FILE)
if os.environ.get("NEXTNOTIF_SECURE_DATABASE"):
    registry.secure_store = SecurePairingStore(os.environ["NEXTNOTIF_SECURE_DATABASE"])


def _secure_authorize(code, device_id, token, role):
    """Secure records never mint replacement credentials from a display code."""
    store = registry.secure_store
    if store is None:
        raise AuthorizationError()
    if role is not None:
        return store.authorize(code, device_id, token, role)
    for candidate in ("sender", "receiver"):
        try:
            return store.authorize(code, device_id, token, candidate)
        except AuthorizationError:
            pass
    raise AuthorizationError()


def _secure_http_denial(request, pairing, role):
    if pairing.security_mode == "legacy":
        return None
    auth = request.headers.get("authorization") or ""
    token = request.headers.get(TOKEN_HEADER) or (auth[7:] if auth.startswith("Bearer ") else None)
    try:
        _secure_authorize(pairing.code, request.headers.get("x-nextnotif-device-id"), token, role)
    except Exception:
        return JSONResponse({"error": "pairing authorization failed"}, status_code=401)
    return None


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


def _issue_device_token(pairing: Pairing, presented) -> str:
    if isinstance(presented, str) and presented in pairing.tokens:
        return presented
    issued = secrets.token_urlsafe(24)
    pairing.tokens.append(issued)
    pairing.tokens = pairing.tokens[-MAX_DEVICE_TOKENS:]
    return issued


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

        # A pending socket is not a relay peer. Publish it and its metadata
        # only after authentication, with a second slot check for concurrent
        # handshakes. No await separates the check and assignment.
        if pairing.security_mode != "legacy":
            try:
                _secure_authorize(code, auth.get("device_id"), auth.get("device_token"), role)
            except Exception:
                await ws.close(code=1008, reason="pairing authorization failed")
                return
        holder = pairing.sender if role == "sender" else pairing.receiver
        if holder is not None:
            await ws.close(code=1008, reason="role occupied")
            return
        if role == "sender":
            pairing.sender = ws
        else:
            pairing.receiver = ws
        if pairing.security_mode != "legacy":
            pairing.device_ids[role] = auth["device_id"]
        name = first_msg.get("device_name")
        if isinstance(name, str):
            name = name.strip()[:64] or None
            if role == "sender":
                pairing.sender_name = name
            else:
                pairing.receiver_name = name
        _store_fcm_token(pairing, role, first_msg.get("fcm_token"))

        # Device tokens: a presented token that belongs to this pairing is kept;
        # anything else (first connect, stale token, legacy client) gets a fresh
        # one. Code-only auth thus stays a bootstrap path, while ongoing relays
        # ride on the high-entropy token.
        if pairing.token_roles.get(auth.get("device_token"), role) != role:
            await ws.close(code=1008, reason="credential role mismatch")
            return
        issued = (auth["device_token"] if pairing.security_mode != "legacy"
                  else _issue_device_token(pairing, auth.get("device_token")))
        if role == "receiver":
            pairing.delivery["receiver"] = "fcm" if auth.get("delivery_mode") == "fcm" else "ws"
        registry.save()
        # The auth message may carry a fresher FCM wake token than hello did.
        if _store_fcm_token(pairing, role, auth.get("fcm_token")):
            registry.save()
        pairing.token_roles[issued] = role
        registry.save()
        await ws.send_text(json.dumps({"type": "auth_ok", "device_token": issued}))

        if role == "receiver" and pairing.delivery.get("receiver") != "fcm":
            # Catch-up: events the sender uplinked while this receiver was
            # offline arrive now, in order, before any live relay.
            queued, pairing.queue = pairing.queue, []
            for out in queued:
                try:
                    await ws.send_text(out)
                except Exception:
                    break

        while True:
            message = await ws.receive()
            if pairing.security_mode != "legacy":
                try:
                    _secure_authorize(code, auth.get("device_id"), auth.get("device_token"), role)
                except Exception:
                    await ws.close(code=1008, reason="pairing authorization failed")
                    break
            if message.get("type") == "websocket.disconnect":
                break
            raw_audio = message.get("bytes")
            if raw_audio is not None:
                # Live call audio is deliberately ephemeral: forward binary
                # frames only to the peer that is online right now. Never put
                # audio in the notification catch-up queue or FCM payloads.
                target = pairing.receiver if role == "sender" else pairing.sender
                if target is not None:
                    try:
                        await target.send_bytes(raw_audio)
                    except Exception:
                        pass
                continue
            msg = message.get("text")
            if msg is None:
                continue
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

            event_id = secrets.token_urlsafe(16)
            payload = json.dumps(
                {
                    "type": data.get("type", "unknown"),
                    "from": role,
                    "event_id": event_id,
                    "data": data.get("data"),
                }
            )

            target = pairing.receiver if role == "sender" else pairing.sender
            # A temporary call socket must not consume the durable inbox.
            # SMS/call information still wakes the on-demand HTTPS fetch/ACK
            # path; live controls and binary audio use the socket as before.
            durable_fcm_event = (
                role == "sender"
                and pairing.delivery.get("receiver") == "fcm"
                and data.get("type") in FCM_DURABLE_TYPES
            )
            delivered = False
            if target is not None and not durable_fcm_event:
                try:
                    await target.send_text(payload)
                    delivered = True
                except Exception:
                    pass  # stale socket: fall through to the queue
            if not delivered and data.get("type") != "call_control":
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
                        None,
                        fcm_wake,
                        pairing,
                        "receiver",
                        data.get("type", "unknown"),
                        data.get("data"),
                        event_id,
                    )
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        if role == "sender" and pairing.sender is ws:
            pairing.sender = None
            pairing.sender_name = None
            pairing.device_ids.pop(role, None)
        elif role == "receiver" and pairing.receiver is ws:
            pairing.receiver = None
            pairing.receiver_name = None
            pairing.device_ids.pop(role, None)


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

    denial = _secure_http_denial(request, p, "sender")
    if denial is not None:
        return denial
    presented = request.headers.get(TOKEN_HEADER)
    if p.security_mode == "legacy" and presented is not None and presented not in p.tokens:
        return JSONResponse({"error": "unknown device token"}, status_code=401)

    body = _safe_json((await request.body()).decode("utf-8", "replace"))
    if not isinstance(body, dict) or not isinstance(body.get("type"), str) or not body.get("type"):
        return JSONResponse({"error": "invalid body"}, status_code=400)

    event_id = secrets.token_urlsafe(16)
    out = json.dumps(
        {"type": body["type"], "from": "sender", "event_id": event_id, "data": body.get("data")}
    )

    target = p.receiver
    delivered = False
    durable_fcm_event = p.delivery.get("receiver") == "fcm" and body["type"] in FCM_DURABLE_TYPES
    if target is not None and not durable_fcm_event:
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
            None, fcm_wake, p, "receiver", body["type"], body.get("data"), event_id
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


async def _handle_fcm_register(request: Request, code: Optional[str]):
    """Register an on-demand receiver and issue its drain credential."""
    if not _valid_code(code):
        return JSONResponse({"error": "missing pairing code"}, status_code=400)
    body = _safe_json((await request.body()).decode("utf-8", "replace"))
    if not isinstance(body, dict) or not (fcm.valid_token(body.get("fcm_token")) or
            (body.get("delivery_mode") == "ws" and not body.get("fcm_token"))):
        return JSONResponse({"error": "invalid body"}, status_code=400)
    # An FCM-only pairing has no bootstrap WebSocket. Its first valid receiver
    # registration claims the code, matching get_or_create in the WS flow.
    p = registry.get_or_create(code)
    if p is None:
        return JSONResponse({"error": "pairing unavailable"}, status_code=401)
    role = request.headers.get("x-nextnotif-role", "receiver")
    if role not in ("sender", "receiver"):
        return JSONResponse({"error": "invalid role"}, status_code=400)
    if p.token_roles.get(body.get("device_token"), role) != role:
        return JSONResponse({"error": "credential role mismatch"}, status_code=401)
    denial = _secure_http_denial(request, p, role)
    if denial is not None:
        return denial
    _store_fcm_token(p, role, body.get("fcm_token"))
    p.delivery[role] = "ws" if body.get("delivery_mode") == "ws" else "fcm"
    name = body.get("device_name")
    if isinstance(name, str) and name.strip():
        setattr(p, role + "_name", name.strip()[:64])
    if p.security_mode == "legacy":
        issued = _issue_device_token(p, body.get("device_token"))
    else:
        auth = request.headers.get("authorization") or ""
        issued = request.headers.get(TOKEN_HEADER) or auth[7:]
    p.token_roles[issued] = role
    p.token_roles = {t: r for t, r in p.token_roles.items() if t in p.tokens}
    registry.save()
    return {"device_token": issued}


@app.post("/fcm-register")
async def fcm_register_header(request: Request):
    return await _handle_fcm_register(request, request.headers.get(CODE_HEADER))


@app.post("/fcm-register/{code}")
async def fcm_register_path(code: str, request: Request):
    header_code = request.headers.get(CODE_HEADER)
    return await _handle_fcm_register(request, header_code if _valid_code(header_code) else code)


def _receiver_queue_pairing(request: Request, code: Optional[str]):
    """Authenticate every queue operation before reading or mutating events."""
    if not _valid_code(code):
        return JSONResponse({"error": "missing pairing code"}, status_code=400)
    p = registry.get(code)
    if p is None:
        return JSONResponse({"error": "unknown pairing"}, status_code=404)
    denial = _secure_http_denial(request, p, "receiver")
    if denial is not None:
        return denial
    if p.security_mode != "legacy":
        return p
    auth = request.headers.get("authorization") or ""
    bearer = auth[7:] if auth.startswith("Bearer ") else ""
    presented = request.headers.get(TOKEN_HEADER) or bearer
    if not presented or presented not in p.tokens:
        return JSONResponse({"error": "unknown device token"}, status_code=401)
    return p


def _queue_events(queued):
    return [event for raw in queued if isinstance(event := _safe_json(raw), dict)]


async def _handle_drain(request: Request, code: Optional[str]):
    """Legacy destructive queue drain; new clients use fetch then acknowledge."""
    p = _receiver_queue_pairing(request, code)
    if isinstance(p, JSONResponse):
        return p
    queued, p.queue = p.queue, []
    registry.save()
    return {"events": _queue_events(queued)}


async def _handle_fetch(request: Request, code: Optional[str]):
    """Retain events until a receiver confirms they are durably stored."""
    p = _receiver_queue_pairing(request, code)
    if isinstance(p, JSONResponse):
        return p
    return {"events": _queue_events(p.queue)}


async def _handle_ack(request: Request, code: Optional[str]):
    p = _receiver_queue_pairing(request, code)
    if isinstance(p, JSONResponse):
        return p
    body = _safe_json((await request.body()).decode("utf-8", "replace"))
    ids = body.get("event_ids") if isinstance(body, dict) else None
    if (
        not isinstance(ids, list)
        or len(ids) > 100
        or any(not isinstance(event_id, str) or not event_id.strip() for event_id in ids)
    ):
        return JSONResponse({"error": "invalid event_ids"}, status_code=400)
    acknowledged_ids = set(ids)
    retained = []
    for raw in p.queue:
        event = _safe_json(raw)
        event_id = event.get("event_id") if isinstance(event, dict) else None
        if not isinstance(event_id, str) or event_id not in acknowledged_ids:
            retained.append(raw)
    acknowledged = len(p.queue) - len(retained)
    if acknowledged:
        p.queue = retained
        registry.save()
    return {"acknowledged": acknowledged}


@app.post("/fetch")
async def fetch_header(request: Request):
    return await _handle_fetch(request, request.headers.get(CODE_HEADER))


@app.post("/fetch/{code}")
async def fetch_path(code: str, request: Request):
    header_code = request.headers.get(CODE_HEADER)
    return await _handle_fetch(request, header_code if _valid_code(header_code) else code)


@app.post("/ack")
async def ack_header(request: Request):
    return await _handle_ack(request, request.headers.get(CODE_HEADER))


@app.post("/ack/{code}")
async def ack_path(code: str, request: Request):
    header_code = request.headers.get(CODE_HEADER)
    return await _handle_ack(request, header_code if _valid_code(header_code) else code)


@app.post("/drain")
async def drain_header(request: Request):
    return await _handle_drain(request, request.headers.get(CODE_HEADER))


@app.post("/drain/{code}")
async def drain_path(code: str, request: Request):
    header_code = request.headers.get(CODE_HEADER)
    return await _handle_drain(request, header_code if _valid_code(header_code) else code)


@app.post("/pair/{code}/revoke")
async def revoke_device(code: str, request: Request):
    p = registry.get(code)
    if p is None or p.security_mode == "legacy":
        return JSONResponse({"error": "secure pairing required"}, status_code=403)
    denial = _secure_http_denial(request, p, None)
    if denial is not None:
        return denial
    body = _safe_json((await request.body()).decode("utf-8", "replace"))
    target = body.get("device_id") if isinstance(body, dict) else None
    if not isinstance(target, str) or not 1 <= len(target) <= 64:
        return JSONResponse({"error": "invalid device_id"}, status_code=400)
    authorization = request.headers.get("authorization") or ""
    token = request.headers.get(TOKEN_HEADER) or authorization[7:]
    try:
        registry.secure_store.authorize_owner(code, request.headers.get("x-nextnotif-device-id"), token)
        if target == request.headers.get("x-nextnotif-device-id"):
            return JSONResponse({"error": "owner removal requires pairing deletion"}, status_code=400)
        role = registry.secure_store.revoke(code, request.headers.get("x-nextnotif-device-id"), token, target)
    except AuthorizationError:
        return JSONResponse({"error": "pairing authorization failed"}, status_code=401)
    except Exception:
        return JSONResponse({"error": "revocation storage unavailable"}, status_code=503)
    # Remove the live slot before awaiting closure; no traffic may target it.
    socket = None
    if p.device_ids.get(role) == target:
        socket = p.sender if role == "sender" else p.receiver
        if role == "sender":
            p.sender = None
            p.sender_name = None
        else:
            p.receiver = None
            p.receiver_name = None
        p.device_ids.pop(role, None)
    p.fcm.pop(role, None)
    p.delivery.pop(role, None)
    p.queue.clear()
    if socket is not None:
        try:
            await socket.close(code=1008, reason="device revoked")
        except (RuntimeError, WebSocketDisconnect):
            pass
    try:
        registry.save(strict=True)
    except Exception:
        return JSONResponse({"error": "revoked; cleanup persistence needs retry"}, status_code=503)
    return {"revoked": target}


@app.delete("/pair/{code}")
async def delete_pairing(code: str, request: Request):
    authorization = request.headers.get("authorization") or ""
    token = request.headers.get(TOKEN_HEADER) or (authorization[7:] if authorization.startswith("Bearer ") else None)
    owner_id = request.headers.get("x-nextnotif-device-id")
    tombstone = registry.blocked_records.get(code)
    p = registry.get(code)
    try:
        if tombstone is not None:
            authorize_deletion(tombstone, owner_id, token)
        else:
            if p is None or p.security_mode == "legacy" or registry.secure_store is None:
                raise AuthorizationError()
            tombstone = registry.secure_store.deletion_identity(code, owner_id, token)
    except AuthorizationError:
        return JSONResponse({"error": "pairing authorization failed"}, status_code=401)
    except Exception:
        return JSONResponse({"error": "deletion storage unavailable"}, status_code=503)
    # Persist the reservation before destroying credentials. Its owner hash
    # grants cleanup-only retries, never relay access or code-only recreation.
    if code not in registry.blocked_records:
        registry.blocked_records[code] = tombstone
        registry.pairings.pop(code, None)
        try:
            registry.save(strict=True)
        except Exception:
            registry.blocked_records.pop(code, None)
            registry.pairings[code] = p
            return JSONResponse({"error": "deletion reservation needs retry"}, status_code=503)
    sockets = []
    if p is not None:
        sockets = [socket for socket in (p.sender, p.receiver) if socket is not None]
        p.sender = p.receiver = None
        p.sender_name = p.receiver_name = None
        p.device_ids.clear()
        p.fcm.clear()
        p.delivery.clear()
        p.queue.clear()
    failed = False
    try:
        if tombstone.get("cleanup_pending", True):
            if registry.secure_store is None:
                raise RuntimeError("credential storage unavailable")
            registry.secure_store.purge_reserved(code)
        tombstone["cleanup_pending"] = False
        registry.save(strict=True)
    except Exception:
        tombstone["cleanup_pending"] = True
        failed = True
    for socket in sockets:
        try:
            await socket.close(code=1008, reason="pairing deleted")
        except (RuntimeError, WebSocketDisconnect):
            pass
    if failed:
        return JSONResponse({"error": "pairing blocked; cleanup needs retry"}, status_code=503)
    return {"deleted": True}


@app.get("/pair/{code}/status")
async def pair_status(code: str, request: Request):
    p = registry.get(code)
    if p is None:
        return {"exists": False}
    denial = _secure_http_denial(request, p, None)
    if denial is not None:
        return denial
    return {
        "exists": True,
        "sender_connected": p.sender is not None,
        "receiver_connected": p.receiver is not None,
        "sender_name": p.sender_name,
        "receiver_name": p.receiver_name,
        "sender_has_fcm": p.fcm.get("sender") is not None,
        "receiver_has_fcm": p.fcm.get("receiver") is not None,
    }


@app.post("/sms-{operation}")
async def sms_operation(operation: str, request: Request):
    if operation not in {"submit", "fetch", "result", "status"}:
        return JSONResponse({"error": "unknown operation"}, status_code=404)
    code = request.headers.get(CODE_HEADER)
    p = registry.get(code) if _valid_code(code) else None
    if p is None:
        return JSONResponse({"error": "unknown pairing"}, status_code=404)
    role = "sender" if operation in {"fetch", "result"} else "receiver"
    denial = _secure_http_denial(request, p, role)
    if denial is not None:
        return denial
    token = request.headers.get(TOKEN_HEADER)
    if p.security_mode == "legacy" and (token not in p.tokens or p.token_roles.get(token) != role):
        return JSONResponse({"error": "pairing authorization failed"}, status_code=401)
    body = _safe_json((await request.body()).decode("utf-8", "replace"))
    if not isinstance(body, dict):
        return JSONResponse({"error": "invalid body"}, status_code=400)
    async with registry.lock:
        if operation == "fetch" and "sim_options" in body:
            options = sms_mailbox.sim_options(body["sim_options"])
            if options is None:
                return JSONResponse({"error": "invalid SIM options"}, status_code=400)
            p.sms_sim_options = options
        if operation == "status" and body.get("request_sims") is True:
            if p.sender is not None:
                try:
                    await p.sender.send_text(json.dumps({"type": "sms_sync", "data": {}}))
                except Exception:
                    pass
            asyncio.get_running_loop().run_in_executor(None, fcm_wake, p, "sender", "sms_request", {}, "sim-options")
        sms_mailbox.expire(p.sms_commands)
        if operation == "submit":
            status, command = sms_mailbox.submit(p.sms_commands, body)
            if status != 200:
                return JSONResponse({"error": "Invalid, conflicting, expired or full SMS request"}, status_code=status)
            registry.save(strict=True)
            if p.sender is not None:
                try:
                    await p.sender.send_text(json.dumps({"type": "sms_sync", "data": {}}))
                except Exception:
                    pass
            asyncio.get_running_loop().run_in_executor(None, fcm_wake, p, "sender", "sms_request", {}, command["id"])
            return {"command": command}
        if operation == "result":
            if not sms_mailbox.update(p.sms_commands, body):
                return JSONResponse({"error": "invalid result"}, status_code=400)
            registry.save(strict=True)
            if p.receiver is not None:
                try:
                    await p.receiver.send_text(json.dumps({"type": "sms_sync", "data": {}}))
                except Exception:
                    pass
            asyncio.get_running_loop().run_in_executor(None, fcm_wake, p, "receiver", "sms_status", {}, body["id"])
            return {"ok": True}
        registry.save(strict=True)
        return {"commands": [x for x in p.sms_commands if x["status"] in {"queued", "sending"}]
                if operation == "fetch" else p.sms_commands, "sim_options": p.sms_sim_options}


if __name__ == "__main__":
    import uvicorn

    # Pterodactyl (e.g. Waifly) injects SERVER_PORT; PORT is a common fallback.
    # Local dev with no env vars keeps the original 8000.
    port = int(os.environ.get("SERVER_PORT") or os.environ.get("PORT") or 8000)
    uvicorn.run(app, host="0.0.0.0", port=port)

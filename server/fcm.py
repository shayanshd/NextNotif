"""FCM wake fallback for the NextNotif relay.

When the receiver's socket is down (app killed, battery, a middlebox zombie
the edge has not reaped), events still land in the per-pairing queue — but
nothing reconnects the phone until it reboots or the user opens the app. This
module sends a high-priority FCM message to the receiver's registered token
so the OS wakes the phone and the relay service re-establishes its socket;
the queued events are replayed on connect.

The high-priority data-only message contains only a pairing code and wake
marker. Android shows a catch-up notification and reconnects to fetch the
canonical queued events. A notification payload would bypass the background
callback; event content in the push would also risk FCM's payload size limit.

Configuration: a Firebase service account, either at the path named by the
``NEXTNOTIF_FCM_SERVICE_ACCOUNT`` env var or, by default,
``fcm-service-account.json`` next to this file. Without one, wakes are
skipped (logged) and the queue-only behaviour remains — the relay is fully
functional, just without the kill-recovery shortcut.

Test hooks: ``NEXTNOTIF_FCM_TOKEN_URL`` and ``NEXTNOTIF_FCM_BASE`` override
the Google OAuth / FCM endpoints; ``reset_cache()`` clears the memoised
service account + access token between test servers.
"""

import base64
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
WAKE_COOLDOWN_SECONDS = 30.0
TOKEN_TTL_SECONDS = 3600
# FCM tokens are long base64url-ish strings; bounds that reject garbage
# without rejecting real (legacy or v1) tokens.
TOKEN_MIN_LEN = 20
TOKEN_MAX_LEN = 1024

_sa_cache: dict = {}
_token_cache: dict = {}


def _log(msg: str) -> None:
    print(f"[fcm] {msg}", flush=True)


def reset_cache() -> None:
    _sa_cache.clear()
    _token_cache.clear()


def valid_token(value) -> bool:
    # FCM tokens are single base64url-ish strings; reject padded/embedded blanks.
    return (
        isinstance(value, str)
        and TOKEN_MIN_LEN <= len(value) <= TOKEN_MAX_LEN
        and value == value.strip()
        and " " not in value
    )


def service_account() -> dict | None:
    """Load (and cache) the service account, or None when not configured."""
    if "sa" in _sa_cache:
        return _sa_cache["sa"]
    path = os.environ.get("NEXTNOTIF_FCM_SERVICE_ACCOUNT") or os.path.join(
        HERE, "fcm-service-account.json"
    )
    sa = None
    if path:
        try:
            with open(path, "r", encoding="utf-8") as f:
                sa = json.load(f)
            if not (
                isinstance(sa, dict)
                and isinstance(sa.get("project_id"), str)
                and isinstance(sa.get("client_email"), str)
                and isinstance(sa.get("private_key"), str)
            ):
                _log(f"service account at {path} is malformed; FCM wakes disabled")
                sa = None
        except (OSError, ValueError) as e:
            _log(f"no service account ({e}); FCM wakes disabled")
    if sa is not None:
        _sa_cache["sa"] = sa
    else:
        _sa_cache["sa"] = None
    return sa


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _jwt_assertion(sa: dict, token_url: str) -> str:
    """A self-signed RS256 JWT in the OAuth 2.0 service-account grant format."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding
    from cryptography.hazmat.primitives.serialization import load_pem_private_key

    now = int(time.time())
    header = {"alg": "RS256", "typ": "JWT"}
    payload = {
        "iss": sa["client_email"],
        "scope": FCM_SCOPE,
        "aud": token_url,
        "iat": now,
        "exp": now + TOKEN_TTL_SECONDS,
    }
    signing_input = (
        f"{_b64url(json.dumps(header, separators=(',', ':')).encode())}."
        f"{_b64url(json.dumps(payload, separators=(',', ':')).encode())}"
    ).encode("ascii")
    key = load_pem_private_key(sa["private_key"].encode("utf-8"), password=None)
    sig = key.sign(signing_input, padding.PKCS1v15(), hashes.SHA256())
    return f"{signing_input.decode('ascii')}.{_b64url(sig)}"


def access_token(sa: dict, token_url: str) -> str:
    """OAuth2 access token for the messaging scope, cached until ~50 s before expiry."""
    key = (token_url, sa["client_email"])
    cached = _token_cache.get(key)
    if cached and cached[1] > time.time():
        return cached[0]
    data = urllib.parse.urlencode(
        {
            "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
            "assertion": _jwt_assertion(sa, token_url),
        }
    ).encode("ascii")
    req = urllib.request.Request(token_url, data=data, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    with urllib.request.urlopen(req, timeout=10) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    token = body.get("access_token")
    if not isinstance(token, str) or not token:
        raise RuntimeError(f"token endpoint returned no access_token: {body!r}")
    expires_in = body.get("expires_in", TOKEN_TTL_SECONDS)
    _token_cache[key] = (token, time.time() + float(expires_in) - 50)
    return token


def send(event_type: str, event_data, fcm_token: str, code: str, fcm_base: str) -> None:
    """Send one wake message. Raises on transport/API failure (caller logs)."""
    sa = service_account()
    if sa is None:
        raise RuntimeError("FCM not configured")
    token_url = os.environ.get("NEXTNOTIF_FCM_TOKEN_URL") or "https://oauth2.googleapis.com/token"
    bearer = access_token(sa, token_url)
    message = {
        "token": fcm_token,
        "data": {"nn": "1", "code": code, "action": "wake"},
        "android": {"priority": "high"},
    }
    url = f"{fcm_base.rstrip('/')}/v1/projects/{sa['project_id']}/messages:send"
    req = urllib.request.Request(
        url, data=json.dumps({"message": message}).encode("utf-8"), method="POST"
    )
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {bearer}")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            resp.read()
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")[:200]
        raise RuntimeError(f"FCM API {e.code}: {detail}") from None


def wake(pairing, role: str, event_type: str, event_data) -> None:
    """Best-effort wake for one pairing/role; never raises into the relay path.

    Rate-limited to one message per pairing+role per [WAKE_COOLDOWN_SECONDS]:
    the events pile up in the queue meanwhile, so one wake is enough to
    reconnect the phone and drain everything.
    """
    token = getattr(pairing, "fcm", {}).get(role)
    if not valid_token(token):
        return
    now = time.time()
    last = getattr(pairing, "last_wake", {}).get(role, 0.0)
    if now - last < WAKE_COOLDOWN_SECONDS:
        return
    try:
        fcm_base = os.environ.get("NEXTNOTIF_FCM_BASE") or "https://fcm.googleapis.com"
        send(event_type, event_data, token, pairing.code, fcm_base)
    except Exception as e:
        _log(f"wake for {pairing.code}/{role} failed: {e}")
        return
    pairing.last_wake[role] = now
    _log(f"wake sent for {pairing.code}/{role} ({event_type})")

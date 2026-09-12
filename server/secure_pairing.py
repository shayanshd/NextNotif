"""Secure pairing state machine; not exposed by relay endpoints yet.

Callers must perform mutations and checked persistence in one server transaction
before returning credentials. Never treat a malformed secure record as legacy.
"""

import copy
import hashlib
import hmac
import re
import secrets
from dataclasses import dataclass, field

MODE = "invite_v1"
INVITE_LIFETIME_SECONDS = 15 * 60
ROLES = {"sender", "receiver"}
DELETED_MODE = "deleted_v1"


class AuthorizationError(Exception):
    def __init__(self):
        super().__init__("pairing authorization failed")


@dataclass(frozen=True)
class DeviceGrant:
    device_id: str
    device_token: str = field(repr=False)


@dataclass(frozen=True)
class InviteGrant:
    role: str
    expires_at: int
    secret: str = field(repr=False)


def _digest(secret):
    if not isinstance(secret, str) or not 1 <= len(secret) <= 128 or not secret.isascii():
        raise AuthorizationError()
    return hashlib.sha256(secret.encode("ascii")).hexdigest()


def _role(role):
    if not isinstance(role, str) or role not in ROLES:
        raise AuthorizationError()


def _time(now):
    if type(now) is not int or now < 0:
        raise ValueError("invalid server time")


def authorize_deletion(record, device_id, token):
    if (not isinstance(record, dict) or record.get("security_mode") != DELETED_MODE
            or not isinstance(device_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{22}", device_id)
            or device_id != record.get("owner_device_id")
            or type(record.get("cleanup_pending")) is not bool
            or not isinstance(record.get("owner_token_hash"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", record["owner_token_hash"])
            or not hmac.compare_digest(record["owner_token_hash"], _digest(token))):
        raise AuthorizationError()



class SecurePairing:
    def __init__(self, record):
        self._record = copy.deepcopy(record)

    @classmethod
    def create(cls, creator_role, now):
        _role(creator_role)
        _time(now)
        grant = DeviceGrant(secrets.token_urlsafe(16), secrets.token_urlsafe(32))
        target_role = "receiver" if creator_role == "sender" else "sender"
        invite = InviteGrant(target_role, now + INVITE_LIFETIME_SECONDS, secrets.token_urlsafe(32))
        record = {
            "security_mode": MODE,
            "created_at": now,
            "owner_device_id": grant.device_id,
            "credentials": {grant.device_id: {
                "role": creator_role, "token_hash": _digest(grant.device_token),
                "created_at": now, "revoked": False,
            }},
            "invite": {
                "role": invite.role, "secret_hash": _digest(invite.secret),
                "expires_at": invite.expires_at, "used": False,
            },
        }
        return cls(record), grant, invite

    def to_record(self):
        return copy.deepcopy(self._record)

    @classmethod
    def from_record(cls, record):
        """Strict restoration: corrupt/unknown secure versions fail closed."""
        try:
            if not isinstance(record, dict) or record.get("security_mode") != MODE:
                raise ValueError()
            _time(record["created_at"])
            credentials = record["credentials"]
            if not isinstance(credentials, dict) or not 1 <= len(credentials) <= 4:
                raise ValueError()
            if record["owner_device_id"] not in credentials:
                raise ValueError()
            active_roles = []
            for device_id, credential in credentials.items():
                if not isinstance(device_id, str) or not re.fullmatch(r"[A-Za-z0-9_-]{22}", device_id):
                    raise ValueError()
                _role(credential["role"])
                _time(credential["created_at"])
                if not re.fullmatch(r"[0-9a-f]{64}", credential["token_hash"]):
                    raise ValueError()
                if type(credential["revoked"]) is not bool:
                    raise ValueError()
                if not credential["revoked"]:
                    active_roles.append(credential["role"])
            if len(active_roles) != len(set(active_roles)):
                raise ValueError()
            invite = record["invite"]
            _role(invite["role"])
            _time(invite["expires_at"])
            if (invite["role"] == credentials[record["owner_device_id"]]["role"]
                    or invite["expires_at"] != record["created_at"] + INVITE_LIFETIME_SECONDS):
                raise ValueError()
            if not re.fullmatch(r"[0-9a-f]{64}", invite["secret_hash"]) or type(invite["used"]) is not bool:
                raise ValueError()
        except (KeyError, TypeError, ValueError, AuthorizationError):
            raise ValueError("invalid secure pairing record") from None
        return cls(record)

    def authorize(self, device_id, token, role):
        _role(role)
        if not isinstance(device_id, str):
            raise AuthorizationError()
        credential = self._record["credentials"].get(device_id)
        presented = _digest(token)
        if (credential is None or credential["revoked"] or credential["role"] != role
                or not hmac.compare_digest(credential["token_hash"], presented)):
            raise AuthorizationError()
        return device_id

    def join(self, secret, role, now):
        _role(role)
        _time(now)
        invite = self._record["invite"]
        if (invite["used"] or now >= invite["expires_at"] or invite["role"] != role
                or not hmac.compare_digest(invite["secret_hash"], _digest(secret))):
            raise AuthorizationError()
        credentials = self._record["credentials"]
        if any(item["role"] == role and not item["revoked"] for item in credentials.values()):
            raise AuthorizationError()
        if len(credentials) >= 4:
            raise AuthorizationError()
        grant = DeviceGrant(secrets.token_urlsafe(16), secrets.token_urlsafe(32))
        if grant.device_id in credentials:
            raise AuthorizationError()
        credentials[grant.device_id] = {
            "role": role, "token_hash": _digest(grant.device_token),
            "created_at": now, "revoked": False,
        }
        invite["used"] = True
        return grant

    def authorize_owner(self, owner_id, owner_token):
        owner = self._record["owner_device_id"]
        if owner_id != owner:
            raise AuthorizationError()
        owner_role = self._record["credentials"][owner]["role"]
        self.authorize(owner_id, owner_token, owner_role)
        return owner_id

    def revoke(self, owner_id, owner_token, device_id):
        self.authorize_owner(owner_id, owner_token)
        if not isinstance(device_id, str) or device_id not in self._record["credentials"]:
            raise AuthorizationError()
        self._record["credentials"][device_id]["revoked"] = True
        return self._record["credentials"][device_id]["role"]

    def deletion_identity(self, owner_id, owner_token):
        self.authorize_owner(owner_id, owner_token)
        return {"security_mode": DELETED_MODE, "owner_device_id": owner_id,
                "owner_token_hash": self._record["credentials"][owner_id]["token_hash"],
                "cleanup_pending": True}

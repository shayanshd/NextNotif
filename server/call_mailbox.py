"""Durable, idempotent outgoing-call requests."""
import re
import time

TTL_MS = 120000
FINAL = {"failed", "ended", "expired"}


def now_ms():
    return int(time.time() * 1000)


def valid(command, now=None):
    now = now_ms() if now is None else now
    created = command.get("created_at") if isinstance(command, dict) else None
    return (isinstance(command, dict)
            and isinstance(command.get("id"), str) and re.fullmatch(r"[a-f0-9-]{36}", command["id"])
            and isinstance(command.get("to"), str) and re.fullmatch(r"\+?[0-9]{3,20}", command["to"])
            and (command.get("subscription_id") is None or
                 type(command["subscription_id"]) is int and 0 <= command["subscription_id"] <= 2147483647)
            and type(created) is int and now - TTL_MS < created <= now + 300000)


def submit(records, command, now=None):
    now = now_ms() if now is None else now
    if not isinstance(command, dict):
        return 400, None
    old = next((x for x in records if x["id"] == command.get("id")), None)
    keys = ("to", "subscription_id", "created_at")
    if old:
        return (200, old) if all(old.get(k) == command.get(k) for k in keys) else (409, None)
    if not valid(command, now):
        return 400, None
    if any(x["status"] not in FINAL for x in records):
        return 409, None
    record = {k: command.get(k) for k in ("id", "to", "subscription_id", "created_at")}
    record.update(status="queued", detail="", expires_at=command["created_at"] + TTL_MS)
    records[:] = [x for x in records if x.get("created_at", 0) > now - 172800000]
    records.append(record)
    return 200, record


def expire(records, now=None):
    now = now_ms() if now is None else now
    for record in records:
        if record["status"] == "queued" and record["expires_at"] <= now:
            record.update(status="expired", detail="Sender did not place the call before it expired")


def update(records, result):
    allowed = {"dialing", "connected", "failed", "ended"}
    if not isinstance(result, dict) or result.get("status") not in allowed:
        return False
    record = next((x for x in records if x["id"] == result.get("id")), None)
    if record is None:
        return False
    if record["status"] in FINAL:
        return True
    record.update(status=result["status"], detail=str(result.get("detail", ""))[:240])
    return True


def cancel_active(records, detail="Receiver started a new call"):
    changed = False
    for record in records:
        if record["status"] not in FINAL:
            record.update(status="ended", detail=detail[:240])
            changed = True
    return changed

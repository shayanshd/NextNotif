"""Pure validation/state transitions shared by the HTTP SMS command endpoints."""
import re
import time

TTL_MS = 3600000
RETENTION_MS = 172800000
STATES = {"sending", "sent", "failed", "unknown"}


def now_ms():
    return int(time.time() * 1000)


def submit(records, command, now=None):
    now = now_ms() if now is None else now
    if not isinstance(command, dict):
        return 400, None
    old = next((x for x in records if x["id"] == command.get("id")), None)
    if old:
        return (200, old) if all(old.get(k) == command.get(k) for k in ("to", "body", "created_at", "subscription_id")) else (409, None)
    created = command.get("created_at")
    if not (isinstance(command.get("id"), str) and re.fullmatch(r"[a-f0-9-]{36}", command["id"])
            and isinstance(command.get("to"), str) and re.fullmatch(r"\+?[0-9]{3,20}", command["to"])
            and isinstance(command.get("body"), str) and command["body"].strip() and len(command["body"].encode('utf-16-le')) // 2 <= 1600
            and (command.get("subscription_id") is None or (type(command["subscription_id"]) is int and 0 <= command["subscription_id"] <= 2147483647))
            and type(created) is int and now - TTL_MS < created <= now + 300000):
        return 400, None
    records[:] = [x for x in records if x["created_at"] > now - RETENTION_MS]
    if len(records) >= 100:
        return 429, None
    record = {k: command[k] for k in ("id", "to", "body", "created_at")}
    record.update(subscription_id=command.get("subscription_id"), status="queued", detail="", expires_at=created + TTL_MS)
    records.append(record)
    return 200, record


def expire(records, now=None):
    now = now_ms() if now is None else now
    for x in records:
        if x["status"] == "queued" and x["expires_at"] <= now:
            x.update(status="expired", detail="Sender did not send before the request expired")


def update(records, result):
    if not isinstance(result, dict) or result.get("status") not in STATES:
        return False
    record = next((x for x in records if x["id"] == result.get("id")), None)
    if record is None:
        return False
    if record["status"] in {"sent", "failed"}:
        return True
    if record["status"] in {"unknown", "expired"} and result["status"] not in {"sent", "failed"}:
        return True
    record.update(status=result["status"], detail=str(result.get("detail", ""))[:240])
    return True


def sim_options(value, now=None):
    if not isinstance(value, dict) or value.get("state") not in {"ready", "permission_required", "unavailable"}:
        return None
    sims = value.get("sims")
    if not isinstance(sims, list) or len(sims) > 8:
        return None
    ids = set()
    clean = []
    for sim in sims:
        if not isinstance(sim, dict) or not (type(sim.get("id")) is int and 0 <= sim["id"] <= 2147483647
                and sim["id"] not in ids and type(sim.get("slot")) is int and 0 <= sim["slot"] <= 7
                and isinstance(sim.get("name"), str) and len(sim["name"].encode("utf-16-le")) // 2 <= 80
                and isinstance(sim.get("carrier"), str) and len(sim["carrier"].encode("utf-16-le")) // 2 <= 80):
            return None
        ids.add(sim["id"])
        clean.append({k: sim[k] for k in ("id", "slot", "name", "carrier")})
    default = value.get("default_id")
    if default is not None and (type(default) is not int or default not in ids):
        return None
    if value["state"] != "ready" and sims:
        return None
    return dict(state=value["state"], sims=clean, default_id=default, updated_at=now_ms() if now is None else now)

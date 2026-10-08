"""Local workerd test for invite-paired WebSocket authorization and delivery.

Starts the non-deployable security-test Worker with Wrangler. Requires the
server virtualenv's websockets package and a local Wrangler binary on PATH.
"""

import asyncio
import inspect
import json
import os
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

if os.environ.get("NEXTNOTIF_SECURITY_RETIRE_TEST") != "1":
    import websockets


HERE = Path(__file__).resolve().parent
PORT = 8788
BASE = f"http://127.0.0.1:{PORT}"
HEADER_KEY = (
    "additional_headers"
    if os.environ.get("NEXTNOTIF_SECURITY_RETIRE_TEST") == "1"
    or "additional_headers" in inspect.signature(websockets.connect).parameters
    else "extra_headers"
)


def credentials(device):
    return {
        "X-NextNotif-Device-Id": device["device_id"],
        "X-NextNotif-Token": device["device_token"],
    }


def fixture():
    with urllib.request.urlopen(BASE + "/secure-ws-fixture", timeout=15) as response:
        assert response.status == 200
        return json.load(response)


def runtime_checks():
    for route in ("/test", "/http-test", "/routes-test", "/rate-test",
                  "/legacy-migration-test", "/maintenance-route-test"):
        with urllib.request.urlopen(BASE + route, timeout=15) as response:
            assert response.status == 200 and json.load(response)["ok"] is True


async def denied(uri, headers):
    try:
        async with websockets.connect(uri, **{HEADER_KEY: headers}):
            raise AssertionError("unauthorized WebSocket upgrade accepted")
    except websockets.exceptions.InvalidStatus:
        pass


async def authenticate(ws, code, queue_sync=False):
    await ws.send(json.dumps({"type": "hello", "device_name": "local security fixture"}))
    challenge = json.loads(await asyncio.wait_for(ws.recv(), 5))
    assert challenge["type"] == "handshake" and challenge["token"]
    await ws.send(json.dumps({"type": "auth", "token": challenge["token"], "code": code,
                              "queue_sync": queue_sync}))
    accepted = json.loads(await asyncio.wait_for(ws.recv(), 5))
    assert accepted == {"type": "auth_ok"}
    if queue_sync:
        ready = json.loads(await asyncio.wait_for(ws.recv(), 5))
        assert ready["type"] == "queue_ready"


def post(operation, code, device, body=None):
    request = urllib.request.Request(
        BASE + "/" + operation,
        data=json.dumps(body or {}).encode(),
        headers={"X-NextNotif-Code": code, "Content-Type": "application/json", **credentials(device)},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        assert response.status == 200
        return json.load(response)


async def test_durable_ws_replay():
    pair = fixture()
    code = pair["code"]
    receiver_uri = f"ws://127.0.0.1:{PORT}/ws/receiver/{code}"
    sender_uri = f"ws://127.0.0.1:{PORT}/ws/sender/{code}"
    async with websockets.connect(receiver_uri, **{HEADER_KEY: credentials(pair["receiver"])}) as receiver:
        await authenticate(receiver, code, queue_sync=True)
        async with websockets.connect(sender_uri, **{HEADER_KEY: credentials(pair["sender"])}) as sender:
            await authenticate(sender, code)
            await sender.send(json.dumps({"type": "relay_test", "data": {"marker": "durable-ws"}}))
            delivered = json.loads(await asyncio.wait_for(receiver.recv(), 5))
            assert delivered["type"] == "relay_test"
            snapshot = await asyncio.to_thread(post, "fetch", code, pair["receiver"])
            assert len(snapshot["events"]) == 1
            assert snapshot["events"][0]["event_id"] == delivered["event_id"]
            event_id = delivered["event_id"]
    # A receiver crash before ACK must leave the same event available.
    async with websockets.connect(receiver_uri, **{HEADER_KEY: credentials(pair["receiver"])}) as restarted:
        await authenticate(restarted, code, queue_sync=True)
        snapshot = await asyncio.to_thread(post, "fetch", code, pair["receiver"])
        assert [event["event_id"] for event in snapshot["events"]] == [event_id]
        result = await asyncio.to_thread(post, "ack", code, pair["receiver"], {"event_ids": [event_id]})
        assert result["acknowledged"] == 1
        assert (await asyncio.to_thread(post, "fetch", code, pair["receiver"]))["events"] == []
    print("durable WebSocket fetch, crash replay, and acknowledgment passed")



async def test():
    pair = fixture()
    code = pair["code"]
    receiver_uri = f"ws://127.0.0.1:{PORT}/ws/receiver/{code}"
    sender_uri = f"ws://127.0.0.1:{PORT}/ws/sender/{code}"
    await denied(receiver_uri, {})
    await denied(receiver_uri, credentials(pair["sender"]))
    await denied(receiver_uri, {
        **credentials(pair["receiver"]), "X-NextNotif-Token": "wrong"
    })

    async with websockets.connect(
        receiver_uri, **{HEADER_KEY: credentials(pair["receiver"])}
    ) as malformed:
        await malformed.send(json.dumps({"type": "hello"}))
        challenge = json.loads(await asyncio.wait_for(malformed.recv(), 5))
        assert challenge["type"] == "handshake"
        await malformed.send(json.dumps({"type": "auth", "token": "wrong", "code": code}))
        try:
            await asyncio.wait_for(malformed.recv(), 5)
            raise AssertionError("bad handshake was accepted")
        except websockets.exceptions.ConnectionClosed as closed:
            assert closed.code == 1008

    async with websockets.connect(
        receiver_uri, **{HEADER_KEY: credentials(pair["receiver"])}
    ) as receiver:
        await authenticate(receiver, code)
        # Rejected upgrades must leave the authenticated incumbent connected.
        await denied(receiver_uri, {})
        async with websockets.connect(
            sender_uri, **{HEADER_KEY: credentials(pair["sender"])}
        ) as sender:
            await authenticate(sender, code)
            await sender.send(json.dumps({"type": "relay_test", "data": {"marker": "secure-ws"}}))
            delivered = json.loads(await asyncio.wait_for(receiver.recv(), 5))
            assert delivered["type"] == "relay_test"
            assert delivered["data"]["marker"] == "secure-ws"

            def remove_pairing():
                request = urllib.request.Request(
                    BASE + "/pair/secure-delete",
                    data=b"{}",
                    headers={"X-NextNotif-Code": code, **credentials(pair["sender"])},
                    method="POST",
                )
                with urllib.request.urlopen(request, timeout=15) as response:
                    assert response.status == 200 and json.load(response)["deleted"] is True

            await asyncio.to_thread(remove_pairing)
            for socket in (sender, receiver):
                try:
                    await asyncio.wait_for(socket.recv(), 5)
                    raise AssertionError("deleted pairing socket remained open")
                except websockets.exceptions.ConnectionClosed:
                    pass

    await denied(receiver_uri, credentials(pair["receiver"]))
    await denied(sender_uri, credentials(pair["sender"]))

    print("secure WebSocket denial, handshake, delivery, and former-device reconnect denial passed")


def main():
    wrangler = os.environ.get("NEXTNOTIF_WRANGLER", "wrangler")
    config = os.environ.get("NEXTNOTIF_SECURITY_TEST_CONFIG", "wrangler.security-test.jsonc")
    proc = subprocess.Popen(
        [wrangler, "dev", "--config", config, "--local", "--port", str(PORT)],
        cwd=HERE, stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
    )
    try:
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if proc.poll() is not None:
                raise RuntimeError("local Wrangler process exited before startup")
            try:
                with socket.create_connection(("127.0.0.1", PORT), timeout=0.5):
                    break
            except OSError:
                time.sleep(0.2)
        else:
            raise RuntimeError("local Wrangler process did not open its port")
        if os.environ.get("NEXTNOTIF_SECURITY_RETIRE_TEST") == "1":
            with urllib.request.urlopen(BASE + "/legacy-retirement-test", timeout=15) as response:
                assert response.status == 200 and json.load(response)["ok"] is True
        else:
            runtime_checks()
            asyncio.run(test())
            asyncio.run(test_durable_ws_replay())
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=5)


if __name__ == "__main__":
    main()

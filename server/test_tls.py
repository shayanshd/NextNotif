"""TLS smoke test: proves the relay serves and relays over TLS/wss.

Self-contained: generates a short-lived self-signed cert, starts uvicorn with
SSL on a free port, then does pairing over https and a full WS handshake
(including device-token issuance) plus an SMS relay over wss://.

    .venv/bin/python test_tls.py
"""

import asyncio
import json
import os
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time

import httpx
import uvicorn
import websockets


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


async def main() -> int:
    tmp = tempfile.mkdtemp(prefix="nn-tls-")
    cert, key = os.path.join(tmp, "cert.pem"), os.path.join(tmp, "key.pem")
    subprocess.run(
        [
            "openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes",
            "-keyout", key, "-out", cert, "-days", "2", "-batch",
            "-subj", "/CN=127.0.0.1",
            "-addext", "subjectAltName=IP:127.0.0.1",
        ],
        check=True,
        capture_output=True,
    )

    ctx = ssl.create_default_context(cafile=cert)
    ctx.check_hostname = False

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    sys.modules.pop("main", None)
    import main as relay_main

    pairings = os.path.join(tmp, "pairings.json")
    relay_main.registry.__init__(pairings)

    port = free_port()
    base = f"https://127.0.0.1:{port}"
    server = uvicorn.Server(
        uvicorn.Config(
            relay_main.app, host="127.0.0.1", port=port, log_level="warning",
            ssl_keyfile=key, ssl_certfile=cert,
        )
    )
    threading.Thread(target=server.run, daemon=True).start()
    deadline = time.monotonic() + 20
    async with httpx.AsyncClient(verify=cert) as client:
        ready = False
        while time.monotonic() < deadline:
            try:
                if (await client.get(base + "/")).status_code == 200:
                    ready = True
                    break
            except Exception:
                pass
            await asyncio.sleep(0.2)
    if not ready:
        print("FAIL: TLS server did not become ready")
        return 1
    print(f"[ok] uvicorn serving TLS on port {port}")

    async with httpx.AsyncClient(verify=cert) as client:
        r = await client.post(base + "/pair/create")
        code = r.json()["code"]
    print(f"[ok] https pairing created: {code}")

    wss = f"wss://127.0.0.1:{port}"
    sender = await websockets.connect(f"{wss}/ws/sender/{code}", ssl=ctx)
    receiver = await websockets.connect(f"{wss}/ws/receiver/{code}", ssl=ctx)

    async def handshake(ws):
        await ws.send(json.dumps({"type": "hello"}))
        hs = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
        await ws.send(json.dumps({"type": "auth", "token": hs["token"], "code": code}))
        ok = json.loads(await asyncio.wait_for(ws.recv(), timeout=5))
        assert ok.get("type") == "auth_ok" and ok.get("device_token"), ok
        return ok["device_token"]

    dt = await handshake(sender)
    await handshake(receiver)
    print(f"[ok] wss handshake + device token over TLS ({len(dt)} chars)")

    await sender.send(json.dumps({"type": "sms", "data": {"from": "+15550001111", "body": "tls-hello", "ts": 1}}))
    got = json.loads(await asyncio.wait_for(receiver.recv(), timeout=5))
    assert got["data"]["body"] == "tls-hello", got
    print("[ok] SMS relayed over wss")

    await sender.close()
    await receiver.close()
    server.should_exit = True
    print("\nALL TLS CHECKS PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

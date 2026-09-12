"""Local-only, ephemeral signaling fixture for the opt-in no-audio phone probe.

No production pairing, credentials, call data, audio, files, or durable queues.
"""
import argparse
import ipaddress
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def candidate_bucket(candidate):
    """Fixed aggregate categories only; never expose candidate addresses or SDP."""
    fields = candidate.split() if isinstance(candidate, str) else []
    if len(fields) < 8:
        return "malformed"
    protocol = fields[2].lower()
    protocol = protocol if protocol in {"udp", "tcp"} else "other"
    try:
        kind = fields[fields.index("typ") + 1]
    except (ValueError, IndexError):
        kind = "other"
    kind = kind if kind in {"host", "srflx", "relay", "prflx"} else "other"
    try:
        address = ipaddress.ip_address(fields[4])
        scope = "loopback" if address.is_loopback else "private" if address.is_private else "public"
        family = f"v{address.version}"
    except ValueError:
        family, scope = "name", "unknown"
    return f"{protocol}_{kind}_{family}_{scope}"


def serve(session, port):
    lock = threading.Lock()
    queues = {"sender": [], "receiver": []}
    ready, connected, verified = set(), set(), set()
    metrics = {role: {"offer": 0, "answer": 0, "ice": 0, "consumed": 0, "candidate_types": {}}
               for role in queues}

    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)

        def log_message(self, *args):
            pass

        def reply(self, status, data):
            body = json.dumps(data).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def dispatch(self, post):
            role = self.headers.get("X-Probe-Role")
            if role not in queues or self.headers.get("X-Probe-Session") != session:
                return self.reply(403, {"error": "invalid probe"})
            other = "receiver" if role == "sender" else "sender"
            data = None
            if post:
                try:
                    size = int(self.headers.get("Content-Length", "0"))
                    if not 0 < size <= 262144:
                        raise ValueError()
                    data = json.loads(self.rfile.read(size))
                    if not isinstance(data, dict):
                        raise ValueError()
                    if self.path == "/signals":
                        allowed = {"ice", "answer" if role == "sender" else "offer"}
                        if data.get("session_id") != session or data.get("kind") not in allowed:
                            raise ValueError()
                except (ValueError, TypeError):
                    return self.reply(400, {"error": "invalid signal"})
            with lock:
                if self.path == "/ready":
                    if post:
                        ready.add(role)
                    result = {"sender": "sender" in ready, "both": len(ready) == 2}
                elif self.path == "/connected":
                    if post:
                        if data.get("connected") is True:
                            connected.add(role)
                        else:
                            connected.discard(role)
                    result = {"both": len(connected) == 2}
                elif self.path == "/verified":
                    if post:
                        verified.add(role)
                    result = {"both": len(verified) == 2}
                elif self.path == "/signals":
                    if post:
                        if len(queues[other]) >= 128:
                            return self.reply(429, {"error": "probe overflow"})
                        queues[other].append(data)
                        kind = data["kind"]
                        metrics[role][kind] += 1
                        if kind == "ice":
                            bucket = candidate_bucket(data.get("candidate"))
                            counts = metrics[role]["candidate_types"]
                            counts[bucket] = counts.get(bucket, 0) + 1
                        result = {"ok": True}
                    else:
                        result = {"signals": queues[role][:]}
                        metrics[role]["consumed"] += len(queues[role])
                        queues[role].clear()
                elif self.path == "/diagnostics" and not post:
                    result = {"metrics": metrics, "queued": {role: len(queue) for role, queue in queues.items()}}
                else:
                    return self.reply(404, {"error": "unknown probe route"})
            self.reply(200, result)

        def do_GET(self):
            self.dispatch(False)

        def do_POST(self):
            self.dispatch(True)

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    expiry = threading.Timer(300, server.shutdown)
    expiry.daemon = True
    expiry.start()
    print(f"No-audio probe signaling listening on localhost:{port}", flush=True)
    try:
        server.serve_forever()
    finally:
        expiry.cancel()
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--session", required=True)
    parser.add_argument("--port", type=int, default=8769)
    args = parser.parse_args()
    serve(args.session, args.port)

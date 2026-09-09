# TLS / wss deployment guide

The relay is a WebSocket server on `127.0.0.1:8000` by default. To let the app
connect over `wss://`, put it behind a reverse proxy that terminates TLS.

## 1. Caddy (simplest)

Caddy gets automatic HTTPS (Let's Encrypt) and handles WebSocket upgrades
automatically — no extra directives needed.

`/etc/caddy/Caddyfile`:

```
relay.example.com {
    reverse_proxy 127.0.0.1:8000
}
```

Then `sudo systemctl reload caddy`.

## 2. nginx

Equivalent `server` block (issue certs with e.g. `certbot certonly --nginx -d relay.example.com`,
or point the paths at certs you already have):

```nginx
server {
    listen 443 ssl;
    server_name relay.example.com;

    ssl_certificate     /etc/letsencrypt/live/relay.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/relay.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8000;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        # Long timeouts so idle relayed websockets survive:
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```

Then `sudo nginx -t && sudo systemctl reload nginx`.

## 3. Cloudflare Workers (no proxy needed)

The `server-workers` deploy is already served over TLS at
`wss://<your-worker>.workers.dev` — there is nothing to configure for TLS.

## 4. App side

Set the server URL in the app to `wss://relay.example.com` — the "Server" field
on the SetupScreen (settings dialog).

The pairing calls `GET /pair/{code}/status` and `POST /pair/create` are made
over plain HTTP(S) by the app, which rewrites the `ws(s)://` prefix of the
configured server URL to `http(s)://` (see `MainActivity.kt`). So they
automatically follow TLS: `wss://relay.example.com` becomes
`https://relay.example.com` for those calls. No app changes needed.

`usesCleartextTraffic="true"` in `AndroidManifest.xml` can only be removed once
**every** deployment is `wss://`. **Leave it for now**: LAN `ws://` (default
`ws://10.0.2.2:8000`) is still the development default, and removing it would
break plain-WebSocket dev connections.

## Gotchas

- Certs for private LAN IPs are not trusted by Android by default — you need a
  locally trusted CA (installed on the device) or certificate pinning;
  Let's Encrypt only issues for public domains/IPs.
- The pairing code bootstraps the connection, but the relay credential is the
  server-issued device token (32-char, per pairing, see the WS auth protocol).
  TLS still matters: it protects the code, the handshake tokens, and the
  device token in transit.
- Firewalls must allow inbound 443/tcp on the proxy host (and 80/tcp if the
  proxy also serves HTTP or uses HTTP-01 challenges).
- Don't expose the relay port 8000 directly — keep it bound behind the proxy
  (or firewall it) so only the TLS endpoint is reachable.
- Idle websockets are dropped by short proxy timeouts — keep
  `proxy_read_timeout`/`proxy_send_timeout` long (e.g. 3600s) as shown above.

## Verifying without a real cert

`server/test_tls.py` is self-contained: it generates a short-lived self-signed
cert, starts uvicorn with SSL on a free port, and verifies https pairing, the
WS handshake + device-token issuance, and an SMS relay over `wss://`.

    .venv/bin/python test_tls.py

import { DurableObject } from 'cloudflare:workers';

const CODE_RE = /^\d{6}$/;
const AUTH_TIMEOUT_MS = 5000;
const MAX_DEVICE_TOKENS = 4;
const MAX_QUEUED = 50;
// One wake per pairing/role per window: events pile up in the queue meanwhile,
// so a single reconnect is all the catch-up needs.
const FCM_WAKE_COOLDOWN_MS = 30_000;
const FCM_SCOPE = 'https://www.googleapis.com/auth/firebase.messaging';
const FCM_TOKEN_MIN_LEN = 20;
const FCM_TOKEN_MAX_LEN = 1024;

function isValidCode(v) {
  return typeof v === 'string' && CODE_RE.test(v);
}

function isValidFcmToken(v) {
  // FCM tokens are single base64url-ish strings; reject padded/embedded blanks.
  return (
    typeof v === 'string' &&
    v.length >= FCM_TOKEN_MIN_LEN &&
    v.length <= FCM_TOKEN_MAX_LEN &&
    v === v.trim() &&
    !v.includes(' ')
  );
}

// Title/body for the system-shown notification (the app process is dead in
// that case, so the OS-rendered notification is the only record of the event).
function notificationFor(evt) {
  const data = evt && typeof evt.data === 'object' && evt.data !== null ? evt.data : {};
  if (evt && evt.type === 'sms') {
    const from = String(data.from || 'unknown');
    const label = data.name ? `${data.name} (${from})` : from;
    return { title: `NextNotif: Text from ${label}`, body: String(data.body || '').slice(0, 200) };
  }
  if (evt && evt.type === 'call') {
    const number = String(data.number || 'unknown');
    const label = data.name ? `${data.name} (${number})` : number;
    const title =
      data.state === 'RINGING'
        ? `NextNotif: Incoming call from ${label}`
        : data.state === 'OFFHOOK'
          ? `NextNotif: Call from ${label} connected`
          : data.state === 'IDLE'
            ? `NextNotif: Call from ${label} ended`
            : `NextNotif: Call ${data.state || ''} from ${label}`.trim();
    return { title, body: number };
  }
  return { title: `NextNotif: ${evt && evt.type ? evt.type : 'event'}`, body: '' };
}

// Forward a request (WS upgrade or otherwise) to the pairing's DO instance.
// The DO only understands the path form /ws/<role>/<code>, so normalize the
// header-form /ws/<role> URL onto it before forwarding.
async function forwardToPairing(env, request, code, role) {
  const url = new URL(request.url);
  url.pathname = `/ws/${role}/${code}`;
  const inst = env.PAIRING.get(env.PAIRING.idFromName(code));
  return inst.fetch(new Request(url, request));
}

// Fire-and-forget sender uplink: forward a POST /send onto the pairing's DO.
// The code rides the path (the forwarder normalizes both the path form and
// the header form onto /send/<code>), so the DO handler needs no header logic.
// The body is materialized first: the DO must get its own stream, or a
// rejected request.json() inside the DO leaks an uncaught stream error.
async function forwardSend(env, request, code) {
  const url = new URL(request.url);
  url.pathname = `/send/${code}`;
  const body = await request.text();
  const inst = env.PAIRING.get(env.PAIRING.idFromName(code));
  const fwd = new Request(url, { method: request.method, headers: request.headers, body });
  return inst.fetch(fwd);
}

// 16 random bytes, base64url-encoded (same shape as Python's secrets.token_urlsafe(16)).
function randomToken() {
  return randomUrlSafe(16);
}

// High-entropy per-device credential (24 bytes, like Python's token_urlsafe(24)).
function randomDeviceToken() {
  return randomUrlSafe(24);
}

function randomUrlSafe(bytes) {
  const arr = crypto.getRandomValues(new Uint8Array(bytes));
  let bin = '';
  for (let i = 0; i < arr.length; i += 1) bin += String.fromCharCode(arr[i]);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlString(s) {
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlBytes(u8) {
  let bin = '';
  for (let i = 0; i < u8.length; i += 1) bin += String.fromCharCode(u8[i]);
  return b64urlString(bin);
}

async function importSaKey(pem) {
  const b64 = pem.replace(/-----(BEGIN|END)[^-]+-----/g, '').replace(/\s+/g, '');
  const bin = atob(b64);
  const der = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i += 1) der[i] = bin.charCodeAt(i);
  return crypto.subtle.importKey('pkcs8', der, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, [
    'sign',
  ]);
}

// Self-signed RS256 JWT in the OAuth 2.0 service-account grant format.
async function fcmJwt(sa, tokenUrl) {
  const now = Math.floor(Date.now() / 1000);
  const header = b64urlString(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const payload = b64urlString(
    JSON.stringify({ iss: sa.client_email, scope: FCM_SCOPE, aud: tokenUrl, iat: now, exp: now + 3600 }),
  );
  const key = await importSaKey(sa.private_key);
  const sig = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(`${header}.${payload}`));
  return `${header}.${payload}.${b64urlBytes(new Uint8Array(sig))}`;
}

export class RelayPairing extends DurableObject {
  constructor(state, env) {
    super(state, env);
    this.state = state;
    this.env = env;
    this.code = null;
    this.accepted = { sender: null, receiver: null };
    this.timers = {};
    // Cached OAuth token (in-memory; the cooldown is in DO storage).
    this._fcmToken = null;
    this._fcmTokenExpiry = 0;
  }

  liveSockets() {
    // Only OPEN sockets count: a just-evicted or half-closed holder can still be
    // listed by getWebSockets() for a moment, and relaying into it drops events.
    const pick = (role) =>
      this.state.getWebSockets(role).find((w) => w.readyState === WebSocket.OPEN) || null;
    return { sender: pick('sender'), receiver: pick('receiver') };
  }

  roleOf(ws) {
    if (this.accepted.sender === ws) return 'sender';
    if (this.accepted.receiver === ws) return 'receiver';
    const sockets = this.liveSockets();
    if (sockets.sender === ws) return 'sender';
    if (sockets.receiver === ws) return 'receiver';
    return null;
  }

  // Atomic: serialized per-instance, so only one caller can claim a fresh code.
  async tryClaim() {
    const existing = await this.state.storage.get('paired');
    if (existing != null) return false;
    await this.state.storage.put('paired', '1');
    return true;
  }

  async getStatus() {
    const paired = await this.state.storage.get('paired');
    const names = await this.getNames();
    const fcm = await this.getFcm();
    const sockets = this.liveSockets();
    return {
      exists: paired != null,
      // Only OPEN sockets count: a closing/replaced holder stays in
      // getWebSockets() for a moment and would flash "Online" for the partner.
      sender_connected: sockets.sender !== null,
      receiver_connected: sockets.receiver !== null,
      sender_name: names.sender ?? null,
      receiver_name: names.receiver ?? null,
      sender_has_fcm: fcm.sender != null,
      receiver_has_fcm: fcm.receiver != null,
    };
  }

  async getNames() {
    const raw = await this.state.storage.get('names');
    try {
      return raw ? JSON.parse(raw) : {};
    } catch {
      return {};
    }
  }

  async putNames(names) {
    await this.state.storage.put('names', JSON.stringify(names));
  }

  async getPending() {
    const raw = await this.state.storage.get('pending');
    try {
      return raw ? JSON.parse(raw) : {};
    } catch {
      return {};
    }
  }

  async putPending(pending) {
    await this.state.storage.put('pending', JSON.stringify(pending));
  }

  async getTokens() {
    const raw = await this.state.storage.get('tokens');
    try {
      return raw ? JSON.parse(raw) : [];
    } catch {
      return [];
    }
  }

  async putTokens(tokens) {
    await this.state.storage.put('tokens', JSON.stringify(tokens));
  }

  async getQueue() {
    const raw = await this.state.storage.get('queue');
    try {
      return raw ? JSON.parse(raw) : [];
    } catch {
      return [];
    }
  }

  async putQueue(queue) {
    await this.state.storage.put('queue', JSON.stringify(queue));
  }

  async getFcm() {
    const raw = await this.state.storage.get('fcm');
    try {
      const fcm = raw ? JSON.parse(raw) : {};
      const out = {};
      if (isValidFcmToken(fcm.sender)) out.sender = fcm.sender;
      if (isValidFcmToken(fcm.receiver)) out.receiver = fcm.receiver;
      return out;
    } catch {
      return {};
    }
  }

  // Cooldown timestamps live in DO storage (not just memory): a local-dev DO
  // can hibernate between two requests, and a woken instance must not
  // re-wake a phone that was just woken by its predecessor.
  async getLastWake() {
    const raw = await this.state.storage.get('lastWake');
    try {
      return raw ? JSON.parse(raw) : {};
    } catch {
      return {};
    }
  }

  async setLastWake(role, ts) {
    const last = await this.getLastWake();
    last[role] = ts;
    await this.state.storage.put('lastWake', JSON.stringify(last));
  }

  async storeFcmToken(role, value) {
    if ((role !== 'sender' && role !== 'receiver') || !isValidFcmToken(value)) return;
    const fcm = await this.getFcm();
    if (fcm[role] === value) return;
    fcm[role] = value;
    await this.state.storage.put('fcm', JSON.stringify(fcm));
  }

  async fcmAccessToken(sa) {
    if (this._fcmToken && this._fcmTokenExpiry > Date.now()) return this._fcmToken;
    const tokenUrl = this.env.FCM_TOKEN_URL || 'https://oauth2.googleapis.com/token';
    const assertion = await fcmJwt(sa, tokenUrl);
    const resp = await fetch(tokenUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${encodeURIComponent(assertion)}`,
    });
    if (!resp.ok) throw new Error(`token endpoint ${resp.status}`);
    const body = await resp.json();
    if (!body.access_token) throw new Error('token endpoint returned no access_token');
    this._fcmToken = body.access_token;
    this._fcmTokenExpiry = Date.now() + (Number(body.expires_in) || 3600) * 1000 - 50_000;
    return this._fcmToken;
  }

  // Best-effort kill-recovery wake: the receiver's socket is dead, so a
  // high-priority FCM message goes to its registered token — the OS wakes the
  // phone (system notification when the process is dead; the app's own
  // branded notification when it is alive), the relay service reconnects, and
  // the queued events drain. Rate-limited per pairing/role. Never throws into
  // the relay path.
  async maybeFcmWake(role, evt) {
    try {
      const saRaw = this.env.FCM_SERVICE_ACCOUNT;
      if (typeof saRaw !== 'string' || saRaw.length === 0) return;
      const fcm = await this.getFcm();
      const token = fcm[role];
      if (!token) return;
      const now = Date.now();
      const last = (await this.getLastWake())[role] || 0;
      if (now - last < FCM_WAKE_COOLDOWN_MS) return;
      const sa = JSON.parse(saRaw);
      const { title, body } = notificationFor(evt);
      const bearer = await this.fcmAccessToken(sa);
      const base = (this.env.FCM_SEND_URL || 'https://fcm.googleapis.com').replace(/\/$/, '');
      const resp = await fetch(`${base}/v1/projects/${sa.project_id}/messages:send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${bearer}` },
        body: JSON.stringify({
          message: {
            token,
            notification: { title, body },
            data: {
              nn: '1',
              code: this.code,
              type: evt && evt.type ? String(evt.type) : 'unknown',
              data: JSON.stringify(evt && evt.data && typeof evt.data === 'object' ? evt.data : {}),
            },
            android: { priority: 'high', defaultActivityName: 'com.nextnotif.app.MainActivity' },
          },
        }),
      });
      if (!resp.ok) {
        console.warn(`FCM wake failed: ${resp.status} ${(await resp.text()).slice(0, 200)}`);
        return;
      }
      await this.setLastWake(role, now);
      console.log(`FCM wake sent for ${this.code}/${role}`);
    } catch (e) {
      console.warn(`FCM wake for ${this.code}/${role} failed:`, e);
    }
  }

  // The /send response must not wait on a slow Google egress; the DO stays
  // awake for the in-flight fetch either way (it is tied to this request).
  async wakeWithTimeout(evt) {
    await Promise.race([this.maybeFcmWake('receiver', evt), new Promise((r) => setTimeout(r, 5000))]);
  }

  // Sender uplink (POST /send): hand the event straight to the receiver's open
  // socket, or hold it in the per-pairing queue for the receiver's next
  // (re)connect. DO methods never interleave, so the live/queue decision and
  // the queue mutation are consistent with each other.
  async deliverToReceiver(type, data) {
    const out = JSON.stringify({ type, from: 'sender', data: data ?? null });
    const sockets = this.liveSockets();
    const receiver = sockets.receiver;
    if (receiver && receiver.readyState === WebSocket.OPEN) {
      receiver.send(out);
      return { delivered: true, queued: 0 };
    }
    const queue = await this.getQueue();
    queue.push({ ts: Date.now(), out });
    while (queue.length > MAX_QUEUED) queue.shift();
    await this.putQueue(queue);
    await this.wakeWithTimeout({ type, data: data ?? null });
    return { delivered: false, queued: queue.length };
  }

  // Replay everything queued while the receiver was offline. Called right
  // after the receiver authenticates, so the socket is provably open.
  async flushQueue(ws) {
    const queue = await this.getQueue();
    if (queue.length === 0) return;
    await this.putQueue([]);
    for (const item of queue) {
      if (ws.readyState === WebSocket.OPEN) ws.send(item.out);
    }
  }

  // Schedule the 5 s auth-timeout close for a role. An open websocket keeps the
  // DO instance awake, so a plain timer is reliable for the pending-auth window.
  scheduleAuthTimeout(role) {
    this.clearAuthTimeout(role);
    this.timers[role] = setTimeout(() => this.authTimeout(role), AUTH_TIMEOUT_MS);
  }

  clearAuthTimeout(role) {
    if (this.timers[role]) {
      clearTimeout(this.timers[role]);
      delete this.timers[role];
    }
  }

  async authTimeout(role) {
    delete this.timers[role];
    const pending = await this.getPending();
    if (!pending[role]) return;
    delete pending[role];
    await this.putPending(pending);
    const ws = this.state.getWebSockets(role)[0];
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.close(1008, 'auth timeout');
    }
  }

  async fetch(request) {
    const url = new URL(request.url);
    const isWsUpgrade = (request.headers.get('Upgrade') || '').toLowerCase() === 'websocket';
    const parts = url.pathname.split('/').filter(Boolean);

    if (
      isWsUpgrade &&
      parts.length === 3 &&
      parts[0] === 'ws' &&
      (parts[1] === 'sender' || parts[1] === 'receiver') &&
      isValidCode(parts[2])
    ) {
      const role = parts[1];
      const code = parts[2];
      if (this.state.getWebSockets(role).length > 0) {
        // Last connection wins: close every previous holder of this role and
        // let the newcomer in. Without this, a holder whose TCP died without
        // a FIN (phone behind a middlebox that resets connections) lingers as
        // a zombie the edge may keep for minutes, and every reconnect from
        // the same phone gets 403 "slot occupied" until the edge notices.
        for (const old of this.state.getWebSockets(role)) {
          if (old.readyState === WebSocket.OPEN || old.readyState === WebSocket.CONNECTING) {
            old.close(1000, 'replaced');
          }
        }
        const pend = await this.getPending();
        if (pend[role]) {
          delete pend[role];
          await this.putPending(pend);
          this.clearAuthTimeout(role);
        }
      }
      const pair = new WebSocketPair();
      const [client, server] = Object.values(pair);
      this.state.acceptWebSocket(client, [role]);
      await this.state.storage.put('paired', '1');
      this.code = code;
      this.accepted[role] = client;
      const token = randomToken();
      const pending = await this.getPending();
      pending[role] = { token, stage: 'hello' };
      await this.putPending(pending);
      this.scheduleAuthTimeout(role);
      return new Response(null, { status: 101, webSocket: server });
    }

    if (
      request.method === 'POST' &&
      parts.length === 2 &&
      parts[0] === 'send' &&
      isValidCode(parts[1])
    ) {
      const code = parts[1];
      // The code is the pairing key (TLS-protected in transit, exactly like
      // the WS bootstrap). Require an established pairing so stray codes
      // don't mint state.
      const paired = await this.state.storage.get('paired');
      if (paired == null) {
        return Response.json({ error: 'unknown pairing' }, { status: 404 });
      }
      this.code = code;
      let body = null;
      try {
        body = await request.json();
      } catch {
        body = null;
      }
      if (!body || typeof body !== 'object' || Array.isArray(body)) {
        return Response.json({ error: 'invalid body' }, { status: 400 });
      }
      const type = typeof body.type === 'string' && body.type ? body.type : 'unknown';
      const result = await this.deliverToReceiver(type, body.data ?? null);
      return Response.json(result);
    }

    return new Response('Not found', { status: 404 });
  }

  async webSocketMessage(ws, message) {
    const role = this.roleOf(ws);
    if (!role) return;

    let msg = null;
    try {
      msg = JSON.parse(String(message));
    } catch {
      msg = null;
    }

    const pending = await this.getPending();
    if (pending[role]) {
      const entry = pending[role];
      if (entry.stage === 'hello') {
        if (msg != null && msg.type === 'hello') {
          // Optional device name ("Xiaomi 23049PCD8G") for the partner's UI;
          // persisted so it survives DO hibernation, cleared on close.
          const name = typeof msg.device_name === 'string' ? msg.device_name.trim().slice(0, 64) : '';
          if (name) {
            const names = await this.getNames();
            names[role] = name;
            await this.putNames(names);
          }
          // Optional FCM wake token for kill-recovery of this peer.
          await this.storeFcmToken(role, msg.fcm_token);
          // First message received: issue the one-time token.
          entry.stage = 'auth';
          await this.putPending(pending);
          this.scheduleAuthTimeout(role);
          ws.send(JSON.stringify({ type: 'handshake', token: entry.token }));
        } else {
          delete pending[role];
          await this.putPending(pending);
          this.clearAuthTimeout(role);
          ws.close(1008, 'auth failed');
        }
        return;
      }
      // stage: 'auth'
      const valid =
        msg != null &&
        msg.type === 'auth' &&
        msg.token === entry.token &&
        msg.code === this.code;
      delete pending[role];
      await this.putPending(pending);
      this.clearAuthTimeout(role);
      if (!valid) {
        ws.close(1008, 'auth failed');
        return;
      }
      // The auth message may carry a fresher FCM wake token than hello did.
      await this.storeFcmToken(role, msg.fcm_token);
      // Device tokens: a presented token that belongs to this pairing is kept;
      // anything else (first connect, stale token, legacy client) gets a fresh
      // one. Code-only auth stays the bootstrap path; ongoing relays ride on
      // the high-entropy token (max MAX_DEVICE_TOKENS kept per pairing).
      const tokens = await this.getTokens();
      let issued;
      if (typeof msg.device_token === 'string' && tokens.includes(msg.device_token)) {
        issued = msg.device_token;
      } else {
        issued = randomDeviceToken();
        tokens.push(issued);
        if (tokens.length > MAX_DEVICE_TOKENS) tokens.splice(0, tokens.length - MAX_DEVICE_TOKENS);
        await this.putTokens(tokens);
      }
        ws.send(JSON.stringify({ type: 'auth_ok', device_token: issued }));
        if (role === 'receiver') {
          // Catch-up: events the sender uplinked while this receiver was
          // offline arrive now, in order, before any live relay.
          await this.flushQueue(ws);
        }
        return; // authenticated: never relayed
    }

    if (!msg || msg.type === 'auth') return; // duplicate auth from an authenticated socket is ignored

    // FCM token refresh while connected (rotation is rare, but a reinstall /
    // OS change rotates it without a re-pairing).
    if (msg.type === 'fcm_token') {
      await this.storeFcmToken(role, msg.data && msg.data.token);
      return;
    }

    const sockets = this.liveSockets();
    const target = role === 'sender' ? sockets.receiver : sockets.sender;
    const out = JSON.stringify({ type: msg.type || 'unknown', from: role, data: msg.data });
    if (target && target.readyState === WebSocket.OPEN) {
      target.send(out);
      return;
    }
    if (role === 'sender') {
      // Receiver offline: hold the event for catch-up and wake the phone
      // (the WS path used to drop it silently).
      const queue = await this.getQueue();
      queue.push({ ts: Date.now(), out });
      while (queue.length > MAX_QUEUED) queue.shift();
      await this.putQueue(queue);
      await this.wakeWithTimeout({ type: msg.type || 'unknown', data: msg.data ?? null });
    }
  }

  async webSocketClose(ws) {
    // A superseded or long-idle instance may have lost its in-memory
    // `accepted` map (local dev hibernates DOs even with sockets open; the
    // edge recovers the socket list on wake, but a closing socket is no longer
    // OPEN, so roleOf() would miss it). Fall back to membership by identity.
    let role = this.roleOf(ws);
    if (!role) {
      for (const r of ['sender', 'receiver']) {
        if (this.state.getWebSockets(r).includes(ws)) {
          role = r;
          break;
        }
      }
    }
    if (!role) return;
    // Cleanup only when no OTHER live socket holds the role. After a takeover
    // the OLD (replaced) socket closes late while the NEW holder is in the
    // slot; wiping pending/names then would drop the new holder's auth (no
    // auth_ok) and erase the name it just stored. The reverse must NOT skip:
    // a long-idle instance can hibernate (local dev does), losing the
    // in-memory `accepted` map while the socket list is restored — its closing
    // socket is then neither `accepted` nor OPEN, but it is still the holder.
    const replaced = this.state
      .getWebSockets(role)
      .some((w) => w !== ws && w.readyState === WebSocket.OPEN);
    if (replaced) return;
    if (this.accepted[role] === ws) this.accepted[role] = null;
    this.clearAuthTimeout(role);
    const pending = await this.getPending();
    if (pending[role]) {
      delete pending[role];
      await this.putPending(pending);
    }
    const names = await this.getNames();
    if (names[role]) {
      delete names[role];
      await this.putNames(names);
    }
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const parts = url.pathname.split('/').filter(Boolean);

    if (url.pathname === '/') {
      return new Response('<h3>NextNotif relay running</h3>', {
        headers: { 'Content-Type': 'text/html' },
      });
    }

    if (parts.length === 2 && parts[0] === 'pair' && parts[1] === 'create' && request.method === 'POST') {
      for (let i = 0; i < 20; i++) {
        const code = String(Math.floor(100000 + Math.random() * 900000));
        const inst = env.PAIRING.get(env.PAIRING.idFromName(code));
        if (await inst.tryClaim()) {
          return Response.json({ code });
        }
      }
      return Response.json({ error: 'could not allocate a code' }, { status: 500 });
    }

    const role = parts.length >= 2 && parts[0] === 'ws' ? parts[1] : null;
    const isRole = role === 'sender' || role === 'receiver';
    const headerCode = request.headers.get('x-nextnotif-code');

    if (parts.length === 3 && parts[0] === 'ws' && isRole) {
      // Legacy path form; a valid header code takes precedence over the path code.
      const code = isValidCode(headerCode) ? headerCode : parts[2];
      if (!isValidCode(code)) return new Response('Not found', { status: 404 });
      return forwardToPairing(env, request, code, role);
    }

    if (parts.length === 2 && parts[0] === 'ws' && isRole) {
      // Header form: /ws/<role> with the code in X-NextNotif-Code.
      if (!isValidCode(headerCode)) {
        return Response.json({ error: 'missing pairing code' }, { status: 400 });
      }
      return forwardToPairing(env, request, headerCode, role);
    }

    if (request.method === 'POST' && parts.length === 2 && parts[0] === 'send') {
      // Path form: POST /send/<code>.
      const code = isValidCode(headerCode) ? headerCode : parts[1];
      if (!isValidCode(code)) return new Response('Not found', { status: 404 });
      return forwardSend(env, request, code);
    }

    if (request.method === 'POST' && parts.length === 1 && parts[0] === 'send') {
      // Header form: POST /send with the code in X-NextNotif-Code.
      if (!isValidCode(headerCode)) {
        return Response.json({ error: 'missing pairing code' }, { status: 400 });
      }
      return forwardSend(env, request, headerCode);
    }

    if (parts.length === 3 && parts[0] === 'pair' && parts[2] === 'status') {
      const code = parts[1];
      if (!CODE_RE.test(code)) {
        return Response.json({ exists: false });
      }
      const inst = env.PAIRING.get(env.PAIRING.idFromName(code));
      return Response.json(await inst.getStatus());
    }

    return new Response('Not found', { status: 404 });
  },
};

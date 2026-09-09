# NextNotif

Forward SMS and incoming-call info from one Android phone to another, relayed through a small FastAPI server. Both phones pair with the server using a 6-digit code.

## Layout

- `server/` — FastAPI + WebSocket relay
- `android/` — Android app (Kotlin + Jetpack Compose)

## Run the server

```bash
cd server
pip install -r requirements.txt
python main.py
# listens on 0.0.0.0:8000
```

Endpoints:
- `POST /pair/create` — returns `{ "code": "123456" }`
- `GET /pair/<code>/status` — `{ exists, sender_connected, receiver_connected }`
- `WS /ws/<sender|receiver>` with an `X-NextNotif-Code: <code>` header (the legacy
  `WS /ws/<sender|receiver>/<code>` path still works) — bidirectional message pipe.
  Every websocket completes a one-time handshake before any relay: the client sends
  `{"type":"hello"}`, the server replies `{"type":"handshake","token":...}`, and the
  client must answer `{"type":"auth","token":...,"code":...}` within 5 seconds —
  otherwise the server closes with 1008.
- `POST /send/<code>` (or `POST /send` with an `X-NextNotif-Code: <code>` header) —
  the sender's fire-and-forget uplink: body `{"type":"sms"|"call","data":{...}}`,
  authenticated by the 6-digit code over TLS. Returns `{"delivered":true}` when the
  receiver's websocket is open, or `{"delivered":false,"queued":N}` when it is not —
  the event is then held in a per-pairing queue (max 50, oldest dropped) and replayed
  in order the next time a receiver authenticates. `404` unknown pairing, `400` bad body.

The Android app's **sender** role uses this uplink: it keeps no persistent socket,
POSTs each SMS/call event the moment it happens, and queues events locally
(SharedPreferences outbox) while the network is down. The **receiver** role still
holds the long-lived websocket so it is notified instantly and picks up anything
missed on (re)connect.

The Android app creates/uses a code through this same flow. Pairings persist across server restarts in `server/pairings.json`.

Server tests: `python test_smoke.py` (self-contained, no pytest).

## Deploy the relay to Cloudflare Workers

`server-workers/` is a Durable Objects port of the same relay: one DO instance per 6-digit code, WebSockets held by the instance, pairing records in DO storage (no file, survives restarts).

```bash
cd server-workers
npx wrangler deploy          # -> https://nextnotif-relay.<account>.workers.dev
```

Endpoints match the Python server: `POST /pair/create`, `GET /pair/<code>/status`,
`WS /ws/<sender|receiver>/<code>`, and `POST /send` (sender fire-and-forget uplink
with the same offline queue + receiver catch-up). A second connection on an occupied
slot takes over: the newcomer is accepted and the previous holder is closed with
1000 "replaced" (the Python server still rejects it with 1008).

Worker tests: `python test_smoke.py` (uses `server/.venv`, self-contained, no pytest) —
default target `http://localhost:8787` (run `npx wrangler dev --port 8787` first), or point
`NEXTNOTIF_WORKER_URL` at the deployed URL.

The Android app works unchanged against the worker: set the server URL to
`wss://<your-worker>.workers.dev`.

## Firebase transport (no server needed)

Besides the relay server, the app can pair the two phones **peer-to-peer through
Firebase** — no server of your own to run. Setup picks "Connection → Firebase"
instead of entering a server URL. How it works:

- The 6-digit **pairing code** becomes the Firebase Auth e-mail
  (`<code>@nextnotif.local`); the **pairing secret** (any word, 6+ chars, typed on
  both phones) is the password. The first phone creates the credential, the second
  signs in with it.
- Events are published to Realtime Database at `pairings/<code>/events/`; each phone
  listens and shows the other phone's events. The RTDB SDK's offline persistence
  queues writes while the network is down, and the app's outbox covers the window
  before the relay is up.

**Default: built-in relay project (zero setup).** The app ships with a
developer-operated Firebase project (see `FirebaseConfig.DEFAULT` in
`android/app/.../FirebaseConfig.kt`). On both phones just pick the same 6-digit
code + same secret and tap "Set up & connect" — no snippet, no config. This is the
"app ships its own backend" model: as the project owner you can see every pairing's
data in the Firebase console, pairings are isolated from each other by the DB rules,
and all traffic counts against the project's plan quota.

**Optional: bring your own Firebase project.** In setup, under the Firebase relay
card, tap "Use your own Firebase project" and paste the web-app `firebaseConfig`
snippet from your project's console. One-time project setup:

1. Create a project at <https://console.firebase.google.com>.
2. **Authentication → Sign-in method →** enable **Email/Password**.
3. **Build → Realtime Database → Create database**, then in the **Rules** tab use:

   ```json
   {
     "rules": {
       "pairings": {
         "$code": {
           ".read": "auth != null && auth.token.email === ($code + '@nextnotif.local')",
           ".write": "auth != null && auth.token.email === ($code + '@nextnotif.local')"
         }
       }
     }
   }
   ```

   The rules bind each pairing to its own code: signing in as `123456@nextnotif.local`
   can only read/write `pairings/123456`.
4. **Project settings (gear) → General → Your apps →** add a **Web app**, then copy
   the `firebaseConfig` snippet.

Security note: the code + secret together are the full credential for the pairing
(same trust model as before — fine for a trusted pair of phones, not for public
exposure). Resetting the pairing in the app does not delete the Firebase user; to
rotate, delete the user under **Authentication → Users** or re-pair with a new code.

## Build & install the Android app

Open `android/` in Android Studio (Giraffe or newer). Gradle sync, then Run. (Or `cd android && ./gradlew assembleDebug` with a JDK 17.)

`minSdk = 26` (Android 8.0 Oreo). Runs on Android 8 and newer; tested target = API 34.

The default server URL is `ws://10.0.2.2:8000` (Android emulator alias for the host). For a real device, edit it in the app to `ws://<your-lan-ip>:8000`. Cleartext WS traffic is allowed in the manifest for development; switch to `wss://` for production.

## Features

- **Many-to-many pairings** — a phone holds a *list* of pairings and can be **Sender** on some and **Receiver** on others at the same time. The home screen is a pairing hub: one card per pairing (optional human label, role, transport, this-phone + partner connection state), a per-pairing detail screen (code, settings, token status, per-pairing activity), and a full-screen add/edit flow.
- **Pairing** — 6-digit code per pairing: "Generate code" asks the server for one, or both phones simply type the same code (auto-pair).
- **Two transports** — the built-in Firebase relay (default, zero setup: same code + secret on both phones), bring-your-own Firebase project, or your own relay server over WebSocket.
- **SMS + call forwarding** — incoming SMS (multi-part joined) and call states (RINGING/OFFHOOK/IDLE) on the sender phone are pushed instantly over WebSocket to the receiver phone.
- **Caller ID** — on Android 8–10 (API 26–30) the incoming call number is resolved to a contact name on the sender and shown in the receiver's notification. On Android 11+ (API 31+) the OS withholds the incoming number from third-party apps, so the call state still forwards but the number shows as "unknown".
- **Live UI** — relay hero card with prominent Start/Stop, per-pairing cards with live "This phone" / "Partner" status pills (partner state polled from the server every 30 s), and a humanized global activity feed (icon + title + pairing chip + time per event).
- **Reliability** — foreground service keeps the socket alive (exponential-backoff reconnect), events arriving while offline are queued (max 100) and replayed on reconnect, the service restarts after a reboot, and a one-tap "Battery: request exemption" whitelists the app from doze.
- **Notifications** — the receiver phone gets a high-priority notification per SMS/call event.

## Android 8 (API 26) notes

- `startForegroundService` is used for all service starts; `startForeground()` is invoked from `onCreate()` to meet the 5-second deadline.
- Notification channels are eagerly created via `Notifications.ensureChannels` on API 26+.
- `FOREGROUND_SERVICE_TYPE_*` is only set on API 29+; below that the type-less overload is used.
- `POST_NOTIFICATIONS` is only requested on API 33+.
- `usesCleartextTraffic="true"` is set so `ws://` works without an extra network-security-config.

## Flow

1. Install the app on **both** phones. Run the server (`python main.py`) on a host reachable by both devices (use the host's LAN IP on real devices, `10.0.2.2` on the emulator).
2. On phone A: tap **Add pairing**, choose **Sender**, tap **Generate code** — the server allocates a 6-digit code and stores it on the phone.
3. On phone B: tap **Add pairing**, choose **Receiver**, enter the same 6-digit code, add it.
4. Tap **Start relay** on each phone.
5. Phone A receives an SMS or incoming call → BroadcastReceiver fires → service forwards over WebSocket → server relays → phone B shows a notification.

Add more pairings any time (the same phone can be sender on one and receiver on another); each pairing has its own card, status, and activity on the home screen.

If you'd rather not use Generate code, both phones can type the same 6-digit number; the server will auto-create the pairing on first contact.

## Permissions the app declares

- `INTERNET`, `ACCESS_NETWORK_STATE`
- `RECEIVE_SMS`, `READ_SMS` (sender)
- `READ_PHONE_STATE`, `READ_CONTACTS` (sender, for call state + caller ID)
- `POST_NOTIFICATIONS` (receiver, Android 13+)
- `RECEIVE_BOOT_COMPLETED` (auto-restart the relay after reboot)
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (optional whitelist)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`

Note: on app start, the app immediately asks for **all** runtime permissions it declares —
SMS, phone state, contacts, and notifications (Android 13+) — on a dedicated permissions
screen, and stays locked there until every one is granted. Pairing and the relay can only
be used after that. If a permission is permanently denied, the screen offers an "Open app
settings" shortcut. (Battery-optimization exemption stays optional, requested from the
home screen menu.)

On some OEM skins the app may additionally need to be set as the **default SMS app**
for `SMS_RECEIVED` to be delivered. Not required on stock AOSP/Google images, and
verified not required on MIUI (Android 15) either — a non-default app with the runtime
`RECEIVE_SMS` permission receives `SMS_RECEIVED` there.

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

For WebSocket pairings, both Android roles keep a full-duplex connection. Normal
SMS/call events still fall back to `POST /send` and the local SharedPreferences
outbox when needed. Binary WebSocket frames are reserved for live G.711 call audio and are
never queued, persisted, or sent through FCM.

## Send SMS from the receiver (no root)

Install the updated APK on both phones and update the relay server. On the sender,
turn on **SMS replies** in Overview and grant SMS permission. On the receiver,
open a conversation to reply, or choose **Messages → New message** and select the
paired sender. Under **Sender SIM**, choose a reported SIM (slot, name and carrier)
or **Use sender’s default**. **Refresh SIMs** asks the sender for an updated list;
the sender needs phone permission and internet access. SIM details are last reported
information, not a guarantee that the phone is currently online. A removed or disabled
selected SIM fails the request without switching to another SIM. Selection applies
to the current composer and does not change Android’s default SIM. Carrier charges
apply; long messages may use multiple SMS parts.

Sending supports FCM and WebSocket-server pairings. Custom Firebase Database
pairings currently support forwarded history only. Both phones need internet for
relay delivery, and the sender needs cellular service. Requests expire after one
hour if they have not reached the modem.

The sender records each request before calling Android's SMS API. Retries retain
the same UUID, and the relay rejects changed content using an existing UUID.
“Sent to carrier” appears only when every SMS part receives Android's successful
sent callback; it is not a recipient delivery receipt. A crash or missing callback
shows an unconfirmed result and never automatically resends through the modem.
Check the sender before manually sending that text again. Clearing history hides
outgoing rows but does not cancel requests or remove duplicate-protection records.

The return path uses `POST /sms-submit` and `/sms-status` with a receiver credential,
and `/sms-fetch` and `/sms-result` with a sender credential. `POST /fcm-register`
accepts `X-NextNotif-Role` (default `receiver`) and binds issued tokens to that role.
Sender-authenticated `/sms-fetch` may publish `sim_options`; receiver-authenticated
`/sms-status` returns them and accepts `request_sims: true` to wake the sender.
Commands carry an optional immutable `subscription_id` (null means sender default).
Only slot, display name, carrier and subscription ID are shared; no SIM serial or
phone-number permission is needed. Update both apps and the relay for SIM selection.
FCM contains only a wake signal; message content stays in the authenticated HTTP
queue. Existing six-digit legacy pairing bootstrap remains unchanged.

Validation without sending a real SMS:

```bash
cd server
.venv/bin/python -m unittest test_sms_mailbox.py
cd ../server-workers
node test_sms_mailbox.mjs
# With a local Worker listening on port 8787:
../server/.venv/bin/python test_sms_http.py
```

## Optional live call relay (rooted gateway beta)

Live calling is disabled by default and enabled separately on each **Sender** pairing.
Ordinary SMS and incoming-call notifications do not require root and continue to work
when this option is off or unsupported. When enabled, the sender verifies both root
access and the gateway mixer helper before advertising the capability. Only then does
an incoming-call notification on the receiver offer **Answer here**; otherwise it is a
normal informational call alert with no remote-call controls.

The sender opens a temporary authenticated WebSocket while the phone rings;
the receiver joins it only after the user answers, then the phones exchange 8 kHz
G.711 mu-law audio in 20 ms binary frames. The gateway captures the
cellular downlink with `VOICE_DOWNLINK`; receiver audio is played into the rooted
Samsung A5 mixer uplink. **Hang up** and call/disconnect cleanup stop both audio
pipelines, restore `AudioMixer CH2 DOUT Select` to `AIF4IN`, close the temporary
sockets, and return event delivery to FCM.

The receiver can also open **Calls → Call through sender**, choose the paired
sender, enter a number, and select one of the sender's reported SIMs. The relay
wakes the sender, which places the cellular call with Android Telecom and joins
the same temporary WebRTC audio session. Only one outgoing request may be active
per pairing; its UUID and SIM selection are immutable, and requests expire after
two minutes. The sender must have Phone, Microphone, and direct-call permissions,
plus the existing rooted gateway capability. Carrier voice charges apply.

Requirements and current limits:

- The gateway is the tested rooted Samsung SM-A520F (Android 8), installed as a
  Magisk privileged app with `CAPTURE_AUDIO_OUTPUT` and the supplied persistent
  audio-device SELinux rule/mixer helper.
- The receiver is a normal Android phone with microphone permission.
- The sender needs Android's phone permissions for remote Place/Answer/Hang up. These
  live-call permissions are optional and do not block the core relay.
- FCM carries event delivery and the call wake-up. Temporary TLS WebSockets carry
  live signaling/audio; no receiver WebSocket remains open between calls.
- PCM is protected in transit by WSS, but it is not end-to-end encrypted from the
  relay operator. Treat this as an experimental build and follow call-consent laws.

Build the APK, then install/update the rooted gateway module with
`tools/install-call-relay-module.sh`. The script expects the A520F `tinymix` binary
at `root-assets/a520f-audio-tools/tinymix`; reboot after installation so Magisk can
load the systemless APK and SELinux policy.

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

Before Gradle sync, place your Firebase Android app configuration at
`android/app/google-services.json` (package `com.nextnotif.app`). This local file is
intentionally excluded from Git. Download it from your Firebase project settings;
do not substitute a service-account key. Client configuration is distinct from the
server's private FCM credentials, which must never ship in the APK or repository.

Open `android/` in Android Studio (Giraffe or newer). Gradle sync, then Run. (Or `cd android && ./gradlew assembleDebug` with a JDK 17.)

`minSdk = 26` (Android 8.0 Oreo). Runs on Android 8 and newer; tested target = API 34.

The default server URL is the production TLS relay,
`wss://relay.amberdogeorgia.com`. Debug builds also allow a development LAN relay
such as `ws://10.0.2.2:8000` (the Android-emulator alias for the host) or
`ws://<your-lan-ip>:8000`. Release builds reject cleartext HTTP/WebSocket traffic.

## Features

- **Many-to-many pairings** — a phone holds a *list* of pairings and can be **Sender** on some and **Receiver** on others at the same time. The home screen is a pairing hub: one card per pairing (optional human label, role, transport, this-phone + partner connection state), a per-pairing detail screen (code, settings, token status, per-pairing activity), and a full-screen add/edit flow.
- **Pairing** — 6-digit code per pairing: "Generate code" asks the server for one, or both phones simply type the same code (auto-pair).
- **Three transports** — FCM on demand (recommended for idle efficiency), the built-in/bring-your-own Firebase relay, or an always-connected WebSocket relay.
- **SMS + call forwarding** — incoming SMS (multi-part joined) and call states (RINGING/OFFHOOK/IDLE) on the sender phone use the configured transport, with FCM/HTTPS recommended for efficient idle delivery.
- **Caller ID** — on Android 8–10 (API 26–30) the incoming call number is resolved to a contact name on the sender and shown in the receiver's notification. On Android 11+ (API 31+) the OS withholds the incoming number from third-party apps, so the call state still forwards but the number shows as "unknown".
- **Live UI** — relay hero card with prominent Start/Stop, per-pairing cards with live "This phone" / "Partner" status pills (partner state polled from the server every 30 s), and a humanized global activity feed (icon + title + pairing chip + time per event).
- **Reliability** — foreground service keeps the socket alive (exponential-backoff reconnect), events arriving while offline are queued (max 100) and replayed on reconnect, the service restarts after a reboot, and a one-tap "Battery: request exemption" whitelists the app from doze.
- **Notifications** — the receiver phone gets a high-priority notification per SMS/call event.

## Android 8 (API 26) notes

- `startForegroundService` is used for all service starts; `startForeground()` is invoked from `onCreate()` to meet the 5-second deadline.
- Notification channels are eagerly created via `Notifications.ensureChannels` on API 26+.
- `FOREGROUND_SERVICE_TYPE_*` is only set on API 29+; below that the type-less overload is used.
- `POST_NOTIFICATIONS` is only requested on API 33+.
- Debug builds set `usesCleartextTraffic="true"` for local `ws://` development;
  release builds keep cleartext disabled and use the TLS relay by default.

## Flow

1. Install the app on **both** phones. Run the server (`python main.py`) on a host reachable by both devices (use the host's LAN IP on real devices, `10.0.2.2` on the emulator).
2. On phone A: tap **Add pairing**, choose **Sender**, tap **Generate code** — the server allocates a 6-digit code and stores it on the phone.
3. On phone B: tap **Add pairing**, choose **Receiver**, enter the same 6-digit code, add it.
4. Tap **Start relay** on the sender. An FCM receiver shows **Ready** without keeping
   a foreground service or WebSocket alive.
5. Phone A receives an SMS or incoming call → the app posts it over HTTPS → the
   relay wakes phone B through FCM → phone B shows a notification and stores it in
   Messages. If the rooted reference-device beta was explicitly enabled and its
   capability check passes, an answered call temporarily opens live sockets.

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

Runtime permissions are progressive and pairing-role aware. A fresh install can reach
the pairing UI without granting anything. After an enabled pairing is added, a sender
requires SMS receive/read plus phone-state access; a receiver requires notifications only
on Android 13+. Contacts, remote answer, microphone/live audio, and battery exemption are
optional capabilities and do not block the baseline SMS/call-information relay. If a
required permission is permanently denied, the permission screen offers an **Open app
settings** shortcut.

On some OEM skins the app may additionally need to be set as the **default SMS app**
for `SMS_RECEIVED` to be delivered. Not required on stock AOSP/Google images, and
verified not required on MIUI (Android 15) either — a non-default app with the runtime
`RECEIVE_SMS` permission receives `SMS_RECEIVED` there.

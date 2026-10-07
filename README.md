# NextNotif

Current release assessment and staged plan: [Production readiness](PRODUCTION_READINESS.md).
For the latest operator checkpoint, see [MVP production handoff](MVP_PRODUCTION_HANDOFF.md).

Forward SMS and incoming-call info from one Android phone to another. New Android pairings use a single-use invite with a Cloudflare Worker relay. The 6-digit code identifies a pairing but is not its credential.

## Layout

- `server/` — legacy FastAPI + WebSocket relay for older pairings and development
- `server-workers/` — Cloudflare Worker relay used by new secure pairings
- `android/` — Android app (Kotlin + Jetpack Compose)

## Legacy FastAPI server

The FastAPI server does not implement secure create/join. The current Android add-pairing flow requires a compatible Worker; use FastAPI only with existing legacy pairings or older clients.

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
  receiver's websocket is open, or `{"delivered":false,"queued":N,"overflow_dropped":M}` when it is not —
  the event is then held in a per-pairing queue (max 50, oldest dropped with a content-free overflow count) and replayed
  in order the next time a receiver authenticates. Local Worker source now expires queued
  content after a provisional seven days using a Durable Object alarm; this has not been
  deployed to the production relay. `404` unknown pairing, `400` bad body.

For WebSocket pairings, both Android roles keep a full-duplex connection. Normal
SMS/call events still fall back to `POST /send` and the local SharedPreferences
outbox when needed. Live-call media uses WebRTC audio tracks; signaling uses the
temporary WebSocket and audio is never queued, persisted, or sent through FCM.

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

Sending supports FCM and WebSocket-server pairings. Both phones need internet for
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
queue. Existing code-only pairings remain a legacy compatibility path and require migration before a private release.

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
the receiver joins it only after the user answers. WebRTC carries audio while the
socket carries call control and signaling. The rooted Samsung A5 gateway uses
`VOICE_DOWNLINK` capture and its mixer uplink. Audio quality, hang-up, route
restoration, and recovery still require controlled physical-device proof before
this beta is enabled for users. See [the active call test plan](CALL_TEST_PLAN.md).

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
- In FCM delivery mode, FCM wakes the receiver to fetch queued events. Temporary
  TLS WebSockets carry call signaling; WebRTC carries call audio. FCM idle delivery
  still needs release-candidate device testing.

Build the signed APK, then install/update the rooted gateway module with
`tools/install-call-relay-module.sh`. The script defaults to the release APK and accepts an explicit APK path for controlled development tests. It expects the A520F `tinymix` binary
at `root-assets/a520f-audio-tools/tinymix`; reboot after installation so Magisk can
load the systemless APK, privileged-permission allowlist, and SELinux policy.

Legacy pairings persist across server restarts in `server/pairings.json`.

Server tests: `python test_smoke.py` (self-contained, no pytest).

## Deploy the relay to Cloudflare Workers

`server-workers/` is a Durable Objects port of the same relay: one DO instance per 6-digit code, WebSockets held by the instance, pairing records in DO storage (no file, survives restarts).

```bash
cd server-workers
npx wrangler deploy          # -> https://nextnotif-relay.<account>.workers.dev
```

The Worker supports secure `POST /pair/secure-create` and `POST /pair/secure-join`. Create returns a single-use invite secret and a device credential; join consumes the invite and grants the opposite role. Both devices then send their device ID and token on relay requests. Legacy endpoints include `POST /pair/create`, `GET /pair/<code>/status`,
`WS /ws/<sender|receiver>/<code>`, and `POST /send` (sender fire-and-forget uplink
with the same offline queue + receiver catch-up). A second connection on an occupied
slot takes over: the newcomer is accepted and the previous holder is closed with
1000 "replaced" (the Python server still rejects it with 1008).

Local Worker source limits secure creation to 12 attempts per minute per client IP, secure join to 60 attempts per minute per client IP, and joins against one known pairing to 20 attempts per minute. Exceeding a limit returns HTTP 429 with `Retry-After`; the Android pairing screen shows a wait message. These limits are not deployed to staging or production and do not cover legacy code-only routes.

Worker tests: `python test_smoke.py` (uses `server/.venv`, self-contained, no pytest) —
default target `http://localhost:8787` (run `npx wrangler dev --port 8787` first), or point
`NEXTNOTIF_WORKER_URL` at the deployed URL.

For new pairings, set the server URL to a Worker running the secure routes,
`wss://<your-worker>.workers.dev`. The default production relay has not been updated to these changes as part of this review.

For isolated physical-device tests, `server-workers/wrangler.staging.jsonc` deploys
`nextnotif-relay-staging` with separate Durable Object storage. The `staging`
Android build uses package `com.nextnotif.app.staging`, defaults to that Worker's
WebSocket URL, and runs on API 25+ with
`-PnextnotifCompatibilityTestMinSdk=25`. It has no cellular SMS/call permissions
or automatic SMS/boot/call-screening handlers, and does not register an FCM
token. The sender's **Send test notification** action works with WebSocket as
well as FCM pairings. It is for secure pairing and WebSocket tests; use a separately reviewed
build and explicit test plan for live SMS or calls. The staging Worker has no
FCM service-account secret. Its TURN bindings are Secrets, but the previously
exposed staging key still requires rotation before a live call.

For an opt-in remote-send check, `stagingSms` builds a separate
`com.nextnotif.app.staging.sms` APK. Its cellular permissions are limited to
`SEND_SMS` and `READ_PHONE_STATE`; it has no incoming-SMS receiver. The completed
SIM 1 test and remaining SIM 2 and multipart gates are recorded in
[MVP production handoff](MVP_PRODUCTION_HANDOFF.md).

For a controlled incoming-SMS check, `stagingInbound` builds a separate
`com.nextnotif.app.staging.inbound` APK. It requests `RECEIVE_SMS` as its only
cellular permission and forwards only the exact staging marker or the shaped
multipart probe while its relay is on. The completed checks are recorded in
[MVP production handoff](MVP_PRODUCTION_HANDOFF.md). The original system app on
Samsung can also receive and forward that message.

## Retired Firebase Database pairings

New pairings use FCM on demand or WebSocket. The former Firebase Realtime Database
transport is no longer available. Existing pairings remain visible but do not
connect; create a new FCM or WebSocket pairing and copy its invite to the other phone,
verify delivery, then remove the old pairing. The app does not convert the old
pairing, so its queued events cannot be replayed to the new relay. History saved
on each phone remains local. Removing a pairing does not erase data in an old Firebase project; its project owner must
review and remove that data separately.

## Build & install the Android app

For a private signed APK, keep the owner-controlled keystore outside this repository.
The local setup script `tools/create-private-signing-key.py` creates a signing key
and an owner-only `signing.env` under `~/Library/Application Support/NextNotif/Signing`;
it refuses to replace an existing identity. Back up the keystore and password
separately before distributing any APK. To build and verify the signed APK:

```bash
source "$HOME/Library/Application Support/NextNotif/Signing/signing.env"
export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
bash tools/build-private-apk.sh
```

The script rejects the CI-only Firebase placeholder configuration, runs Android unit
tests and lint, verifies the APK signature, and prints the certificate fingerprint
and SHA-256 checksum. A successful signed build is
not a release approval; the compatible Worker and two-phone gates still apply.

For the Android 7.1 HTC m8 receiver experiment only, build the side-by-side
`com.nextnotif.app.compat` APK with
`cd android && ./gradlew testCompatUnitTest lintCompat assembleCompat -PnextnotifCompatibilityTestMinSdk=25`.
It is labeled **NextNotif Compat**, uses a fake Firebase configuration, and can
exercise WebSocket transport only. The normal private release remains API 26+.

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
- **Pairing** — the first phone creates a single-use invite; the second pastes the complete invite within 15 minutes. The 6-digit code is only a display identifier. Existing code-only pairings need migration.
- **Two transports** — FCM on demand (recommended for idle efficiency) or an always-connected WebSocket relay.
- **SMS + call forwarding** — incoming SMS (multi-part joined) and call states (RINGING/OFFHOOK/IDLE) on the sender phone use the configured transport, with FCM/HTTPS recommended for efficient idle delivery.
- **Caller ID** — on Android 8–10 (API 26–30) the incoming call number is resolved to a contact name on the sender and shown in the receiver's notification. On Android 11+ (API 31+) the OS withholds the incoming number from third-party apps, so the call state still forwards but the number shows as "unknown".
- **Live UI** — relay hero card with prominent Start/Stop, per-pairing cards with live "This phone" / "Partner" status pills (partner state polled from the server every 30 s), and a humanized global activity feed (icon + title + pairing chip + time per event).
- **Reliability** — foreground service keeps a WebSocket connected when selected (with reconnect backoff); the Worker queue holds up to 50 events for offline recovery. Reboot, Doze, long-offline, and queue-overflow behavior still need release-candidate device testing.
- **Notifications** — the receiver phone gets a high-priority notification per SMS/call event.

## Android 8 (API 26) notes

- `startForegroundService` is used for all service starts; `startForeground()` is invoked from `onCreate()` to meet the 5-second deadline.
- Notification channels are eagerly created via `Notifications.ensureChannels` on API 26+.
- `FOREGROUND_SERVICE_TYPE_*` is only set on API 29+; below that the type-less overload is used.
- `POST_NOTIFICATIONS` is only requested on API 33+.
- Debug builds set `usesCleartextTraffic="true"` for local `ws://` development;
  release builds keep cleartext disabled and use the TLS relay by default.

## Flow

1. Install the app on **both** phones and configure both to use a Worker relay with secure pairing support. The local changes have not been deployed to the default relay.
2. On phone A: tap **Add pairing**, choose **Sender** or **Receiver**, choose **Create an invite**, then **Copy invite**. Keep the text private.
3. On phone B: tap **Add pairing**, choose **Join with an invite**, paste the complete text, and join within 15 minutes. The invite sets the opposite role and relay automatically and can be used once.
4. Tap **Start relay** on the sender. An FCM receiver shows **Ready** without keeping
   a foreground service or WebSocket alive.
5. Phone A receives an SMS or incoming call → the app posts it over HTTPS → the
   relay wakes phone B through FCM → phone B shows a notification and stores it in
   Messages. If the rooted reference-device beta was explicitly enabled and its
   capability check passes, an answered call temporarily opens live sockets.

Add more pairings any time (the same phone can be sender on one and receiver on another); each pairing has its own card, status, and activity on the home screen.

If the invite expires or is lost, remove that pairing and create a fresh one. Editing a secure pairing can change its name, transport, and optional call setting; its role and relay are fixed.
Deleting a secure pairing on the phone that created it revokes both devices and
erases queued relay data before removing the local pairing. The other phone can
only remove its own local pairing; the creator controls relay deletion.

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

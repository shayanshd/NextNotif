# NextNotif — Roadmap

Goal (from original request): an Android app that reads received SMS and incoming calls,
forwards each event to a paired server, which instantly relays it to the same app on a
second paired phone and shows a notification of the content. Each phone can participate
in **multiple pairings** simultaneously — one sender can relay to several receivers, and
one receiver can listen to several senders. On each phone, the UI shows the live
connection status of every paired partner.

Status legend: [x] done · [~] in progress / partially done · [ ] not started

---

## 1. Requirements vs. status

| # | Requirement | Status | Where |
|---|-------------|--------|-------|
| 1 | App reads received text messages | [x] | `SmsReceiver.kt` (`SMS_RECEIVED` broadcast, multi-part SMS joined) |
| 2 | App reads incoming call info | [x] | `RelayForegroundService` (runtime call-state listener: `TelephonyCallback` on 31+, `PhoneStateListener` on 26–30) |
| 3 | Forwards each event to a server | [x] | `PendingForwards` → `RelayForegroundService` → `RelaySocket` (WebSocket) |
| 4 | Server paired with the app, receives info | [x] | `server/main.py` — 6-digit pairing code, `WS /ws/{role}/{code}` |
| 5 | App asks sender vs receiver role | [x] | `MainActivity.kt` — role picker, persisted in `SessionStore` |
| 6 | Receiver phone paired with server too | [x] | same pairing code, `/ws/receiver/{code}` slot |
| 7 | Instant forward sender → receiver | [x] | WebSocket push, no polling |
| 8 | Receiver shows notification of content | [x] | `IncomingNotifier.kt` (high-priority channel, big text) |
| 9 | Runs on Android 8+ (API 26) | [x] | `minSdk = 26`, API-26 compat shims (see §4) |
| 10 | Status + live event log in UI + **partner connection indicator** | [x] | `AppState.kt` (per-pairing `connStates` + `partnerStates` maps), `HomeScreen.kt` (one `PairingCard` per pairing: own conn state row + "Partner online/offline" badge); partner state refreshed via server `/pair/{code}/status` poll (30 s, immediate first tick) and own socket open/close events. See §8. |
| 17 | Many-to-many pairing (one sender ↔ multiple receivers; one receiver ↔ multiple senders) | [x] | `SessionStore` persists a `pairings: List<PairingInfo>` (JSON under `pairings`, legacy single-code fields auto-migrated on load and kept in sync for compat). Service runs one socket/uplink/Firebase-relay + outbox queue + reconnect job per pairing. UI: Add pairing (menu) / remove-per-card. See §8. |
| 18 | UI redesigned around pairings (hub, per-pairing identity + detail + history, full-screen add/edit) | [x] | `HomeScreen` (hub: hero card, pairing cards w/ labels + dual status pills, feed w/ pairing chips, add-pairing FAB), `PairingDetailScreen`, `AddEditPairingScreen`; `PairingInfo.label` persisted; per-pairing log entries (`AppState.Entry.code`); manual back-stack nav. See §9. |
| 11 | Survive reboot + battery killing | [x] | `BootReceiver.kt`, `BatteryGuard.kt` |
| 12 | Queue events while offline, replay on reconnect | [x] | `OutboxQueue.kt` |
| 13 | Show contact name on incoming calls | [x] | `Contacts.kt` (fuzzy match, cached), resolved on **both** phones — receiver's own contacts first, sender-provided name as fallback; `IncomingNotifier.kt` shows `Name (number)` |
| 14 | Pairings survive server restart | [x] | `server/main.py` (`pairings.json`, new format `{code: {tokens: [...]}}`) |
| 15 | Auth beyond the guessable pairing code | [x] | Server-issued per-device token: WS `auth` → `auth_ok{device_token}` (32 chars, persisted per pairing, max 4 kept). Code stays the bootstrap/pairing key; the token is the relay credential. `server/main.py`, `server-workers/src/worker.js`, `RelaySocket.kt` + `SessionStore.kt` |
| 16 | Optional Firebase transport (no server to run) | [x] | Setup "Connection → Firebase": Firebase Auth (code as e-mail `<code>@nextnotif.local`, per-pairing secret as password) + Realtime Database pub/sub at `pairings/{code}/events`. Default = built-in developer relay project (zero setup); own-project override via pasted snippet. WS server path unchanged. `FirebaseRelay.kt`, `FirebaseConfig.kt`, `SetupScreen.kt` |
| 14 | Pairings survive server restart | [x] | `server/main.py` (`pairings.json`) |

## 2. Delivered architecture (v2, many-to-many)

```
Phone A (SENDER in pairing 1, RECEIVER in pairing 2, ...)
SmsReceiver ─┐
CallReceiver ─┴→ PendingForwards → RelayForegroundService
                                     (foreground, keeps sockets alive,
                                      per-pairing reconnect w/ backoff)
                                     pairing 1: RelaySocket (OkHttp WS) ─┐
                                     pairing 2: RelaySocket (OkHttp WS) ─┤  (one socket,
                                     pairing N: FirebaseRelay (RTDB)    ─┘   outbox queue,
                                             │                             │   device token
                                             ▼                             │   per pairing)
                                 FastAPI server (server/main.py) / workers.dev relay
                                 pairing registry + WS relay
                                             │
                                             ▼
                                   partner phone: RelaySocket → handleIncoming
                                                       → IncomingNotifier
                                                       → Android notification
```

## 8. Many-to-many design (shipped + two-device E2E verified 2026-09-08)

Each phone holds a **list** of pairings instead of a single `(role, code, server)`.
A `PairingInfo` entry tracks:

- `code`, `role`, `server`, `transport`, `fbConfig?`, `secret?`
- partner connection state (`ConnState`) — refreshed from
  `GET /pair/{code}/status` on connect, periodically, and on each open/close
- partner address (last known-good IP from `SessionStore.goodIp`)

The home screen shows one status row per pairing:
```
[● Connected]  Pairing ABC123  Sender  relay.amberdogeorgia.com  [Partner ● Online]
[○ Disconnected] Pairing DEF456  Receiver  Firebase (built-in)   [Partner ● Offline]
```

Only sockets for pairings that are part of an active pairing list run; stopping the
service cancels all of them together. The outbox queue is per-pairing code so an
offline event is replayed to every sender pairing (a shared queue would have
delivered a drained event to only the first pairing that answered).

Implementation notes (2026-09-08):
- `SessionStore` persists the list as JSON under the `pairings` pref; pre-upgrade
  installs (legacy single-code fields) are migrated to a one-entry list on load, and
  a corrupt stored list falls back to the same migration. Legacy fields are kept in
  sync with the first pairing for compatibility.
- Device tokens are **per pairing** (the server issues one per code): a new pairing
  starts with a null token and gets its own via `auth_ok`; tokens are never shared
  across pairings.
- `AppState` now tracks per-pairing own-conn state, per-pairing error, and per-pairing
  partner state; the global `conn` is an aggregate (connected > connecting >
  disconnected > idle) used by the Start/Stop menu.
- UI: menu → "Add pairing" dialog (role/code/server/transport/secret), per-card
  remove with confirm dialog; the old global "Server settings" dialog was removed
  since the server is chosen per pairing.
- The call-state listener registers when *any* pairing is a SENDER (a phone can be
  sender in one pairing and receiver in another); a SENDER pairing added later picks
  it up on the service restart that adding a pairing triggers.
- Server side unchanged — the existing model already supports an unlimited number of
  codes, each with its own sender/receiver slot.

## 9. UI redesign v2 — pairings as the center of the app (2026-09-08)

The v1 UI was built around a single pairing and patched for many-to-many (add-pairing
hidden in a ⋮ menu, cramped dialog, no per-pairing identity or history). v2 makes
pairings first-class:

- **No more global role / setup gate.** The app always opens on the hub; a fresh
  install lands on an onboarding card ("Add your first pairing"). Role is purely
  per pairing (the legacy `SessionState.role` field stays for migration but the UI
  no longer reads or writes it). `SetupScreen` + `AddPairingDialog` are deleted.
- **Hub (`HomeScreen`)** — single scrolling `LazyColumn`: relay hero card (live dot
  in the real aggregate state, Relay on/off, "N pairings" + "N partners online"
  chips, prominent Start/Stop button), notifications-off banner, "Your pairings"
  section, and the global activity feed. A FAB "Add pairing" is the primary action.
- **Pairing card** — role-tinted icon tile + live dot, human label (falls back to
  "Pairing <code>"), role + transport subtitle, and two status pills:
  "This phone: …" and "Partner: …". Per-card ⋮ menu: Edit / Remove (confirm
  dialog). Tap → detail.
- **Pairing detail (`PairingDetailScreen`)** — both status pills + error, the 6-digit
  code large with one-tap copy, role / connection / server / device-token rows, and
  an activity list filtered to this pairing. Edit + Remove actions.
- **Add/edit pairing (`AddEditPairingScreen`)** — full screen (not a dialog):
  optional label, role cards, transport picker, 6-digit code + "Generate code"
  (WS only), server field (WS) or Firebase built-in/own-project + secret.
  Editing a pairing keeps its device token only when the code is unchanged.
- **Per-pairing activity** — `AppState.Entry` now carries `code: String?`; every
  service log push is tagged with its pairing (WS connect/close/error/reconnect,
  outbox queue/flush, per-pairing OUT entries — an event forwarded to N sender
  pairings now yields N tagged entries), and both the global feed (with a pairing
  chip per row) and the detail screen's filtered list derive from it.
- **Navigation** — manual back stack in `MainActivity`
  (`AppScreen.Home / Detail(code) / AddEdit(code?)` + `OnBackPressedCallback`);
  no navigation-compose dependency added. Session state is a `MutableState`
  refreshed after each mutation (replaces the old `recreate()` calls).
- **Persistence** — `PairingInfo` gained `label` (persisted in the `pairings` JSON,
  tolerant of its absence for pre-upgrade installs).
- New drawables: `ic_copy`, `ic_smartphone`. Shared UI pieces moved to
  `Components.kt` (`StatusDot`, `StatusPill`, `TransportOption`, `StepRow`,
  `serverHost`, `ConnVisuals`).

Status: built and unit-verified (45/45 JVM tests, `assembleDebug` +
`assembleRelease` green). **Not yet device-verified** — the two-phone E2E with the
new hub/detail/add screens still needs to be run on the Samsung + Xiaomi pair.

## 10. UI redesign v3 — design language pass (2026-09-09)

Continues the v2 hub UI with one consistent design language across all screens
(behavior, navigation, and data flow untouched; public screen signatures intact):

- **Deterministic brand theme** — `Theme.kt` no longer falls back to Android 12+
  Material You dynamic color, which was silently overriding the brand palette on
  S+ devices; the indigo/sky palette now renders identically on every device. Added
  an app `Typography` scale (22sp semibold titleLarge, 16sp semibold titleMedium,
  11–14sp medium labels) and `Brand.heroGradient` (indigo → violet → sky) used by
  the hero + onboarding tiles.
- **Edge-to-edge** — `MainActivity` uses `enableEdgeToEdge()` (activity 1.9.1)
  instead of a hardcoded status-bar color; Scaffold inner padding already applies
  insets, so content never slips under the system bars.
- **Live status pills** — `StatusPill` is now tinted by connection state (13 %
  alpha of the state color behind the dot), so "Connected" reads green,
  "Connecting" amber, "Offline" red, "Stopped" gray — on home cards and the detail
  screen alike.
- **Home hub** — the app-bar title carries a live status dot; the relay hero card
  switches to the brand gradient with white text and a white pill Stop button while
  running (flat look when idle); section labels use the shared `SectionHeader`;
  activity-feed timestamps are relative (`now` / `N min ago` / `HH:mm` today /
  `M/d HH:mm` older, via the pure `timeAgo` helper); pairing cards get a hairline
  `outlineVariant` border; the onboarding icon tile uses the brand gradient.
- **Pairing detail** — the status card opens with the pairing identity (role tile +
  label + role/transport subtitle, mirroring the home card); the device-token row
  shows a green check when active or an amber warning when not issued; activity
  rows use relative timestamps; all cards get the hairline border.
- **Add/edit pairing** — the Firebase transport option carries a "Recommended"
  badge (`TransportOption` gained an optional `badge` parameter).
- New shared parts in `Components.kt`: `SectionHeader`, `timeAgo`,
  `TransportOption.badge`; `StatusPill` tint change.

Status: `testDebugUnitTest` 45/45, `assembleDebug` + `assembleRelease` (lint + R8)
BUILD SUCCESSFUL. **Not yet device-verified** — same pending Samsung + Xiaomi E2E
as v2 (§9).

## 11. Four reported issues (2026-09-09)

Device-reported: (1) stopping a relay means stopping *all* pairings,
(2) the partner pill never shows the peer's device name ("Partner: Online"),
(3) long activity entries can't be read in full, (4) editing a relay-server
pairing opens the editor with Firebase preselected.

- [x] **Per-pairing stop/start (2026-09-09)** — the service has always connected *every*
      stored pairing on start (`RelayForegroundService.ensureConnected`), and
      the hero Start/Stop button toggles the whole foreground service, so a
      single pairing could not be paused without taking the phone's other
      pairings down with it. Fix: persisted `PairingInfo.enabled` flag
      (default true; absent key parses as true so pre-upgrade installs keep
      relaying), `ACTION_START_PAIRING` / `ACTION_STOP_PAIRING` service
      intents keyed by code, per-pairing teardown (socket + uplink + Firebase
      relay + reconnect job) that leaves the other pairings running,
      forwarding / outbox / partner-poll / call-state guard all skip disabled
      pairings, the service self-stops when no pairing is active anymore, and
      a per-card + per-detail "Stop relay" / "Start relay" menu item. New JVM
      tests: `enabled` persistence (absent key = true, false round-trip) and
      `AppState.clearPairingState` map cleanup + aggregate recompute.
- [x] **Partner device name never showed (2026-09-09)** — two stacked causes:
      (a) the **deployed** worker predates the device-name feature — live
      `GET /pair/<code>/status` on relay.amberdogeorgia.com answered with only
      `{exists, sender_connected, receiver_connected}`, so the app's
      `sender_name` / `receiver_name` reads always fell back to "Partner";
      (b) in the repo worker, `webSocketClose()` resolved the role of a closing
      socket by list membership, so a *replaced* holder's late close event
      (guaranteed by the last-connection-wins takeover) wiped the name the
      newcomer had just advertised in its hello. Fix: the close handler only
      clears the slot/name when no other OPEN socket holds the role (which
      also stops a late close from deleting the newcomer's `pending` auth
      entry — that would have dropped its `auth_ok`); `getStatus` counts only
      OPEN sockets; new smoke check "takeover keeps the new holder's name".
      Worker redeployed to the amber account (version
      `e805ebc6-a25b-4f70-b776-4da543f7db8f`); `test_smoke.py` 26/26 vs
      `wrangler dev` **and** 26/26 vs the deployed URL; live status now
      answers `sender_name`/`receiver_name`.
- [x] **Long activity entries unreadable (2026-09-09)** — incoming SMS bodies
      were truncated to 80 chars in `AppState.pushIncoming` before ever
      reaching the UI, and both feed rows clipped the rest (`maxLines=1`
      detail on home, `maxLines=2` title on detail) with no way to see the
      full text. Fix: store the full body, and tapping any activity row (home
      feed or pairing detail) opens a shared `ActivityDetailDialog` with the
      full title + body, pairing label, and relative + full timestamp.
- [x] **Edit screen preselects Firebase for relay pairings (2026-09-09)** —
      WS pairings persist `transport = null` (the default; `isFirebase`
      checks equality with `"firebase"`), but `AddEditPairingScreen`'s
      `when (editing?.transport)` mapped the `null` case to Firebase, so
      opening a relay pairing for edit showed the Firebase transport selected
      (and a plain "Save changes" would have silently re-saved the pairing as
      Firebase). Fix: `null` (and any unrecognized value) maps to the relay
      server; extracted as a pure       `initialTransportFor()` with 4 unit tests.
      `testDebugUnitTest` 52/52 + `assembleDebug` green for the whole
      four-issue pass.
- [x] **Two-device E2E of the four fixes (2026-09-09, Samsung SM-A520F
      API 26 ↔ Xiaomi 23049PCD8G API 35, LAN relay)** — each phone held 3
      pairings (445566 S/R, 777888 S/R, 889900 R/S):
      - **Per-pairing stop**: card ⋮ → "Stop relay" on 445566 → that card went
        "Stopped/Unknown" while 777888 + 889900 stayed Connected with partners
        online; server confirmed only 445566's sender slot freed; "Start relay"
        restored it (partner name back after the next 30 s poll tick).
      - **Partner name**: both phones showed the peer's device name in the
        pill ("Xiaomi 23049PCD8G: Online" / "samsung SM-A520F: Online") on
        every pairing. Note: the LAN server process on the Mac was a stale
        pre-name build and had to be restarted to serve the name fields.
      - **Edit transport**: editing the relay pairing (stored
        `transport=null`) opened with "Relay server" selected — server field
        + Generate code visible, no secret field.
      - **Full message**: a real carrier SMS (multi-line Persian body)
        forwarded end-to-end; the receiver's entry stored the complete body
        (verified via UI dump text), and tapping a feed row on the Samsung
        opened the detail dialog (title + pairing chip + relative/full
        timestamp). The >80-char case is pinned by
        `AppStateTest.pushIncomingSmsKeepsFullBody`; the dialog tap on the
        Xiaomi itself was not possible (MIUI blocks adb input over wireless).

## 3. Verified

- [x] **Server smoke test passed** (run live, all green): pairing create, typed-code
      auto-creation, both-roles connect, SMS relayed sender→receiver, call relayed
      sender→receiver, reverse direction works, disconnect updates status, bogus role
      rejected.
- [x] **Server tests live in the repo**: `server/test_smoke.py` (self-contained, no
      pytest; starts uvicorn on a free port, also covers persistence across restart).
      Last run: ALL SERVER TESTS PASSED.
- [x] **Server Python syntax check.**
- [x] **Android app compiles** — `./gradlew assembleDebug` BUILD SUCCESSFUL (JDK 17,
      compileSdk 34, AGP 8.5.2).
- [x] **End-to-end device test on a real (emulated) device (API 37)** — all core
      requirements verified live:
      - SMS on sender phone → forwarded to server → receiver phone shows a notification
        with the exact content ("SMS from +15550007777: Notification test: pizza is 20 mins away").
      - Incoming call on sender phone → forwarded → receiver shows "Call RINGING from +15550008888".
      - Role selection, pairing, WebSocket connect/reconnect, boot auto-start, live UI
        status + event log all observed working.
- [x] **On-physical-device test** — verified with a real Samsung SM-A520F running
      **Android 8.0.0** (the minimum target OS) over a real carrier:
      - Real incoming SMS (from `+989173145071`, body `سلام`, non-Latin/Unicode) was
        received by the app, relayed through the server, and captured by the receiver
        with intact content.
      - Receiver-side notifications on the physical device also verified (SMS + call).
- [x] **UI polish pass (real-app feel)** — role-based runtime permission requests
      (receiver is no longer asked for SMS/call/contacts access), setup flow fixes
      (typed code now visible — was `NumberPassword`; full-width "Generate code";
      "How it works" steps), home screen (prominent Start/Stop button instead of
      menu-only; humanized activity feed with icons/titles instead of raw IN/OUT/WS
      log lines; role-aware empty state), branding (custom adaptive launcher icon,
      branded `ic_stat_relay` notification icon, night window theme aligned), and
      stacking incoming notifications (ids no longer collide per number).
      `./gradlew assembleDebug` BUILD SUCCESSFUL.
- [x] **Build environment on the dev machine** — `gradlew` needs JDK 17:
      `JAVA_HOME=/opt/homebrew/opt/openjdk@17`, `ANDROID_HOME=$HOME/Library/Android/sdk`
      (system `java` is 1.8 and fails with a variant-matching error).
- [x] **Two-physical-device E2E retest (2026-09-06)** — Samsung SM-A520F
      **Android 8.0.0 (API 26, the min)** as SENDER + Xiaomi 23049PCD8G
      **Android 15 (API 35)** as RECEIVER, over real carrier + LAN
      (server on 192.168.8.121:8000, pairing code 246810):
      - Real SMS (`خوبی`) Xiaomi→Samsung → relayed → receiver notification
        (high-priority `incoming` channel, branded icon) + home feed entry
        "Text from +989173145071" with intact body.
      - Real call Xiaomi→Samsung → `call RINGING +989173145071` captured by the
        legacy Android 8 listener (caller ID works on API 26) → receiver feed
        "Incoming call from ..." then "Call ended from ...".
      - Exponential-backoff reconnect observed live (server restart mid-test:
        10s→12s→16s→27s→42s attempts, auto-recovered, no user action).
      - Receiver-side permission flow verified: "Notifications are off" banner
        on home → system dialog → Allow (new path, since pairing-time dialog
        is the only other entry point).
- [x] **Physical-device retest with permission gate + auth handshake (2026-09-06, 2nd round)**
      — same two devices (Samsung SM-A520F API 26, Xiaomi 23049PCD8G API 35/MIUI),
      server 192.168.8.121:8000, pairing code 424645:
      - Startup permission gate verified on both: fresh launch shows the locked
        permissions screen listing SMS/Phone/Contacts(/Notifications); granting all
        lifts the gate; reinstall over granted state lifts it immediately.
      - New WS auth verified on-device: both phones connected via `WS /ws/<role>` +
        `X-NextNotif-Code` header, hello → handshake → auth (server log shows both
        `/ws/sender` + `/ws/receiver` accepted from the two phone IPs).
      - Real carrier SMS Samsung→Xiaomi (`+989037199603`): Xiaomi `SmsReceiver`
        fired (logcat 17:46:20) → relayed → Samsung showed high-priority
        `incoming`-channel notification. Closes the Android 14+/MIUI default-SMS-app
        caveat (not required).
       - Xiaomi's foreground relay service confirmed alive under MIUI
       (MilletPolicy HasForegroundService logs).
- [x] **Contact-name fix verified on-device (2026-09-06, 3rd round)** — Samsung
       SM-A520F (API 26) as receiver, code 424645; a scripted fake-sender WS
       delivered three events:
       - SMS `+12368661378` **without** a name → receiver resolved it against its
         own contacts (saved as `+1 (236) 866-1378` — different format) → feed
         "Text from Samin Shadravan (+12368661378)".
       - SMS `+989120001111` with sender-provided name, number not saved locally
         → "Text from Sender Name (+989120001111)" (fallback path).
       - Call `+12368661378` RINGING → "Incoming call from Samin Shadravan
         (+12368661378)". Notifications posted on the high-priority `incoming`
         channel.
- [x] **Instrumented (on-device) test suite** — `android/app/src/androidTest/`:
       `SessionStoreInstrumentedTest` (4), `OutboxQueueInstrumentedTest` (5, incl.
       100-cap + corrupt-data tolerance), `ContactsLookupInstrumentedTest` (5:
       exact / reformatted / zero-prefixed number match against real device
       contacts, short + unsaved → null; auto-skips on a clean device).
       `./gradlew connectedDebugAndroidTest` green on the physical SM-A520F.
       Note: AGP uninstalls the app after a connected run (test-rig artifact).
- [x] **Device-token auth verified across the stack (2026-09-06)**:
       - Server: 19/19 smoke checks incl. token issued on first auth (32 chars),
         kept on reconnect, replaced when stale, persisted + still valid after a
         server restart (new `pairings.json` format `{code: {tokens: [...]}}`,
         old `{code: true}` still loads).
       - Worker: 19/19 vs `npx wrangler dev` (same token semantics, DO storage).
       - Android: paired a real device against the live server — token received
         via `auth_ok` and persisted to SharedPreferences; relay Stop/Start
         reconnected presenting the stored token; **token unchanged** (server
         kept it). Code-only (legacy) auth still accepted → rolling upgrade safe.
       - Live-server restart on the real LAN pairing: phone auto-reconnected and
         its stored token was accepted from the reloaded pairing file.
- [x] **TLS smoke test** — `server/test_tls.py` (self-contained: short-lived
        self-signed cert, uvicorn `--ssl` on a free port, https pairing, wss
        handshake + device token, SMS relay over `wss://`) — ALL TLS CHECKS
        PASSED; added to CI (`server` job).
- [~] **Firebase transport built out (2026-09-07)** — the previous session's
        half-wired Auth + RTDB relay is now end-to-end and compiles (it had
        never been built before):
        - **Default = built-in relay project** (`FirebaseConfig.DEFAULT`, the
          `nextnotif-5bcf9` project) so a fresh install is zero-config: same code +
          secret on both phones, no snippet. Bring-your-own-project stays available
          via "Use your own Firebase project" (paste the web-app snippet); pasted
          config is validated and stored per phone.
        - Setup UI: "Connection" picker (Firebase default / Relay server), pairing
          secret field (6+ chars), inline validation. Config parser rewritten to
          accept the real console snippet (unquoted JS keys — the old `JSONObject`
          path rejected every genuine paste); 10 JVM unit tests.
        - Session persists `transport`/`fbConfig`/`secret`; Firebase pairing skips
          the WS server pre-check; home status card shows "Firebase" (built-in) or
          "Firebase (own)" (custom).
        - `FirebaseRelay` fixes: self-echo filter (events tagged with sender role —
          RTDB broadcasts writes back to the writer), listener/auth teardown in
          `stop()` (no spurious reconnect after user stop), default-app re-init when
          the config changes, `db.getReference(...)` (property-style `reference(...)`
          does not compile — the no-arg overload blocks Kotlin's getter mapping),
          `ValueEventListener` object where a lambda was used, outbox fallback +
          flush on connect (same offline-queue guarantee as the WS path).
        - `assembleDebug`, `assembleRelease` (lint + R8) and `testDebugUnitTest`
          (30/30) all green. Release build also required pre-existing lint fixes:
          API-30/31 guards in `CallScreeningService`/`teardownPhoneStateListener`,
          `fragment` pinned to 1.8.3 (transitive 1.1.0 tripped the Activity-Result
          check), `uses-feature telephony required="false"`.
         - **Not yet device-verified**: project `nextnotif-5bcf9` needs
           Email/Password auth enabled + the RTDB rules published (README
           "Firebase transport" section) before the first pairing can connect.
         - **Device test (2026-09-07, Xiaomi over LTE, no VPN)**: Firebase
           sign-in times out after 30 s ("Couldn't reach Firebase") — the carrier
           blocks Google egress too, so the Firebase transport is unreachable
           from that network without a VPN (see next entry).
 - [x] **Deployed workers.dev relay was broken — found & fixed (2026-09-07)**:
         - `POST /pair/create` answered 200 but **every WS upgrade failed with
           Cloudflare 500 `error 1101`**: `worker.js` called `forwardToPairing()`
           (header-form + legacy path routes) without the function ever being
           defined → ReferenceError. Fixed: `forwardToPairing(env, request, code,
           role)` rewrites the header-form `/ws/<role>` URL to the DO's expected
           `/ws/<role>/<code>` path and forwards to the pairing's DO instance.
         - `server-workers/test_smoke.py` 19/19 vs `wrangler dev` **and** vs the
           deployed URL; deployed as version `f9c614ea-ef49-4a1a-abf1-de5ed1337691`
           at `https://nextnotif-relay.leadroyale.workers.dev`.
 - [~] **No-VPN reachability of the public relay (2026-09-07)** — investigated
         whether the phones can reach the workers.dev relay without any VPN:
         - **App change**: `RelaySocket.kt` DNS flipped from system-first to
           **DoH-first** (`DohFirstDns`): the ISP resolvers hijack
           `*.workers.dev` (LTE: NXDOMAIN; WiFi: intermittent sinkhole answers),
           and DoH endpoints are IP literals (`1.1.1.1`, `9.9.9.9`) so the lookup
           cannot depend on the poisoned resolver; system resolver is the
           fallback when DoH is unreachable; IP-literal servers (LAN `ws://`)
           skip DNS entirely. DoH timeouts cut to 3 s; `RelayDns` log tag shows
           which source served each lookup.
         - **Samsung SM-A520F on home WiFi "Aster"**: device + LAN are fine
           (LAN HTTP to the Mac works), DNS works, ICMP works, TCP handshakes
           complete — but **every direct outbound HTTP/TLS connection stalls
           after the TCP handshake** (OkHttp "SSL handshake timed out"; Chrome
           `ERR_TIMED_OUT` even on `example.com` over plain HTTP/80). The router
           DNS actively hijacks: `example.com` → `188.114.98.0/99.0`
           (CF-hosted sinkhole that serves a *valid* cert for
           `*.leadroyale.workers.dev` and real worker HTTP responses, but
           rejects the WS upgrade), `www.tasnimnews.com` → NXDOMAIN,
           `www.google.com` → real IP, workers.dev subdomain flips between real
           CF anycast IPs and the sinkhole. The Mac on a VPN over the same WAN
           reaches everything — the egress filter is ISP-side, not router-side.
           **Verdict: relay unreachable on this WiFi until the ISP egress
           filter stops interfering** (router reboot / ISP fix / other network).
         - **Xiaomi 23049PCD8G on LTE (no data plan on Samsung)**: DoH
           endpoints blocked (`1.1.1.1` RST, `9.9.9.9` read timeout), system
           DNS sinkholes the relay domain to the same `188.114.98.0/99.0` pair,
           direct WS to CF IPs read-times-out, Firebase sign-in times out —
           the carrier blocks foreign egress broadly. **Verdict: unreachable on
           LTE without VPN.**
         - **What works without a VPN right now**: the **LAN relay** —
           `server/main.py` on the Mac (`ws://192.168.8.121:8000`, currently
           running); both phones on the home WiFi reach it (LAN TCP verified
           from the Samsung). Public workers.dev relay works wherever the
           network permits direct CF egress (verified from the VPN'd Mac:
           full 19/19 smoke suite against the production URL).
         - **Why the ISP lets cloudflare.com/Google through but not the relay
           (2026-09-07, follow-up)**: the egress filter is **per-domain
           (SNI-based), not per-provider**. From the Samsung, no VPN:
           `https://www.cloudflare.com` loaded fully (4.9 MB, "Secure
           connection", TTFB 579 ms) — same CF anycast infrastructure the
           worker sits on — while `*.workers.dev` subdomains are
           DNS-sinkholed (router answers `188.114.98.0/99.0`, a CF-hosted
           relay that serves real worker HTTP but drops the WS upgrade) and
           TLS to real CF IPs with that SNI stalls. Workers is a common
           circumvention tool, so the zone is blocklisted; `cloudflare.com`
           and `google.com` are not.
         - **Firebase transport also region-blocked (2026-09-07)**: from the
           home WiFi the app reaches Google's Identity Toolkit fine (TLS
           works) but both `signupNewUser` AND `verifyPassword` (sign-in)
           answer **403 with a raw HTML block page** — Google's own
           regional restriction on this (fresh) account/IP, not the ISP.
           Sign-in succeeds from the VPN'd Mac with the same credential,
           proving it's IP/region-based. App fix applied: `FirebaseRelay`
           now falls through to sign-in on *any* create failure except
           `OPERATION_NOT_ALLOWED` (the old fallback only matched the
           parseable `ERROR_EMAIL_ALREADY_IN_USE`, which the block page
           never produces); `ERROR_USER_NOT_FOUND` gets its own message.
           So Firebase pairing must be bootstrapped from an unrestricted
           network (user `111222@nextnotif.local` now exists, created via
           Identity Toolkit from the Mac) — and even then sign-in from the
           censored IP is 403 for a fresh account.
         - **RESOLVED (2026-09-07, evening): custom-domain relay, both phones
           connected with no VPN.** The workers.dev zone is blocklisted by both
           the home ISP (WiFi) and the mobile carrier (LTE), but a *different*
           SNI on the same CF infrastructure is not. Final setup:
           - The relay worker was deployed into the **amberdogeorgia.com
             account** (`shayanshad@gmail.com`, free plan — required
             `new_sqlite_classes` migration, see `wrangler.amber.jsonc`) as
             `nextnotif-relay.shayanshad.workers.dev`; the old
             leadroyale-account deployment stays live as backup.
           - **`relay.amberdogeorgia.com`** (user's own domain, zone in the same
             CF account) is bound to the worker via the dashboard custom-domain
             flow (proxied CNAME). Note: a CNAME to another *account's*
             workers.dev 403s with CF error 1014 — domain and worker must
             share an account.
           - Verified end-to-end, no VPN on either device:
             - Samsung (home WiFi): browser loads the worker page (462 ms,
               32 B, no errors); app: system DNS → real CF IPs (no rewrite),
               `ws open` + `device token stored`, UI "Connected — Relaying";
               pairing `600661` sender slot filled. (DoH on the Samsung is
               unusable — 1.1.1.1 fails Android 8's trust store, 9.9.9.9
               stalls — but the DoH-first → system fallback handles it.)
             - Xiaomi (LTE, no data plan needed on Samsung): DoH succeeded
               this time (`via DoH -> [104.21.81.8, 172.67.136.151]`),
               `ws open`, receiver slot filled.
             - `GET /pair/600661/status` →
               `{"exists":true,"sender_connected":true,"receiver_connected":true}`.
            - App server URL for both phones: `wss://relay.amberdogeorgia.com`.
          - **FOLLOW-UP (2026-09-08): "sometimes connects, sometimes doesn't"
            on the home WiFi — root-caused from live device logs and fixed.**
            Three independent failure modes were stacking:
            1. **The ISP now sinkholes `relay.amberdogeorgia.com` too,
               intermittently.** The home router's resolver flips the answer
               for the relay name between the real CF anycast pair
               (`104.21.81.8`/`172.67.136.151`) and a domestic sinkhole pair
               (`188.114.98.0`/`188.114.99.0`) from one query to the next —
               same behavior previously seen for `*.workers.dev`. Worse, the
               sinkhole pair sits *inside published Cloudflare address space*
               (188.114.96.0/20), so no IP-range check can tell them apart.
               Sinkhole connections stall at TLS ("SSL handshake timed out" /
               "Read timed out"); real-IP connections open in ~1 s.
            2. **Every DoH endpoint is blocked from the WiFi.** 8.8.8.8 /
               8.8.4.4 TLS-stall (3 s read timeout), 1.1.1.1 is RST, and the
               domain forms are poisoned too (`dns.google` /
               `cloudflare-dns.com` → NXDOMAIN or `10.10.34.35`, a private-IP
               sinkhole). Plus the Samsung's Android 8.0.0 trust store
               predates the issuing roots (GTS, SSL.com), so even a reachable
               DoH endpoint would fail validation.
            3. **Worker zombie slots → 403 storms.** When the ISP cuts a
               connection without a FIN, the DO instance kept the dead socket
               as its slot holder; reconnects from the same phone got
               `403 slot occupied` until the CF edge eventually reaped the
               zombie (minutes — observed 00:28–00:37 on the Xiaomi). The old
               eviction path only evicted *unauthenticated* holders.
            Fixes (all deployed):
            - **Worker: last-connection-wins takeover.** A new same-role WS
              connection now closes every previous holder (`1000 replaced`)
              and is accepted, instead of 403 — an authenticated zombie slot
              can no longer wedge the phone out. `liveSockets()` only counts
              OPEN sockets. `test_smoke.py`: "slot occupied 403" check
              replaced by "slot takeover" (second sender accepted, first
              holder closed 1000 'replaced'); 19/19 green vs `wrangler dev`.
              Deployed as `f43cd29d-7221-47f0-9758-197555e79f05`.
            - **Android: verified resolution instead of blind DoH-first.**
              `DohFirstDns` now builds a candidate list — persisted
              known-good IP (`SessionStore.rememberGoodIp`, the last address
              that completed a full relay handshake), DoH answers (IP-literal
              8.8.8.8/8.8.4.4/1.1.1.1, then domain
              `dns.google`/`cloudflare-dns.com` for networks that block the
              IPs but allow the provider domains), then the system resolver —
              and **verifies each candidate with a 3 s TLS probe** before
              handing it to the socket (real edge: ~700 ms; sinkhole: stalls
              past the budget). Bad addresses are cached process-wide for 10
              min. IP-literal servers (LAN) skip all of it.
            - **Android: DoH trust that works on Android 8.** DoH moves from
              `HttpURLConnection` (can't take a custom trust manager) to a
              dedicated OkHttp client with layered trust: platform store →
              PKIX against platform + bundled roots (`DohRoots.kt`: GTS Root
              R1 self-signed + cross-signed, GTS Root R4 self-signed +
              cross-signed, SSL.com Root ECC — the relay domain chains to
              GTSR4, DoH chains to GTSR1/SSL.com) → clearly-logged
              unvalidated retry. SAN-only hostname verification replaces the
              platform verifier (which would re-check the chain against the
              stale system store). The relay socket itself is never relaxed.
            - **Android: churn + backoff fixes.** The app can start the
              service twice in quick succession (double `onStartCommand`),
              which with takeover would ping-pong sockets; a generation guard
              in `RelayForegroundService.connect()` drops events from
              superseded sockets. Reconnect backoff capped 60 s → 15 s.
              `stop()` now resets the conn state (UI no longer shows a stale
              "Connected" after stopping). WS `connectTimeout` 5 s so a
              stalled address fails over fast.
            - **Live verification (no VPN, home WiFi):** Samsung `ws open`
              in ~1 s of the DNS step (`TLS probe ok (725ms)` +
              `ws open`), real SMS relayed Samsung→Xiaomi end-to-end.
              Takeover drill: a third connection from the Mac seized the
              sender slot (101 + auth, no 403); the Samsung detected the
              `replaced` close and re-took the slot in ~5 s
              (`ws open` 09:45:49, status green again).
            - **Remaining caveat:** if the ISP ever starts stalling TLS to
              the *real* CF IPs too (escalation beyond DNS), the probes
              prove it in logs and the relay is unreachable until the filter
              changes — the LAN relay (`server/main.py`) stays the fallback.
              Note the Python LAN server has the same zombie-slot gap as the
              old worker (no takeover) — acceptable while LAN is the
               zero-VPN fallback only.
 - [x] **Many-to-many pairing + per-partner status indicator built out (2026-09-08)**:
       - Completed the half-wired scaffold from the prior session. The keystone bug:
         `SessionStore` accepted a `pairings` list but never persisted it — saving
         only wrote the legacy single-code fields, so a second pairing was lost on
         every restart. Now the list is persisted as JSON (`pairings` pref) with
         legacy-field migration on load and corrupt-JSON fallback.
       - `AppState`: per-pairing own-conn state, per-pairing error, per-pairing
         partner state; global `conn` is now an aggregate over all pairings (the
         Start/Stop menu reflects the whole phone).
       - Service: own-socket open no longer marks the *partner* online (partner
         state comes only from the `/pair/{code}/status` poll, which now fires an
         immediate first tick instead of waiting 30 s); device tokens saved per
         pairing (previously a shared field, so pairing B could clobber A's token);
         call-state listener registers when *any* pairing is SENDER; `stop()`
         clears all per-pairing state.
       - Outbox is now per-pairing: a shared queue meant a drained offline event
         went to the first sender pairing that answered and was re-queued only when
         ALL failed — partial failures re-sent duplicates to already-working
         pairings. Each pairing now has its own FIFO (cap 100) and its own retry.
       - UI: each `PairingCard` shows own state (pulsing dot + label) and a
         "Partner online/offline/unknown" badge; per-card remove with confirm
         dialog; "Add pairing" dialog from the menu. The stale global
         "Server settings" dialog was removed (server is per pairing now).
       - Tokens: a newly added pairing starts with a null device token and gets its
         own from `auth_ok`; re-pairing the same code reuses the stored token.
       - `./gradlew testDebugUnitTest` 43/43 (new: 5 `SessionStoreTest` list
         round-trip/migration/corrupt-JSON cases, 6 `AppStateTest`
         per-pairing + aggregate cases, 2 `OutboxQueueTest` per-pairing isolation
         cases); `assembleDebug` + `assembleRelease` BUILD SUCCESSFUL.
       - **Instrumented re-run on the physical SM-A520F (Android 8.0.0, API 26):**
         `connectedDebugAndroidTest` — 18 instrumented cases: 13 passed, 5
         auto-skipped (ContactsLookup, clean device), 0 failures. Added on-device
         cases: `OutboxQueueInstrumentedTest.queuesAreIsolatedPerPairingOnDevice`,
         `SessionStoreInstrumentedTest.pairingsListSurvivesRealSharedPreferencesRoundTrip`,
         `.removingAllPairingsClearsLegacyFields`, `.legacyOnlyPrefsMigrateToPairingsList`.
       - **Two-physical-device E2E (2026-09-08 evening)** — Samsung SM-A520F
         (API 26) as RECEIVER for pairings 111222 + 333444, Xiaomi 23049PCD8G
         (API 35/MIUI) as SENDER for the same two pairings, LAN relay
         `ws://192.168.8.121:8000`:
         - Each phone shows one card per pairing with own state **Connected** and
           the live **Partner online** badge (verified via UI dumps on both
           phones; MIUI blocks adb input injection over wireless, so the Xiaomi's
           off-screen partner badge was confirmed via the same poll data the
           Samsung rendered).
         - Scripted senders delivered test SMS to the Samsung on BOTH pairings
           (notifications posted on the `incoming` channel + feed entries);
           per-pairing device tokens issued and kept across a server restart.
         - A real call on the Xiaomi (API 35, number hidden — documented
           limitation) relayed to the Samsung: "Incoming call / Call connected /
           Call ended" feed entries.
         - Live outbox proof: the Xiaomi's events while the link was down queued
           per pairing and flushed per pairing ("flushed 3 queued event(s) for
           111222", "flushed 2 … for 333444").
       - **Bug found & fixed during that E2E: stale-socket state clobber.** After
         a server restart, a superseded socket's late close/failure events could
         land after the replacement socket was already open, leaving a card stuck
         on "Connecting..." until the service was restarted. The 2026-09-08
         morning "generation guard" from the churn fixes had been lost in the
         refactor. Restored and extended in `RelayForegroundService.connect()`:
         (a) never replace a socket that is already open (reconnect jobs racing a
         live socket no longer churn it), (b) the callback checks
         `sockets[code] !== <this socket>` and drops state-changing events from
         superseded sockets (Incoming events are still delivered — the relay
         sends each event to a single socket, so a stale delivery is the only
         copy). Verified: double server-restart storm on the Samsung left both
          cards stably "Connected + Partner online" with no stuck state.
          `testDebugUnitTest` 43/43 + `assembleDebug` green after the fix.
  - [x] **UI redesign v2 built (2026-09-08)** — pairings as the center of the app
          (see §9): hub with relay hero card + per-pairing cards + global activity
          feed with pairing chips, pairing detail screen (code copy, token status,
          per-pairing activity), full-screen add/edit pairing (label, role,
          transport, code/server/secret), per-pairing tagged log entries
          (`AppState.Entry.code`), manual back-stack navigation, `PairingInfo.label`
          persisted. `testDebugUnitTest` 45/45 (new: 2 `AppStateTest` per-pairing
          code-tag cases, `SessionStoreTest` label round-trip + `displayName`
          fallback), `assembleDebug` + `assembleRelease` (lint + R8) BUILD
           SUCCESSFUL. **Device E2E of the new screens still pending** on the
           Samsung + Xiaomi pair.
   - [x] **UI redesign v3 built (2026-09-09)** — design language pass over the v2
           hub (see §10): deterministic brand theme + app typography (Material You
           dynamic color no longer overrides the palette on S+), edge-to-edge
           system bars (`enableEdgeToEdge`), state-tinted status pills, gradient
           relay hero card (white pill Stop button while running) with a live
           status dot in the app-bar title, `SectionHeader` section labels,
           relative-time activity feed (`timeAgo`), pairing-detail identity header
           + token state icon (green check / amber warning), hairline
           `outlineVariant` card borders, "Recommended" badge on the Firebase
           transport option. `testDebugUnitTest` 45/45, `assembleDebug` +
           `assembleRelease` (lint + R8) BUILD SUCCESSFUL. **Not yet
           device-verified.**

## 4. Android 8 (API 26) compatibility — done

- `minSdk = 26`
- `startForegroundService()` for all starts; `startForeground()` called in `onCreate()`
  (meets the 5 s deadline on Oreo)
- Notification channels created eagerly
- `FOREGROUND_SERVICE_TYPE_*` only applied on API 29+
- `POST_NOTIFICATIONS` only requested on API 33+
- `usesCleartextTraffic="true"` so plain `ws://` works on API 28+
- Reboot survival: `BootReceiver` restarts the service (`RECEIVE_BOOT_COMPLETED`)
- Battery optimization exemption: `BatteryGuard` requests the whitelist (button in UI),
  the main failure mode for background WS on Android 8–12

## 5. Bugs found & fixed during device testing

Testing on a real (emulated) API 37 device caught four issues that code review missed:

1. **Compile errors (6)** — caught by the first `assembleDebug`:
   - `Intent.ACTION_PHONE_STATE` doesn't exist → use the literal `"android.intent.action.PHONE_STATE"`.
   - `Phone.LOOKUP_URI` doesn't exist (only `Email`/`Contacts` have it) → query
     `Phone.CONTENT_URI` with a `NUMBER = ?` selection instead.
   - `import androidx.compose.foundation.lazy.item` is invalid (`item` is a
     `LazyListScope` member, not top-level) → removed the import.
   - Smart-cast on a delegated `lastError` property → captured into a local `val`.
   - `SharedPreferences.getString(key)` 1-arg form is missing from the SDK stub →
     pass an explicit default (`getString(key, null)`).
2. **FGS `phoneCall` type `SecurityException`** — starting a foreground service with
   type `phoneCall` requires `MANAGE_OWN_CALLS`/dialer role, which we don't have. The
   service crashed on start. Fixed by using only the `dataSync` FGS type (we observe
   calls; we don't manage them).
3. **`PHONE_STATE` manifest receiver never fires on API 26+** — the `PHONE_STATE`
   broadcast is subject to the implicit-broadcast restriction, so a manifest-declared
   receiver is never delivered it. Fixed by registering a runtime listener in the
   service: `TelephonyCallback.CallStateListener` on API 31+, legacy
   `PhoneStateListener` on API 26–30. Note: the modern (31+) API drops the incoming
   number for privacy, so the number is "unknown" on 31+ (state still forwards); the
   26–30 path still supplies the number.
4. **Runtime permissions must be granted** — manifest receivers aren't invoked until the
   dangerous permissions (`RECEIVE_SMS`, `READ_PHONE_STATE`, …) are granted at runtime
   (the "Connect" button does this). Verified the full flow works once granted.

Also confirmed: the "Android 14+ default-SMS-app" restriction in the old notes is **not**
required on stock AOSP/Google images — a third-party app with the runtime `RECEIVE_SMS`
permission does receive `SMS_RECEIVED`. Some OEM skins (Samsung/MIUI) may still gate it,
so that remains a physical-device caveat to check.

The UI polish pass (2026-09-06) also found compile errors the previous session had left
behind (the UI rewrite had never been built):

5. **Stale/incorrect API references** — caught by `assembleDebug`:
   - `Icons.Filled.Stop` does not exist in `material-icons-core` (only a curated set is
     on the classpath via material3) → replaced with vector drawables
     `res/drawable/ic_stop.xml` / `ic_play.xml` + `painterResource`.
   - `FastOutSlowInEasing` lives in `androidx.compose.animation.core`, not
     `androidx.compose.animation` (the non-core `animation` artifact is not a dependency).
   - `Color.toArgb()` is an extension requiring an explicit import
     (`androidx.compose.ui.graphics.toArgb`).
   - M3 1.2.1 (BOM 2024.06.00) has no `OutlinedTextField(textAlignment=...)` param —
     centering goes in `textStyle = ...copy(textAlign = TextAlign.Center)`; and no
     `Card(onClick=...)` / `contentPadding` (added in M3 1.3) — `RoleCard` uses
     `Modifier.clip(...).clickable(...)` instead.
   - `BorderStroke` is in `androidx.compose.foundation`, not `...foundation.border`.
   - Compose 1.6 `ImageVector.Builder.addPath` takes `List<PathNode>`, not a path-data
     string (string overload arrived in 1.7).

The two-device E2E retest (2026-09-06) caught one runtime crash code review missed:

6. **Receiver service crash on Android 11+** — `setupPhoneStateListener()` ran
   unconditionally in `RelayForegroundService.onCreate()`, so on a RECEIVER phone
   (which correctly does NOT hold `READ_PHONE_STATE`) `registerTelephonyCallback`
   threw `SecurityException` and the whole service died on start. Fixed: the
   telephony listener is now registered only for the SENDER role, registration is
   wrapped in `runCatching` (permission loss degrades gracefully + surfaces
   `lastError`), and the spurious initial `IDLE` event at registration is filtered
   out so both phones don't log "Call state forwarded: IDLE ()" on every start.
   Also added a "Notifications are off" banner + one-tap re-grant on the receiver
   home screen (Android 13+) — previously the only way to (re-)grant
   `POST_NOTIFICATIONS` was resetting the pairing and going through setup again.

7. **Contact name shown only when saved on the SENDER phone / "unknown" calls
   (2026-09-06)** — the name was resolved only on the sender via an exact-match
   query on the raw number, so (a) a contact saved on the receiver's phone never
   showed, and (b) format differences (`0912…` vs `+98912…`) broke the lookup.
   Fixed:
   - `Contacts.lookupName` now normalizes (digits, leading zeros dropped) and
     falls back to a full-book scan with suffix matching (min 7 digits), cached
     in-memory (cap 512, misses included).
   - Receiver side resolves the name against its **own** contacts in
     `handleIncoming` (local hit wins, sender's `name` payload is the fallback)
     and the resolved name feeds both the notification and the activity feed
     (`Name (number)`).
   - The half-finished call-screening work from the previous session didn't
     compile against this SDK: `Telecom.EXTRA_INCOMING_NUMBER` and
     `Settings.ACTION_CALL_SCREENING_SETTINGS` don't exist here — the stub's
     `onScreenCall` takes `Call.Details` (number from the `tel:` handle URI,
     system contact name from `contactDisplayName`/`callerDisplayName`), and the
     menu item now tries the literal `android.settings.CALL_SCREENING_SETTINGS`
     action with an app-settings fallback.
   - `handleCallState` had `finalNumber` inferred as `String?`
     (`currentCallNumber` is nullable) — now explicitly non-null.
   - `./gradlew assembleDebug testDebugUnitTest` green (19/19).
   - Caveat: if a sender phone is API 31+ and call screening is not enabled, the
     number still arrives as "unknown" and no name can be resolved (no digits to
     look up); enabling screening via the home menu fixes the number.

## 6. Remaining work

### Short-term (finish the current pass)

- [x] **UI: connection status indicator + live event log** — `AppState` (StateFlow of
      conn state / error / last-50 events) wired into a status row + scrolling LazyColumn
      in `MainActivity.kt`.
- [x] **Battery-optimization exemption prompt** — `BatteryGuard.kt` + "Battery: request
      exemption" button.
- [x] **`gradlew` wrapper jar + scripts** — v8.7.0 wrapper committed.
- [x] **Startup permission gate** — on app start the app now requests ALL runtime
      permissions (SMS + phone + contacts, plus notifications on 13+) regardless of role,
      and stays locked on a permissions screen (`PermissionsGate.kt`) until every one is
      granted; pairing-time role-based dialog removed. Permanently-denied perms get an
      "Open app settings" shortcut.
- [x] **Release build ProGuard rules** — `android/app/proguard-rules.pro` added;
      `isMinifyEnabled = true` for release (R8 + proguard-android-optimize);
      `./gradlew assembleRelease` BUILD SUCCESSFUL (OkHttp/Compose covered by consumer
      rules; no app-specific keeps needed — no reflection/JNI).
- [x] **Unit tests (JVM)** — `OutboxQueueTest` (6), `AppStateTest` (8), `SessionStoreTest` (5)
      under `android/app/src/test/` with an in-memory SharedPreferences fake
      (`FakeSharedPreferences.kt`); minimal internal-overload refactor in `OutboxQueue`/
      `SessionStore` for JVM testability. `./gradlew testDebugUnitTest` 19/19 green.

### Medium-term (make it actually usable on real phones)

- [x] **Many-to-many pairing + partner status indicator** — each phone stores a list
      of `PairingInfo` (code, role, server, transport) instead of a single one; the
      home screen shows a card per pairing with its own live connection state and a
      "Partner online/offline" badge (refreshed via `GET /pair/{code}/status` and own
      socket open/close events). The relay service keeps one socket per active
      pairing, with per-pairing outbox queues, device tokens, reconnect jobs, and
      errors. Menu → "Add pairing" and per-card remove. See §8 for design.

- [x] **Build & device test** — compiled (JDK 17), tested end-to-end on an API 37
      emulator AND on a real Samsung (Android 8.0.0) with a real carrier SMS. SMS +
      call forwarding and receiver notifications all verified on both.
- [x] **Android 14+ SMS caveat** — NOT required on stock AOSP/Google images AND on
      MIUI: verified 2026-09-06 with a real carrier SMS on the Xiaomi 23049PCD8G
      (Android 15, MIUI) as SENDER — `SmsReceiver` fired on a non-default app with
      runtime `RECEIVE_SMS` (`logcat: SmsReceiver: Forwarding SMS from +989037199603`),
      relayed to the Samsung which showed the high-priority incoming notification.
- [x] **Server persistence** — pairings survive restarts via `server/pairings.json`
      (atomic write, loaded on startup).
- [x] **Auth hardening** — pairing code is no longer the only credential, and it no longer
      rides in the WS URL path for app traffic:
      - Protocol: client connects to `WS /ws/<role>` with header `X-NextNotif-Code`
        (legacy `/ws/<role>/<code>` path still accepted), sends `{"type":"hello"}`,
        server replies `{"type":"handshake","token":<secrets.token_urlsafe(16)>}`,
        client must answer `{"type":"auth","token":...,"code":...}` within 5 s.
        Close 1008 "auth timeout" / "auth failed" otherwise. Relay only after auth;
        duplicate auth from an authenticated socket is ignored.
      - Implemented in `server/main.py` (asyncio wait_for), `server-workers/src/worker.js`
        (DO storage `pending = {role: {token, stage}}`, per-role `setTimeout` auth timeout —
        open websockets keep the DO instance awake; a new connection evicts a stale
        still-unauthenticated slot holder), and `android/.../RelaySocket.kt` (hello on
        open, `isAuthenticated` gate on `send()`, `Event.Open` fires only once authed so
        the outbox flush never drops).
      - Both smoke suites extended: wrong-token, wrong-code, no-auth-timeout (~5.0 s),
        header-code connect, duplicate-auth-ignored. Python 17/17 + worker 17/17 green
        (worker run against `npx wrangler dev --port 8787`).
- [x] **Offline queue** — `OutboxQueue.kt` (SharedPreferences, cap 100) enqueues on send
      failure, drained on WS open; "flushed N queued event(s)" logged.
- [x] **Caller ID lookup** — `Contacts.kt` resolves contact names on the sender; name
      rides in the payload and appears in the receiver's call notification titles.
      `READ_CONTACTS` declared + requested at runtime.

### Long-term (production quality)

- [~] **wss:// TLS** — Deploy guide ready in `server/TLS.md` (Caddy auto-HTTPS
  Caddyfile, nginx block with WS upgrade headers + long read timeout, Cloudflare
  Workers already TLS, app-side `wss://` usage, note on when
  `usesCleartextTraffic` can go). Server-side TLS path proven by
  `server/test_tls.py` (in CI). **Public deployment done**: the relay is live at
  `https://nextnotif-relay.leadroyale.workers.dev` (2026-09-07, after fixing the
  missing `forwardToPairing` — see Verified); 19/19 smoke suite passes against
  the production URL. Remaining: the phones' current networks (Iran ISP WiFi +
  LTE) filter direct Cloudflare/Google egress, so the public relay is only
  usable from networks that permit it (or via VPN); on those networks the
  app's DoH-first DNS handles the resolver hijack. LAN `ws://` stays the
  zero-VPN fallback.
- [ ] **FCM wake fallback** — if the foreground service is killed, FCM data
      messages can wake the receiver phone. The Firebase transport (req 16) removes
      the server dependency but still needs a live process on both phones; FCM is
      the missing piece for delivery after a hard kill on either transport.
- [x] **Auth for real** — shared-secret model beyond the pairing code, implemented as
  server-issued per-device tokens (see requirement 15 + Verified above). Protocol:
  client `auth{token, code, device_token?}` → server `auth_ok{device_token}`.
  Presented-and-known token is kept; unknown/absent gets a fresh one (max 4 per
  pairing, oldest evicted). Persisted on the server (pairings.json / DO storage)
  and in the app (SharedPreferences). The 6-digit code remains the human pairing
  key + bootstrap; the token is the ongoing relay credential. An account model
  (user login, per-user pairings, remote revoke) is a possible future extension.
- [x] **Test suite** — [x] server: `server/test_smoke.py` (19 checks incl. auth protocol
  + device tokens) and `server/test_tls.py` (TLS/wss). [x] worker:
  `server-workers/test_smoke.py` (19 checks, run vs `wrangler dev`, incl. device
   tokens). [x] JVM unit: `OutboxQueueTest`/`AppStateTest`/`SessionStoreTest`/
   `FirebaseConfigTest` (43 tests, 2026-09-08). [x] instrumented (on-device):
   `SessionStoreInstrumentedTest` / `OutboxQueueInstrumentedTest` /
   `ContactsLookupInstrumentedTest` (18 cases; ContactsLookup auto-skips on a
   clean device).
  Not covered: a direct unit test of `SmsReceiver`/`CallScreeningService`
  dispatch (protected broadcasts can't be sent by an app) — those are covered
  by the live-device E2E rounds instead.
- [x] **CI** — `.github/workflows/ci.yml` on push/PR: `server` job (pip install +
  `test_smoke.py` + `test_tls.py`) and `android` job (JDK 17, `testDebugUnitTest
  assembleDebug assembleRelease`, APKs uploaded as artifact). No workers job
  (wrangler needs a CI account).

## 7. Known limitations (accepted for v1)

- Pairing codes are guessable (6 digits); fine for a trusted private LAN, not for public internet.
  The ongoing relay now uses a server-issued 32-char device token, but the code still
  bootstraps a pairing (a new device needs it), so a leaked code can still be used to
  join **before** it is rotated by re-pairing.
- **Call number is "unknown" on Android 11+ (API 31+)** — the modern `TelephonyCallback`
  drops the incoming number for privacy. Call state (RINGING/OFFHOOK/IDLE) still forwards;
  the number + contact name are available on API 26–30 via the legacy listener. On 31+
  the user can enable NextNotif as the call-screening service (home menu → "Call
  screening settings") to recover the number (and a system-resolved contact name).
- SMS forwarding requires the app to have the runtime `RECEIVE_SMS` permission granted
  (the "Connect" button requests it). On some OEM skins it may additionally require being
  the default SMS app.
- Receiver notifications are standard app notifications, not system call/SMS UI.
- No message history on the receiver — each event is one-and-done.

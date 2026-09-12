# NextNotif agent handoff — 2026-09-12

## Pause and goal

User requested: pause implementation, update roadmap/goal, commit and push so another
agent can continue. Product goal is already **paused**, not complete. The goal remains
a friendly reliable Android MVP for SMS/call information over FCM/HTTPS, with optional,
default-off rooted Samsung A5 live-call beta. Resume only when requested.

## Current architecture and evidence

- Idle delivery uses FCM wake-up and HTTPS fetch/explicit acknowledgment. Temporary
  WebSockets carry live call controls and SDP/ICE, **not audio**.
- RelayForegroundService selects native WebRTC/Opus. The old G.711 bridge remains
  source-only and is not an automatic media fallback.
- Native WebRTC is pinned to io.getstream:stream-video-webrtc-android:145.9.0.
  Preserve advertised codec capability objects; mutating their parameters previously
  caused native codec preference rejection. Current SDP checks require Opus with DTX
  absent (default off) or explicitly zero.
- Android unit tests, assembleDebug, assembleRelease and lintDebug most recently all
  passed, including the pending call UI changes. No physical UI validation of those
  latest changes has happened.
- Audio-disabled same-process native tests passed on both phones. A separate
  audio-disabled **two-phone** host-only ICE probe failed with Xiaomi VPN enabled,
  then passed on both phones after the user disabled VPN. It proves LAN negotiation,
  not real cellular audio, root routing, silence/mute behavior or VPN support.
- server/webrtc_probe.py is an ephemeral localhost-only test signaling fixture, not
  a production service. Its three Python tests passed. No fixture/ADB reverse from
  that probe is intentionally left running.
- Compatible production Worker last deployed version:
  9d1489af-35c8-4da4-909c-522cf3051e6d. New secure-pairing work is not a completed
  production migration. Legacy bootstrap/auth still needs hardening; secure WS
  currently refuses access until its implementation is finished.

## Installed versus pending

Both phones have the WebRTC service, bounded root hang-up command, inbound RTP
diagnostic and offline editing of unchanged pairing preferences. Those installations
preserved data. Root hang-up/RTP diagnostics still need real-call validation.

Pending source changes add an Answer button in RelayCallActivity for enabled receiver
pairings while Ringing, a secondary Close button, current displayed-call callbacks,
intent recreation handling and RelayCallUiPolicy tests. These changes build but are
**not installed**. Do not present them as physically verified.

Read-only peer review identified two unfixed defects in this pending flow:

1. Repeated answer=true intents invoke Answer again during ACTIVE. The service lacks
   an idempotency guard, resets UI/timer to ANSWERING, then ignores the same ready
   session. Guard controller and authoritative service state before resetting it.
2. RelayCallActivity.onCreate manufactures incoming state when an old intent's code
   differs from the current call, overwriting a real call's identity. Reject stale
   identity; handle explicit process-death recovery separately and safely.

Fix both and test duplicate, stale, pending and process-death intents before installing
the pending UI. Do not auto-enable the root live-call toggle by editing private prefs.

## Physical test handoff

- Samsung sender: SM-A520F, Android 8, rooted, USB serial 52006a98f0ac6489.
- Xiaomi receiver: 23049PCD8G; Wi-Fi ADB last identifier
  adb-53d75ef-ChIRTM._adb-tls-connect._tcp (rediscover if changed).
- User disabled Xiaomi VPN and is ready for a test call. We asked them to wait until
  explicitly invited to call, while enabling Samsung **Live call relay (beta)** in
  Edit pairing and saving. Last confirmed sender preference was absent/default-off;
  no subsequent successful opt-in confirmation is available.
- A screenshot previously showed Save stuck at Connecting. Offline preference-save
  fix was installed on both phones and normal startup restored. Ask/verify owner
  retry. Do not interrupt an unsaved setup form with another install without warning.
- Never initiate private call recording/capture automatically. Confirm opt-in/readiness,
  explicitly ask for a controlled call, verify actual RTP and both-way speech, then
  silence/mute, volume/latency, hang-up, recovery and FCM idle return.
- No current real-call result should be inferred from the successful audio-disabled
  probe. TURN/STUN are not configured; current media connectivity is host-only LAN.

## Build and repository

From android with JDK 17 and Android SDK configured:

    env JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/Users/shayan/Library/Android/sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug

Gradle/ADB/local sockets can require sandbox escalation. Do not rebuild an APK while
adb install is streaming that same file. Never run the whole instrumentation suite on
paired phones: older preference tests reset saved pairings; use explicit opt-in classes.

android/app/google-services.json is local and intentionally excluded from Git. A fresh
checkout must supply its own Firebase Android configuration for com.nextnotif.app
before building. Service-account keys, root assets, gallery backups, APKs and runtime
pairing stores must stay uncommitted. No phone data should be wiped for this handoff.

The snapshot includes accumulated app/server work and the user's design changes.
See roadmap.md, WEBRTC_MIGRATION.md, DELIVERY_PROTOCOL.md, PRODUCT.md and
MVP_RELEASE_CHECKLIST.md for the wider plan. Prioritize the actionable items at the
top of roadmap.md; historical completed prototype entries are not WebRTC release proof.

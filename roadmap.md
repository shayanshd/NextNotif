# NextNotif — Roadmap

Goal (from original request): an Android app that reads received SMS and incoming calls,
forwards each event to a paired server, which instantly relays it to the same app on a
second paired phone and shows a notification of the content. Each phone can participate
in **multiple pairings** simultaneously — one sender can relay to several receivers, and
one receiver can listen to several senders. On each phone, the UI shows the live
connection status of every paired partner.

Status legend: [x] done · [~] in progress / partially done · [ ] not started

## MVP execution roadmap (resumed 2026-09-12)

Latest checkpoint 2026-09-13 (overrides historical TURN pending/billing notes below):

- [x] Owner activated Cloudflare; keys stored in GitHub and encrypted Worker secrets.
- [x] Authenticated, rate-limited temporary Cloudflare TURN credential endpoint deployed.
- [x] Forced-TURN no-audio two-phone test passes across Samsung Wi-Fi / Xiaomi cellular.
- [x] Actual call path provisions TURN on IO, buffers early session signals and bounds setup.
- [x] Build/unit/lint checks pass; latest call build installed on both phones, data preserved.
- [ ] Owner live cellular call: two-way speech/RTP, volume, latency and sustained quality.
- [ ] Metered expiring-credential rotation and tested fallback (not yet enabled).
- [ ] Longer-call, interruption and network handover coverage; remaining MVP/release gates.

Transport probe success is not live-call or MVP completion. FCM remains idle wake-and-drain;
WebSocket remains active-call signaling only; actual call media remains WebRTC/Opus.

Cloudflare free-tier check 2026-09-13: authenticated dashboard confirms 1,000 GB/month
included, but account activation requires an auto-renewing usage-billed Realtime
subscription. No subscription was activated under the owner's no-paid-billing
constraint, and no TURN connectivity test ran. Next: owner chooses activation with
overage risk or another provider's genuinely suitable free plan; verify billing and
limits before provisioning keys. Do not bypass account activation via the API.

2026-09-13 isolated USB-signaled, audio-disabled Wi-Fi/LTE comparison:
host-only ICE failed on both peers despite exchanged offer/answer/candidates.
STUN comparison gathered public srflx candidates on both peers but also failed;
this reproduces the network/media failure without any cellular call or audio capture.
Implemented public STUN discovery in the real call path and preserved the existing
setup/reconnect timeout across replacement peers (previously it checked an obsolete
session or was cancelled on each connected/resume control message).
STUN is NOT the full fix: provision authenticated short-lived TURN, with owner approval
for any billed service, then repeat no-audio relay connectivity before a cellular call.
Do not label mobile-data calling fixed or the deadline physically verified yet.

2026-09-13 latest call checkpoint: LAN speech both ways and in-app Answer confirmed.
On mobile data, durable wss:// settings now permit receiver signaling and Samsung
Answer, but media never connects (no STUN/TURN configured). Repeated media recreation
keeps Connecting audio visible instead of a bounded failure. Next priorities are
authenticated STUN/TURN provisioning and an overall media setup/reconnect deadline;
then repeat mobile-data and five-minute stability tests. User requested dev checkpoint
commit/push before continuing implementation.

Implementation resumed after the handoff checkpoint on `dev`. The product goal is
not complete. Resume context is recorded in
[AGENT_HANDOFF.md](AGENT_HANDOFF.md), which distinguishes installed behavior from
uninstalled changes and records the next tests and known defects.

Immediate priorities on resumption:

Latest controlled-call result: owner confirms caller audio now reaches Xiaomi after
the Samsung answerer track-association fix. Combined with the previously working
Xiaomi-to-caller path, basic two-way audio is demonstrated. Samsung capture signal
and progressing RTP corroborate the fix; tests, debug/release builds and lint pass.
Clarity, delay, packet-loss resilience and sustained-call stability remain open gates.

Incoming-call discoverability: implemented locally an app-wide Ringing prompt with
Answer/Open call screen, call-screen notification navigation and OS-controlled
full-screen incoming-call notification. Preserve the existing design and microphone
permission flow. Installed successfully on both idle phones with data preserved; verify foreground,
background and locked-screen behavior, including full-screen access denied.

Owner confirmed foreground incoming-call screen appeared and Answer worked directly
from that screen. Next physical gate: background/locked-screen incoming call.

Mobile-data test failed twice at receiver WebSocket signaling (timeouts and
rendezvous_timeout before WebRTC). Upgraded both existing test pairings to wss://
relay.amberdogeorgia.com via explicitly opted-in scoped device checks, preserving
settings/history/live-call consent. Repeat mobile-data test before attributing the
failure to ICE. STUN/TURN remains necessary work for reliable cross-network calling.

USB/LTE follow-up: secure URL had reverted on Xiaomi despite prior in-memory test.
Added durable maintenance flush; both phones now verified wss:// on disk after
normal restart, with Samsung opt-in retained. Native and app-level no-audio WS
upgrade probes succeed on LTE (app system DNS 798 ms; custom DNS 1076 ms). Authenticated
call signaling and cross-network media still need owner retest; earlier timeout
cannot be conclusively assigned to DNS or carrier filtering.

Follow-up notification-only report: added short-lived durable incoming-offer recovery
so reopening MainActivity or RelayCallActivity can restore Answer after process death.
Preserves source-time expiry and pairing opt-in; clears on answer/end/connected.
Tests/build/lint pass; verify dismiss notification then reopen during live ringing.

2026-09-13 backend regression: today's deployed Worker lost compatible FCM routing.
Restored the previously working deployed version 9d1489af to 100% traffic, preserving
bound pairing storage and local changes. Registration route again validates requests
instead of returning 404; verify real receiver registration and owner SMS/call retest.

1. Verify the new duplicate Answer guards and stale-notification identity fix on
   devices. Controller claims Answer synchronously; service rejects existing active
   sessions; simply opening a notification no longer manufactures incoming state.
   Explicit Answer can recover only an empty idle process with an enabled receiver
   pairing. JVM policy coverage spans all phases and mismatched pairing codes.
2. Verify the installed in-app Answer UI and intent guards on a controlled call.
   Latest unit tests, debug/release builds and debug lint pass. Both idle phones now
   have the tested update installed with data preserved; Samsung opt-in is confirmed.
3. Confirm the Samsung owner's live-call opt-in, then explicitly invite the user to
   place a controlled call. VPN-off, two-phone ICE with device audio disabled passed;
   basic actual cellular WebRTC audio now works both ways; volume, delay, sustained
   stability, mute and hang-up still require verification.
4. Complete TURN/VPN support, authenticated setup/WebSocket hardening, delivery and
   recovery tests, UX verification and the MVP release checklist.

Resumed delivery/call-state work: FCM fetch now shares Ringing state handling; service
checks duplicate durable events before passive state/teardown, and repeated Ringing
cannot replace an established session. Availability parsing requires JSON boolean
true. New unit coverage passes as part of verification; these latest changes are now
installed on both test phones. Receiver first-Answer microphone prompting is
implemented; Xiaomi already has the owner grant, so denial/first-grant dialog tests
remain. Prioritize integrated duplicate-Idle tests and call-generation/expiry guards
before release claims.

Delayed-offer protection is now implemented locally: communication history is kept,
but call information needs a valid source timestamp no older than 90 seconds (with
30 seconds future skew tolerance) to expose live interaction on receipt. This latest
guard is uninstalled. End-to-end per-call identity, expiration at action tap and stale
Idle protection remain required; receipt freshness is not a substitute for those gates.

Answer-time freshness is also implemented locally: original source time travels with
the call-screen state and notification, is checked before/after permission and again
before dispatch, including process-death recovery. Expired actions report an expired
alert instead of answering. This remains uninstalled and needs physical prompt/delayed
tap testing; a timestamp is not authenticated per-call identity.

### MVP promise

NextNotif turns an older Android phone into an unattended SIM gateway and a newer
Android phone into its clear, dependable companion. The supported MVP forwards SMS
and incoming-call identity/state on Android 8+ through FCM/HTTPS while idle. Remote
answering with two-way cellular audio is an explicitly labelled **reference-device
beta** that is off by default and can be enabled only on a rooted/capable Samsung
SM-A520F sender; the app must remain fully useful when that beta is unavailable.

MVP is reached only when a non-developer can pair two phones, understand whether the
gateway is ready, receive and revisit messages/calls, recover from ordinary network
failures, and identify when an action is required without reading a technical log.

### P0 — required before an MVP build

Latest physical live-call evidence: controlled cellular call failed two-way audio;
owner reports Xiaomi-to-caller works but caller-to-Xiaomi is inaudible. Samsung retains
privileged status and CAPTURE_AUDIO_OUTPUT grant. Prioritize source PCM versus receiver
RTP/playout diagnostics and a repeat controlled call; the live-audio gate remains open.
The debug-only five-second capture/playout signal summaries and periodic inbound RTP
counts are now installed on both idle test phones with data preserved, alongside
Answer-time expiry checks. Verification passes; repeat-call evidence is pending.
Repeat diagnostics show Samsung receives RTP and playback signal; Xiaomi capture has
signal but no inbound RTP, with no Samsung capture summaries. Corrected answerer
track association locally (addTrack rather than unassociated addTransceiver), with
two-way SDP validation and a strengthened native no-audio regression. Installation,
native regression execution and actual bidirectional cellular speech are still pending.
Update: fix installed on both phones; strengthened Samsung native audio-disabled
regression passes 2 tests (0.487 s). Actual two-way cellular speech remains unverified.

#### M0. Product boundary and safety

- [x] Define the general Android MVP (SMS + call information) separately from the
      rooted-A5 live-call beta.
- [ ] Add plain-language disclosure before enabling live audio: reference device,
      root requirement, relay encryption boundary, and local call-consent laws.
- [x] Make live-call forwarding a per-sender opt-in that defaults off. A receiver must
      show Answer only when the sender explicitly advertises an enabled, rooted, and
      helper-capable gateway; normal call alerts never depend on root.
- [ ] Ensure diagnostics and normal operation never persist raw call audio.

#### M1. Friendly setup and recovery

- [x] Replace the all-permissions-at-launch gate with a baseline role-aware gate:
      notifications for receivers and SMS/phone state for senders; a fresh install
      reaches pairing without unrelated permission prompts.
- [x] Add contextual optional-permission prompts: microphone/answer-call access only
      when live calling is enabled; sender caller-name lookup offers optional contacts
      access with explanation, retry, and app-settings recovery.
- [ ] Add a guided first-pairing flow with two-phone instructions, sensible FCM
      default, validation, and a final readiness check.
- [ ] Make every status actionable: Ready, Waiting for partner, Needs permission,
      Offline, Reconnecting, and Live call active must each explain the next step.
- [x] Add an in-app connection/notification self-test that does not require a real SMS
      or cellular call and cannot trigger live-call controls.

#### M2. Durable communication experience

- [~] Keep Overview, Messages, and Activity as separate destinations.
- [x] Persist a bounded SMS/call history independently of transient diagnostics,
      tolerate corrupt storage, and restore it after process death/reboot.
- [~] Show complete message content, caller identity, direction, call state, pairing,
      and absolute time; pairing filters and confirmed clear-history are implemented.
      End-to-end delivery acknowledgments/results still need verification.
- [ ] Verify stacked notifications, notification tap destinations, and history
      de-duplication across authenticated queue retries. FCM is wake-only and never
      renders communication content directly.

#### M3. FCM-idle relay correctness

- [x] Register both devices with FCM and carry idle SMS/call events through HTTPS + FCM.
- [x] Close both persistent WebSockets while idle and open temporary authenticated
      sockets only for a live call.
- [x] Replace pairing-edit stop/start races with an atomic in-service configuration
      reload so saving a transport change cannot leave the gateway stopped.
- [x] Persist the user's relay Start/Stop intent and restore enabled pairings after
      process recreation, reboot, or app replacement without reviving an explicit Stop.
- [~] Add and verify explicit timeouts for unanswered calls, temporary-socket rendezvous,
      reconnect attempts, and orphaned calls; always return to FCM Ready afterward.
      Bounded cleanup and a no-media watchdog are implemented; physical failure tests remain.
- [ ] Prove delivery after receiver force-stop, reboot, Doze, Wi-Fi/LTE change, and a
      temporary relay outage; queued events must arrive once and in order.
- [x] Make outbox replay crash-safe: never erase an event before its successful send is
      acknowledged; preserve ordering, cap behavior, and corrupt-storage recovery.
- [ ] Add event IDs/acknowledgments, queue TTL and visible overflow, and handle FCM token
      rotation/invalid-token recovery without duplicate notifications.
      Non-destructive `/fetch` plus specific-ID `/ack` and checked, atomic local history/
      replay protection are implemented locally. Python recovery tests and Android
      builds/tests pass; Worker verification and staged deployment remain. Temporary
      FCM receiver sockets now advertise their delivery mode; Python keeps backlog
      and new durable events on fetch/ACK while controls/audio stay live (smoke passed).
      Worker focused parity and final integrated full suite pass. Compatible relay
      deployed as `9d1489af-35c8-4da4-909c-522cf3051e6d` before successful updates on
      both test phones. Opt-in physical labelled delivery/retry tests pass on Samsung
      and Xiaomi, with one durable event-ID history entry; crash/notification/Doze
      tests remain. Legacy persistent WebSocket queue replay still
      needs equivalent safeguards; do not claim universal delivery durability yet.

#### M4. Reference A5 live-call beta

- [x] Remote Answer/Hang up, dedicated call UI, rooted A5 digital downlink capture,
      modem-uplink injection, G.711 media, and bounded backpressure are functional.
- [x] Use startup jitter buffering and adaptive playout instead of abrupt whole-frame
      deletion; add deterministic unit coverage for the policy.
- [x] Instrument each call with setup time, reconnect count, queue high-water mark,
      underrun/drop count, and end reason—never raw audio.
- [ ] Complete 10 repeated calls (including 5+ minutes), with no stuck Connecting state,
      no growing delay, intelligible audio both ways, and clean FCM return after hangup.
- [x] Label unsupported senders clearly and leave Answer/audio controls hidden or
      disabled without the proven privileged gateway capability.
- [x] Enforce the opt-in at runtime: missing root/helper permission must prevent the
      temporary media socket and remote call controls while SMS/call-info forwarding
      continues normally.

#### M5. Privacy, security, and release engineering

- [ ] Replace code-only bootstrap and HTTPS operations with a high-entropy, expiring
      invite plus role-scoped device credentials. A leaked 6-digit display code alone
      must not join/take over a role, inject events/audio/control, drain a queue, replace
      an FCM token, or read status.
      Planned migration: explicit legacy records keep the test phones working;
      new secure records use one-use 32-byte invites, 15-minute expiry, hashed
      role-scoped credentials, and authenticated metadata/takeover. Enable secure
      creation only after all endpoint/WS authorization guards and negative tests
      pass; never upgrade legacy ownership based on code-mintable old tokens.
      Interim credential-retention fix is tested: pairing edits discard device
      tokens when server authority, role, code, or Firebase backend/project changes.
      Python secure state-machine foundation is implemented/tested (not exposed by
      endpoints): one-use expiring invites, hashed role credentials, owner revocation,
      strict restoration, and secret-safe grant representations. Next: persist secure
      records and enforce every HTTP/WS guard before enabling secure creation; Worker
      parity and Android invite/Keystore UX follow. Existing live pairings remain legacy.
      Transactional Python secure storage now passes restart, concurrent one-use
      consumption, rollback, duplicate-code protection, secret-at-rest, and corrupt
      record tests (16 foundation/storage tests total). SQLite storage is isolated
      from legacy JSON pairings until endpoint guards and creation collision checks
      are complete; no secure creation is exposed yet.
      Python pre-authentication peer leak is fixed and full smoke passes: pending
      sockets cannot receive queued content, advertise connectivity/name/FCM data,
      or occupy the authenticated slot. Worker parity review and role-credential
      endpoint enforcement remain; code-only legacy bootstrap is still insecure.
      Python secure-record HTTP/WS guards now pass local negative smoke tests:
      sender-only send, receiver-only FCM/queue operations, authenticated status,
      credential-required WS, no legacy token minting, missing-store fail-closed,
      and persisted namespace markers. Secure sockets revalidate credentials on
      incoming frames. Creation remains disabled; Worker parity, namespace/queue
      atomic creation, immediate revocation teardown, Android invite/Keystore UX,
      and session-bound control/media remain before secure public release.
      Python owner-only `/pair/{code}/revoke` now immediately detaches/closes the
      target socket, clears role FCM/queue data, and checks cleanup persistence.
      Full smoke and 16 foundation/storage tests pass, including failed cleanup
      writes, credential denial afterward, safe owner retry, and persisted purge.
      Owner self-removal through device revocation is rejected; it uses whole-pair
      deletion instead. Worker parity remains; not deployed.
      Python whole-pair DELETE is implemented/tested: checked deletion reservations
      prevent code-only recreation, both sockets/data are purged, credentials are
      deleted, and owner-hash cleanup-only retries survive restart/storage failure.
      Full local smoke and 17 security/storage tests pass; Worker parity and secure
      creation/setup/session authorization remain before public release.
      Worker security transitions now pass seven Node tests plus an isolated local
      workerd runtime check using native timing-safe comparisons: invite rejection,
      role binding, restoration, owner-only revocation/deletion, and secret-safe
      serialization. This helper is not wired into relay routes. Atomic Durable
      Object storage/one-use concurrency and complete endpoint guards remain;
      secure creation stays disabled and live legacy pairings are unchanged.
      Transactional Worker storage is now implemented in an isolated module:
      versioned compare-and-swap, atomic namespace reservation, checked sync before
      grant release, and fail-closed restoration. Eleven Node security/storage
      tests pass, including collision, rollback, sync failure, and 12 competing
      consumers. The isolated SQLite-backed local DO runtime also produced exactly
      one committed grant for 12 simultaneous consumers. Actual relay integration,
      eviction/restart checks, legacy-backend runtime parity, and HTTP/WS guards
      remain; no production routes or phone credentials have changed.
      Worker HTTP guards are now integrated and pass an isolated real-relay-class
      runtime test: sender-only send, receiver-only registration/fetch/ack/drain,
      authenticated status, rejection without metadata/token/queue changes, and
      inconsistent/missing secure records quarantined rather than downgraded.
      Secure WS currently rejects before takeover or legacy bootstrap; its fully
      authenticated lifecycle is the next implementation step. Secure creation
      remains disabled and none of this security rollout is deployed yet.
- [ ] Add pairing revocation/rotation plus call session IDs and command nonces so replayed
      Answer/End requests are rejected and removing a device invalidates it remotely.
- [x] Remove development cleartext/network defaults from release builds while retaining
      a debug-only LAN option for development or controlled sideload testing.
- [ ] Review exported components, logs, notification privacy, secrets, backup policy,
      FCM payload contents, and retention; document what the relay can observe.
- [~] Use wake-only FCM pushes followed by authenticated HTTPS fetch: Android and both
      relays are implemented, Python smoke passes, and Android ignores legacy push
      content; final Worker deployment/physical revalidation remain.
- [ ] Add versioning, release signing/config separation, reproducible CI artifacts,
      crash-safe migrations, and an upgrade test from the current installed build.
- [x] Refresh README/user help so FCM is the default architecture and the live-call
      limitations match the implementation.

- [~] **Live-call MVP gate (promoted 2026-09-12):** replace G.711-over-WebSocket media
      with WebRTC/Opus, STUN, and short-lived TURN credentials while retaining FCM
      idle delivery and WS signaling only. Optional/root-gated live calls are not
      MVP-ready on the prototype transport. Pinned native dependency and role-aware
      audio-device factory compile in the debug APK. Session-bound offer/answer/ICE
      validation and bounded early-ICE buffering are added; all six new unit tests,
      the full debug unit suite, debug/release APK builds, release shrinking, and
      debug lint pass against the latest changes. No WebRTC call is wired or tested yet.
      Audio-only WebRtcCallPeer is implemented and compiles with the pinned API:
      native audio tracks, Opus-only codec preferences, serialized SDP callbacks,
      local/remote early-ICE ordering, force-relay configuration, mute, and owned
      resource disposal. Unit suite passes; this does not prove native negotiation
      or cleanup behavior. Engine runtime tests, service/mixer lifecycle wiring,
      server signaling/session guards, TURN provisioning, and physical calls remain.
      Native ABI/API smoke now passes on Samsung Android 8 and Xiaomi: audio-only
      Opus offer creation with recording/playout disabled and no SDP applied.
      Both phones received the latest debug groundwork APK without clearing data;
      normal startup was restored. This is not proof of WebRtcCallPeer negotiation,
      ICE/TURN, sender downlink/mixer operation, or two-way live-call quality.
      Actual peer-engine no-audio loopback and rejected-signaling cleanup both
      pass on Samsung Android 8 and Xiaomi after review fixes. Subagent review fixes pre-start mute loss and
      bounds signaling executor admission; three new mute-policy tests, full
      unit suite, debug/release builds and lint pass. Stable APK installation retry
      succeeded; normal apps restored without clearing data. The audio lifecycle
      wrapper now prepares the root mixer, preserves mute and restores mode only
      after native disposal. Service selects it with sender-ready handoff, native
      CONNECTED UI, fresh SDP/ICE sessions, ordered bounded live controls and a
      teardown barrier. Initial ICE is host-only/LAN; no G.711 fallback. Service
      device testing and STUN/TURN provisioning remain. Native received-audio RTP
      watchdog is implemented with bounded stats requests, fresh-sample stall
      confirmation and separate unavailable-telemetry warnings; physical silence,
      mute and media-interruption validation remain.
      Matching WebRTC service APK installed on both phones without clearing data;
      host-only no-audio native negotiation/stats callback/cleanup tests pass
      (Samsung 0.370 s, Xiaomi 0.358 s). Normal app startup restored. Real cellular
      forwarding and audible RTP counters remain unverified on this transport.
      Native capability parameter mutation was experimentally rejected; corrected
      engine keeps advertised capabilities intact and validates Opus DTX-off SDP.
      Latest APKs installed preserving data; three no-audio native offer/answer,
      negotiation/stats/cleanup tests pass on Samsung and Xiaomi, with apps restored.
      **Two-phone ICE gate now contradicts LAN readiness:** stronger no-audio
      physical probe fails on both phones despite same-subnet Wi-Fi and bounded
      direct ping reachability. Candidate gathering/delivery and VPN/UDP routing
      need diagnosis before a real call; fixture/reverse cleanup complete.
      Controlled comparison after user paused Xiaomi VPN now passes physical
      host-only ICE on both phones, including two-second hold and stats callbacks.
      Prior tunnel/policy routing and candidate aggregate diagnostics strongly
      implicate VPN routing; VPN/TURN remains a release gate. Real call awaits
      explicit Samsung opt-in (currently safe-default off), not ICE on current LAN.
      First inbound-RTP diagnostic and bounded five-second Android8 root hang-up
      are implemented locally; unit tests, debug/release builds and lint pass.
      Installed on both phones preserving saved data; first-RTP and remote hang-up
      still require real-call validation.
      Existing-pairing local preferences now save without relay reachability:
      identity/role/authority/transport/project must match exactly (normalized
      server whitespace/trailing slash only). Credential/settings copy preserved;
      identity changes retain normal setup preflight. Three targeted tests, full
      unit suite, debug/release builds and lint pass; physical offline-save
      verification remains. Samsung foreground Edit pairing was observed stuck
      on Connecting after Save in the previous build. Corrected APK installed on
      both phones preserving saved data; edit form closes and live opt-in must be
      retried normally. Actual offline-save/permission-prompt proof still pending.
      Negotiation, call-session binding, ICE/TURN, teardown, release validation, and
      physical Samsung/Xiaomi quality/soak checks remain. See WEBRTC_MIGRATION.md.

### P1 — beta quality after MVP

- [ ] Add accessible localization-ready copy, large-text testing, RTL verification,
      notification privacy controls, export, and configurable retention.
- [ ] Add remote gateway health (battery, charging, network, last seen) and receiver-side
      troubleshooting without exposing technical activity by default.
- [ ] Expand the privileged-device compatibility matrix only through controlled probes;
      never promise universal Android call-audio support.

### MVP acceptance matrix

- [ ] **Fresh setup:** a new user pairs Samsung sender + Xiaomi receiver and reaches
      Ready in under five minutes without ADB or editing stored preferences.
- [ ] **Messaging:** 20 mixed single/multipart/Unicode SMS events arrive once, in order,
      notify correctly, and remain in history across reboot.
- [ ] **Call information:** 10 incoming calls show correct identity/state and never leave
      a stale active-call UI after either side hangs up; unrooted/opted-out senders show
      notifications without an Answer action.
- [ ] **Idle efficiency:** relay status shows no receiver WebSocket between calls; FCM
      wakes the receiver and both phones return to idle mode after every call.
- [ ] **Live-call beta:** 10/10 reference-device calls establish or fail with a clear
      reason; successful calls remain intelligible with bounded latency for five minutes.
- [ ] **Recovery:** reboot, Doze, process death, network handoff, and relay interruption
      recover automatically or present one clear action.
- [ ] **Release:** JVM, Android device, Python relay, Worker smoke, release/lint, migration,
      and two-physical-device E2E checks are green from a clean checkout.

### Explicitly out of scope for MVP

- Universal cellular call-audio capture/injection on unrooted Android phones.
- Play Store distribution of the privileged A5 gateway build.
- User accounts, cloud message sync across more than the paired devices, call recording,
  iOS support, or end-to-end encrypted media. These require separate product/security
  decisions and must not delay the focused two-phone MVP.

## Gateway calling experiments (Samsung SM-A520F, Android 8)

The first reference sender is a stock Samsung Galaxy A5 SM-A520F running Android 8.
Experiments are deliberately staged so no bootloader, root, or system partition change
is made until the stock firmware has been measured.

### Milestone G1 — stock capability diagnostics

- [x] Add an on-demand **Gateway diagnostics** screen, available from the home overflow menu.
- [x] Show device/firmware identity, current call state, answer-call permission state, and microphone permission state.
- [x] Probe `MIC`, `VOICE_COMMUNICATION`, `VOICE_CALL`, `VOICE_UPLINK`, and `VOICE_DOWNLINK`
      sequentially during a controlled call.
- [x] Keep PCM only in memory, report signal level/non-zero ratio, and discard every sample.
- [x] Make the report copyable and explicitly avoid claiming that a detected microphone signal is caller audio.
- [ ] Run and preserve two reports from the physical A5: one idle baseline and one answered-call probe.
- [ ] Repeat the answered-call probe with earpiece, speakerphone, wired headset, and Bluetooth routes.

### Milestone G2 — remote call control

- [x] Add authenticated receiver-to-sender Answer and End control messages over the paired WebSocket.
- [x] Add a receiver notification Answer action that opens a dedicated live-call screen.
- [x] Validate that the sender is ringing before Answer; show answering, connecting, connected,
      reconnecting, ended, and failed phases on the receiver.
- [x] Add End call and an ongoing call notification with a Hang up action.

### Milestone G3 — stock audio feasibility

- [ ] Compare idle and in-call diagnostic reports to identify sources that expose meaningful signal.
- [ ] Perform a controlled caller-speech/sender-silence test; do not infer downlink access from energy alone.
- [ ] If digital downlink is available, stream a one-way, opt-in Opus/WebRTC prototype to the receiver.
- [ ] Measure latency, dropout, route changes, and echo behavior; store metrics, never raw call audio by default.

### Milestone G5 — rooted A5 full-duplex prototype

- [x] Prove A5 `VOICE_DOWNLINK` capture and root-only `DMIX_OUT` telephony-uplink injection.
- [x] Complete a bidirectional call between the rooted A5 sender and Xiaomi receiver.
- [x] Add G.711 mu-law media frames, bounded playback, outbound WebSocket backpressure,
      stale-socket rejection, resumable call state, and a visible receiver call surface.
- [x] Live stability check (2026-09-11): 68-second call completed without a WebSocket failure;
      both peers remained connected afterward. Occasional single-frame playout drops remain.
- [ ] Replace TCP WebSocket media with WebRTC/Opus over UDP plus TURN fallback for production latency,
      jitter buffering, loss concealment, and network handoff behavior.

### Milestone G4 — privileged gateway research (conditional)

- [ ] Proceed only if stock results justify it and the device owner explicitly accepts Samsung/root consequences.
- [ ] Back up the phone and record exact firmware/baseband/build identifiers before modification.
- [ ] Inspect Samsung audio policy, mixer paths, AudioFlinger, and telephony routes with a minimal privileged helper.
- [ ] Test digital call downlink capture before attempting any uplink work.
- [ ] Research telephony-uplink injection as a device-specific Audio HAL problem; no compatibility promise.

### Exit criteria

- **Broad feature:** remote Answer/Reject works without root and cannot be triggered by an unauthenticated or replayed command.
- **A5 one-way audio:** controlled tests prove the receiver hears caller speech from a digital path, not an acoustic microphone path.
- **A5 full duplex:** receiver speech reaches the cellular caller with acceptable echo and latency across repeated calls.
- A failed audio milestone leaves SMS, caller ID, notifications, and call control intact and supported.

### Milestone G5 — FCM-idle hybrid calling

- [~] Keep SMS and call-state delivery on FCM/HTTPS while idle; do not maintain a receiver WebSocket.
- [~] Open a temporary authenticated sender socket when the gateway begins ringing.
- [~] Open a temporary authenticated receiver socket only after the user taps Answer.
- [~] Carry answer/end/resume signaling and the proven G.711 prototype audio on the temporary sockets.
- [~] Close both call sockets on IDLE/end and explicitly restore the receiver to FCM on-demand mode.
- [ ] Add timeouts for unanswered calls and failed temporary-socket rendezvous.
- [ ] Replace temporary WebSocket media with WebRTC/Opus while retaining WebSocket only for SDP/ICE signaling.
- [ ] Add STUN plus short-lived TURN credentials so calls survive different NATs and restrictive networks.
- [ ] Measure setup time, mouth-to-ear latency, dropouts, reconnects, and battery use against the always-on WS baseline.

### Milestone U1 — receiver information architecture

- [~] Split the home experience into Overview, Messages & calls, and Activity log destinations.
- [~] Keep pairing health and controls in Overview.
- [~] Put human communication events—texts, caller identity, and call outcomes—in Messages & calls.
- [~] Reserve Activity log for connection, retry, queue, and live-call diagnostics.
- [ ] Persist a bounded message history separately from the transient technical log.
- [ ] Add per-pairing filtering and clear-history controls after the split is validated on both device sizes.

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

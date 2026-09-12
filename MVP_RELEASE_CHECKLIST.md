# NextNotif MVP release checklist

## Latest verification evidence (2026-09-12)

- Android unit tests, debug/release APKs, debug Android-test APK, and lint: passed.
- Latest permission/timestamp regression tests, debug/release builds, and debug lint
  passed: live-call availability requires both optional permissions, and delayed
  communication history preserves a valid source timestamp.
- Global Stop now gates FCM enqueue/sync and automatic service restoration; unit
  suite, debug build, and debug lint pass. Physical stopped-state recovery tests remain.
- Pairing edits no longer retain a device credential across a changed relay
  authority, role, code, or Firebase backend/project. Policy regression tests,
  debug/release builds, and debug lint passed. High-entropy secure pairing and
  role-scoped server authorization are still required before public release.
- Fetch/ACK receiver integration and atomic history/replay storage: Android unit
  suite, debug/release builds, and debug lint passed. Python fetch/ACK recovery
  smoke suite passed. Worker full smoke passed before the temporary-call routing
  addition; subsequent focused socket/FCM regressions and the final integrated
  full suite passed. Deployment dry run passed. The compatible relay was deployed
  as `9d1489af-35c8-4da4-909c-522cf3051e6d`, then both phones were updated successfully.
  Production `/fetch` rejected missing credentials with HTTP 401. Physical sync,
  then opt-in live delivery tests passed on both phones (`20260912-1143b`): Samsung
  sender accepted the labelled event; Xiaomi received it through normal push-triggered
  sync, persisted one history entry with event ID, and repeated sync/ACK without
  duplication. Receiver test: 8.763s; sender test: 2.424s. Actual notification
  presentation, interrupted-delivery, Doze, and call-soak tests remain.
- Python relay smoke suite (HTTP, WebSocket, FCM wake, queue, auth handshake,
  persistence/restart, and binary audio routing): passed.
- Local Worker smoke suite and deployment dry run: passed, including content-free
  FCM wakes and authenticated queue retrieval. Live relay deployed as
  `9d1489af-35c8-4da4-909c-522cf3051e6d`; previous version is
  `518c9396-eb2f-4762-aa65-9c02a4168be7`. Revert new clients before reverting to a
  relay without fetch/ACK. Earlier post-deployment idle registration/status passed;
  unlocked-phone self-test remains.
- Samsung SM-A520F sender + Xiaomi 23049PCD8G receiver: APK installation and FCM idle
  registration passed; the in-app HTTPS/FCM self-test produced both a Xiaomi notification
  and durable TEST history, and the sender restored automatically after app replacement.
  Baseline call-alert validation and rooted opt-in soak remain.

This checklist is the ship gate for the first user-functional MVP. A checked item
must link to a test result, device log, screenshot, or reviewed change. The supported
MVP is SMS and incoming-call information on Android 8+; rooted Samsung A5 live-call
forwarding is an optional beta and cannot weaken the baseline experience.

## 1. Scope and user promise

- [ ] SMS and incoming-call information work without root or live-call opt-in.
- [ ] Live-call forwarding defaults off and is described as rooted SM-A520F beta.
- [ ] Unsupported/unrooted senders never advertise an Answer action.
- [ ] In-app copy and README accurately describe FCM idle delivery and relay visibility.

## 2. Clean build and automated gates

- [ ] `:app:testDebugUnitTest` passes from a clean checkout on JDK 17.
- [ ] `:app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest` pass.
- [ ] `:app:lintDebug` and release lint/R8 pass without a new baseline.
- [ ] Python relay smoke and TLS suites pass in a fresh virtual environment.
- [ ] Worker smoke suite passes locally and against the release deployment.
- [ ] CI produces identifiable debug/release artifacts with no bundled private secrets.

## 3. Fresh setup and permissions

- [ ] Fresh API 26 sender and API 35 receiver reach pairing without unrelated prompts.
- [ ] Sender requests only baseline SMS and phone-state permissions.
- [ ] Receiver requests only notifications on Android 13+.
- [ ] Denying optional contacts/live-call permissions does not block baseline relay.
- [ ] Permanent denial shows the exact setting/action required to recover.
- [ ] Both phones reach an understandable Ready state in under five minutes without ADB.

## 4. Messages, calls, and notifications

- [ ] 20 mixed single-part, multipart, long, and Unicode SMS messages arrive once/in order.
- [ ] Incoming call RINGING/OFFHOOK/IDLE shows correct identity and outcome.
- [ ] Messages history survives process death and reboot and is separate from Activity.
- [ ] Notification taps open the relevant content; stacked notifications do not collide.
- [ ] Clear-history and per-pairing filtering are usable and do not clear technical state.

## 5. FCM idle and recovery

- [ ] Queue retrieval is non-destructive until a receiver durably stores and
      acknowledges specific event IDs; dropped responses and process death cannot
      silently erase messages. `/fetch` + `/ack` and atomic local history/replay
      protection are implemented locally; staged deployment and device crash tests
      remain. Legacy `/drain` and WebSocket replay are still destructive.
- [ ] Opening a temporary FCM call socket retains queued messages, and SMS/call
      information arriving during a call still uses fetch/ACK. Android handshake
      policy tests/builds and Python active-call regression pass; Worker parity and
      focused active-call regression also pass. Physical-device tests remain.
- [ ] Relay status shows no receiver WebSocket outside an active opted-in call.
- [ ] Receiver wakes from screen-off/Doze and receives queued events.
- [ ] Force-stop, reboot, Wi-Fi/LTE handoff, offline interval, and relay restart recover.
- [ ] 50 queued events preserve FIFO ordering, show overflow, and do not duplicate.
- [ ] FCM token rotation/invalid-token behavior recovers or shows one actionable error.
- [ ] Pairing edit/reload never leaves an enabled sender stopped.

## 6. Optional rooted-A5 live-call beta

- [ ] Opt-in is stored per sender pairing and changing role to Receiver clears it.
- [ ] Root/helper capability is checked before opening a temporary call socket.
- [ ] Opted-out and failed-capability calls remain normal call-info notifications.
- [ ] 10/10 attempts establish or fail with a clear reason; at least 9 connect successfully.
- [ ] p95 setup is at most 5 seconds after Answer; no stuck state lasts over 10 seconds.
- [ ] Five-minute calls remain intelligible both ways with no monotonically growing delay.
- [ ] Hangup, network loss, timeout, and app/process failure restore the A5 mixer route.
- [ ] Both phones return to FCM idle within 5 seconds after every termination path.
- [ ] Metrics contain setup/jitter/drop/reconnect/end reason only—never raw audio.

## 7. Security and privacy

- Python secure state-machine tests pass, but helpers are not integrated into
  live endpoints. This is implementation groundwork, not a completed security gate.
- Secure state/storage tests: 16 passed, including restart, concurrent invite
  consumption, rollback on interruption, duplicate-code protection, and persisted
  revocation. Storage remains isolated from the live legacy relay.
- Python pending-peer isolation regression and full relay smoke suite pass:
  metadata/peer slots are published only after authentication, and failed pending
  peers cannot consume backlog. Worker parity and secure credential guards remain;
  this Python change has not been deployed.
- Python secure-record HTTP/WS authorization is implemented locally and tested
  against missing/wrong-role credentials, credential minting fallback, metadata
  injection, and unavailable credential storage. Secure creation is not exposed.
  Legacy records stay compatible; secure sockets revalidate incoming-frame
  credentials. Worker parity and immediate revocation teardown remain.
- Python owner-only revocation/teardown passes local full smoke: unauthenticated
  and non-owner requests cannot revoke; removal disconnects without another frame,
  purges FCM/queued data, and rejects the credential afterward. Simulated cleanup
  persistence failure returns 503 while remaining revoked; owner retry persists
  the purge. Owner removal uses whole-pair deletion; Worker parity remains.
- Python whole-pair deletion passes local smoke, including non-owner denial,
  reservation-write failure, credential-purge failure, immediate two-socket teardown,
  restarted owner-only retries, credential purge, and prevention of code reuse.
  Security/storage unit tests: 17 passed. No deployment; Worker parity remains.

- [ ] A 6-digit display code alone cannot join/take over a role, inject events/control/audio,
      drain queues, overwrite an FCM token, or read sensitive status.
- [ ] Bootstrap invite is high entropy, single-use/expiring, and yields role-scoped credentials.
- [ ] Device removal revokes its server credential and deletes its queued payloads.
- [ ] Call session IDs/nonces reject replayed Answer and End commands.
- [ ] Public/release traffic is TLS-only; LAN cleartext is an explicit advanced/debug mode.
- [ ] FCM content/redaction, relay visibility, local retention, root risk, and call-consent
      responsibilities are disclosed before the relevant data or feature is enabled.
- [ ] Secrets and personal message/caller content are absent from production logs.

## 8. Upgrade and release artifact

- [ ] Deploy compatible `/fetch` and `/ack` relay endpoints before installing the
      new receiver client; prove notification delivery and retry on both test phones.
      Do not fall back to destructive `/drain` when the new endpoint is unavailable.
- [ ] Upgrade from the currently installed build preserves pairings, tokens, and history.
- [ ] Corrupt/legacy preferences and outbox entries migrate or fail safely.
- [ ] Version code/name, release signing, backend environment, and rollback artifact are set.
- [ ] Samsung sender + Xiaomi receiver complete the full matrix on the release candidate APK.
- [ ] Known limitations and support/recovery steps are included with the build.

## Release decision

- [ ] All baseline gates above are checked.
- [ ] Any unchecked live-call-beta gate either blocks the beta toggle or is explicitly removed
      from the MVP build; it never blocks SMS/call-information release.
- [ ] Final smoke result, APK checksum, backend version, and device/firmware identifiers are
      recorded below.

Evidence:

- Release candidate:
- APK SHA-256:
- Relay/Worker version:
- Samsung build/baseband:
- Xiaomi build:
- Test date/operator:

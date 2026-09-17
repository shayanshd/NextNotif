# NextNotif — current dev snapshot

Updated 2026-09-17 from the local main worktree before commit/push at
43068443bd313de0f3b20fadb26ddbf66cf667db:
“Add authenticated Cloudflare TURN for cross-network WebRTC calls.”
Repository: https://github.com/shayanshd/NextNotif/tree/dev

This supersedes the previous snapshot of the older main working tree. Evidence
comes from committed dev code/docs and recorded tests, plus read-only device
log inspection on 2026-09-14. No new call/test or deployment was initiated.

## Owner-confirmed live-call success (2026-09-14 update)

- Owner confirms the previously pending Samsung Wi-Fi / Xiaomi cellular real-call
  test succeeded with audible two-way speech. Both phones were visible through ADB.
- Read-only retained logs on both devices corroborate real bidirectional media
  during two September 13 calls. Device clocks differ by about one hour:
  Samsung 23:45–23:47 corresponds to Xiaomi 22:45–22:47.
- Longer call: Samsung inbound RTP rose 102 -> 3,776; Xiaomi 86 -> 4,739 over
  roughly 72 seconds of sampled counters, with about 75 seconds connected media.
  Capture/playback report signal at Samsung 8 kHz and Xiaomi 48 kHz.
- Longer-call setup_ms: Samsung 8201, Xiaomi 4338. Both report reconnects=0;
  sender ends remote_hangup, receiver local_hangup, and Samsung returns cellular
  IDLE. Xiaomi resumes FCM sync about 0.45 seconds after its call metrics entry.
- Earlier call likewise has increasing inbound RTP on both devices, signal in
  capture/playback, paired hangup outcomes, and zero recorded reconnects.
- These logs substantiate live media and post-call sync; audible success and
  cross-network conditions are owner-confirmed. Selected ICE candidate-pair type
  is not present in these logs, so actual TURN selection for these calls is not
  independently established. Earlier forced-TURN probe remains separate evidence.
- Zero logged legacy playback/drop counters do not establish zero RTP packet loss.
  Five-minute soak, handover, repeated reliability and full release gates remain.

## Git and source of truth

- Dev is committed/pushed, three commits ahead of main: 6c72418 (WebRTC migration),
  2b2403d (sender audio/incoming-call recovery), 4306844 (authenticated TURN).
- This workspace checks out main. The current application changes are being
  committed from this worktree and pushed to origin/main after verification.
- Read current files using git show origin/dev:<path>. Consult AGENT_HANDOFF.md
  (top checkpoint wins), PRODUCT.md, MVP_RELEASE_CHECKLIST.md,
  DELIVERY_PROTOCOL.md, WEBRTC_MIGRATION.md, and roadmap.md on dev.
- Older handoff entries saying TURN absent/not deployed/uncommitted are historical.
  README still describes obsolete G.711 media. Local main roadmap is not dev state.

## Product and architecture

- Kotlin/Compose Android 8+ SMS and incoming-call relay, with multiple pairings,
  per-pairing controls, status, and activity. An older handset is the SIM gateway.
- Baseline SMS/call alerts require no root. Optional live-call forwarding defaults
  off and requires sender opt-in plus root/helper capability. Supported experimental
  sender is the Samsung Galaxy A5 SM-A520F Android 8; receiver needs microphone access.
- Preferred idle path: content-free FCM wake -> authenticated HTTPS /fetch ->
  atomic history/replay storage -> /ack. No permanent receiver socket in FCM mode.
  Backends are FastAPI and Cloudflare Worker/Durable Objects.
- Fetch is non-destructive; ACK removes specific event IDs. History is capped at
  200 entries, replay protection at 1,000 IDs, relay queue at 50 events.
  History clearing preserves replay protection; overflow/TTL reporting is unfinished.
- Answered calls use temporary authenticated WebSockets for control and SDP/ICE,
  native WebRTC Opus audio tracks for media. No G.711 fallback on WebRTC failure.
- Samsung uses VOICE_DOWNLINK capture and rooted telephony mixer injection.
  Cleanup restores mixer routing, releases audio/peers, and returns to FCM idle.

## Latest TURN checkpoint

- Authenticated Worker POST /ice requires an existing pairing/device credential;
  no bootstrap. Durable limit 16/minute/pair, no-store responses, bounded provider
  request/JSON and sanitized errors. Provider secrets never enter the APK.
- TURN credentials last 14,700 seconds (four-hour call cap plus setup grace).
  Actual call path fetches on IO, buffers up to 128 validated early signals,
  discards retired-peer results, and includes fetch in the fixed setup deadline.
- Latest recorded deployed Worker: 5a1c3e2a-82c7-42e4-b754-859ae774012b,
  serving relay.amberdogeorgia.com. Preserve explicit custom domain and SQLite
  migration v1 in wrangler.amber.jsonc.
- Cloudflare is the only enabled TURN provider. Metered fallback is NOT wired
  into /ice. Its current fetch-only credentials are static; expiring fallback
  requires management credentials and advance rotation, not call-time creation.
- Cloudflare keys are Worker secrets; FCM_SERVICE_ACCOUNT retained. GitHub Metered
  credential rotation is recorded complete. Original FCM private-key backup remains
  deferred/unconfirmed. Never copy secrets into this file.

## Recorded verification

- Latest APK installed with data preserved on Samsung SM-A520F API 26
  (USB 52006a98f0ac6489) and Xiaomi 23049PCD8G API 35 (USB 53d75ef).
  Both relay URLs verified durably wss:// after restart.
- Owner confirmed in-app Answer and basic two-way speech on LAN after Samsung's
  answerer track-association fix. Quality/soak release gates remain.
- Forced production TURN probe passed with Samsung on validated Wi-Fi and Xiaomi
  on validated cellular. Native peers held CONNECTED for two seconds with public
  IPv4 UDP relay candidates; queues drained and disposal succeeded.
  Audio was DISABLED; signaling used localhost/ADB. This proves transport,
  NOT cross-network speech, actual Answer flow, quality, long calls, or handover.
- Current main change verification: Android testDebugUnitTest and assembleDebug
  passed; server/test_smoke.py passed. The server-workers smoke test was not run
  because this checkout lacks its Python websockets dependency.
  Earlier checkpoints passed debug/release builds and lint; latest TURN checkpoint
  does not record a fresh release build.
- Physical FCM fetch/store/ACK delivery, durable TEST history, repeat sync/ACK
  without duplicates, idle registration and self-test notification were verified
  in earlier checkpoints.
- Probe processes/reverse mappings cleaned up and normal app startup restored.
  No raw call audio recorded by probes.

## Next work and release gates

1. Initial cross-network real-call gate is now owner-confirmed successful and
   corroborated by both phones' RTP/audio logs. Next extend to repeatability and
   five-minute calls; collect selected ICE-pair stats if TURN-path proof is needed.
2. Validate clarity/latency, five-minute stability, bounded setup, Active/timer,
   mute, duplicate Answer, hangup, failure cleanup and return to FCM idle.
3. Complete interrupted fetch/store/ACK, process death, Doze, offline queue,
   reboot, network handover, notifications, permissions and upgrade matrix.
   FCM cannot bypass Android force-stop.
4. Secure pairing remains a public-release gate: high-entropy invites, role-scoped
   credentials, revocation, replay-safe controls. Security groundwork/tests exist,
   but integration/parity/rollout are incomplete. Legacy code bootstrap and
   destructive /drain/WebSocket replay remain.
5. Metered fallback/rotation, quota handling, credential backup, documentation,
   release signing/artifacts and full release checklist remain unfinished.
   MVP is not declared release-ready.

## Resume constraints

- Preserve working-tree changes, phone data/history, permissions and sender opt-in.
  Do not automatically enable live-call consent or grant permissions.
- Xiaomi full-screen call access was recorded denied; in-app Answer/heads-up are
  fallback. Background/locked-screen and notification-dismiss recovery need checks.
- Never run the entire instrumented suite on configured phones: storage tests can
  overwrite preferences. Select only explicit opt-in classes; do not interrupt
  active calls for installs/tests. Keep diagnostics aggregate, without raw audio.
- Compatible fetch/ACK backend must precede receiver rollout. A stale deployment
  previously broke /fcm-register; restored compatibility preceded latest TURN deploy.
  Do not deploy this older main Worker over current dev.
- Snapshot follow-up included read-only ADB discovery/log inspection. No device
  mutations, test calls, deployment, or secret changes were performed here.

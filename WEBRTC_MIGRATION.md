# Live-call WebRTC migration

Live-call media is an MVP gate for the optional rooted-sender feature, not a
post-MVP enhancement. SMS and call alerts remain usable without root.

## Target architecture

Idle: FCM wake -> authenticated HTTPS fetch/store/ack. No permanent receiver socket.
During an explicitly answered call: temporary authenticated WebSocket for session
control and SDP/ICE signaling; WebRTC audio tracks carry Opus media independently.
Ending or failing the call releases audio, peer connection, root mixer route, and
temporary signaling resources, returning to idle FCM delivery.

Do not send audio over a WebRTC data channel: use native audio tracks and their
codec/jitter/loss handling. Do not silently fall back to G.711-over-WebSocket when
WebRTC fails; show an actionable failure and clean up the call session.

## Implementation sequence

1. Verify the pinned native library (`io.getstream:stream-video-webrtc-android:145.9.0`)
   against Android 8, Kotlin 1.9, both device ABIs, and release shrinking. A role-aware
   audio-device factory is present but not connected to the live-call service yet.
2. Preserve Samsung audio I/O: VOICE_DOWNLINK, mono 8 kHz capture/playback, voice-call
   playback attributes, explicit root mixer enable/restore. Receiver uses normal
   communication capture. The audio-device builder supports these selections;
   successful modem routing still requires physical verification. See the
   [upstream audio-device API](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/audio/JavaAudioDeviceModule.java).
3. Add a serialized audio-only peer engine with offer/answer, bounded early ICE
   candidates, Opus-only codec preference, role-specific audio processing, and
   deterministic disposal. Keep processing of digital gateway audio separate from
   receiver microphone echo/noise processing. Never run the old bridge concurrently.
4. Bind signaling and controls to a unique call session and authenticated pairing;
   reject stale sessions, wrong-role negotiation, replayed controls, oversized SDP,
   and unbounded candidates. Complete backend guards before public rollout.
5. Provide STUN and authenticated short-lived TURN credentials. No static TURN secret
   in the APK; no production connectivity claim based only on same-Wi-Fi testing.
   Exercise direct and forced-relay candidates. UDP is preferred; TURN TCP/TLS may
   be needed where UDP is unavailable, so measure that path separately.
6. Switch the service's audio lifecycle and media watchdog to actual peer/audio
   state, not WebSocket binary-frame arrival. Show connecting, active, recovering,
   and failed states clearly. End/decline/mute must address the current session only.
7. Validate debug and release builds, dependency licenses, permissions, foreground
   startup, device upgrades, and cleanup after caller hangup, lost signaling,
   failed ICE, app/process interruption, and network changes.
8. Test Samsung sender and Xiaomi receiver: both directions, volume, delay, loss,
   long-call delay drift, different networks, forced TURN, screen-off/Doze, and idle
   return. Store aggregate diagnostics only; do not record private call audio.

## Current evidence

- WebRTC is selected by the installed call service; no real forwarded WebRTC
  call has been tested. The old G.711 implementation remains unused.
- The pinned WebRTC dependency and audio-device factory compile in debug/release
  APKs; unit tests, release shrinking, and debug lint pass. Native no-audio
  compatibility tests pass; modem audio routing and physical quality gates remain.
- Session-bound offer/answer/ICE validation and a bounded, deduplicated early-ICE
  queue are implemented. Offers are receiver-initiated; wrong-role descriptions,
  stale sessions, malformed/oversized SDP and invalid ICE fields are rejected.
  An audio-only peer engine now consumes these signals, creates native audio
  tracks with Opus-only codec preferences, serializes callbacks, buffers local
  ICE until SDP is sent and remote ICE until SDP is applied, and disposes owned
  media resources on failure/close. Installed service selects this engine;
  no-audio native negotiation/cleanup pass. Real media runtime remains untested.
- Factory policy requires supported root/helper capability for sender capture;
  receiver microphone capture does not require root.
- Worker HTTP security regression suite passes; secure setup and full WebSocket
  authorization/session controls remain incomplete and are not deployed.
- The opt-in `NativeWebRtcInstrumentedTest` passes on both SM-A520F Android 8
  (0.125 s) and Xiaomi 23049PCD8G (0.055 s). It loads the pinned native library,
  creates the receiver audio module and audio-only Opus offer, and disposes them.
  Recording/playout and the local audio track are disabled; no SDP is applied.
  This proves native API/ABI smoke compatibility, not ICE connectivity, engine
  negotiation, root mixer routing, private-call capture, or two-way audio quality.
  App/test APKs were installed with `-r` (no data clear), and normal app startup
  was restored afterward. Run only this class with `-e nativeWebRtc true`; never
  run the complete instrumentation suite against configured phones.
- `NativeWebRtcPeerInstrumentedTest` passes both tests on Samsung Android 8
  and Xiaomi after the mute/executor review fixes: actual engines reach CONNECTED through same-process
  ICE loopback with device audio disabled, and rejected signaling reports once
  and completes owned-resource disposal. This is not a two-phone/media/TURN test.
- A subagent review found pre-start mute loss and unbounded executor admission.
  Desired/initial mute now survives track creation; admission is bounded to 128
  queued/running actions and closed queued actions are skipped. Three microphone
  policy tests, the full unit suite, debug/release builds and lint pass. The stable
  APK retry succeeded and both normal apps were restored without clearing data.
- `WebRtcCallAudioBridge` now owns audio-mode preparation, rooted sender mixer
  setup, initial mute, and teardown ordering until native disposal completes.
  The root helper has a bounded process wait. This wrapper builds successfully,
  and is now selected by the service; its hardware lifecycle is not yet tested.
- Service wiring sends sender-ready only after preparing its peer and shows Active
  only on native CONNECTED. Fresh session IDs bind SDP/ICE and callback generations;
  replacement waits for previous native audio teardown. Live controls use a bounded
  ordered queue and reject superseded sockets/stale session-bearing termination.
  The binary-WebSocket watchdog is removed; native negotiation timeout remains,
  and native received-audio RTP monitoring now polls every two seconds with at
  most one outstanding stats request. A fresh stagnant sample confirms a
  30-second stall; missing/stale telemetry warns instead of asserting audio
  failure. This does not prove the modem capture/playout paths work. There is no G.711 fallback.
  The initial configuration is host-candidate/LAN only (no STUN/TURN configured).
  The matching service build is installed on both phones without clearing data.
  Updated opt-in native tests pass on Samsung (0.370 s) and Xiaomi (0.358 s):
  host-only same-process negotiation, stats callbacks, rejected-signaling cleanup,
  all with recording/playout disabled. Normal app startup restored afterward.
  This is not proof of real forwarded-call service behavior or audible RTP media.
- Before accepting the RTP monitor: verify audio counters on both phones,
  silence and each-side mute for over 40 seconds, a real media interruption with
  healthy signaling, and fresh-session recovery. Stats fields follow the
  [WebRTC statistics specification](https://www.w3.org/TR/webrtc-stats/).
- RTP decoding has targeted tests using the pinned native Java stats classes:
  received audio only, legacy/current kind fields, missing/malformed counters,
  and unsigned counter saturation. Decoder hardening is now installed on both
  phones. Native no-audio callbacks do not validate actual audio counters.
- Attempting to add `usedtx=0` to codec capabilities failed native validation on
  both phones (missing matching advertised capability). That experiment was
  replaced: preserve native codec capabilities and validate DTX-off in local and
  remote Opus SDP; omitted `usedtx` is off per RFC 7587. Do not rewrite SDP.
  Corrected build passes full unit tests, debug/release builds and lint; three
  opt-in no-audio native tests pass on Samsung (0.510 s) and Xiaomi (0.504 s),
  including offer/answer DTX checks, stats and cleanup. Normal apps restored.
  Continuous actual audio RTP during silence/mute still needs physical proof.
- Stronger two-physical-phone probe now exists: local-only ephemeral HTTP
  signaling through temporary ADB reverse, with native device audio disabled,
  per-peer stats callbacks and a two-second connection hold. Its first execution
  **fails native ICE on both phones** (Samsung 19.098 s, Xiaomi 15.560 s).
  Both Wi-Fi interfaces have addresses on the same /24; Samsung-to-Xiaomi
  bounded ping succeeds, while Xiaomi-to-Samsung ping loses both packets.
  ICMP asymmetry is a routing/filtering clue, not proof of UDP behavior.
  Passing same-process tests do not establish LAN ICE.
  Investigate candidate gathering/delivery, routing/VPN and direct UDP before
  asking for a real call. No production pairing/settings/audio writes occurred;
  fixture stopped, temporary reverse mappings removed, normal apps restored.
  The fixture is localhost-only with bounded queues/body/socket timeout and
  automatic five-minute expiry; never deploy it as the application relay.
- User paused Xiaomi VPN for a controlled comparison. Read-only routing confirmed
  its prior active tunnel/policy routing; with VPN off, the same physical no-audio
  probe **passes on both phones** (Samsung 6.018 s, Xiaomi 3.527 s). Each role
  sends one private IPv4 UDP host candidate, one description, and consumes both
  opposite-role signals; both queues empty. This strongly implicates VPN routing
  in the earlier failure, but does not establish reliable VPN/TURN operation.
  Fixture stopped, reverse mappings removed and normal apps restored. Readiness
  check finds Samsung live-call opt-in absent (safe default false); enable through
  normal setup before real cellular testing. No call audio has yet been tested.
- Samsung's foreground Edit pairing was subsequently observed stuck on
  Connecting after Save. Corrected local-preference/offline-save build, first-RTP
  diagnostic, and bounded Android8 hang-up are installed on both phones with saved
  data preserved. Old unsaved form closes on update; retry opt-in through normal
  UI. Permission/root prompts, actual local-save behavior and real call still need
  verification. Root hang-up timeout must not be described as a confirmed hang-up.

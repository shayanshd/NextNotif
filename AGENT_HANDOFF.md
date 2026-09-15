# NextNotif agent handoff — 2026-09-15

## Current installed checkpoint (supersedes historical pending notes)

### 2026-09-15 — receiver-initiated outgoing call implementation

Latest fix: Samsung can emit a transient telephony `IDLE` between `placeCall` and
`OFFHOOK`. Sender teardown now waits 2.5 seconds for `OFFHOOK` before reporting an
outgoing call ended, while real post-call `IDLE` remains immediate. Android unit
tests and debug build pass; the APK is installed on both phones and Samsung's
privileged copy was refreshed.

Follow-up hardening: receiver call and SMS number fields now offer the Android
system phone contact picker. A finished receiver call that receives HTTP 409 from
the submit endpoint clears retained non-final mailbox records through the new
authenticated cancel operation, then retries the same UUID, destination, and SIM;
active calls are never force-cancelled. Python/Node tests cover cancellation and
the Android debug/unit/test APK build passes. Returned-contact selection was not
completed on the emulator because it has no usable Contacts handler.
Worker version `336a5ddf-1ac9-4808-8c3a-1b82b0a7ba9b` is deployed, and the rebuilt
APK is installed on both phones.

Receiver status-race follow-up: repeated physical calls sometimes showed Ended before
switching to Connected. `/call-status` intentionally retains recent records, but the
receiver was applying every historical `ended` record to the screen by pairing code.
The receiver now persists the active outgoing request UUID, applies final mailbox
status only to that exact request, and clears it conditionally on completion. Cellular
`connected` mailbox state cannot mark WebRTC audio Connected; the live socket remains
authoritative. A regression test covers an old ended record preceding the current
connected record. The corrected APK is installed on both phones and the Samsung Magisk
copy matches SHA-256
`5d57c9421fb3963b47fc764a2edd96dc8ac433d574c011c0962ca31b0b6eb16d`.

Samsung follow-up fix: the SM-A520F's Telecom account IDs are its full ICCIDs with
one OEM prefix character, so the initial exact ID/ICCID matcher rejected both SIMs
with “Could not match the selected SIM.” A read-only on-device mapping test confirmed
each subscription uniquely matches the full ICCID suffix. The matcher now accepts
that authoritative Samsung form without positional fallback; the test passes for
both installed SIMs. The corrected APK is installed on both phones and the Samsung
Magisk copy matches SHA-256
`5daa33cbc7d5bfa99fa82625c18a26e17510cec6beb2bcd26bac4dd965bc5170`.

The current `dev` checkpoint adds **Calls → Call through sender**. The receiver chooses
an enabled server-backed sender pairing, destination, and reported SIM. A durable
two-minute call mailbox wakes the sender; the sender verifies live-call capability
and CALL_PHONE, maps the subscription exactly to a Telecom phone account, places the call,
and reuses the current temporary WebRTC audio/hang-up lifecycle. Commands reject
overlap and keep UUID, destination, and SIM immutable. Outgoing state is returned
only to the initiating receiver and recorded with outgoing direction.

Python/Node mailbox tests, authenticated HTTP round trips, both full relay smoke suites,
Android unit tests, debug/test APK builds, and the render-only dialer instrumentation
test pass. The scoped UI finish review passed after requiring valid SIM inventory and
removing unsafe positional Telecom-account matching. Worker version
`d50df604-afe5-45a6-b4b2-d72d99e8dc9a` is deployed to `relay.amberdogeorgia.com`.
The matched debug APK is installed with data preserved on Samsung SM-A520F sender and
Xiaomi 23049PCD8G receiver; Samsung was granted CALL_PHONE, RECORD_AUDIO, and
READ_PHONE_STATE. The Samsung Magisk module APK was also refreshed and matches the
local artifact (superseded by the Samsung mapping fix hash above);
no reboot was performed. No real call was placed. Physical Xiaomi-to-Samsung validation of
Samsung account IDs, selected-SIM placement, carrier connection, relayed audio, and
hang-up remains; unmatched account IDs fail visibly instead of using another SIM.


### 2026-09-14 — SMS compose, sender SIM selection, and paused diagnosis

This section supersedes older installation/deployment statements below. Branch: `dev`.
Owner's latest instruction: stop diagnosis, update roadmap/handoff, commit and push.

Implemented receiver new-message/reply composers, explicit sender SMS opt-in,
permission-aware modem sending, durable idempotent command queues, carrier callback
status, and sender SIM discovery/selection. Both Python and Worker relays preserve
optional immutable `subscription_id`; an unavailable explicit SIM never falls back.
SIM inventory travels through sender-authenticated `/sms-fetch` and receiver-authenticated
`/sms-status`, whose `request_sims` flag wakes the sender. FCM carries wake signals only.
Custom Firebase Database transport still does not support remote SMS sending.

Installed the current debug APK on Samsung SM-A520F sender and Xiaomi 23049PCD8G
receiver, preserving their existing enabled FCM pairing at `wss://relay.amberdogeorgia.com`.
Also updated the Samsung Magisk module's NextNotif.apk; its SHA-256 matches the local
APK (`58335796e9cb8a45f9448e82101632a7c0e00d63edd718ba93d8175a1d13ab91`).
No reboot was performed. Worker deployed with `wrangler.amber.jsonc` to version
`482cd705-1a2e-4e85-b297-b26423812be0`; retain the SQLite Durable Object migration.

Validation completed:
- Android unit tests, debug APK and instrumentation APK builds passed.
- Python SMS mailbox HTTP tests (5), Worker mailbox tests, and local Worker SMS HTTP
  integration passed. Full Python/Worker smoke suites also passed earlier in the session,
  before the SIM extension; focused SIM suites ran after that extension.
- Emulator selector interaction passed selecting SIM 2 and reverting to sender default.
  Synthetic fixture captures checked in light/dark (dark at 1.3x text); scoped review passed.
- `LiveSimOptionsInstrumentedTest` with explicit `liveSimOptions=true` passed on both
  physical phones. Samsung published two active SIMs; Xiaomi retrieved SIM 1 / CARD 1 /
  IR-MCI and SIM 2 / CARD 2 / Irancell from the deployed relay. This test only shares SIM
  metadata and ignores fetched SMS commands; it never sends texts.

Latest failure and stop point:
- Owner attempted two single-part messages with `subscription_id=2`; sender records
  have `status=failed` and `part_results={"0":false}`. An earlier stored one-part request
  has `status=sent`, but its actual default SIM was not recorded.
- Owner explicitly confirmed Samsung's native Messages app ALSO initially failed on
  SIM 2 (Irancell), then reported that the operator fixed the problem. This was not
  a NextNotif coding issue. Both SIM states were READY.
- `SmsSentReceiver` currently reduces Android's result code to a boolean; radio `errorCode`
  is not saved. Its PendingIntent is immutable. More detailed result-code capture and
  accurate single-part failure wording were discussed but NO such edits were made.
- If work resumes, consider retaining Android and radio error codes and distinguishing
  complete vs partial failure. The operator-side SIM 2 issue is resolved; no automatic
  retransmission, real SMS test, default-SIM change, or balance/SMSC change is authorized
  without an explicit request. Obtain an explicit recipient/message before agent-initiated
  sending.

Last device identifiers (rediscover): Samsung USB `52006a98f0ac6489`; Xiaomi Wi-Fi ADB
`adb-53d75ef-ChIRTM._adb-tls-connect._tcp`. Samsung USB was briefly unstable, then recovered
when the owner reconnected it. Xiaomi blocks ADB injected taps under its current security
settings; do not override them. Both apps were reopened normally after live SIM tests.
Temporary local Worker and emulator from UI validation were stopped. Test APKs remain
installed. Generated `.impeccable/review/` captures are local-only and ignored by Git.

### Historical checkpoints (read the current section above first)

2026-09-13 TURN implementation checkpoint (supersedes all older pending-TURN notes):
Cloudflare TURN key ID and issuance token provisioned as encrypted Worker secrets;
FCM_SERVICE_ACCOUNT preserved. Production Worker version
5a1c3e2a-82c7-42e4-b754-859ae774012b exposes authenticated POST /ice (existing
pairing/device credential required, no bootstrap, durable 16/min/pair limit,
no-store response, bounded provider request/JSON, sanitized errors).
Provider keys never enter the APK or credential response. Credentials last 14,700s,
covering the existing four-hour call cap plus setup grace. Existing relay custom
domain is now explicit in wrangler.amber.jsonc; preserve SQLite migration v1.

Both USB phones passed NativeWebRtcTwoPhoneInstrumentedTest with probeIce=production,
forced relay, audio disabled, localhost/ADB signaling only. Samsung default network
was validated Wi-Fi; Xiaomi default was validated cellular. Both native peers held
CONNECTED for two seconds and disposed successfully (Samsung 5.615s, Xiaomi 9.47s).
Aggregate candidates: sender 5, receiver 4, all udp_relay_v4_public; queues drained.
This validates network transport, NOT RTP speech, call-answer signaling, quality,
long calls, provider failover, or handover. No raw audio collected or cellular call made.

Actual call path now fetches authenticated TURN on IO before bridge startup, buffers
up to 128 validated early session signals until audio is ready, discards stale fetch
results after peer retirement, and includes fetch in the fixed rendezvous deadline.
Debug unit tests, assembleDebug, assembleDebugAndroidTest and lintDebug passed;
16 backend Node tests and 3 Python probe tests passed. Latest real-call APK installed
on Samsung 52006a98f0ac6489 and Xiaomi 53d75ef with app/pairing data preserved.
Test-only adb reverse tcp:8769 mappings removed. Next: owner-initiated live call
with Xiaomi on cellular and Samsung Wi-Fi; verify two-way RTP/audible speech.

Metered fallback is NOT wired into /ice yet. Its existing fetch-only API key returns
static transport credentials. Official docs require management secret + ahead-of-time
rotation for expiring credentials (propagation can take two minutes); do not create
brand-new Metered credentials on the critical call-setup path. Cloudflare remains
the only enabled TURN provider; fallback and quota-safe rotation still open.
Original FCM private-key backup deferred by owner. Owner requested committing and
pushing this checkpoint to dev; live-call speech validation remains pending.

GitHub rotation confirmation 2026-09-13: owner completed authentication; current
browser reports Secret updated and gh secret list confirms METERED_TURN_API_KEY
updated at 17:51:09Z (replaces revoked key's 17:42:44Z timestamp). Rotation storage
is complete; the earlier pending-auth notes below are historical. Saved secret names
also include CLOUDFLARE_TURN_KEY_ID, CLOUDFLARE_TURN_API_TOKEN,
METERED_TURN_DOMAIN and GOOGLE_SERVICES_JSON. FCM_SERVICE_ACCOUNT backup still
unconfirmed/missing. No Worker deployment or TURN two-phone test has run yet.

Rotation/audit follow-up 2026-09-13: owner approved revoking the exposed Metered
credential and backing up project credentials to GitHub. Removed only generated
nextnotif-fallback credential after explicit confirmation; created nextnotif-fallback-v2.
Replacement API key retained privately in browser session, not printed/exported.
IMPORTANT: GitHub update is NOT complete. Browser form triggers Confirm access
passkey/email owner re-authentication; gh secret list still reports the original
METERED_TURN_API_KEY timestamp 17:42:44Z. That GitHub value refers to the revoked
credential and must not be deployed/used until replaced. Owner must complete the
GitHub confirmation prompt. GOOGLE_SERVICES_JSON successfully added via gh CLI at
17:49Z; this is Firebase public client/build configuration, not an admin private key.
Local checkout, project/Downloads and project-specific temporary filename scans
found no original FCM service-account private-key file or production signing key.
Existing Cloudflare-stored FCM_SERVICE_ACCOUNT cannot be treated as backed up on
GitHub without its original value. Personal GitHub/Wrangler OAuth and phone pairing
tokens are not project deployment secrets; do not upload those. Local copies preserved.

Credential checkpoint 2026-09-13: owner activated Cloudflare Realtime personally and
authorized creating dedicated Cloudflare/Metered credentials and storing them on
GitHub. Cloudflare TURN app nextnotif-relay created successfully (the interrupted
creation had succeeded; no duplicate created). Encrypted Actions repository secrets
on shayanshd/NextNotif: CLOUDFLARE_TURN_KEY_ID, CLOUDFLARE_TURN_API_TOKEN,
METERED_TURN_API_KEY, METERED_TURN_DOMAIN. No secret values written to Git files or
APK. Existing CI does not consume these credentials, so storage alone does not
configure the deployed Worker or actual calls. Metered credential nextnotif-fallback
created on existing TURN TRIAL GLOBAL 500MB plan; payments/plan unchanged.
SECURITY: Metered per-credential API key appeared in diagnostic tool output because
the page embedded it in a code comment missed by filtering. Must revoke/remove
this test credential and replace it, then update METERED_TURN_API_KEY before use.
User confirmation required before irreversibly removing it through browser UI.
Do not log/export raw credential page content. No TURN connectivity test has run.

Cloudflare TURN eligibility checked in authenticated dashboard 2026-09-13, following
owner request to test Cloudflare's free allowance first. Account is not subscribed
to Realtime: TURN redirects to realtime-subscribe/turn. Activation checkout confirms
1,000 GB/month included and $0.05/additional GB, but requires an automatically renewing
Realtime subscription billed to the payment method on file and acceptance of terms.
Stopped before Add Realtime subscription: no subscription, TURN key, credentials,
deployment or new device test created. Owner previously declined billed service;
free allowance is not a hard no-billing cap. Dashboard left available for owner
decision. Existing Wrangler OAuth scopes do not include Calls Read/Write.

Latest isolated test 2026-09-13: Samsung Wi-Fi / Xiaomi validated LTE, USB serials
unchanged. NativeWebRtcTwoPhoneInstrumentedTest with recording/playout disabled and
ephemeral localhost signaling exchanged offer/answer and all candidates, but failed
on both peers. Host-only: Samsung one private IPv4 host candidate; Xiaomi IPv4/IPv6
host candidates. STUN comparison: public srflx candidates gathered by both peers,
still WebRTC connection failed. No cellular call, raw audio, pairing or permission
changes were needed. Thus TURN is the next connectivity test, not more root/audio
experiments. Public STUN discovery added to actual call configuration; bounded media
deadline now survives replacement peers and connected/resume signaling. Full network
fix pending TURN credentials and approval for any billed service. Final shared ICE
configuration and deadline patch passed testDebugUnitTest, assembleDebug,
assembleDebugAndroidTest and lintDebug. Both phones received the final app/test APKs
with data-preserving install -r and normal MainActivity startup restored. Only this
test's signaling processes and tcp:8769 USB reverses were removed. Actual cellular
timeout behavior is not physically verified. Changes remain uncommitted on dev.

Latest handoff 2026-09-13: user requested commit/push on dev. LAN two-way speech
and in-app Answer have owner confirmation. Sender's missing liveCallEnabled was
re-enabled by the owner, now verified true with gateway AVAILABLE. Compatible
backend 9d1489af was restored after today's FCM-route regression. Both device relay
URLs now verified wss:// on disk after restart; USB Xiaomi serial is 53d75ef.
Mobile-data retest now reaches real receiver signaling and answers Samsung, but
WebRTC stays Connecting audio: receiver ws open at 14:47:39, no connected audio,
setup_ms=-1, local_hangup at 14:49:15; both endpoints show silent playback while
media peers are repeatedly recreated. STUN/TURN remains absent. Next work: provision
authenticated short-lived ICE credentials and fix bounded setup/reconnect deadline
so failed media cannot loop indefinitely. No more call tests or deployments started
as part of this commit request. Historical pending/unpushed notes below are dated
context, not the current repository/deployment state.

Both Samsung and Xiaomi now have the latest tested debug build installed successfully
with data-preserving `adb install -r`, after both reported mCallState=0. This includes
receiver contextual microphone requests, call-service rejection handling, shared FCM
call state, duplicate-event-before-teardown handling, strict availability parsing and
duplicate Answer/stale intent guards. Normal startup was restored on both phones.
Xiaomi RECORD_AUDIO is already granted by the owner; tooling did not change it. Thus
first-grant/denial/permanent-denial behavior is still physically unverified. Relevant
JVM classes passed: AppState 21, CallRequestDispatch 3, IncomingEventHandler 5 and
RelayCallUiPolicy 4 tests. Debug/release builds and debug lint passed.

The owner now confirms caller audio is audible on Xiaomi after the installed Samsung
answerer track-association fix, following the earlier confirmed working opposite path.
Samsung live diagnostics show restored 8 kHz capture frames with signal, playback
signal and progressing inbound RTP (390 -> 694 -> 998 -> 1306). Xiaomi also reported
inbound RTP after this fix. Basic two-way cellular audio is demonstrated, not sustained
quality or release readiness. Still verify clarity, latency, long-call stability,
Active/timer, duplicate Answer, hang-up and FCM return. No raw audio was recorded.

## Resumption addendum

USB/LTE follow-up: Xiaomi USB serial is 53d75ef (Samsung unchanged). Validated LTE
active default network confirmed. Credential-free mobile HTTPS returned expected
400 in 0.62 s; WS-upgrade-shaped requests missing code returned expected 400 using
system DNS (2.98 s) and saved address (0.41 s). Real GET WS upgrade for existing
idle pairing, without hello/auth and discarding all response content, returned HTTP
101 in 0.68 s. Curl max-time afterward simply closes the held-open upgraded socket.

IMPORTANT correction to previous secure-settings checkpoint: app-level probe first
refused to run because Xiaomi had ws:// saved again. Earlier instrumented assertion
only checked memory; SessionStore.save uses asynchronous apply. Exact reason for
reversion is not established. Added explicit durable prefs flush to scoped upgrade
test, force-stopped idle app before/after maintenance, and restarted normally.
Both devices passed the upgrade test, and read-only ON-DISK prefs after normal
restart now confirm wss:// on both, Samsung liveCallEnabled=true. Owner settings,
history and permissions preserved. Added opted-in RelayNetworkProbeInstrumentedTest
that sends no hello/auth, Answer or media, probes only upgrade and verifies pairing
settings unchanged. On LTE with durable wss:// it passed: system resolver HTTP 101
798 ms; custom resolver HTTP 101 1076 ms. This disproves transport/DNS unavailability
for these probes, NOT proof of authenticated signaling or WebRTC across NAT.
Normal Xiaomi startup restored. Next owner mobile-data call can distinguish auth/
rendezvous from ICE media failure. STUN/TURN still absent; no production audio changes.

Mobile-data failure evidence: Xiaomi's two 14:21/14:22 attempts received FCM events
but temporary signaling WS timed out, both ending rendezvous_timeout, setup_ms=-1,
reconnects=1. Samsung corresponding 15:21/15:22 sockets opened but no audio, ending
cellular_ended. Phone local clocks differ by one hour. This is signaling failure
before WebRTC media, not proof of ICE/TURN failure. Saved URL on Xiaomi was ws://
relay.amberdogeorgia.com. Owner authorized upgrading both to wss:// same host.
Added opt-in SecureRelayUpgradeInstrumentedTest, strictly targeting pairing 454512
and exact old/new host URLs. Explicit single-class test passed on Samsung (0.21 s)
and Xiaomi (0.041 s); changes only pairing server while verifying all other settings
and history unchanged. MainActivity startup restored on both; no permission grants,
raw audio capture, credential clearing or server changes. Mobile-data secure
signaling retest and cross-network STUN/TURN remain unproven.

Owner re-enabled Samsung live-call relay. Read-only verification confirms pairing
454512 SENDER/enabled/fcm/liveCallEnabled=true; fresh service logs report gateway
capability AVAILABLE. Sender remains FCM idle with no persistent socket. Ready to
repeat notification-dismiss/reopen test; do not change owner opt-in automatically.

Latest failed notification-dismiss test: read-only sender prefs show pairing 454512
SENDER/enabled/fcm but liveCallEnabled key is absent. SessionStore defaults absent
liveCallEnabled to false; liveCallDenial therefore disables interactive relay and
advertises live_call_available=false. Receiver pairing enabled/fcm and relay enabled;
pending offer absent after Samsung reported cellular idle. This identifies a concrete
sender opt-in reset/migration issue independent of process recovery. Do not flip
sender consent automatically: owner must re-enable Answer calls on receiver in
Samsung pairing settings, then repeat test while ringing. It is not established
when or which action removed the setting.

Incoming-call discoverability follow-up: owner reports notification-only Answer again.
Verified Xiaomi installed APK hash matched the previous in-app-prompt build. Source
reveals process-recreation gap: Ringing state was memory-only, while notifications
survive process death. Implemented IncomingCallOfferStore for one bounded, original-
timestamp call offer. Shared incoming handler saves fresh supported Ringing, clears
matching non-older OFFHOOK/IDLE; explicit Answer consumes offer. Both activities
restore only an empty idle state for an enabled receiver pairing while source offer
is still fresh; no notification tap manufactures new call state. Expired/disabled/
removed offers cannot revive. Three pure reducer unit tests plus full JVM tests,
debug build and debug lint passed. Physical notification-dismiss/reopen regression
still needs owner verification. This identifies a real lifecycle gap, but logs alone
do not establish that process death caused the owner's specific repeat failure.
Recovery build now installed successfully on both confirmed-idle phones with adb
install -r and data preserved. Normal MainActivity startup restored on both.

2026-09-13 authorized backend repair: deployment history revealed a new active
version b0dcb6ba-066d-4af7-b87d-de0e2568dbca at 09:58:50 UTC, replacing the last
known compatible version. Both have the same PAIRING (RelayPairing) binding and
FCM_SERVICE_ACCOUNT secret name. Restored 9d1489af-35c8-4da4-909c-522cf3051e6d
to 100% traffic using Wrangler rollback; command succeeded. This changes Worker
code/config, not bound pairing storage, and does not change local files. Invalid
credential-free POST /fcm-register now correctly returns JSON missing pairing code
HTTP 400 instead of plain HTTP 404. No new local backend or unfinished security
work was deployed. Xiaomi confirmed cellular idle, then normal app restart retried
its one existing receiver pairing with settings/data/permissions preserved.
Real receiver verification succeeded: fresh Xiaomi process acquired its FCM token
and logged on-demand sync complete for pairing 454512, events=0, after restoration.
Previous HTTP 404 entries are historical; owner SMS/call retest remains next.

2026-09-13 retest failure: Xiaomi FcmOnDemand registration repeatedly returns HTTP
404 before WebRTC negotiation. Basic internet ping to 1.1.1.1 succeeds; google.com
resolves but ICMP does not answer (not proof of blocked Play services). GMS installed
and enabled; FCM token acquisition logged successfully. Credential-free invalid POST
to /fcm-register on relay.amberdogeorgia.com returns plain “Not found” on HTTP and
HTTPS, whereas current Worker source should reject missing code with JSON HTTP 400.
This supports deployed backend routing/version mismatch; no backend deployment made.
Owner requested restart. Xiaomi denies adb input injection, so with exactly one
receiver pairing visible, restarted the app via force-stop then normal MainActivity
launch, preserving settings/data/permissions. No security setting was changed.

2026-09-13 owner-requested retest preparation: current unit tests, debug build and
debug lint pass. Both devices confirmed cellular idle, then the same verified APK
was reinstalled successfully with adb install -r. Pairings/settings/data preserved;
no uninstall, app-data clear or automatic permission grant. MainActivity startup
restored on both. Owner will test notifications, in-app Answer and two-way audio.

Owner verified the installed foreground incoming-call UI: “yeah I saw the screen
and answered through that instead.” Answer from inside NextNotif now works without
the notification action. Background/locked-screen presentation remains unverified;
next check Xiaomi full-screen access read-only and invite a locked-screen owner call.
Do not interrupt an active call or grant access automatically.

Xiaomi read-only appops check reports uid USE_FULL_SCREEN_INTENT=ignore (package
entry default), so automatic full-screen presentation is currently denied by OS.
Owner must enable full-screen call access in system settings if desired; no tooling
grant was applied. Heads-up notification and verified in-app Answer remain fallback.

Incoming-call discoverability fix is now installed successfully on both test phones
with data-preserving adb install -r after both confirmed cellular idle. MainActivity observes
live Ringing state on every route and presents Answer/Open call screen using the
existing guarded permission/Answer flow. Notification content opens RelayCallActivity
without automatically answering. Live calls have a dedicated high-importance channel,
call category, ongoing ringing notification, bounded timeout and full-screen intent
when OS access permits; call screen may show above lock screen and wake display.
Notification identity is pairing-based so missing caller metadata on IDLE does not
leave an orphan ringing notification. No permissions were granted by tooling.
Need physical foreground/background/locked-screen inspection;
Android 14 full-screen special access and Xiaomi settings can limit automatic display.

Latest owner result: “yes I could hear the call now.” The missing Samsung-to-Xiaomi
audio regression is resolved for this controlled call. Unit tests, debug/release builds
and debug lint pass, including rejection of disabled/invalid SDP audio ports. Xiaomi's
native regression test was deliberately not run because a live owner call began;
only Samsung's audio-disabled native test has run for this fix. Historical pending
verification statements below are superseded by this checkpoint. No new Git push.

Answerer fix physical verification: both test phones now have the track-association
fix installed successfully with data preserved. Samsung's opt-in
NativeWebRtcPeerInstrumentedTest passed 2 tests in 0.487 s, including two-way generated
offer/answer validation and native connection/disposal, with recording/playout/tracks
disabled. Normal NextNotif startup restored on both phones. This is stronger than the
previous connection-only test, but still not proof of cellular downlink signal. Repeat
the owner call now, inspect sender capture signal and Xiaomi RTP/playback counters,
and require intelligible two-way speech before closing the one-way failure gate.

Repeat-call diagnostics now localize the one-way path: Samsung inbound RTP progressed
93 -> 401 -> 705, and three 8 kHz mono playback windows reported signal. Xiaomi
48 kHz capture windows reported signal, while inbound RTP stayed unavailable; neither
Samsung capture nor Xiaomi playback summaries were present. This supports a missing
Samsung sending stream rather than simply low receiver volume. Native recorder log
only showed construction. The External log tag is used by the ordinary pinned recorder
class too; do NOT assume a custom external-feed route from that tag alone.

Source fix pending verification/install: Samsung answerer formerly used addTransceiver
before remote offer. W3C RTP media API explicitly states that such unassociated
transceivers cannot be associated with an incoming offer, unlike addTrack-created ones:
https://w3c.github.io/webrtc-pc/#rtp-media-api . Sender now uses addTrack, selects its
matching transceiver for original advertised Opus preferences, and validates generated
and remote SDP as one bidirectional audio section. Native audio-disabled peer test now
asserts both offer and answer are two-way, not merely ICE-connected. No capture source,
mixer or codec format changes are made. Native regression and actual two-way cellular
speech still need physical proof before declaring this failure fixed.

Latest installed diagnostic checkpoint: both phones were confirmed idle, then the
debug build including PCM capture/playback health, periodic inbound RTP counts and
Answer-time freshness was installed successfully with data preserved. Normal startup
was restored on both. Tests, debug/release builds and lint passed after enabling
BuildConfig; initial diagnostic compile failed only because BuildConfig generation
was disabled, and that failure is resolved. Next controlled call should keep speaker
mode unchanged and have caller speech for at least 15 seconds. Read only
`WebRtcMediaHealth` counters plus RelayService end/state metrics; no raw-audio probe
or file capture is authorized/needed. Diagnosis still awaits that repeated call.

Pending one-way diagnostics: debug-only JavaAudioDeviceModule capture and playback
callbacks aggregate PCM16 frames in memory over five-second windows, logging only
frame/sample counts and signal/quiet/silent classification at WebRtcMediaHealth.
Inbound RTP aggregate counts log separately at that tag approximately every six
seconds. No buffers/samples are retained by PcmSignalHealth, no raw audio is written,
and release builds do not register PCM diagnostic callbacks. Unit tests cover signed
little-endian PCM, silence, quiet signal, reset, malformed buffers and clock rollback.
BuildConfig generation is enabled for the debug-only guard. Capture source, mixer,
codec and playout policy are unchanged. Do not interpret playback signal as proof of
audible speaker output or silent windows as failure without controlled caller speech.

First actual cellular WebRTC report: owner placed a test call and reports Xiaomi's
voice reaches the caller, but caller audio cannot be heard back on Xiaomi. Treat this
as a failed **one-way audio** gate, not a successful two-way call. Samsung afterward
reports both mCallState=0; retained service metrics show cellular_ended, setup_ms=18007.
Filtered retained logs did not provide inbound RTP evidence on either phone; absence
from retained logs is not proof of no RTP. Read-only package verification confirms
Samsung UPDATED_SYSTEM_APP/PRIVILEGED and CAPTURE_AUDIO_OUTPUT granted=true, so lost
privileged capture grant is not supported as the cause. No source/route/gain change
has been made in response. Next diagnostic should distinguish Samsung downlink PCM
signal from Xiaomi inbound RTP/playout using coarse in-memory counters only, without
recording call audio; then repeat the controlled call. Do not guess at a capture-source
change or declare packet loss based on the one-way report alone.

Pending Answer-time expiry: original offeredAt now flows from durable payload to
AppState and notification activity intent. Activity checks freshness before prompting
and after the permission result; controller checks again before service dispatch.
Explicit process-death recovery requires the original valid intent timestamp, never
receipt/reopen time. Expired offers do not request Answer and surface an expired-alert
message. JVM regression covers retaining original time through phase changes. This
latest change is not installed or physically tested; per-call ID binding is still
missing, so an old same-pair intent must not be treated as generation-safe yet.

Pending delayed-offer guard: CallEventFreshness permits interactive availability only
for positive integer source timestamps within 90 seconds old / 30 seconds future skew.
Missing, malformed and expired call payloads still enter durable history and normal
notifications, but cannot create an interactive Ringing offer or action on delivery.
Three JVM tests cover age/skew boundaries, numeric extremes, malformed timestamps and
capability gating. This newest change is not installed. It is not full generation
security: action-tap expiration, replayed historical Idle against a replacement call,
clock skew outside tolerance and sender-authenticated per-call IDs still need coverage.

Pending receiver microphone UX: RelayCallActivity requests RECORD_AUDIO only on an
explicit Answer, suppresses repeat requests, preserves pending request through saved
instance state and revalidates phase/pairing on grant. Denial leaves Ringing and shows
retry/settings guidance. onNewIntent now updates the observed screen without recreate,
preserving pending permission state. Service also refuses Answer without receiver
microphone permission before requesting the sender to answer. These changes are not
installed or physically verified. First grant, denial/permanent denial, rotation,
call ending during permission prompt and stale notification paths remain test gates.

Replay-path consolidation: RelayForegroundService now invokes shared durable handling
before passive call-state/Idle cleanup and returns on a duplicate event. Its separate
pre-dedup Ringing mutation was removed. Live availability now accepts only JSON boolean
true, not coercible strings/numbers; JVM coverage includes string true/false, 1/0 and
null. These changes are pending installation; integrated duplicate-Idle teardown tests
and call-generation/expiry protection remain. Review receiver microphone permission
on first Answer: current optional setup prompts are sender-oriented, while receiver
media also needs a microphone grant before foreground promotion.

Further pending fix: shared IncomingEventHandler now updates passive call state for
FCM fetch as well as other transports. Root-capable Ringing after a terminal call can
reach Answer again; unsupported call information cannot enable it. AppState rejects
incoming Ringing while Answering/Connecting/Active/Reconnecting and serializes call
state mutations. Passive Idle only dismisses unanswered Ringing, leaving active media
teardown with the service. JVM tests cover duplicate same-pair alerts, other-pair
alerts, terminal-to-next-call and informational-only payloads. These newest changes
are not installed. Delayed historical call events still need call-generation/expiry
protection; do not claim that transport ordering/session security gate is complete.

Additional source safeguard: CallRequestDispatch catches Android permission and
background-start rejections during Answer, reports an actionable failed state instead
of throwing or leaving Answering stuck, and does not swallow unrelated programming
errors. Three JVM tests cover success, both rejection classes, and propagation. This
latest safeguard is not yet installed, so the already invited controlled call should
use the installed duplicate-Answer/identity fixes without another disruptive update.
Filtered RelayService diagnostics and Samsung mCallState=0 showed no new call/RTP
evidence at the latest observation; no real-call success is established.

Work resumed on `dev` after checkpoint 6c72418. Duplicate Answer and stale identity
issues below now have source fixes and JVM policy tests: controller accepts only the
current Ringing call or an empty Idle process, claims Answering before service start,
and validates receiver pairing; service refuses to restart/replace an active session.
Opening an activity no longer creates incoming state from notification extras.
These changes remain uninstalled and require physical notification/recovery tests.
Subsequent physical update: both phones confirmed cellular mCallState=0; the latest
tested debug APK was installed successfully with `adb install -r` on Samsung and
Xiaomi. Saved Samsung liveCallEnabled is now confirmed true, enabled sender true;
the owner opt-in was not changed by tooling. Normal app startup was restored on both.
Pending Answer/identity changes are now installed, but notification-flow and real
cellular audio verification still require a controlled call. Earlier uninstalled and
unresolved idle-check statements below are historical and superseded by this evidence.
Latest resumed verification: Android unit tests, debug/release builds and lint passed;
the four RelayCallUiPolicy tests have zero failures. Python pairing/storage and probe
tests pass (20 cases), run from `server/` using
`python3 -m unittest test_secure_pairing test_secure_pairing_store test_webrtc_probe`.
ADB lists both phones, but the subsequent idle-state check did not complete and was
stopped; no APK update or real-call test was performed during that check. Revalidate
idle state before installing. README now documents the excluded Firebase build config.
The pause section below records historical handoff state, not a new pause request.

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

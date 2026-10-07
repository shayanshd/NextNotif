# Private MVP release notes — draft

Status: **draft, not approved for installation or distribution** (2026-10-07).

## Included

- Forward incoming SMS and incoming-call information to a paired phone through the relay, with local history and notifications.
- Remote SMS sending with an explicit SIM choice and no automatic retry after an uncertain carrier result.
- Copyable, single-use pairing invite; FCM wake and WebSocket delivery; queued events acknowledged after storage on the receiver.
- Local message-content expiry after 30 days and relay event expiry after seven days once the updated backend is deployed and migrated.

## Current limits

- SIM 1 has one confirmed remote send and owner-confirmed receipt. SIM 2 remains visible by owner choice but has not passed a carrier send test. Multipart remote sending and long-term reliability are also unvalidated. Confirm the chosen SIM and recipient before sending; do not repeat an uncertain send without checking its status.
- Rooted live-call answer/audio is excluded from the private release. Incoming-call information and alerts remain in scope.
- The relay processes SMS/call metadata and content to deliver it; there is no end-to-end encryption between paired phones. Local app storage is encrypted with Android Keystore on supported devices.
- The original Samsung system app has a different signing certificate. Its eventual replacement requires the separate controlled cutover and may require re-pairing after verified backup. Do not install this APK over it directly.

## Release record to fill at approval

- Source commit and clean-tree verification:
- Production Worker deployment and migration verification:
- Signed APK SHA-256, package/version, and certificate fingerprint:
- Signing-key separate-backup verification:
- Physical canary results and rollback checkpoint:
- Owner approval to replace Samsung's original app:

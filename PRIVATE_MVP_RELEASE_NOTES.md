# Private MVP release notes — draft

Status: **draft, installed on three test phones; not approved for distribution** (2026-10-08). Version 3 is the current private build.

## Included

- Forward incoming SMS and incoming-call information to a paired phone through the relay, with local history and notifications.
- Remote SMS sending with an explicit SIM choice and no automatic retry after an uncertain carrier result.
- Remote call answering, dialing, and two-way speech on the Samsung A5 rooted gateway with a paired receiver. This path is installed but has not passed a live call test.
- Copyable, single-use pairing invite; FCM wake and WebSocket delivery; queued events acknowledged after storage on the receiver.
- Local message-content expiry after 30 days and relay event expiry after seven days once the updated backend is deployed and migrated.

## Current limits

- SIM 1 has one confirmed remote send and owner-confirmed receipt. SIM 2 remains visible by owner choice but has not passed a carrier send test. Multipart remote sending and long-term reliability are also unvalidated. Confirm the chosen SIM and recipient before sending; do not repeat an uncertain send without checking its status.
- Rooted call audio requires the Samsung A5 Magisk helper and protected permissions. They are installed and granted on Samsung; repeatable two-way speech and hang-up remain unverified. HTC has receiver microphone access. Xiaomi has the normal build, but its call receiver path is untested.
- The relay processes SMS/call metadata and content to deliver it; there is no end-to-end encryption between paired phones. Local app storage is encrypted with Android Keystore on supported devices.
- The original Samsung system app was replaced before this version. Version 3 updated the private app in place on Samsung, HTC, and Xiaomi; the original app-data backup remains unverified. Old pairings are to be revoked and recreated with fresh copied invites, as approved by the owner.

## Release record to fill at approval

- Source commit and clean-tree verification:
- Production Worker deployment and migration verification:
- Signed APK SHA-256, package/version, and certificate fingerprint:
- Signing-key separate-backup verification:
- Physical canary results and rollback checkpoint:
- Owner approval to replace Samsung's original app:

# Receiver wake fallback

NextNotif uses Firebase Cloud Messaging to request receiver catch-up when its
WebSocket is offline. The normal relay queue remains the source of event content.

The Android APK defaults to the existing `com.nextnotif.app` registration in
`nextnotif-5bcf9` (sender ID `223835571995`). Its public SDK settings are generated
by `android/app/build.gradle.kts`; no service-account key belongs in the APK.
Private builds can override all four Gradle properties: `nextnotif.fcm.appId`,
`nextnotif.fcm.apiKey`, `nextnotif.fcm.senderId`, and `nextnotif.fcm.projectId`.
Use an Android app registration, not a Firebase web app ID. Setting the app ID
property to an empty string disables default Firebase initialization and FCM.
Database transport pairings use separate named Firebase apps.

## Server configuration

For FastAPI, set `NEXTNOTIF_FCM_SERVICE_ACCOUNT` to the path of a Firebase service
account JSON file, or use the ignored `server/fcm-service-account.json` path.
For Workers, store the JSON as the `FCM_SERVICE_ACCOUNT` secret on the relay
Worker. The account must be authorized to send FCM messages in the APK's Firebase
project. Without this credential, queued catch-up still works on reconnect.

## Protocol and behavior

1. Android obtains a registration token and sends it in receiver `auth.fcm_token`.
   Token rotation is also sent through an authenticated `fcm_token` message.
   Servers persist tokens only after successful authentication.
2. An offline receiver's event is queued. The server sends a high-priority,
   data-only push: `{"nn":"1","code":"123456","action":"wake"}`. No SMS body,
   caller number, or contact name travels in the push, keeping it small even for
   long SMS messages. Wakes have a 30-second per-pairing cooldown.
3. Android checks the current pairing, receiver role, transport, enabled flag,
   and persisted global stop preference. Removed, paused, sender, and RTDB
   pairings are ignored. Explicit Stop also survives reboot.
4. The app immediately shows a catch-up notification, then requests the foreground
   relay service for high-priority delivery. The authenticated socket retrieves
   queued events, shows the normal content notifications, and clears the catch-up
   notification. Notification taps also work when the activity is already open.
5. If the push is downgraded or Android refuses the service start, the notification
   offers recovery by opening the app. The original event stays in the queue.

FCM is best effort and requires working Google Play services and network access.
It cannot bypass an Android force-stop. This integration wakes **relay-server
receivers**; it does not capture sender events while the sender is unavailable,
or add a server-side trigger to the separate RTDB transport.

The data-only payload and immediate notification follow the official
[FCM message handling guidance](https://firebase.google.com/docs/cloud-messaging/customize-messages/set-message-type)
and [priority guidance](https://firebase.google.com/docs/cloud-messaging/android-message-priority).
Background service starts also obey
[Android's restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

## Verification

`server/test_smoke.py` and `server-workers/test_smoke.py` use mock OAuth/FCM
endpoints to check signed credentials, the exact data-only payload, registration,
offline queue replay, online delivery without wakes, and cooldown behavior. The
server suite uses temporary pairing storage, preserving the local relay database.
Android JVM tests cover wake routing and independent Firebase app identities.

For live verification, install the APK, start a receiver pairing, and check its
`/pair/<code>/status` response for `receiver_has_fcm: true`. Test an offline
receiver with a clearly labeled synthetic event, then verify the catch-up
notification, reconnect, and content notification. Killing a process and
force-stopping an application are different tests; force-stop suppresses push
recovery until the user opens the app.

Live verification on 2026-09-13: Worker version
`b0dcb6ba-066d-4af7-b87d-de0e2568dbca` queued a synthetic SMS for an offline
Xiaomi API 35 receiver. Android recorded a `PUSH_MESSAGING` allowance when the
foreground relay service started; the receiver reconnected and displayed the
synthetic content, clearing the catch-up notification. Both the Samsung API 26
and Xiaomi then reported connected. Both phones cached FCM tokens. This checks
the deployed push-to-catch-up path, not arbitrary OEM kill policies.

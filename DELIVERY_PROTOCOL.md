# Durable FCM delivery

NextNotif uses FCM only to wake a receiver. The push contains a pairing locator,
not SMS bodies, caller identity, event IDs, or audio. The receiver retrieves events
over HTTPS from its configured relay. TLS protects each network hop; this is not
end-to-end encryption, and the relay can read queued communication content.

## Fetch, store, acknowledge

1. `POST /fetch` with `X-NextNotif-Code` and `X-NextNotif-Token` returns
   `{ "events": [...] }` in queue order without deleting the queue.
2. The receiver stores SMS/call/test history and pairing-scoped replay IDs in
   one checked local write. Failed storage interrupts sync and schedules retry.
3. `POST /ack` with the same headers and `{ "event_ids": [...] }` removes
   only those IDs from the current queue. It accepts up to 100 nonblank string
   IDs; an empty list is a no-op. Retrying an ACK is safe.

A dropped fetch response leaves the events queued. A crash after local storage
but before ACK causes replay; durable replay protection suppresses duplicate
history and notifications. A new event arriving after fetch survives ACK of the
older snapshot. Cleared history does not clear replay protection.

Local history is bounded to 200 entries and replay protection to 1,000 IDs.
The relay queue remains bounded to 50 events; overflow/TTL reporting is not yet
implemented. Notification presentation is best-effort after durable history:
a crash at that boundary can leave history present without its notification.

## During optional live calls

An FCM receiver sends `delivery_mode: "fcm"` in WebSocket authentication for
its temporary call connection. The relay keeps its backlog on fetch/ACK and
queues new `sms`, `call`, and `relay_test` events even while that socket is open.
Live call controls and binary audio use the socket; they are not durable inbox
messages. Legacy clients without this flag keep the old WebSocket behavior.

## Rollout and remaining release gates

- Deploy `/fetch` and `/ack` support before installing the new receiver build.
- Do not fall back to destructive `/drain` if the new relay endpoint is absent.
- Keep the compatible relay available while rolling a receiver back; rolling
  the relay back first can strand new-client sync until compatibility is restored.
- Verify response loss, process death, cleared-history replay, and live-call
  delivery on the Samsung sender and Xiaomi receiver before shipping.
- Legacy `/drain` and persistent WebSocket replay are still destructive.
- Existing device credentials are not role-scoped and code-only bootstrap is
  still possible. This protocol improves durability, not pairing authorization;
  secure invites, role credentials, revocation, and command nonces remain P0 gates.

## Opt-in phone test

`LiveFcmDeliveryInstrumentedTest` is skipped unless `liveDelivery=true` is supplied.
Run only that class, on the receiver and sender, with the same `pairingCode` and
unique alphanumeric/underscore/hyphen `deliveryMarker`. Resolve the current Android
user explicitly (Xiaomi requires it). The test preserves preferences/history and
never enables a stopped relay. It sends one labelled TEST event, waits for real
push-triggered history on the receiver, and repeats sync to check ACK and deduplication.
Start the normal app afterward because instrumentation can interrupt its process.

Do not run the whole instrumented suite on these configured phones: existing
storage tests intentionally overwrite or clear preferences. This lab-only test
does not replace notification visual checks, response-loss/process-death tests,
Doze recovery, or a rooted live-call soak.

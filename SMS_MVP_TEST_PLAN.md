# Private MVP remote SMS staging test plan

Prepared 2026-10-07. **No carrier SMS is authorized by this document and none was sent while preparing it.** The owner approved preparation of controlled SIM 2 and multipart tests. Obtain a separate final send approval after showing the exact recipient, message text, selected SIM, and maximum send/segment count for each run.

## Isolation and preflight

- Use HTC's separate `com.nextnotif.app.staging` receiver and Samsung A5's separate `com.nextnotif.app.staging.sms` sender, paired through `nextnotif-relay-staging`. Never use the original `com.nextnotif.app` packages or the production Worker for these tests.
- Recheck connected device serials, installed package paths, signing identities, staging pairing health, relay state, `SEND_SMS` and `READ_PHONE_STATE` only on the SMS Test sender, and current SIM labels. Prior evidence showed SIM 1 / IR-MCI and SIM 2 / Irancell; do not assume that mapping is still current.
- The owner selects and verifies a controlled recipient on the phone. Do not put its full number, message body, pairing token, or service credentials in command output or this plan. Confirm the test recipient can independently report delivery and exact content.
- Before each send, confirm there is no unresolved prior request with the same test ID. Do not repeat a Send tap after a timeout or an uncertain result.

## Run 1: SIM 2 single-part send

1. Prepare a unique short GSM-7 marker. Show the owner its exact text, destination as displayed on the phone, SIM 2 label, and a cap of **one Send tap / one segment**.
2. After explicit approval for that exact send, submit once from HTC Staging with SIM 2 selected on Samsung SMS Test.
3. Record the receiver's request ID and state (`queued`, `sent to carrier`, `failed`, or `unknown`) without copying the number or body to logs. Ask the controlled recipient to confirm receipt and the marker.
4. On timeout or `unknown`, stop. Inspect the stored request ID and carrier callback state before any further action. Never resubmit the same content to resolve uncertainty.

## Run 2: SIM 2 multipart boundary

1. Prepare one unique, printable GSM-7 message just over the two-part concatenated boundary (307 characters, expected three segments). Count its length and check the exact text with the owner before the send. Verify the segment count with Android's [`SmsManager.divideMessage`](https://developer.android.com/reference/android/telephony/SmsManager#divideMessage(java.lang.String)) on the selected sender SIM before approval. Cap at **one Send tap / three verified segments**; carrier billing must still be verified independently.
2. After separate approval for that exact recipient, text, SIM 2 selection, and three-segment cap, submit once.
3. Verify the per-part callback result, final status, and recipient's exact assembled message. A partial acceptance or uncertain result is a failure/stop condition, not an instruction to retry.

## Non-carrier checks and exit criteria

- Run the local Android regression suite for uncertain-result deduplication, per-part error preservation, and no false `sent` state. Exercise relay fetch/ACK/restart and staging synthetic delivery separately; do not count these as paid SMS proof.
- Revoke temporary SMS permissions if they were granted solely for this run, and leave the SMS Test reply switch off unless a later test explicitly needs it.
- The remote-SMS MVP gate closes only when SIM 2 selection, one-part delivery, multipart assembly, failure/uncertain handling, and no duplicate send after timeout have evidence from the relevant local and phone tests. Record timestamps, package identities, request IDs, and outcome without recipient or message content.

# Production relay retention and pairing migration

Status: **prepared, not deployed** (2026-10-08). Current production Worker checkpoint: `56286d69-ab26-468c-ac74-6c3a88dff283` (read-only Wrangler check on 2026-10-07/08). The current Worker source retains legacy-client behavior while supporting new invite pairings; it does not convert old pairing credentials in place. The owner approved revoking old pairings and creating fresh copied-invite pairings during cutover.

## Scope

The new code prunes queued relay events after seven days and SMS/call command records after 48 hours. New writes schedule a Durable Object alarm. Existing quiet objects need one maintenance touch to prune expired content and schedule their next alarm. Cloudflare's [list objects API](https://developers.cloudflare.com/api/resources/durable_objects/subresources/namespaces/subresources/objects/methods/list/) can enumerate stored object IDs without reading their contents; the [Durable Object ID API](https://developers.cloudflare.com/durable-objects/api/id/) allows the Worker binding to address each existing object by that ID.

`tools/backfill-relay-retention.py` lists IDs using a scoped Cloudflare API token. It prints only object counts and defaults to a dry run. With `--apply`, it calls the new `POST /__maintenance/touch` route once per object. That route is unavailable unless production has a `NEXTNOTIF_MAINTENANCE_TOKEN` Secret of at least 32 characters. It checks the token before parsing the ID, validates the ID against the bound namespace, and returns only `{ "ok": true }`. The Durable Object purges expired queue/command content and reschedules its alarm. Do not use a token in a command argument, chat, Git, or shell history.

## Pre-deploy checks

1. Confirm a clean source commit, passing Worker unit tests, both local workerd runtime configurations (SQLite and legacy KV), and `wrangler deploy --dry-run --config wrangler.jsonc`. The local legacy fixture checks old status, token-authenticated fetch, code-only send/drain compatibility, expired queue pruning, and secure namespace isolation. It does not contain actual production data.
2. Recheck production Worker deployment ID, relay hostname/root, Durable Object namespace ID/backend, current Secret binding types, and the original Samsung app's connectivity. Record the old Worker version as the rollback checkpoint. Do not display pairing codes or credentials.
3. Create a new random maintenance token in a secure vault and add it as a production Worker **Secret**; never use a plain-text variable. Provision a separate short-lived Cloudflare API token with only Workers Scripts Read for listing namespace objects. Do not reuse the FCM, TURN, or signing credentials.
4. Determine how to test one original client against the updated backend without reading message content. Keep the new Android package off Samsung until old-client compatibility is confirmed.

## Controlled rollout

1. Deploy the compatible Worker code before new Android clients. Confirm deployment version and bindings; check root health and a synthetic secure pairing on production only after explicit approval and with cleanup credentials preserved. Verify an original client's connection/status remains healthy.
2. Set `CLOUDFLARE_ACCOUNT_ID`, `NEXTNOTIF_PAIRING_NAMESPACE_ID`, and `CLOUDFLARE_API_TOKEN` in a private operator environment. Run `python3 tools/backfill-relay-retention.py` without flags and record the count only. It must match the intended production namespace; a surprising count stops the rollout.
3. Set `NEXTNOTIF_MAINTENANCE_TOKEN` from the vault, run `python3 tools/backfill-relay-retention.py --apply`, and compare touched count to the dry-run count. Rerun the dry run and apply once to catch objects created during pagination. The operation is idempotent; it never returns or prints stored content. A failed or partial run stops for diagnosis rather than silently declaring retention complete.
4. Verify a known old client still functions, a new invite pairing passes create/join/send/fetch/ACK/deletion, and a controlled aged-data fixture gets an alarm. Monitor content-free errors, queue overflow, and FCM wake status. Keep legacy routes until old clients have moved to fresh secure pairings. Then disable legacy code-only routes and verify the old credentials fail before calling the cutover complete.
5. After backfill, remove the temporary maintenance Secret and revoke the listing API token. The maintenance route then returns 404. Recheck the active Worker version and Secret inventory by name/type only.

## Rollback and limits

If old clients fail after code deployment, restore the recorded previous Worker version while the original client remains installed. This code rollback does not reverse data already purged at its retention boundary. New secure pairings created on the updated Worker may not work on the old version, so freeze new-client rollout until backend canary passes. A cloud rollback must be tested before relying on it during the Samsung cutover.

The backfill is a privacy/data-expiry operation; run it only after the owner has approved the seven-day retention, which they did. It does not revoke a pairing, retire legacy code-only routes, or shut down old Firebase Database access. Those are separate cutover steps. The original Samsung app and production Worker have not been changed by preparing this document.

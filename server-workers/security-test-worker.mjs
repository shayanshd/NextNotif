// Local-only runtime fixture. Never deploy this test entry point.
import { DurableObject } from 'cloudflare:workers';
import { RelayPairing } from './src/worker.js';
import { SecurePairingStore } from './src/secure-pairing-store.mjs';
import { createSecure, joinSecure, authorizeSecure, authorizeOwner, revokeSecure,
  deletionIdentity, authorizeDeletion, restoreSecure, AuthorizationError } from './src/secure-pairing.mjs';

function assert(condition) { if (!condition) throw new Error('security assertion failed'); }
async function denied(action) {
  try { await action(); } catch (error) {
    assert(error instanceof AuthorizationError);
    return;
  }
  throw new Error('authorization unexpectedly succeeded');
}
export class SecurityStoreTest extends DurableObject {
  async fetch() {
    const store = new SecurePairingStore(this.ctx.storage);
    const created = await store.create('sender', 1000);
    const results = await Promise.allSettled(Array.from({ length: 12 }, () => store.join(created.invite.secret, 'receiver', 1001)));
    const winners = results.filter(result => result.status === 'fulfilled');
    assert(winners.length === 1);
    const peer = winners[0].value.device;
    const restored = await new SecurePairingStore(this.ctx.storage).read();
    assert(Object.keys(restored.credentials).length === 2);
    await authorizeSecure(restored, peer.device_id, peer.device_token, 'receiver');
    await denied(() => store.join(created.invite.secret, 'receiver', 1002));
    await denied(() => store.create('receiver', 1002));
    const data = JSON.stringify([...await this.ctx.storage.list()]);
    for (const secret of [created.device.device_token, created.invite.secret, peer.device_token]) assert(!data.includes(secret));
    return Response.json({ ok: true, consumers: 12, committedGrants: winners.length });
  }
}
export class SecurityHttpTest extends RelayPairing {
  async fetch(request) {
    if (new URL(request.url).pathname !== '/run') return super.fetch(request);
    const store = new SecurePairingStore(this.ctx.storage);
    const created = await store.create('sender', 1000);
    const joined = await store.join(created.invite.secret, 'receiver', 1001);
    const sender = created.device;
    const receiver = joined.device;
    const headers = device => device ? { 'X-NextNotif-Device-Id': device.device_id, 'X-NextNotif-Token': device.device_token } : {};
    const call = (operation, device, body = {}, extra = {}) => super.fetch(new Request(`http://local/${operation}/123456`, {
      method: 'POST', headers: { ...headers(device), ...extra }, body: JSON.stringify(body),
    }));
    const before = JSON.stringify([...await this.ctx.storage.list()]);
    const registration = { fcm_token: 'runtime-test-token-123456789', device_name: 'Secure receiver' };
    for (const operation of ['send', 'fcm-register', 'fetch', 'drain', 'ack', 'status']) {
      assert((await call(operation, null, registration)).status === 401);
      assert((await call(operation, { ...receiver, device_token: 'wrong' }, registration)).status === 401);
    }
    assert((await call('send', receiver)).status === 401);
    for (const operation of ['fcm-register', 'fetch', 'drain', 'ack']) assert((await call(operation, sender, registration)).status === 401);
    assert(JSON.stringify([...await this.ctx.storage.list()]) === before);
    assert((await call('status', sender)).status === 200);
    assert((await call('status', receiver)).status === 200);
    const registered = await call('fcm-register', receiver, registration);
    assert(registered.status === 200);
    assert((await registered.json()).device_token === receiver.device_token);
    assert((await this.getTokens()).length === 0);
    assert((await call('send', sender, { type: 'relay_test', data: { marker: 'secure-runtime' } })).status === 200);
    const fetched = await call('fetch', receiver);
    const events = (await fetched.json()).events;
    assert(events.length === 1);
    assert((await call('ack', receiver, { event_ids: [events[0].event_id] })).status === 200);
    assert((await this.getQueue()).length === 0);
    // Secure WS is intentionally disabled until its authenticated lifecycle is
    // integrated. The rejection must precede any state or metadata mutation.
    const ws = await super.fetch(new Request('http://local/ws/receiver/123456', { headers: { Upgrade: 'websocket' } }));
    assert(ws.status === 401);
    await this.ctx.storage.put('securityMode', 'legacy');
    assert((await call('fetch', receiver)).status === 401);
    assert(await this.tryClaim() === false);
    await this.ctx.storage.put('securityMode', 'invite_v1');
    await this.ctx.storage.delete('secureRecord');
    assert((await call('fcm-register', receiver, registration)).status === 401);
    return Response.json({ ok: true, checks: 'HTTP roles, no metadata/token mint on rejection, queue fetch/ack, namespace quarantine, secure WS disabled' });
  }
}
export default {
  async fetch(request, env) {
    if (new URL(request.url).pathname === '/http-test') {
      return env.SECURITY_HTTP.getByName(crypto.randomUUID()).fetch('http://local/run');
    }
    if (new URL(request.url).pathname === '/storage-test') {
      const stub = env.SECURITY_TEST.getByName(crypto.randomUUID());
      return stub.fetch('http://local/test');
    }
    if (new URL(request.url).pathname !== '/test') return new Response('Not found', { status: 404 });
    const created = await createSecure('sender', 1000);
    const { device: owner, invite } = created;
    const original = JSON.stringify(created.record);
    await denied(() => joinSecure(created.record, 'wrong', 'receiver', 1001));
    await denied(() => joinSecure(created.record, invite.secret, 'sender', 1001));
    await denied(() => joinSecure(created.record, invite.secret, 'receiver', 1900));
    const joined = await joinSecure(created.record, invite.secret, 'receiver', 1001);
    assert(JSON.stringify(created.record) === original);
    await denied(() => joinSecure(joined.record, invite.secret, 'receiver', 1002));
    const restored = restoreSecure(JSON.parse(JSON.stringify(joined.record)));
    const peer = joined.device;
    assert(await authorizeSecure(restored, peer.device_id, peer.device_token, 'receiver') === peer.device_id);
    await denied(() => authorizeSecure(restored, peer.device_id, peer.device_token, 'sender'));
    await denied(() => authorizeSecure(restored, peer.device_id, 'wrong', 'receiver'));
    await denied(() => authorizeOwner(restored, peer.device_id, peer.device_token));
    const revoked = await revokeSecure(restored, owner.device_id, owner.device_token, peer.device_id);
    await denied(() => authorizeSecure(revoked.record, peer.device_id, peer.device_token, 'receiver'));
    await authorizeOwner(revoked.record, owner.device_id, owner.device_token);
    const tombstone = await deletionIdentity(revoked.record, owner.device_id, owner.device_token);
    await authorizeDeletion(tombstone, owner.device_id, owner.device_token);
    await denied(() => authorizeDeletion(tombstone, peer.device_id, peer.device_token));
    const serialized = JSON.stringify({ created, joined, revoked, tombstone });
    for (const secret of [owner.device_token, peer.device_token, invite.secret]) assert(!serialized.includes(secret));
    return Response.json({ ok: true, runtime: 'workerd', checks: 'invite, role, restore, revocation, deletion, secret serialization' });
  },
};

// Local-only runtime fixture. Never deploy this test entry point.
import { DurableObject } from 'cloudflare:workers';
import relay, { RelayPairing } from './src/worker.js';
import { SecurePairingStore } from './src/secure-pairing-store.mjs';
import { QUEUE_RETENTION_MS } from './src/relay-queue.mjs';
import { SMS_RETENTION_MS } from './src/sms-mailbox.mjs';
import { CALL_RETENTION_MS } from './src/call-mailbox.mjs';
import { CREATE_ATTEMPTS_PER_MINUTE, JOIN_CLIENT_ATTEMPTS_PER_MINUTE } from './src/pairing-rate-limit.mjs';
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
    const path = new URL(request.url).pathname;
    if (path === '/seed-maintenance') {
      const old = Date.now() - Math.max(QUEUE_RETENTION_MS, SMS_RETENTION_MS, CALL_RETENTION_MS) - 1000;
      await this.ctx.storage.put({
        queue: JSON.stringify([{ ts: old, event_id: 'expired-maintenance', out: 'private fixture content' }]),
        smsCommands: [{ id: 'expired-maintenance', created_at: old, body: 'private fixture content' }],
      });
      return Response.json({ ok: true });
    }
    if (path === '/assert-maintained') {
      assert(await this.ctx.storage.get('queue') == null);
      assert((await this.ctx.storage.get('smsCommands')).length === 0);
      assert(await this.ctx.storage.getAlarm() == null);
      return Response.json({ ok: true });
    }
    if (path === '/run-legacy-migration') {
      // Simulate the persisted KV layout of an older production pairing. The
      // updated Worker must preserve its client path while pruning old data.
      const now = Date.now();
      const token = 'local-legacy-fixture-token';
      const fresh = { ts: now, event_id: 'legacy-fresh',
        out: JSON.stringify({ type: 'relay_test', event_id: 'legacy-fresh' }) };
      const expired = { ts: now - QUEUE_RETENTION_MS, event_id: 'legacy-expired',
        out: JSON.stringify({ type: 'relay_test', event_id: 'legacy-expired' }) };
      await this.ctx.storage.put({ paired: '1', names: JSON.stringify({ sender: 'old sender' }),
        tokens: JSON.stringify([token]), queue: JSON.stringify([expired, fresh]),
        smsCommands: [{ id: 'old-sms', created_at: now - SMS_RETENTION_MS, body: 'expired fixture' }] });
      assert(await this.securityMode() === 'legacy');
      const status = await super.fetch(new Request('http://local/status/123456'));
      assert(status.status === 200 && (await status.json()).sender_name === 'old sender');
      const before = await super.fetch(new Request('http://local/fetch/123456', {
        method: 'POST', headers: { 'X-NextNotif-Token': token }, body: '{}',
      }));
      assert(before.status === 200);
      assert((await before.json()).events.map(event => event.event_id).join(',') === 'legacy-fresh');
      assert(await this.ctx.storage.getAlarm() != null);
      const sent = await super.fetch(new Request('http://local/send/123456', {
        method: 'POST', body: JSON.stringify({ type: 'relay_test', data: { marker: 'legacy-new' } }),
      }));
      assert(sent.status === 200);
      const drained = await super.fetch(new Request('http://local/drain/123456', {
        method: 'POST', headers: { 'X-NextNotif-Token': token }, body: '{}',
      }));
      assert(drained.status === 200 && (await drained.json()).events.length === 2);
      assert(await this.ctx.storage.get('queue') == null);
      const secureCreate = await super.fetch(new Request('http://local/pair-secure-create/123456', {
        method: 'POST', body: JSON.stringify({ role: 'sender' }),
      }));
      assert(secureCreate.status === 409);
      assert(await this.securityMode() === 'legacy');
      return Response.json({ ok: true, checks: 'legacy KV status, expired queue pruning, send/drain compatibility, secure namespace isolation' });
    }
    if (path === '/run-legacy-retirement') {
      await this.ctx.storage.put({ paired: '1', tokens: JSON.stringify(['old-local-token']) });
      assert(await this.securityMode() === 'quarantined');
      const status = await super.fetch(new Request('http://local/status/123456'));
      assert(status.status === 401);
      const send = await super.fetch(new Request('http://local/send/123456', {
        method: 'POST', body: JSON.stringify({ type: 'relay_test', data: { marker: 'denied' } }),
      }));
      assert(send.status === 401);
      const fetch = await super.fetch(new Request('http://local/fetch/123456', {
        method: 'POST', headers: { 'X-NextNotif-Token': 'old-local-token' }, body: '{}',
      }));
      assert(fetch.status === 401);
      const socket = await super.fetch(new Request('http://local/ws/receiver/123456', {
        headers: { Upgrade: 'websocket', 'X-NextNotif-Token': 'old-local-token' },
      }));
      assert(socket.status === 401);
      assert(await this.tryClaim() === false);
      return Response.json({ ok: true });
    }
    if (path === '/assert-queued-alarm') {
      assert((await this.getQueue()).length === 1);
      assert((await this.ctx.storage.getAlarm()) != null);
      return Response.json({ ok: true });
    }
    if (path === '/assert-deleted-queue') {
      assert(await this.ctx.storage.get('queue') == null);
      assert(await this.ctx.storage.getAlarm() == null);
      return Response.json({ ok: true });
    }
    if (path !== '/run') return super.fetch(request);
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
    const senderOperations = ['send', 'battery-status', 'sms-fetch', 'sms-result', 'call-fetch', 'call-result'];
    const receiverOperations = ['drain', 'fetch', 'ack', 'sms-submit', 'sms-status', 'call-submit', 'call-status', 'call-cancel'];
    const sharedOperations = ['status', 'ice'];
    for (const operation of [...senderOperations, ...receiverOperations, ...sharedOperations, 'fcm-register']) {
      assert((await call(operation, null, registration)).status === 401);
      assert((await call(operation, { ...receiver, device_token: 'wrong' }, registration)).status === 401);
    }
    for (const operation of senderOperations) assert((await call(operation, receiver, registration)).status === 401);
    for (const operation of receiverOperations) assert((await call(operation, sender, registration)).status === 401);
    assert((await call('fcm-register', sender, registration)).status === 401);
    assert((await call('fcm-register', receiver, registration, { 'X-NextNotif-Role': 'sender' })).status === 401);
    assert(JSON.stringify([...await this.ctx.storage.list()]) === before);
    assert((await call('status', sender)).status === 200);
    assert((await call('status', receiver)).status === 200);
    assert((await call('drain', receiver)).status === 410);
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
    assert(await this.ctx.storage.getAlarm() === null);
    for (let i = 0; i < 51; i += 1) {
      const response = await call('send', sender, { type: 'relay_test', data: { marker: i } });
      assert(response.status === 200);
      assert((await response.json()).overflow_dropped === (i === 50 ? 1 : 0));
    }
    const overflowSnapshot = await (await call('fetch', receiver)).json();
    assert(overflowSnapshot.events.length === 50);
    assert(overflowSnapshot.overflow_dropped_total === 1);
    assert((await call('ack', receiver, { event_ids: overflowSnapshot.events.map(event => event.event_id) })).status === 200);
    assert((await (await call('fetch', receiver)).json()).overflow_dropped_total === 1);
    const oldAt = Date.now() - Math.max(SMS_RETENTION_MS, CALL_RETENTION_MS);
    await this.ctx.storage.put('smsCommands', [{ id: 'old-sms', created_at: oldAt, body: 'private old SMS' }]);
    await this.ctx.storage.put('callCommands', [{ id: 'old-call', created_at: oldAt, to: 'private old caller' }]);
    assert((await call('sms-status', receiver)).status === 200);
    assert((await call('call-status', receiver)).status === 200);
    assert((await this.ctx.storage.get('smsCommands')).length === 0);
    assert((await this.ctx.storage.get('callCommands')).length === 0);
    const retainedAt = Date.now() - SMS_RETENTION_MS + 60_000;
    await this.ctx.storage.put('smsCommands', [{ id: 'retained-sms', created_at: retainedAt, body: 'private idle SMS' }]);
    await this.ctx.storage.put('callCommands', [{ id: 'retained-call', created_at: retainedAt, to: 'private idle caller' }]);
    await this.rescheduleMaintenance();
    assert(await this.ctx.storage.getAlarm() === retainedAt + SMS_RETENTION_MS);
    // Clearing the delivery queue must preserve the command cleanup alarm.
    await this.putQueue([]);
    assert(await this.ctx.storage.getAlarm() === retainedAt + SMS_RETENTION_MS);
    await this.ctx.storage.put('smsCommands', [{ id: 'retained-sms', created_at: oldAt, body: 'private idle SMS' }]);
    await this.ctx.storage.put('callCommands', [{ id: 'retained-call', created_at: oldAt, to: 'private idle caller' }]);
    await this.alarm();
    assert((await this.ctx.storage.get('smsCommands')).length === 0);
    assert((await this.ctx.storage.get('callCommands')).length === 0);
    assert(await this.ctx.storage.getAlarm() === null);
    const queuedAt = Date.now();
    await this.ctx.storage.put('queue', JSON.stringify([{
      ts: queuedAt, event_id: 'retained', out: JSON.stringify({ type: 'relay_test', event_id: 'retained' }),
    }]));
    assert((await this.getQueue()).length === 1);
    assert((await this.ctx.storage.getAlarm()) === queuedAt + QUEUE_RETENTION_MS);
    await this.ctx.storage.put('queue', JSON.stringify([{
      ts: queuedAt - QUEUE_RETENTION_MS, event_id: 'expired', out: 'private expired body',
    }]));
    await this.alarm();
    assert(await this.ctx.storage.get('queue') == null);
    assert(await this.ctx.storage.getAlarm() === null);
    // A secure socket without device credentials must be denied before any
    // slot replacement or metadata mutation.
    const ws = await super.fetch(new Request('http://local/ws/receiver/123456', { headers: { Upgrade: 'websocket' } }));
    assert(ws.status === 401);
    await this.ctx.storage.put('securityMode', 'legacy');
    assert((await call('fetch', receiver)).status === 401);
    assert(await this.tryClaim() === false);
    await this.ctx.storage.put('securityMode', 'invite_v1');
    await this.ctx.storage.delete('secureRecord');
    assert((await call('fcm-register', receiver, registration)).status === 401);
    return Response.json({ ok: true, checks: 'all HTTP roles, no metadata/token mint on rejection, queue fetch/ack, command purge on status, namespace quarantine, unauthenticated secure WS denied' });
  }
}
export default {
  async fetch(request, env) {
    if (new URL(request.url).pathname === '/legacy-retirement-test') {
      const created = await relay.fetch(new Request('http://local/pair/create', { method: 'POST' }), env);
      assert(created.status === 410);
      const object = env.SECURITY_HTTP.get(env.SECURITY_HTTP.idFromName('legacy-retirement-fixture'));
      return object.fetch('http://local/run-legacy-retirement');
    }
    if (new URL(request.url).pathname === '/maintenance-route-test') {
      const objectId = env.SECURITY_HTTP.idFromName('legacy-migration-fixture');
      const object = env.SECURITY_HTTP.get(objectId);
      assert((await object.fetch('http://local/seed-maintenance')).status === 200);
      const binding = { PAIRING: env.SECURITY_HTTP, NEXTNOTIF_MAINTENANCE_TOKEN: 'local-fixture-maintenance-secret-123456' };
      const maintenance = (token, object_id) => relay.fetch(new Request('http://local/__maintenance/touch', {
        method: 'POST', headers: { 'X-NextNotif-Maintenance-Token': token },
        body: JSON.stringify({ object_id }),
      }), binding);
      assert((await maintenance('', objectId.toString())).status === 401);
      assert((await maintenance('wrong', objectId.toString())).status === 401);
      assert((await maintenance(binding.NEXTNOTIF_MAINTENANCE_TOKEN, '0'.repeat(64))).status === 400);
      const result = await maintenance(binding.NEXTNOTIF_MAINTENANCE_TOKEN, objectId.toString());
      assert(result.status === 200 && (await result.json()).ok === true);
      assert((await object.fetch('http://local/assert-maintained')).status === 200);
      return Response.json({ ok: true, checks: 'maintenance token denial, ID validation, expired legacy data purge' });
    }
    if (new URL(request.url).pathname === '/legacy-migration-test') {
      const object = env.SECURITY_HTTP.get(env.SECURITY_HTTP.idFromName('legacy-migration-fixture'));
      return object.fetch('http://local/run-legacy-migration');
    }
    if (new URL(request.url).pathname.startsWith('/ws/') ||
        ['/send', '/fetch', '/ack'].includes(new URL(request.url).pathname) ||
        new URL(request.url).pathname === '/pair/secure-delete') {
      return relay.fetch(request, { PAIRING: env.SECURITY_HTTP });
    }
    if (new URL(request.url).pathname === '/secure-ws-fixture') {
      const createdResponse = await relay.fetch(new Request('http://local/pair/secure-create', {
        method: 'POST', body: JSON.stringify({ role: 'sender' }),
      }), { PAIRING: env.SECURITY_HTTP });
      assert(createdResponse.status === 200);
      const created = await createdResponse.json();
      const joinedResponse = await relay.fetch(new Request('http://local/pair/secure-join', {
        method: 'POST', headers: { 'X-NextNotif-Code': created.code },
        body: JSON.stringify({ role: 'receiver', secret: created.invite.secret }),
      }), { PAIRING: env.SECURITY_HTTP });
      assert(joinedResponse.status === 200);
      return Response.json({ code: created.code, sender: {
        device_id: created.device_id, device_token: created.device_token,
      }, receiver: await joinedResponse.json() }, { headers: { 'Cache-Control': 'no-store' } });
    }
    if (new URL(request.url).pathname === '/routes-test') {
      const binding = { PAIRING: env.SECURITY_HTTP };
      const createdResponse = await relay.fetch(new Request('http://local/pair/secure-create', {
        method: 'POST', body: JSON.stringify({ role: 'sender' }),
      }), binding);
      assert(createdResponse.status === 200);
      assert(createdResponse.headers.get('Cache-Control') === 'no-store');
      const created = await createdResponse.json();
      assert(/^\d{6}$/.test(created.code));
      assert(created.device_id && created.device_token && created.invite.secret);
      const join = () => relay.fetch(new Request('http://local/pair/secure-join', {
        method: 'POST', headers: { 'X-NextNotif-Code': created.code },
        body: JSON.stringify({ role: 'receiver', secret: created.invite.secret }),
      }), binding);
      const joinedResponse = await join();
      assert(joinedResponse.status === 200);
      assert(joinedResponse.headers.get('Cache-Control') === 'no-store');
      const joined = await joinedResponse.json();
      assert(joined.device_id && joined.device_token);
      assert((await join()).status === 401);
      const statusUrl = `http://local/pair/${created.code}/status`;
      assert((await relay.fetch(new Request(statusUrl), binding)).status === 401);
      assert((await relay.fetch(new Request(statusUrl, { headers: {
        'X-NextNotif-Device-Id': joined.device_id, 'X-NextNotif-Token': joined.device_token,
      } }), binding)).status === 200);
      const pairObject = env.SECURITY_HTTP.get(env.SECURITY_HTTP.idFromName(created.code));
      assert((await relay.fetch(new Request('http://local/send', { method: 'POST', headers: {
        'X-NextNotif-Code': created.code,
        'X-NextNotif-Device-Id': created.device_id,
        'X-NextNotif-Token': created.device_token,
      }, body: JSON.stringify({ type: 'relay_test', data: { marker: 'queued-before-delete' } }) }), binding)).status === 200);
      assert((await pairObject.fetch('http://local/assert-queued-alarm')).status === 200);
      const deleteUrl = 'http://local/pair/secure-delete';
      const deletion = device => relay.fetch(new Request(deleteUrl, { method: 'POST', headers: {
        'X-NextNotif-Code': created.code,
        'X-NextNotif-Device-Id': device.device_id,
        'X-NextNotif-Token': device.device_token,
      } }), binding);
      assert((await deletion(joined)).status === 401);
      assert((await deletion(created)).status === 200);
      assert((await pairObject.fetch('http://local/assert-deleted-queue')).status === 200);
      assert((await deletion(created)).status === 200);
      assert((await deletion(joined)).status === 401);
      const formerDevices = [created, joined];
      const operations = ['send', 'battery-status', 'fcm-register', 'drain', 'fetch', 'ack', 'ice',
        'sms-submit', 'sms-fetch', 'sms-result', 'sms-status',
        'call-submit', 'call-fetch', 'call-result', 'call-status', 'call-cancel'];
      for (const device of formerDevices) {
        const deviceHeaders = {
          'X-NextNotif-Code': created.code,
          'X-NextNotif-Device-Id': device.device_id,
          'X-NextNotif-Token': device.device_token,
        };
        for (const operation of operations) {
          const response = await relay.fetch(new Request(`http://local/${operation}`, {
            method: 'POST', headers: deviceHeaders, body: '{}',
          }), binding);
          assert(response.status === 401);
        }
        assert((await relay.fetch(new Request(statusUrl, { headers: deviceHeaders }), binding)).status === 401);
      }
      assert((await pairObject.fetch('http://local/assert-deleted-queue')).status === 200);
      return Response.json({ ok: true, checks: 'secure route creation, one-use join, owner deletion, all former-device HTTP routes denied' });
    }
    if (new URL(request.url).pathname === '/rate-test') {
      const binding = { PAIRING: env.SECURITY_HTTP };
      const suffix = crypto.randomUUID().replaceAll('-', '').slice(0, 12);
      const ip = `2001:db8:${suffix.slice(0, 4)}:${suffix.slice(4, 8)}:${suffix.slice(8)}`;
      const otherIp = `2001:db8:${suffix.slice(0, 4)}:${suffix.slice(4, 8)}:ffff`;
      const create = source => relay.fetch(new Request('http://local/pair/secure-create', {
        method: 'POST', headers: { 'CF-Connecting-IP': source }, body: JSON.stringify({ role: 'sender' }),
      }), binding);
      for (let i = 0; i < CREATE_ATTEMPTS_PER_MINUTE; i += 1) assert((await create(ip)).status === 200);
      const blockedCreate = await create(ip);
      assert(blockedCreate.status === 429 && Number(blockedCreate.headers.get('Retry-After')) >= 1);
      assert((await create(otherIp)).status === 200);
      const join = i => relay.fetch(new Request('http://local/pair/secure-join', {
        method: 'POST', headers: { 'CF-Connecting-IP': ip, 'X-NextNotif-Code': String(i).padStart(6, '0') },
        body: JSON.stringify({ role: 'receiver', secret: 'wrong' }),
      }), binding);
      const results = await Promise.all(Array.from({ length: JOIN_CLIENT_ATTEMPTS_PER_MINUTE + 1 }, (_, i) => join(i)));
      assert(results.filter(response => response.status === 401).length === JOIN_CLIENT_ATTEMPTS_PER_MINUTE);
      assert(results.filter(response => response.status === 429).length === 1);
      assert((await relay.fetch(new Request('http://local/__secure-create-budget'), binding)).status === 404);
      return Response.json({ ok: true, checks: 'per-client create and join budgets, retry interval, separate clients, no public budget route' });
    }
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

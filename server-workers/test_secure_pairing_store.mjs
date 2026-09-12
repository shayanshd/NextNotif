import test from 'node:test';
import assert from 'node:assert/strict';
import { timingSafeEqual } from 'node:crypto';
import { SecurePairingStore } from './src/secure-pairing-store.mjs';
import { authorizeSecure, AuthorizationError } from './src/secure-pairing.mjs';
crypto.subtle.timingSafeEqual = (a, b) => timingSafeEqual(a, b);

// Serialized, rollback-capable adapter; runtime tests must separately exercise
// the real DO transaction and persistence implementation.
class Storage {
  data = new Map();
  tail = Promise.resolve();
  failWrite = false;
  failSync = false;
  async get(key) { return this.data.get(key); }
  async sync() { if (this.failSync) throw new Error('sync failed'); }
  async transaction(action) {
    const previous = this.tail;
    let unlock;
    this.tail = new Promise(resolve => { unlock = resolve; });
    await previous;
    const next = new Map(this.data);
    try {
      const result = await action({ get: async key => next.get(key), put: async (key, value) => {
        if (this.failWrite) throw new Error('write failed');
        if (typeof key === 'object') for (const [k, v] of Object.entries(key)) next.set(k, v);
        else next.set(key, value);
      } });
      this.data = next;
      return result;
    } finally { unlock(); }
  }
}

test('competing invite consumers receive exactly one committed grant', async () => {
  const storage = new Storage();
  const store = new SecurePairingStore(storage);
  const created = await store.create('sender', 1000);
  const results = await Promise.allSettled(Array.from({ length: 12 }, () => store.join(created.invite.secret, 'receiver', 1001)));
  const winners = results.filter(result => result.status === 'fulfilled');
  assert.equal(winners.length, 1);
  const grant = winners[0].value.device;
  const restarted = new SecurePairingStore(storage);
  const record = await restarted.read();
  assert.equal(Object.keys(record.credentials).length, 2);
  assert.equal(await authorizeSecure(record, grant.device_id, grant.device_token, 'receiver'), grant.device_id);
  await assert.rejects(restarted.join(created.invite.secret, 'receiver', 1002), AuthorizationError);
  assert.ok(!JSON.stringify([...storage.data]).includes(grant.device_token));
});

test('creation atomically reserves the namespace without claiming legacy ownership', async () => {
  for (const key of ['paired', 'secureRecord', 'securityMode']) {
    const storage = new Storage();
    storage.data.set(key, 'legacy-or-reserved');
    const before = [...storage.data];
    await assert.rejects(new SecurePairingStore(storage).create('sender', 1000), AuthorizationError);
    assert.deepEqual([...storage.data], before);
  }
  const storage = new Storage();
  const results = await Promise.allSettled([new SecurePairingStore(storage).create('sender', 1000), new SecurePairingStore(storage).create('receiver', 1000)]);
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
});

test('failed persistence releases no grant and rollback permits retry', async () => {
  const storage = new Storage();
  const store = new SecurePairingStore(storage);
  storage.failWrite = true;
  await assert.rejects(store.create('sender', 1000), /write failed/);
  assert.equal(storage.data.size, 0);
  storage.failWrite = false;
  const created = await store.create('sender', 1000);
  const before = [...storage.data];
  storage.failWrite = true;
  await assert.rejects(store.join(created.invite.secret, 'receiver', 1001), /write failed/);
  assert.deepEqual([...storage.data], before);
  storage.failWrite = false;
  await store.join(created.invite.secret, 'receiver', 1001);
});

test('sync failure releases no grant and corrupt stored records fail closed', async () => {
  const storage = new Storage();
  storage.failSync = true;
  await assert.rejects(new SecurePairingStore(storage).create('sender', 1000), /sync failed/);
  storage.failSync = false;
  for (const raw of [undefined, '{}', '{', JSON.stringify({ version: '1', record: { security_mode: 'unknown' } })]) {
    storage.data.set('secureRecord', raw);
    await assert.rejects(new SecurePairingStore(storage).read(), AuthorizationError);
  }
});

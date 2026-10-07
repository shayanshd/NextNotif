import test from 'node:test';
import assert from 'node:assert/strict';
import { consumePairingBudget, PairingRateLimitError } from './src/pairing-rate-limit.mjs';

class Storage {
  data = new Map();
  tail = Promise.resolve();
  async transaction(action) {
    const previous = this.tail;
    let unlock;
    this.tail = new Promise(resolve => { unlock = resolve; });
    await previous;
    try {
      return await action({ get: async key => this.data.get(key), put: async (key, value) => this.data.set(key, value) });
    } finally { unlock(); }
  }
}

test('pairing budget is exact under concurrent attempts and returns a retry interval', async () => {
  const storage = new Storage();
  const results = await Promise.allSettled(Array.from({ length: 25 }, () =>
    consumePairingBudget(storage, 'join', 20, 100_000)));
  assert.equal(results.filter(result => result.status === 'fulfilled').length, 20);
  const blocked = results.filter(result => result.status === 'rejected');
  assert.equal(blocked.length, 5);
  assert.ok(blocked.every(result => result.reason instanceof PairingRateLimitError &&
    result.reason.retryAfterSeconds === 60));
  assert.equal(storage.data.get('join').count, 20);
  await assert.rejects(consumePairingBudget(storage, 'join', 20, 159_000), error =>
    error instanceof PairingRateLimitError && error.retryAfterSeconds === 1);
  await consumePairingBudget(storage, 'join', 20, 160_000);
  assert.equal(storage.data.get('join').count, 1);
});

test('bad persisted counters reset without granting an unlimited window', async () => {
  const storage = new Storage();
  storage.data.set('create', { count: 'bad', resetAt: 160_000 });
  await consumePairingBudget(storage, 'create', 1, 100_000);
  await assert.rejects(consumePairingBudget(storage, 'create', 1, 100_001), PairingRateLimitError);
});

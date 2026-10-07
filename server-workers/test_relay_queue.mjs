import test from 'node:test';
import assert from 'node:assert/strict';
import { appendQueued, MAX_QUEUED, nextQueueExpiry, pruneQueue, QUEUE_RETENTION_MS } from './src/relay-queue.mjs';

const now = 1_800_000_000_000;
const event = (id, ts) => ({ event_id: String(id), ts, out: JSON.stringify({ event_id: String(id) }) });

test('queue keeps the 24-hour recovery window and expires events at seven days', () => {
  const oldest = event('oldest', now - QUEUE_RETENTION_MS);
  const inside = event('inside', now - QUEUE_RETENTION_MS + 1);
  const recent = event('recent', now - 24 * 60 * 60 * 1000);
  const result = pruneQueue([oldest, inside, recent], now);
  assert.deepEqual(result.queue.map(item => item.event_id), ['inside', 'recent']);
  assert.equal(result.expired, 1);
  assert.equal(nextQueueExpiry(result.queue), inside.ts + QUEUE_RETENTION_MS);
});

test('queue rejects malformed or unbounded legacy entries instead of retaining their content', () => {
  assert.deepEqual(pruneQueue('not a queue', now), { queue: [], expired: 0, invalid: true });
  const result = pruneQueue([event('good', now), { event_id: 'missing timestamp', out: 'private' },
    event('future', now + 1), { ts: now, event_id: 'missing output' }], now);
  assert.deepEqual(result.queue.map(item => item.event_id), ['good']);
  assert.equal(result.expired, 3);
});

test('overflow is counted and the newest fifty events remain in order', () => {
  let queue = [];
  let dropped = 0;
  for (let i = 0; i < MAX_QUEUED + 2; i += 1) {
    const result = appendQueued(queue, event(i, now));
    queue = result.queue;
    dropped += result.overflow;
  }
  assert.equal(dropped, 2);
  assert.equal(queue.length, MAX_QUEUED);
  assert.equal(queue[0].event_id, '2');
  assert.equal(queue.at(-1).event_id, String(MAX_QUEUED + 1));
});

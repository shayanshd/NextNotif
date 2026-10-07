export const MAX_QUEUED = 50;
// Provisional privacy bound; long enough for the 24-hour offline recovery gate.
export const QUEUE_RETENTION_MS = 7 * 24 * 60 * 60 * 1000;

export function pruneQueue(queue, now = Date.now()) {
  if (!Array.isArray(queue)) return { queue: [], expired: 0, invalid: true };
  const retained = queue.filter(item =>
    item && Number.isSafeInteger(item.ts) && item.ts > 0 &&
    item.ts <= now && item.ts + QUEUE_RETENTION_MS > now &&
    typeof item.event_id === 'string' && typeof item.out === 'string');
  return { queue: retained, expired: queue.length - retained.length, invalid: false };
}

export function appendQueued(queue, item) {
  const overflow = Math.max(0, queue.length + 1 - MAX_QUEUED);
  return { queue: [...queue.slice(overflow), item], overflow };
}

export function nextQueueExpiry(queue) {
  return queue.length ? Math.min(...queue.map(item => item.ts + QUEUE_RETENTION_MS)) : null;
}

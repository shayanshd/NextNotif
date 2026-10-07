export const CREATE_ATTEMPTS_PER_MINUTE = 12;
export const JOIN_CLIENT_ATTEMPTS_PER_MINUTE = 60;
export const JOIN_ATTEMPTS_PER_MINUTE = 20;
export const PAIRING_LIMIT_WINDOW_MS = 60_000;

export class PairingRateLimitError extends Error {
  constructor(retryAfterSeconds) {
    super('pairing request rate exceeded');
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/** Exact per-Durable-Object fixed window; the stored value contains only a count and expiry. */
export async function consumePairingBudget(storage, key, limit, nowMs = Date.now()) {
  if (!Number.isSafeInteger(nowMs) || nowMs < 0) throw new Error('invalid budget time');
  const result = await storage.transaction(async txn => {
    const previous = await txn.get(key);
    const active = previous && Number.isSafeInteger(previous.resetAt) &&
      previous.resetAt > nowMs && previous.resetAt <= nowMs + PAIRING_LIMIT_WINDOW_MS &&
      Number.isSafeInteger(previous.count) && previous.count >= 0;
    const resetAt = active ? previous.resetAt : nowMs + PAIRING_LIMIT_WINDOW_MS;
    const count = active ? previous.count : 0;
    if (count >= limit) return { allowed: false, resetAt };
    await txn.put(key, { count: count + 1, resetAt });
    return { allowed: true, resetAt };
  });
  if (!result.allowed) throw new PairingRateLimitError(Math.max(1, Math.ceil((result.resetAt - nowMs) / 1000)));
  return result.resetAt;
}

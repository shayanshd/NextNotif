// SMS commands are durable and idempotent. An expired command can never be re-created.
export const SMS_TTL_MS = 60 * 60 * 1000;
export const SMS_RETENTION_MS = 48 * 60 * 60 * 1000;
export const SMS_STATES = new Set(['sending', 'sent', 'failed', 'unknown']);
export function validSms(command, now = Date.now()) {
  return command && typeof command.id === 'string' && /^[a-f0-9-]{36}$/.test(command.id) &&
    typeof command.to === 'string' && /^\+?[0-9]{3,20}$/.test(command.to) &&
    typeof command.body === 'string' && command.body.trim().length > 0 && command.body.length <= 1600 &&
    (command.subscription_id == null || (Number.isInteger(command.subscription_id) && command.subscription_id >= 0 && command.subscription_id <= 2147483647)) &&
    Number.isSafeInteger(command.created_at) && command.created_at <= now + 300000 &&
    command.created_at > now - SMS_TTL_MS;
}
export function submitSms(records, command, now = Date.now()) {
  const old = records.find(x => x.id === command?.id);
  if (old) {
    if (old.to !== command.to || old.body !== command.body || old.created_at !== command.created_at || (old.subscription_id ?? null) !== (command.subscription_id ?? null))
      return { status: 409, error: 'Request ID already used' };
    return { status: 200, records, command: old };
  }
  if (!validSms(command, now)) return { status: 400, error: 'Invalid or expired SMS request' };
  const retained = records.filter(x => x.created_at > now - SMS_RETENTION_MS);
  if (retained.length >= 100) return { status: 429, error: 'SMS queue is full' };
  const next = { id: command.id, to: command.to, body: command.body, created_at: command.created_at,
    subscription_id: command.subscription_id ?? null, status: 'queued', detail: '', expires_at: command.created_at + SMS_TTL_MS };
  return { status: 200, records: [...retained, next], command: next };
}
export function updateSms(records, result) {
  if (!result || !SMS_STATES.has(result.status)) return false;
  const record = records.find(x => x.id === result.id);
  if (!record) return false;
  // Late retries cannot regress a carrier result to "sending".
  if (['sent', 'failed'].includes(record.status)) return true;
  if (['unknown', 'expired'].includes(record.status) && !['sent', 'failed'].includes(result.status)) return true;
  record.status = result.status;
  record.detail = typeof result.detail === 'string' ? result.detail.slice(0, 240) : '';
  return true;
}
export function expireSms(records, now = Date.now()) {
  return records.map(x => x.status === 'queued' && x.expires_at <= now
    ? { ...x, status: 'expired', detail: 'Sender did not send before the request expired' } : x);
}

// Only sender-authenticated fetches may publish this small, non-identifying inventory.
export function simOptions(value, now = Date.now()) {
  if (!value || !['ready', 'permission_required', 'unavailable'].includes(value.state) ||
      !Array.isArray(value.sims) || value.sims.length > 8) return null;
  const ids = new Set();
  const sims = [];
  for (const sim of value.sims) {
    if (!sim || !Number.isInteger(sim.id) || sim.id < 0 || sim.id > 2147483647 || ids.has(sim.id) ||
        !Number.isInteger(sim.slot) || sim.slot < 0 || sim.slot > 7 ||
        typeof sim.name !== 'string' || sim.name.length > 80 || typeof sim.carrier !== 'string' || sim.carrier.length > 80) return null;
    ids.add(sim.id);
    sims.push({id: sim.id, slot: sim.slot, name: sim.name, carrier: sim.carrier});
  }
  if (value.default_id != null && !ids.has(value.default_id)) return null;
  if (value.state !== 'ready' && sims.length) return null;
  return {state: value.state, sims, default_id: value.default_id ?? null, updated_at: now};
}

export const CALL_TTL_MS = 120000;
const FINAL = new Set(['failed', 'ended', 'expired']);
export function validCall(c, now = Date.now()) {
  return c && typeof c.id === 'string' && /^[a-f0-9-]{36}$/.test(c.id) &&
    typeof c.to === 'string' && /^\+?[0-9]{3,20}$/.test(c.to) &&
    (c.subscription_id == null || Number.isInteger(c.subscription_id) && c.subscription_id >= 0 && c.subscription_id <= 2147483647) &&
    Number.isSafeInteger(c.created_at) && c.created_at <= now + 300000 && c.created_at > now - CALL_TTL_MS;
}
export function submitCall(records, command, now = Date.now()) {
  const old = records.find(x => x.id === command?.id);
  if (old) return old.to === command.to && (old.subscription_id ?? null) === (command.subscription_id ?? null) &&
    old.created_at === command.created_at ? {status: 200, records, command: old} : {status: 409};
  if (!validCall(command, now)) return {status: 400};
  if (records.some(x => !FINAL.has(x.status))) return {status: 409};
  const retained = records.filter(x => x.created_at > now - 172800000);
  const next = {id: command.id, to: command.to, subscription_id: command.subscription_id ?? null,
    created_at: command.created_at, expires_at: command.created_at + CALL_TTL_MS, status: 'queued', detail: ''};
  return {status: 200, records: [...retained, next], command: next};
}
export function expireCalls(records, now = Date.now()) {
  return records.map(x => x.status === 'queued' && x.expires_at <= now
    ? {...x, status: 'expired', detail: 'Sender did not place the call before it expired'} : x);
}
export function updateCall(records, result) {
  if (!result || !['dialing', 'connected', 'failed', 'ended'].includes(result.status)) return false;
  const record = records.find(x => x.id === result.id);
  if (!record) return false;
  if (FINAL.has(record.status)) return true;
  record.status = result.status;
  record.detail = typeof result.detail === 'string' ? result.detail.slice(0, 240) : '';
  return true;
}

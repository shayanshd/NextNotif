// Pure security transitions. Not wired into relay routes yet.
// The storage caller must atomically persist a transition before releasing its
// grant; crypto awaits alone do not serialize competing invite consumption.
export const SECURE_MODE = 'invite_v1';
export const DELETED_MODE = 'deleted_v1';
export const INVITE_SECONDS = 900;
const roles = new Set(['sender', 'receiver']);
const devicePattern = /^[A-Za-z0-9_-]{22}$/;
const hashPattern = /^[0-9a-f]{64}$/;

export class AuthorizationError extends Error {
  constructor() { super('pairing authorization failed'); }
}
function requireRole(role) { if (!roles.has(role)) throw new AuthorizationError(); }
function requireTime(now) {
  if (!Number.isSafeInteger(now) || now < 0) throw new AuthorizationError();
}
function randomSecret(bytes) {
  const data = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...data)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
function privateGrant(fields, key, secret) {
  return Object.defineProperty(fields, key, { value: secret, enumerable: false });
}
async function digest(secret) {
  if (typeof secret !== 'string' || secret.length < 1 || secret.length > 128 || /[^\x00-\x7f]/.test(secret)) {
    throw new AuthorizationError();
  }
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(secret));
  return [...new Uint8Array(hash)].map(value => value.toString(16).padStart(2, '0')).join('');
}
function equalHashes(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string' || !hashPattern.test(a) || !hashPattern.test(b)) {
    throw new AuthorizationError();
  }
  const bytes = hash => Uint8Array.from(hash.match(/../g), value => parseInt(value, 16));
  // Cloudflare's native extension; deliberately no home-grown timing fallback.
  return crypto.subtle.timingSafeEqual(bytes(a), bytes(b));
}
function object(value) { return value !== null && typeof value === 'object' && !Array.isArray(value); }

export function restoreSecure(record) {
  try {
    if (!object(record) || record.security_mode !== SECURE_MODE || !object(record.credentials)) throw new Error();
    requireTime(record.created_at);
    const entries = Object.entries(record.credentials);
    if (entries.length < 1 || entries.length > 4 || !Object.hasOwn(record.credentials, record.owner_device_id)) throw new Error();
    const active = new Set();
    const credentials = {};
    for (const [id, item] of entries) {
      if (!devicePattern.test(id) || !object(item) || typeof item.token_hash !== 'string' ||
          !hashPattern.test(item.token_hash) || typeof item.revoked !== 'boolean') throw new Error();
      requireRole(item.role);
      requireTime(item.created_at);
      if (!item.revoked) {
        if (active.has(item.role)) throw new Error();
        active.add(item.role);
      }
      credentials[id] = { role: item.role, token_hash: item.token_hash, created_at: item.created_at, revoked: item.revoked };
    }
    const invite = record.invite;
    if (!object(invite) || typeof invite.secret_hash !== 'string' || !hashPattern.test(invite.secret_hash) ||
        typeof invite.used !== 'boolean') throw new Error();
    requireRole(invite.role);
    requireTime(invite.expires_at);
    if (invite.role === credentials[record.owner_device_id].role ||
        invite.expires_at !== record.created_at + INVITE_SECONDS) throw new Error();
    return { security_mode: SECURE_MODE, created_at: record.created_at, owner_device_id: record.owner_device_id,
      credentials, invite: { role: invite.role, secret_hash: invite.secret_hash, expires_at: invite.expires_at, used: invite.used } };
  } catch { throw new AuthorizationError(); }
}

export async function createSecure(role, now) {
  requireRole(role);
  requireTime(now);
  requireTime(now + INVITE_SECONDS);
  const device = privateGrant({ device_id: randomSecret(16) }, 'device_token', randomSecret(32));
  const invite = privateGrant({ role: role === 'sender' ? 'receiver' : 'sender', expires_at: now + INVITE_SECONDS }, 'secret', randomSecret(32));
  const record = { security_mode: SECURE_MODE, created_at: now, owner_device_id: device.device_id,
    credentials: { [device.device_id]: { role, token_hash: await digest(device.device_token), created_at: now, revoked: false } },
    invite: { role: invite.role, secret_hash: await digest(invite.secret), expires_at: invite.expires_at, used: false } };
  return { record, device, invite };
}

export async function authorizeSecure(input, deviceId, token, role) {
  const record = restoreSecure(input);
  requireRole(role);
  if (typeof deviceId !== 'string' || !Object.hasOwn(record.credentials, deviceId)) throw new AuthorizationError();
  const item = record.credentials[deviceId];
  if (item.revoked || item.role !== role || !equalHashes(item.token_hash, await digest(token))) throw new AuthorizationError();
  return deviceId;
}

export async function joinSecure(input, secret, role, now) {
  const record = restoreSecure(input);
  requireRole(role);
  requireTime(now);
  const invite = record.invite;
  if (invite.used || now >= invite.expires_at || invite.role !== role ||
      !equalHashes(invite.secret_hash, await digest(secret))) throw new AuthorizationError();
  if (Object.values(record.credentials).some(item => !item.revoked && item.role === role) ||
      Object.keys(record.credentials).length >= 4) throw new AuthorizationError();
  const device = privateGrant({ device_id: randomSecret(16) }, 'device_token', randomSecret(32));
  if (Object.hasOwn(record.credentials, device.device_id)) throw new AuthorizationError();
  record.credentials[device.device_id] = { role, token_hash: await digest(device.device_token), created_at: now, revoked: false };
  record.invite.used = true;
  return { record, device };
}

export async function authorizeOwner(input, deviceId, token) {
  const record = restoreSecure(input);
  if (record.owner_device_id !== deviceId) throw new AuthorizationError();
  return authorizeSecure(record, deviceId, token, record.credentials[deviceId].role);
}
export async function revokeSecure(input, ownerId, token, targetId) {
  const record = restoreSecure(input);
  await authorizeOwner(record, ownerId, token);
  if (typeof targetId !== 'string' || !Object.hasOwn(record.credentials, targetId)) throw new AuthorizationError();
  record.credentials[targetId].revoked = true;
  return { record, role: record.credentials[targetId].role };
}
export async function deletionIdentity(input, ownerId, token) {
  const record = restoreSecure(input);
  await authorizeOwner(record, ownerId, token);
  return { security_mode: DELETED_MODE, owner_device_id: ownerId,
    owner_token_hash: record.credentials[ownerId].token_hash, cleanup_pending: true };
}
export async function authorizeDeletion(record, deviceId, token) {
  if (!object(record) || record.security_mode !== DELETED_MODE || typeof deviceId !== 'string' ||
      !devicePattern.test(deviceId) || record.owner_device_id !== deviceId ||
      typeof record.cleanup_pending !== 'boolean' || !equalHashes(record.owner_token_hash, await digest(token))) {
    throw new AuthorizationError();
  }
}

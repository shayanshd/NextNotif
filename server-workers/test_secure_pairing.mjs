import test from 'node:test';
import assert from 'node:assert/strict';
import { timingSafeEqual } from 'node:crypto';
import { createSecure, joinSecure, authorizeSecure, revokeSecure, restoreSecure,
  deletionIdentity, authorizeDeletion, AuthorizationError } from './src/secure-pairing.mjs';

// Node lacks Workers' extension; use Node's native constant-time comparator,
// not a JS approximation. Real Worker runtime validation remains a separate gate.
crypto.subtle.timingSafeEqual = (a, b) => timingSafeEqual(a, b);

test('one-use invite, opposite role, and expiry boundary', async () => {
  const { record, device, invite } = await createSecure('sender', 1000);
  assert.equal(invite.role, 'receiver');
  assert.equal(invite.expires_at, 1900);
  assert.equal(invite.secret.length, 43);
  assert.equal(device.device_token.length, 43);
  await assert.rejects(joinSecure(record, invite.secret, 'receiver', 1900), AuthorizationError);
  const joined = await joinSecure(record, invite.secret, 'receiver', 1001);
  await assert.rejects(joinSecure(joined.record, invite.secret, 'receiver', 1002), AuthorizationError);
  assert.equal(record.invite.used, false, 'transition must not mutate storage input');
});
test('roles and credentials are bound to the device', async () => {
  const { record, device, invite } = await createSecure('receiver', 1000);
  assert.equal(invite.role, 'sender');
  assert.equal(await authorizeSecure(record, device.device_id, device.device_token, 'receiver'), device.device_id);
  for (const [id, token, role] of [[device.device_id, device.device_token, 'sender'],
    [device.device_id, 'wrong', 'receiver'], ['unknown', device.device_token, 'receiver']]) {
    await assert.rejects(authorizeSecure(record, id, token, role), AuthorizationError);
  }
});
test('rejected invite does not consume the original', async () => {
  const { record, invite } = await createSecure('sender', 1000);
  for (const [secret, role] of [['wrong', 'receiver'], [invite.secret, 'sender'], [null, 'receiver']]) {
    await assert.rejects(joinSecure(record, secret, role, 1001), AuthorizationError);
  }
  await joinSecure(record, invite.secret, 'receiver', 1001);
});
test('owner-only revocation survives serialization', async () => {
  const created = await createSecure('sender', 1000);
  const joined = await joinSecure(created.record, created.invite.secret, 'receiver', 1001);
  await assert.rejects(revokeSecure(joined.record, joined.device.device_id,
    joined.device.device_token, created.device.device_id), AuthorizationError);
  const revoked = await revokeSecure(joined.record, created.device.device_id,
    created.device.device_token, joined.device.device_id);
  assert.equal(revoked.role, 'receiver');
  const restored = restoreSecure(JSON.parse(JSON.stringify(revoked.record)));
  await assert.rejects(authorizeSecure(restored, joined.device.device_id,
    joined.device.device_token, 'receiver'), AuthorizationError);
});
test('persisted record and incidental grant JSON exclude plaintext secrets', async () => {
  const created = await createSecure('sender', 1000);
  const encoded = JSON.stringify(created.record);
  assert.ok(!encoded.includes(created.device.device_token));
  assert.ok(!encoded.includes(created.invite.secret));
  assert.ok(!JSON.stringify(created.device).includes(created.device.device_token));
  assert.ok(!JSON.stringify(created.invite).includes(created.invite.secret));
});
test('corrupt or unknown secure records fail closed', async () => {
  const { record } = await createSecure('sender', 1000);
  for (const corrupted of [null, {}, { ...record, security_mode: 'legacy' },
    { ...record, created_at: true }, { ...record, owner_device_id: 'missing' }, { ...record, invite: null }]) {
    assert.throws(() => restoreSecure(corrupted), AuthorizationError);
  }
});
test('deletion identity permits owner cleanup only', async () => {
  const created = await createSecure('sender', 1000);
  const identity = await deletionIdentity(created.record, created.device.device_id, created.device.device_token);
  await authorizeDeletion(identity, created.device.device_id, created.device.device_token);
  await assert.rejects(authorizeDeletion(identity, created.device.device_id, 'wrong'), AuthorizationError);
  await assert.rejects(authorizeDeletion(identity, null, created.device.device_token), AuthorizationError);
  for (const key of ['security_mode', 'owner_device_id', 'owner_token_hash', 'cleanup_pending']) {
    const corrupted = { ...identity }; delete corrupted[key];
    await assert.rejects(authorizeDeletion(corrupted, created.device.device_id, created.device.device_token), AuthorizationError);
  }
});

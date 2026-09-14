import assert from 'node:assert/strict';
import { submitSms, updateSms, expireSms, SMS_TTL_MS, simOptions } from './src/sms-mailbox.mjs';
const now = 10000000;
const command = {id: '00000000-0000-4000-8000-000000000001', to: '+15551234567', body: 'Hello', created_at: now};
let result = submitSms([], command, now);
assert.equal(result.status, 200);
let records = result.records;
assert.equal(submitSms(records, command, now).records.length, 1);
assert.equal(submitSms(records, {...command, body: 'different'}, now).status, 409);
assert.equal(submitSms([], {...command, to: '*123#'}, now).status, 400);
assert.equal(submitSms([], {...command, body: ''}, now).status, 400);
assert.equal(submitSms([], command, now + SMS_TTL_MS).status, 400);
assert.equal(updateSms(records, {id: command.id, status: 'sending'}), true);
assert.equal(updateSms(records, {id: command.id, status: 'sent'}), true);
updateSms(records, {id: command.id, status: 'sending'});
assert.equal(records[0].status, 'sent');
assert.equal(updateSms(records, {id: command.id, status: 'invalid'}), false);
assert.equal(expireSms(submitSms([], command, now).records, now + SMS_TTL_MS)[0].status, 'expired');
console.log('SMS mailbox validation, idempotency, expiry and state transitions passed');

const selected = submitSms([], {...command, subscription_id: 12}, now);
assert.equal(selected.command.subscription_id, 12);
assert.equal(submitSms(selected.records, {...command, subscription_id: 7}, now).status, 409);
assert.equal(submitSms(selected.records, command, now).status, 409);
for (const subscription_id of [-1, true, '12', 1.5, 2147483648])
  assert.equal(submitSms([], {...command, subscription_id}, now).status, 400);
const inventory = {state: 'ready', default_id: 7, sims: [
  {id: 7, slot: 0, name: 'Personal', carrier: 'Carrier A'},
  {id: 12, slot: 1, name: 'Work', carrier: 'Carrier B'}]};
assert.deepEqual(simOptions(inventory, now), {...inventory, updated_at: now});
assert.equal(simOptions({...inventory, sims: [...inventory.sims, inventory.sims[0]]}), null);
assert.equal(simOptions({...inventory, default_id: 99}), null);
assert.equal(simOptions({...inventory, state: 'permission_required'}), null);
assert.equal(simOptions({state: 'permission_required', sims: []}, now).state, 'permission_required');
console.log('SIM inventory validation and immutable SMS SIM selection passed');

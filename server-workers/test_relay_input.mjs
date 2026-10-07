import test from 'node:test';
import assert from 'node:assert/strict';
import { MAX_RELAY_BODY_BYTES, RelayBodyTooLarge, randomPairingCode, readRelayBody } from './src/relay-input.mjs';

test('pairing codes are six display digits sampled from crypto', () => {
  for (let i = 0; i < 100; i += 1) assert.match(randomPairingCode(), /^[1-9][0-9]{5}$/);
});

test('relay body accepts the byte limit and rejects the next byte', async () => {
  const exact = 'x'.repeat(MAX_RELAY_BODY_BYTES);
  assert.equal(await readRelayBody(new Request('https://example.test', { method: 'POST', body: exact })), exact);
  await assert.rejects(
    readRelayBody(new Request('https://example.test', { method: 'POST', body: exact + 'x' })),
    RelayBodyTooLarge,
  );
});

test('relay body counts UTF-8 bytes rather than characters', async () => {
  const body = '😀'.repeat(MAX_RELAY_BODY_BYTES / 4);
  assert.equal(await readRelayBody(new Request('https://example.test', { method: 'POST', body })), body);
  await assert.rejects(
    readRelayBody(new Request('https://example.test', { method: 'POST', body: body + '😀' })),
    RelayBodyTooLarge,
  );
});

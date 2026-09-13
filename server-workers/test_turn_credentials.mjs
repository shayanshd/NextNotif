import test from 'node:test';
import assert from 'node:assert/strict';
import { boundedJson, validateIceServers, cloudflareIceServers, consumeIceBudget, TURN_TTL_SECONDS } from './src/turn-credentials.mjs';
const servers = [{ urls: ['turn:turn.cloudflare.com:3478?transport=udp', 'turn:turn.cloudflare.com:53'],
  username: 'test-user', credential: 'test-only-password' }];
test('credential response strips port 53 and unexpected provider fields', () => {
  assert.deepEqual(validateIceServers([{ ...servers[0], apiKey: 'must-not-leave' }]),
    [{ urls: ['turn:turn.cloudflare.com:3478?transport=udp'], username: 'test-user', credential: 'test-only-password' }]);
});
test('reject missing credentials, no relay, unsafe URL and oversized arrays', () => {
  for (const value of [[], Array(9).fill(servers[0]), [{ urls:'https://example.org' }],
    [{ urls:'stun:stun.cloudflare.com:3478' }], [{ urls:'turn:turn.cloudflare.com:3478' }]]) {
    assert.throws(() => validateIceServers(value));
  }
});
test('provider key goes only to upstream API and response has bounded expiry', async () => {
  const result = await cloudflareIceServers({ CLOUDFLARE_TURN_KEY_ID:'a'.repeat(32), CLOUDFLARE_TURN_API_TOKEN:'test-master' },
    async (url, options) => {
      assert.ok(url.endsWith('/credentials/generate-ice-servers'));
      assert.equal(options.headers.Authorization, 'Bearer test-master');
      assert.equal(JSON.parse(options.body).ttl, TURN_TTL_SECONDS);
      return Response.json({ iceServers:servers, apiKey:'must-not-leave' });
    });
  assert.equal(result.provider, 'cloudflare');
  assert.ok(result.expiresAtMs > Date.now());
  assert.ok(!JSON.stringify(result).includes('master'));
  assert.ok(!JSON.stringify(result).includes('must-not-leave'));
});
test('upstream errors and oversized bodies fail closed', async () => {
  await assert.rejects(() => boundedJson(new Response('private provider error', {status:403})));
  await assert.rejects(() => boundedJson(new Response('x'.repeat(65537))));
});
test('durable issue budget resets only after its window', async () => {
  const values = new Map();
  const transaction = { get:async key=>values.get(key), put:async (key,value)=>values.set(key,value) };
  const storage = { transaction:async callback=>callback(transaction) };
  for (let i=0;i<16;i++) assert.equal(await consumeIceBudget(storage,1000),true);
  assert.equal(await consumeIceBudget(storage,1001),false);
  assert.equal(await consumeIceBudget(storage,61000),true);
});

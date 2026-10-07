// One-off staging-only TURN smoke check. Never prints pairing or TURN credentials.
// Creates a short-lived secure pairing, requests /ice, then owner-deletes it.
const base = 'https://nextnotif-relay-staging.shayanshad.workers.dev';
let pairing = null;
let result = 'not checked';
let cleanup = 'not needed';

try {
  const created = await fetch(`${base}/pair/secure-create`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ role: 'sender' }),
    signal: AbortSignal.timeout(15000),
  });
  if (!created.ok) throw new Error(`secure-create HTTP ${created.status}`);
  pairing = await created.json();
  if (!pairing.code || !pairing.device_id || !pairing.device_token) {
    throw new Error('secure-create returned an incomplete grant');
  }

  const headers = {
    'X-NextNotif-Code': pairing.code,
    'X-NextNotif-Device-Id': pairing.device_id,
    'X-NextNotif-Token': pairing.device_token,
  };
  const response = await fetch(`${base}/ice`, {
    method: 'POST', headers, signal: AbortSignal.timeout(15000),
  });
  if (!response.ok) throw new Error(`/ice HTTP ${response.status}`);
  const body = await response.json();
  if (!Array.isArray(body.iceServers) || !body.iceServers.some(server => {
    const urls = Array.isArray(server.urls) ? server.urls : [server.urls];
    return urls.some(url => typeof url === 'string' && /^turns?:/.test(url)) &&
      typeof server.username === 'string' && server.username.length > 0 &&
      typeof server.credential === 'string' && server.credential.length > 0;
  }) || !(body.expiresAtMs > Date.now() + 60000)) {
    throw new Error('/ice returned no usable expiring TURN credentials');
  }
  result = 'valid expiring TURN response';
} catch (error) {
  result = error instanceof Error ? error.message : 'unknown error';
} finally {
  if (pairing?.code && pairing?.device_id && pairing?.device_token) {
    try {
      const deleted = await fetch(`${base}/pair/secure-delete`, {
        method: 'POST',
        headers: {
          'X-NextNotif-Code': pairing.code,
          'X-NextNotif-Device-Id': pairing.device_id,
          'X-NextNotif-Token': pairing.device_token,
        },
        signal: AbortSignal.timeout(15000),
      });
      cleanup = deleted.ok ? 'owner deletion confirmed' : `owner deletion HTTP ${deleted.status}`;
    } catch {
      cleanup = 'owner deletion failed';
    }
  }
}

console.log(`Staging TURN: ${result}; temporary pairing: ${cleanup}`);
if (result !== 'valid expiring TURN response' || cleanup !== 'owner deletion confirmed') process.exitCode = 1;

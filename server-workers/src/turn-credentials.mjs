// Provider keys stay in Worker secrets; only expiring transport credentials leave.
export const TURN_TTL_SECONDS = 14700; // Four-hour call limit plus setup grace.
const MAX_JSON_BYTES = 65536;

export async function boundedJson(response) {
  if (!response.ok || !response.body) throw new Error('TURN provider unavailable');
  const reader = response.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_JSON_BYTES) throw new Error('TURN response too large');
      chunks.push(value);
    }
  } finally {
    await reader.cancel();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
  return JSON.parse(new TextDecoder().decode(bytes));
}

export function validateIceServers(input) {
  if (!Array.isArray(input) || input.length < 1 || input.length > 8) throw new Error('Invalid ICE servers');
  let hasTurn = false;
  const servers = input.map(server => {
    if (!server || typeof server !== 'object') throw new Error('Invalid ICE server');
    const urls = typeof server.urls === 'string' ? [server.urls] : server.urls;
    if (!Array.isArray(urls) || urls.length < 1 || urls.length > 12) throw new Error('Invalid ICE URLs');
    const filtered = urls.filter(url => {
      if (typeof url !== 'string' || url.length > 512 ||
          !/^(stun|turn|turns):[a-zA-Z0-9.-]+:\d+(\?transport=(udp|tcp))?$/.test(url)) {
        throw new Error('Invalid ICE URL');
      }
      return !/:53(?:\?|$)/.test(url);
    });
    if (!filtered.length) throw new Error('No usable ICE URLs');
    const result = { urls: filtered };
    if (filtered.some(url => /^turns?:/.test(url))) {
      if (typeof server.username !== 'string' || !server.username.length || server.username.length > 512 ||
          typeof server.credential !== 'string' || !server.credential.length || server.credential.length > 1024) {
        throw new Error('Missing TURN authentication');
      }
      result.username = server.username;
      result.credential = server.credential;
      hasTurn = true;
    }
    return result;
  });
  if (!hasTurn) throw new Error('No TURN relay');
  return servers;
}

export async function cloudflareIceServers(env, fetcher = fetch) {
  if (!/^[a-fA-F0-9]{32}$/.test(env.CLOUDFLARE_TURN_KEY_ID || '') || !env.CLOUDFLARE_TURN_API_TOKEN) {
    throw new Error('Cloudflare TURN not configured');
  }
  const response = await fetcher(
    `https://rtc.live.cloudflare.com/v1/turn/keys/${env.CLOUDFLARE_TURN_KEY_ID}/credentials/generate-ice-servers`,
    { method: 'POST', headers: { Authorization: `Bearer ${env.CLOUDFLARE_TURN_API_TOKEN}`,
      'Content-Type': 'application/json' }, body: JSON.stringify({ ttl: TURN_TTL_SECONDS }),
      signal: AbortSignal.timeout(7000) },
  );
  return { provider: 'cloudflare', iceServers: validateIceServers((await boundedJson(response)).iceServers),
    expiresAtMs: Date.now() + TURN_TTL_SECONDS * 1000 };
}

export async function consumeIceBudget(storage, now = Date.now()) {
  // Persist before external I/O; eviction and concurrent requests cannot reset it.
  return storage.transaction(async transaction => {
    let gate = await transaction.get('turnIssueBudget');
    if (!gate || now - gate.startedAt >= 60000 || now < gate.startedAt) gate = { startedAt: now, count: 0 };
    if (gate.count >= 16) return false;
    await transaction.put('turnIssueBudget', { startedAt: gate.startedAt, count: gate.count + 1 });
    return true;
  });
}

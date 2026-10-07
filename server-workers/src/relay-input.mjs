export const MAX_RELAY_BODY_BYTES = 64 * 1024;

export class RelayBodyTooLarge extends Error {
  constructor() { super('relay request body too large'); }
}

/** Six display digits, sampled without modulo bias from a cryptographic source. */
export function randomPairingCode() {
  const span = 900_000;
  const limit = 0x1_0000_0000 - (0x1_0000_0000 % span);
  const sample = new Uint32Array(1);
  do {
    crypto.getRandomValues(sample);
  } while (sample[0] >= limit);
  return String(100_000 + (sample[0] % span));
}

/** Bound bytes before decoding or forwarding a caller-controlled body. */
export async function readRelayBody(request, maxBytes = MAX_RELAY_BODY_BYTES) {
  if (!request.body) return '';
  const reader = request.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > maxBytes) {
        await reader.cancel().catch(() => {});
        throw new RelayBodyTooLarge();
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(length);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

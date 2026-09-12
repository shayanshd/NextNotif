import { AuthorizationError, createSecure, joinSecure, restoreSecure } from './secure-pairing.mjs';

const KEY = 'secureRecord';
// Uses the transactional KV API supported by both existing relay backends.
// Crypto runs outside transactions. A versioned compare-and-swap prevents stale
// transitions (including competing joins) from overwriting a committed record.
export class SecurePairingStore {
  constructor(storage) { this.storage = storage; }

  decode(raw) {
    try {
      const envelope = JSON.parse(raw);
      if (typeof envelope.version !== 'string' || !envelope.version) throw new Error();
      return restoreSecure(envelope.record);
    } catch { throw new AuthorizationError(); }
  }

  async read() {
    return this.decode(await this.storage.get(KEY));
  }

  async create(role, now) {
    const grant = await createSecure(role, now);
    const encoded = JSON.stringify({ version: crypto.randomUUID(), record: grant.record });
    const committed = await this.storage.transaction(async txn => {
      // Never infer ownership of a legacy or quarantined/reserved namespace.
      if (await txn.get('paired') != null || await txn.get(KEY) != null ||
          await txn.get('securityMode') != null) return false;
      await txn.put({ paired: '1', securityMode: 'invite_v1', [KEY]: encoded });
      return true;
    });
    if (!committed) throw new AuthorizationError();
    await this.storage.sync();
    return grant;
  }

  async transition(action) {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const snapshot = await this.storage.get(KEY);
      const result = await action(this.decode(snapshot));
      // Validate before persistence; never let an action downgrade the schema.
      const record = restoreSecure(result.record);
      const encoded = JSON.stringify({ version: crypto.randomUUID(), record });
      const committed = await this.storage.transaction(async txn => {
        if (await txn.get(KEY) !== snapshot || await txn.get('securityMode') !== 'invite_v1') return false;
        await txn.put(KEY, encoded);
        return true;
      });
      if (!committed) continue;
      await this.storage.sync();
      return result;
    }
    throw new AuthorizationError();
  }

  async join(secret, role, now) {
    return this.transition(record => joinSecure(record, secret, role, now));
  }
}

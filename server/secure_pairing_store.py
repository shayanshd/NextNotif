"""Transactional secure-pairing storage, not yet exposed through relay routes."""

import json
import re
import sqlite3

from secure_pairing import AuthorizationError, SecurePairing


class SecurePairingStore:
    def __init__(self, path):
        self.path = path
        connection = self._connect()
        try:
            with connection:
                connection.execute("CREATE TABLE IF NOT EXISTS secure_pairings (code TEXT PRIMARY KEY, record TEXT NOT NULL)")
        finally:
            connection.close()

    def _connect(self):
        connection = sqlite3.connect(self.path, timeout=5)
        connection.execute("PRAGMA synchronous = FULL")
        return connection

    def exists(self, code):
        connection = self._connect()
        try:
            return connection.execute("SELECT 1 FROM secure_pairings WHERE code = ?", (code,)).fetchone() is not None
        finally:
            connection.close()

    def create(self, code, role, now):
        if not isinstance(code, str) or not re.fullmatch(r"[0-9]{6}", code):
            raise AuthorizationError()
        pairing, device, invite = SecurePairing.create(role, now)
        connection = self._connect()
        try:
            with connection:
                connection.execute("INSERT INTO secure_pairings VALUES (?, ?)",
                                   (code, json.dumps(pairing.to_record())))
        except sqlite3.IntegrityError:
            raise AuthorizationError() from None
        finally:
            connection.close()
        # No bearer or invitation secret is released until commit succeeds.
        return device, invite

    def _mutate(self, code, operation):
        connection = self._connect()
        try:
            with connection:
                # Serialize invite consumption across processes as well as threads.
                connection.execute("BEGIN IMMEDIATE")
                row = connection.execute("SELECT record FROM secure_pairings WHERE code = ?", (code,)).fetchone()
                if row is None:
                    raise AuthorizationError()
                pairing = SecurePairing.from_record(json.loads(row[0]))
                result = operation(pairing)
                connection.execute("UPDATE secure_pairings SET record = ? WHERE code = ?",
                                   (json.dumps(pairing.to_record()), code))
            return result
        finally:
            connection.close()

    def join(self, code, secret, role, now):
        return self._mutate(code, lambda pairing: pairing.join(secret, role, now))

    def revoke(self, code, owner_id, owner_token, device_id):
        return self._mutate(code, lambda pairing: pairing.revoke(owner_id, owner_token, device_id))

    def authorize_owner(self, code, device_id, token):
        connection = self._connect()
        try:
            row = connection.execute("SELECT record FROM secure_pairings WHERE code = ?", (code,)).fetchone()
            if row is None:
                raise AuthorizationError()
            return SecurePairing.from_record(json.loads(row[0])).authorize_owner(device_id, token)
        finally:
            connection.close()

    def deletion_identity(self, code, device_id, token):
        connection = self._connect()
        try:
            row = connection.execute("SELECT record FROM secure_pairings WHERE code = ?", (code,)).fetchone()
            if row is None:
                raise AuthorizationError()
            return SecurePairing.from_record(json.loads(row[0])).deletion_identity(device_id, token)
        finally:
            connection.close()

    def purge_reserved(self, code):
        """Internal cleanup only: caller must persist/authorize a deletion tombstone."""
        if not isinstance(code, str) or not re.fullmatch(r"[0-9]{6}", code):
            raise AuthorizationError()
        connection = self._connect()
        try:
            with connection:
                connection.execute("DELETE FROM secure_pairings WHERE code = ?", (code,))
        finally:
            connection.close()

    def authorize(self, code, device_id, token, role):
        connection = self._connect()
        try:
            row = connection.execute("SELECT record FROM secure_pairings WHERE code = ?", (code,)).fetchone()
            if row is None:
                raise AuthorizationError()
            pairing = SecurePairing.from_record(json.loads(row[0]))
            return pairing.authorize(device_id, token, role)
        finally:
            connection.close()

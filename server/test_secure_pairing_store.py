import os
import sqlite3
import tempfile
import unittest
from concurrent.futures import ThreadPoolExecutor

from secure_pairing import AuthorizationError
from secure_pairing_store import SecurePairingStore


class SecurePairingStoreTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="nextnotif-secure-test-")
        self.path = os.path.join(self.directory.name, "secure.sqlite")
        self.store = SecurePairingStore(self.path)
        self.owner, self.invite = self.store.create("123456", "sender", 1000)

    def tearDown(self):
        self.directory.cleanup()

    def test_restart_preserves_used_invite_role_and_revocation(self):
        receiver = self.store.join("123456", self.invite.secret, "receiver", 1001)
        reopened = SecurePairingStore(self.path)
        reopened.authorize("123456", receiver.device_id, receiver.device_token, "receiver")
        with self.assertRaises(AuthorizationError):
            reopened.join("123456", self.invite.secret, "receiver", 1002)
        reopened.revoke("123456", self.owner.device_id, self.owner.device_token, receiver.device_id)
        with self.assertRaises(AuthorizationError):
            SecurePairingStore(self.path).authorize("123456", receiver.device_id, receiver.device_token, "receiver")

    def test_concurrent_invite_consumption_has_one_winner(self):
        def attempt(_):
            try:
                return SecurePairingStore(self.path).join("123456", self.invite.secret, "receiver", 1001)
            except AuthorizationError:
                return None
        with ThreadPoolExecutor(max_workers=4) as executor:
            outcomes = list(executor.map(attempt, range(4)))
        self.assertEqual(1, sum(result is not None for result in outcomes))

    def test_duplicate_code_cannot_replace_owner(self):
        with self.assertRaises(AuthorizationError):
            self.store.create("123456", "receiver", 1000)
        self.store.authorize("123456", self.owner.device_id, self.owner.device_token, "sender")

    def test_failed_mutation_rolls_back_invite_consumption(self):
        def fail_after_join(pairing):
            pairing.join(self.invite.secret, "receiver", 1001)
            raise RuntimeError("simulated interruption before commit")
        with self.assertRaises(RuntimeError):
            self.store._mutate("123456", fail_after_join)
        self.store.join("123456", self.invite.secret, "receiver", 1002)

    def test_database_contains_no_plaintext_credentials(self):
        connection = sqlite3.connect(self.path)
        try:
            encoded = connection.execute("SELECT record FROM secure_pairings").fetchone()[0]
        finally:
            connection.close()
        self.assertNotIn(self.owner.device_token, encoded)
        self.assertNotIn(self.invite.secret, encoded)

    def test_corrupt_record_fails_closed(self):
        connection = sqlite3.connect(self.path)
        try:
            with connection:
                connection.execute("UPDATE secure_pairings SET record = '{}' WHERE code = '123456'")
        finally:
            connection.close()
        with self.assertRaises(ValueError):
            self.store.authorize("123456", self.owner.device_id, self.owner.device_token, "sender")


if __name__ == "__main__":
    unittest.main()

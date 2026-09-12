import copy
import json
import unittest

from secure_pairing import AuthorizationError, INVITE_LIFETIME_SECONDS, SecurePairing, authorize_deletion


class SecurePairingTest(unittest.TestCase):
    def setUp(self):
        self.pairing, self.owner, self.invite = SecurePairing.create("sender", 1000)

    def test_invite_has_expiry_and_opposite_role(self):
        self.assertEqual("receiver", self.invite.role)
        self.assertEqual(1000 + INVITE_LIFETIME_SECONDS, self.invite.expires_at)
        self.assertEqual(43, len(self.invite.secret))
        self.assertEqual(43, len(self.owner.device_token))

    def test_receiver_creator_invites_only_sender(self):
        pairing, owner, invite = SecurePairing.create("receiver", 1000)
        self.assertEqual("sender", invite.role)
        pairing.authorize(owner.device_id, owner.device_token, "receiver")
        sender = pairing.join(invite.secret, "sender", 1001)
        pairing.authorize(sender.device_id, sender.device_token, "sender")

    def test_restored_revocation_remains_enforced(self):
        receiver = self.pairing.join(self.invite.secret, "receiver", 1001)
        self.pairing.revoke(self.owner.device_id, self.owner.device_token, receiver.device_id)
        restored = SecurePairing.from_record(self.pairing.to_record())
        with self.assertRaises(AuthorizationError):
            restored.authorize(receiver.device_id, receiver.device_token, "receiver")

    def test_join_once_and_role_scoped_authorization(self):
        receiver = self.pairing.join(self.invite.secret, "receiver", 1001)
        self.assertEqual(receiver.device_id, self.pairing.authorize(receiver.device_id, receiver.device_token, "receiver"))
        for device, token, role in (
            (receiver.device_id, receiver.device_token, "sender"),
            (self.owner.device_id, self.owner.device_token, "receiver"),
            (receiver.device_id, "wrong", "receiver"),
            ("unknown", receiver.device_token, "receiver"),
        ):
            with self.assertRaises(AuthorizationError):
                self.pairing.authorize(device, token, role)
        with self.assertRaises(AuthorizationError):
            self.pairing.join(self.invite.secret, "receiver", 1002)

    def test_wrong_secret_or_role_does_not_consume_invite(self):
        for secret, role in (("wrong", "receiver"), (self.invite.secret, "sender"), (None, "receiver")):
            with self.assertRaises(AuthorizationError):
                self.pairing.join(secret, role, 1001)
        self.pairing.join(self.invite.secret, "receiver", 1001)

    def test_expiry_boundary_rejected(self):
        with self.assertRaises(AuthorizationError):
            self.pairing.join(self.invite.secret, "receiver", self.invite.expires_at)

    def test_only_owner_can_revoke_and_revoked_token_fails(self):
        receiver = self.pairing.join(self.invite.secret, "receiver", 1001)
        with self.assertRaises(AuthorizationError):
            self.pairing.revoke(receiver.device_id, receiver.device_token, self.owner.device_id)
        self.pairing.revoke(self.owner.device_id, self.owner.device_token, receiver.device_id)
        with self.assertRaises(AuthorizationError):
            self.pairing.authorize(receiver.device_id, receiver.device_token, "receiver")

    def test_roundtrip_keeps_used_invite_and_credentials_without_plaintext_secrets(self):
        receiver = self.pairing.join(self.invite.secret, "receiver", 1001)
        encoded = json.dumps(self.pairing.to_record())
        for secret in (self.owner.device_token, receiver.device_token, self.invite.secret):
            self.assertNotIn(secret, encoded)
        self.assertNotIn(self.owner.device_token, repr(self.owner))
        self.assertNotIn(self.invite.secret, repr(self.invite))
        restored = SecurePairing.from_record(json.loads(encoded))
        restored.authorize(receiver.device_id, receiver.device_token, "receiver")
        with self.assertRaises(AuthorizationError):
            restored.join(self.invite.secret, "receiver", 1002)

    def test_corrupt_secure_record_never_falls_back_to_legacy(self):
        record = self.pairing.to_record()
        mutations = [None, {}, {"security_mode": "legacy"}]
        for path, bad in (("owner_device_id", "missing"), ("created_at", True), ("credentials", []), ("invite", None)):
            changed = copy.deepcopy(record)
            changed[path] = bad
            mutations.append(changed)
        for changed in mutations:
            with self.assertRaises(ValueError):
                SecurePairing.from_record(changed)

    def test_invalid_secret_inputs_are_rejected(self):
        for token in (None, {}, "", "x" * 129, "\u2603"):
            with self.assertRaises(AuthorizationError):
                self.pairing.authorize(self.owner.device_id, token, "sender")

    def test_deletion_identity_only_authorizes_owner_cleanup(self):
        identity = self.pairing.deletion_identity(self.owner.device_id, self.owner.device_token)
        authorize_deletion(identity, self.owner.device_id, self.owner.device_token)
        for device, token in ((None, self.owner.device_token), ("unknown", self.owner.device_token),
                              (self.owner.device_id, "wrong")):
            with self.assertRaises(AuthorizationError):
                authorize_deletion(identity, device, token)
        for field_name in ("security_mode", "owner_device_id", "owner_token_hash", "cleanup_pending"):
            corrupted = dict(identity)
            corrupted.pop(field_name)
            with self.assertRaises(AuthorizationError):
                authorize_deletion(corrupted, self.owner.device_id, self.owner.device_token)


if __name__ == "__main__":
    unittest.main()

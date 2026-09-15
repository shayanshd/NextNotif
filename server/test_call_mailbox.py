import unittest
import call_mailbox
import tempfile
import httpx
import main
from unittest.mock import patch


class CallMailboxTest(unittest.TestCase):
    def setUp(self):
        self.now = 1000000
        self.command = {"id": "00000000-0000-4000-8000-000000000001", "to": "+15551234567",
                        "subscription_id": 12, "created_at": self.now}

    def test_selection_is_immutable_and_only_one_call_is_active(self):
        records = []
        self.assertEqual(call_mailbox.submit(records, self.command, self.now)[0], 200)
        self.assertEqual(call_mailbox.submit(records, self.command, self.now)[0], 200)
        self.assertEqual(call_mailbox.submit(records, dict(self.command, subscription_id=7), self.now)[0], 409)
        self.assertEqual(call_mailbox.submit(records, dict(self.command, id="00000000-0000-4000-8000-000000000002"), self.now)[0], 409)
        self.assertTrue(call_mailbox.update(records, {"id": self.command["id"], "status": "ended"}))
        self.assertEqual(call_mailbox.submit(records, dict(self.command, id="00000000-0000-4000-8000-000000000002"), self.now)[0], 200)

    def test_validation_expiry_and_monotonic_result(self):
        for value in (-1, True, "12", 1.5, 2147483648):
            self.assertFalse(call_mailbox.valid(dict(self.command, subscription_id=value), self.now))
        records = []
        call_mailbox.submit(records, self.command, self.now)
        call_mailbox.expire(records, self.now + call_mailbox.TTL_MS)
        self.assertEqual(records[0]["status"], "expired")
        call_mailbox.update(records, {"id": self.command["id"], "status": "connected"})
        self.assertEqual(records[0]["status"], "expired")


class CallHttpTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.previous = main.registry
        main.registry = main.Registry(self.temp.name + "/pairings.json")
        self.wake = patch("main.fcm_wake", lambda *args: None)
        self.wake.start()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url="http://test")
        self.tokens = {}
        for role in ("sender", "receiver"):
            response = await self.client.post("/fcm-register", headers={"X-NextNotif-Code": "123456", "X-NextNotif-Role": role},
                json={"delivery_mode": "ws"})
            self.tokens[role] = response.json()["device_token"]

    async def asyncTearDown(self):
        await self.client.aclose()
        self.wake.stop()
        main.registry = self.previous
        self.temp.cleanup()

    async def post(self, operation, role, body=None):
        return await self.client.post("/call-" + operation, headers={"X-NextNotif-Code": "123456",
            "X-NextNotif-Token": self.tokens[role]}, json=body or {})

    async def test_roles_and_round_trip(self):
        command = {"id": "00000000-0000-4000-8000-000000000001", "to": "+15551234567",
                   "subscription_id": 12, "created_at": call_mailbox.now_ms()}
        self.assertEqual((await self.post("submit", "sender", command)).status_code, 401)
        self.assertEqual((await self.post("submit", "receiver", command)).status_code, 200)
        fetched = (await self.post("fetch", "sender")).json()["commands"]
        self.assertEqual(fetched[0]["subscription_id"], 12)
        self.assertEqual((await self.post("result", "receiver", {"id": command["id"], "status": "dialing"})).status_code, 401)
        self.assertEqual((await self.post("result", "sender", {"id": command["id"], "status": "connected"})).status_code, 200)
        self.assertEqual((await self.post("status", "receiver")).json()["commands"][0]["status"], "connected")


if __name__ == "__main__":
    unittest.main()

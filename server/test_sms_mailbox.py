"""Isolated HTTP tests. No FCM calls and no real SMS messages."""
import asyncio
import json
import tempfile
import time
import unittest
import runpy
from unittest.mock import patch
import httpx
import main

class SmsMailboxTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.old = main.registry
        main.registry = main.Registry(self.temp.name + '/pairings.json')
        self.wake = patch('main.fcm_wake', lambda *a: None)
        self.wake.start()
        self.client = httpx.AsyncClient(transport=httpx.ASGITransport(app=main.app), base_url='http://test')
        self.code = '123456'
        self.tokens = {}
        for role in ['sender', 'receiver']:
            response = await self.client.post('/fcm-register', headers={'X-NextNotif-Code': self.code, 'X-NextNotif-Role': role},
                json={'fcm_token': 'fake-token-for-tests-' + role})
            self.assertEqual(response.status_code, 200)
            self.tokens[role] = response.json()['device_token']
        self.command = {'id': '00000000-0000-4000-8000-000000000001', 'to': '+15551234567', 'body': 'Test only', 'created_at': int(time.time()*1000)}
    async def asyncTearDown(self):
        await self.client.aclose()
        self.wake.stop()
        main.registry = self.old
        self.temp.cleanup()
    async def post(self, operation, role, body=None):
        return await self.client.post('/sms-' + operation, headers={'X-NextNotif-Code': self.code,
            'X-NextNotif-Token': self.tokens.get(role, 'wrong')}, json=body or {})
    async def test_roles_dedup_restart_and_carrier_result(self):
        for operation, wrong in [('submit', 'sender'), ('fetch', 'receiver'), ('result', 'receiver'), ('status', 'sender')]:
            self.assertEqual((await self.post(operation, wrong, self.command)).status_code, 401)
        self.assertEqual((await self.post('submit', 'receiver', self.command)).status_code, 200)
        self.assertEqual((await self.post('submit', 'receiver', self.command)).status_code, 200)
        self.assertEqual((await self.post('submit', 'receiver', dict(self.command, body='changed'))).status_code, 409)
        main.registry = main.Registry(main.registry.path)
        fetched = (await self.post('fetch', 'sender')).json()['commands']
        self.assertEqual(len(fetched), 1)
        self.assertEqual(fetched[0]['body'], 'Test only')
        self.assertEqual((await self.post('result', 'sender', {'id': self.command['id'], 'status': 'sent'})).status_code, 200)
        await self.post('result', 'sender', {'id': self.command['id'], 'status': 'sending'})
        self.assertEqual((await self.post('status', 'receiver')).json()['commands'][0]['status'], 'sent')
        self.assertEqual((await self.post('fetch', 'sender')).json()['commands'], [])
    async def test_sim_inventory_roles_refresh_restart_and_explicit_selection(self):
        class Socket:
            def __init__(self): self.messages = []
            async def send_text(self, value): self.messages.append(json.loads(value))
        socket = Socket()
        main.registry.get(self.code).sender = socket
        inventory = {"state": "ready", "default_id": 7, "sims": [
            {"id": 7, "slot": 0, "name": "Personal", "carrier": "Carrier A"},
            {"id": 12, "slot": 1, "name": "Work", "carrier": "Carrier B"}]}
        self.assertEqual((await self.post('fetch', 'receiver', {"sim_options": inventory})).status_code, 401)
        await self.post('status', 'receiver', {"sim_options": inventory, "request_sims": True})
        self.assertEqual(socket.messages, [{"type": "sms_sync", "data": {}}])
        self.assertIsNone((await self.post('status', 'receiver')).json()['sim_options'])
        self.assertEqual((await self.post('fetch', 'sender', {"sim_options": inventory})).status_code, 200)
        main.registry = main.Registry(main.registry.path)
        options = (await self.post('status', 'receiver')).json()['sim_options']
        self.assertEqual(options['sims'], inventory['sims'])
        self.assertEqual(options['default_id'], 7)
        self.assertGreater(options['updated_at'], 0)
        selected = dict(self.command, subscription_id=12)
        self.assertEqual((await self.post('submit', 'receiver', selected)).status_code, 200)
        self.assertEqual((await self.post('submit', 'receiver', dict(selected, subscription_id=7))).status_code, 409)
        self.assertEqual((await self.post('submit', 'receiver', self.command)).status_code, 409)
        self.assertEqual((await self.post('fetch', 'sender')).json()['commands'][0]['subscription_id'], 12)
        for value in [-1, True, "12", 1.5, 2147483648]:
            import uuid
            self.assertEqual((await self.post('submit', 'receiver', dict(selected, id=str(uuid.uuid4()), subscription_id=value))).status_code, 400)
        bad = dict(inventory, sims=inventory['sims'] * 2)
        self.assertEqual((await self.post('fetch', 'sender', {"sim_options": bad})).status_code, 400)
        self.assertEqual((await self.post('status', 'receiver')).json()['sim_options'], options)

    async def test_ws_without_fcm_gets_direct_sms_wake(self):
        class Socket:
            def __init__(self): self.messages = []
            async def send_text(self, value): self.messages.append(json.loads(value))
        socket = Socket()
        main.registry.get(self.code).sender = socket
        registration = await self.client.post('/fcm-register', headers={'X-NextNotif-Code': self.code, 'X-NextNotif-Role': 'sender'},
            json={'delivery_mode': 'ws', 'device_token': self.tokens['sender']})
        self.assertEqual(registration.status_code, 200)
        await self.post('submit', 'receiver', self.command)
        self.assertEqual(socket.messages, [{'type': 'sms_sync', 'data': {}}])

    async def test_script_launcher_registers_sms_routes_before_serving(self):
        with patch('uvicorn.run') as serve:
            runpy.run_path(main.__file__, run_name='__main__')
        self.assertTrue(any(route.path == '/sms-{operation}' for route in serve.call_args.args[0].routes))

    async def test_expiry_and_cross_role_reregistration(self):
        self.assertEqual((await self.post('submit', 'receiver', dict(self.command, created_at=1))).status_code, 400)
        response = await self.client.post('/fcm-register', headers={'X-NextNotif-Code': self.code, 'X-NextNotif-Role': 'sender'},
            json={'fcm_token': 'fake-token-for-tests-sender', 'device_token': self.tokens['receiver']})
        self.assertEqual(response.status_code, 401)

if __name__ == '__main__': unittest.main()

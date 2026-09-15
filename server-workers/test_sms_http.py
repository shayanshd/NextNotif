"""Local Worker HTTP integration; never targets a deployed relay or a real phone."""
import asyncio
import time
import uuid
import secrets
import httpx
import websockets

async def main():
    code = str(secrets.randbelow(900000) + 100000)
    async with httpx.AsyncClient(base_url='http://127.0.0.1:8787') as client:
        tokens = {}
        for role in ['sender', 'receiver']:
            r = await client.post('/fcm-register', headers={'X-NextNotif-Code': code, 'X-NextNotif-Role': role},
                json={'fcm_token': 'local-test-fcm-token-' + role})
            assert r.status_code == 200, r.text
            tokens[role] = r.json()['device_token']
        async def post(op, role, body=None):
            return await client.post('/sms-' + op, headers={'X-NextNotif-Code': code, 'X-NextNotif-Token': tokens.get(role, 'bad')}, json=body or {})
        inventory = {'state': 'ready', 'default_id': 7, 'sims': [
            {'id': 7, 'slot': 0, 'name': 'Personal', 'carrier': 'Carrier A'},
            {'id': 12, 'slot': 1, 'name': 'Work', 'carrier': 'Carrier B'}]}
        assert (await post('fetch', 'receiver', {'sim_options': inventory})).status_code == 401
        await post('status', 'receiver', {'sim_options': inventory})
        assert (await post('status', 'receiver')).json()['sim_options'] is None
        assert (await post('fetch', 'sender', {'sim_options': inventory})).status_code == 200
        assert (await post('status', 'receiver')).json()['sim_options']['sims'] == inventory['sims']
        command = {'subscription_id': 12, 'id': str(uuid.uuid4()), 'to': '+15551234567', 'body': 'Local test only', 'created_at': int(time.time()*1000)}
        for op, wrong in [('submit', 'sender'), ('fetch', 'receiver'), ('result', 'receiver'), ('status', 'sender')]:
            assert (await post(op, wrong, command)).status_code == 401
        results = await asyncio.gather(*(post('submit', 'receiver', command) for _ in range(12)))
        assert all(r.status_code == 200 for r in results), [r.text for r in results]
        assert len((await post('fetch', 'sender')).json()['commands']) == 1
        assert (await post('submit', 'receiver', dict(command, body='changed'))).status_code == 409
        assert (await post('submit', 'receiver', dict(command, subscription_id=7))).status_code == 409
        assert (await post('fetch', 'sender')).json()['commands'][0]['subscription_id'] == 12
        commands = [dict(command, id=str(uuid.uuid4())) for _ in range(12)]
        results = await asyncio.gather(*(post('submit', 'receiver', c) for c in commands))
        assert all(r.status_code == 200 for r in results)
        assert len((await post('fetch', 'sender')).json()['commands']) == 13
        assert (await post('result', 'sender', {'id': command['id'], 'status': 'sent'})).status_code == 200
        await post('result', 'sender', {'id': command['id'], 'status': 'sending'})
        record = next(x for x in (await post('status', 'receiver')).json()['commands'] if x['id'] == command['id'])
        assert record['status'] == 'sent'
        wrong = await client.post('/fcm-register', headers={'X-NextNotif-Code': code, 'X-NextNotif-Role': 'sender'},
            json={'fcm_token': 'local-test-fcm-token-sender', 'device_token': tokens['receiver']})
        assert wrong.status_code == 401
        assert (await post('submit', 'receiver', dict(command, id=str(uuid.uuid4()), created_at=1))).status_code == 400
        async def call_post(op, role, body=None):
            return await client.post('/call-' + op, headers={'X-NextNotif-Code': code,
                'X-NextNotif-Token': tokens.get(role, 'bad')}, json=body or {})
        call = {'id': str(uuid.uuid4()), 'to': '+15551234567', 'subscription_id': 12,
            'created_at': int(time.time()*1000)}
        assert (await call_post('submit', 'sender', call)).status_code == 401
        assert (await call_post('submit', 'receiver', call)).status_code == 200
        assert (await call_post('fetch', 'sender')).json()['commands'][0]['subscription_id'] == 12
        assert (await call_post('result', 'receiver', {'id': call['id'], 'status': 'connected'})).status_code == 401
        assert (await call_post('result', 'sender', {'id': call['id'], 'status': 'connected'})).status_code == 200
        assert (await call_post('status', 'receiver')).json()['commands'][0]['status'] == 'connected'
        # A healthy WebSocket must be sufficient even without an FCM token.
        ws_code = str(secrets.randbelow(900000) + 100000)
        ws_tokens = {}
        for role in ['sender', 'receiver']:
            r = await client.post('/fcm-register', headers={'X-NextNotif-Code': ws_code, 'X-NextNotif-Role': role}, json={'delivery_mode': 'ws'})
            assert r.status_code == 200, r.text
            ws_tokens[role] = r.json()['device_token']
        async with websockets.connect('ws://127.0.0.1:8787/ws/sender/' + ws_code) as socket:
            import json
            await socket.send(json.dumps({'type': 'hello'}))
            challenge = json.loads(await socket.recv())
            await socket.send(json.dumps({'type': 'auth', 'token': challenge['token'], 'code': ws_code, 'device_token': ws_tokens['sender']}))
            assert json.loads(await socket.recv())['type'] == 'auth_ok'
            refresh = await client.post('/sms-status', headers={'X-NextNotif-Code': ws_code, 'X-NextNotif-Token': ws_tokens['receiver']}, json={'request_sims': True})
            assert refresh.status_code == 200
            assert json.loads(await asyncio.wait_for(socket.recv(), timeout=3)) == {'type': 'sms_sync', 'data': {}}
            r = await client.post('/sms-submit', headers={'X-NextNotif-Code': ws_code, 'X-NextNotif-Token': ws_tokens['receiver']}, json=dict(command, id=str(uuid.uuid4())))
            assert r.status_code == 200, r.text
            assert json.loads(await asyncio.wait_for(socket.recv(), timeout=3)) == {'type': 'sms_sync', 'data': {}}
        print('Worker SMS HTTP: role authorization, concurrent deduplication, queue durability, status monotonicity and expiry passed')

asyncio.run(main())

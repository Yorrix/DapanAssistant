import asyncio
import io
import json
import time
import uuid

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app import create_app
from shared_speech import (MAX_BYTES, RESERVATION, SharedSpeech, SpeechError,
                           install_shared_speech, settle_cost, start_payload)


def identifier():
    return str(uuid.uuid4())


def start(task=None, hint='玉兰卡，体测'):
    return {'header': {'action': 'run-task', 'task_id': task or identifier()},
            'payload': {'model': 'client-must-not-control', 'parameters': {'sample_rate': 48000},
                        'input': {'context': [{'role': 'system', 'content': [{'type': 'input_text', 'text': hint}]}]}}}


def finish(task):
    return {'header': {'action': 'finish-task', 'task_id': task}}


class Supplier:
    def __init__(self, fail=False, usage=1):
        self.queue = asyncio.Queue()
        self.bytes = 0
        self.opening = None
        self.closed = False
        self.fail = fail
        self.usage = usage

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        self.closed = True

    async def send(self, value):
        if isinstance(value, bytes):
            self.bytes += len(value)
        else:
            message = json.loads(value)
            if message['header']['action'] == 'run-task':
                self.opening = message
                await self.queue.put({'header': {'event': 'task-started'}})
            else:
                if self.fail:
                    await self.queue.put({'header': {'event': 'task-failed', 'error_message': 'SECRET-MUST-NOT-LEAK'}})
                else:
                    await self.queue.put({'header': {'event': 'result-generated', 'private': 'SECRET-MUST-NOT-LEAK'},
                                          'payload': {'usage': {'duration': self.usage}, 'output': {'sentence': {
                                              'text': '明天体测带玉兰卡', 'sentence_id': 1, 'sentence_end': True,
                                              'begin_time': 0, 'end_time': 1000, 'private': 'SECRET-MUST-NOT-LEAK'}}}})
                    await self.queue.put({'header': {'event': 'task-finished'}})

    async def recv(self):
        return json.dumps(await self.queue.get())


@pytest.fixture
def relay(tmp_path, monkeypatch):
    monkeypatch.setenv('SHARED_SPEECH_ENABLED', '1')
    monkeypatch.setenv('ALIYUN_API_KEY', 'SECRET-MUST-NOT-LEAK')
    app = create_app(tmp_path, 'test', lambda *args: None)
    ai = app.state.shared_ai
    service = app.state.service
    user = {'id': identifier(), 'email': 'voice-fixture@mail.dlut.edu.cn', 'nickname': 'voice fixture'}
    with service.db() as db:
        db.execute('INSERT INTO users(id,email,nickname,password,created) VALUES(?,?,?,?,?)',
                   (user['id'], user['email'], user['nickname'], 'not-a-password', int(time.time())))
        token = service.token(db, user)['token']
    speech = app.state.shared_speech
    supplier = Supplier()
    speech.connector = lambda: supplier
    return TestClient(app), ai, speech, supplier, {'Authorization': 'Bearer ' + token}, user


def test_unauthenticated_and_disabled(relay, monkeypatch):
    client, ai, speech, supplier, headers, user = relay
    with client.websocket_connect('/ai/speech') as ws:
        assert ws.receive_json()['header']['error_code'] == 'login_required'
    monkeypatch.setenv('SHARED_SPEECH_ENABLED', '0')
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        assert ws.receive_json()['header']['error_code'] == 'disabled'
    assert ai.budget.status(user['id'])['day']['held'] == 0
    assert supplier.opening is None


def test_fixed_payload_hotwords_and_bounded_context():
    task, value = start_payload(start())
    assert value['payload']['model'] == 'fun-asr-realtime'
    assert value['payload']['parameters']['sample_rate'] == 16000
    assert value['payload']['input']['context'][0]['role'] == 'user'
    assert '玉兰卡' in value['payload']['input']['context'][0]['content'][0]['text']
    with pytest.raises(SpeechError):
        start_payload(start(hint='a' * 401))
    with pytest.raises(SpeechError):
        start_payload(start(task='not-uuid'))


def test_result_sanitized_and_settled_before_done(relay):
    client, ai, speech, supplier, headers, user = relay
    request = start();task = request['header']['task_id']
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(request)
        assert ws.receive_json()['header']['event'] == 'task-started'
        assert ai.budget.status(user['id'])['day']['held'] == RESERVATION
        ws.send_bytes(bytes(3200))
        ws.send_json(finish(task))
        result = ws.receive_json()
        assert result['payload']['output']['sentence']['text'] == '明天体测带玉兰卡'
        assert 'SECRET' not in json.dumps(result)
        assert ws.receive_json()['header']['event'] == 'task-finished'
        balance = ai.budget.status(user['id'])['day']
        assert balance['held'] == 0 and balance['used'] == 330
    assert supplier.closed and supplier.bytes == 3200


def test_upstream_error_is_sanitized_and_charged(relay):
    client, ai, speech, supplier, headers, user = relay
    supplier.fail = True
    request = start()
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(request);ws.receive_json()
        ws.send_bytes(bytes(3200));ws.send_json(finish(request['header']['task_id']))
        result = ws.receive_json()
        assert result['header']['event'] == 'task-failed'
        assert 'SECRET' not in json.dumps(result)
    assert ai.budget.status(user['id'])['day']['used'] == 330


def test_disconnect_accounts_for_forwarded_tail(relay):
    client, ai, speech, supplier, headers, user = relay
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(start());ws.receive_json()
        ws.send_bytes(bytes(3200))
        ws.close()
    balance = ai.budget.status(user['id'])['day']
    assert balance['held'] == 0 and balance['used'] == 330
    assert supplier.closed


def test_invalid_frame_stops_without_forwarding(relay):
    client, ai, speech, supplier, headers, user = relay
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(start());ws.receive_json()
        ws.send_bytes(bytes(16386))
        assert ws.receive_json()['header']['error_code'] == 'invalid'
    assert supplier.bytes == 0
    assert ai.budget.status(user['id'])['day']['held'] == 0
    assert ai.budget.status(user['id'])['day']['used'] == 0


def test_hard_audio_cap_and_settlement(relay):
    client, ai, speech, supplier, headers, user = relay
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(start());ws.receive_json()
        for _ in range(MAX_BYTES // 16000):
            ws.send_bytes(bytes(16000))
        ws.send_bytes(bytes(2))
        assert ws.receive_json()['header']['error_code'] == 'limit'
    assert supplier.bytes == MAX_BYTES
    balance = ai.budget.status(user['id'])['day']
    assert balance['used'] == 19800 and balance['held'] == 0


def test_shared_pool_and_same_kind_concurrency(relay):
    client, ai, speech, supplier, headers, user = relay
    text_id = identifier()
    ai.budget.reserve(text_id, user['id'], 1000, kind='text')
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(start());assert ws.receive_json()['header']['event'] == 'task-started'
        with client.websocket_connect('/ai/speech', headers=headers) as another:
            another.send_json(start())
            assert another.receive_json()['header']['error_code'] == 'busy'
        assert ai.budget.status(user['id'])['day']['held'] == 1000 + RESERVATION
    ai.budget.settle_amount(text_id, 100)
    assert ai.budget.status(user['id'])['day']['used'] == 100


def test_quota_rejects_before_supplier_connection(relay):
    client, ai, speech, supplier, headers, user = relay
    previous = identifier()
    ai.budget.reserve(previous, user['id'], 999000)
    ai.budget.settle_amount(previous, 999000)
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(start())
        assert ws.receive_json()['header']['error_code'] == 'daily_limit'
    assert supplier.opening is None


def test_rate_limit_does_not_invalidate_valid_login(relay):
    client, ai, speech, supplier, headers, user = relay
    def limited(*args):
        raise HTTPException(429, 'rate limit')
    ai.service.rate = limited
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        assert ws.receive_json()['header']['error_code'] == 'busy'
    assert supplier.opening is None


def test_cost_uses_cumulative_provider_or_unfinalized_tail():
    assert settle_cost(0, 0) == 0
    assert settle_cost(64001, 1) == 990
    assert settle_cost(3200, 3) == 990


def test_idle_timeout_closes_and_releases(relay):
    client, ai, speech, supplier, headers, user = relay
    speech.audio_idle_timeout = 0.05
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(start());ws.receive_json()
        assert ws.receive_json()['header']['error_code'] == 'idle'
    assert supplier.closed
    assert ai.budget.status(user['id'])['day']['held'] == 0


def test_absolute_deadline_accounts_tail(relay):
    client, ai, speech, supplier, headers, user = relay
    speech.session_timeout = 0.1
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(start());ws.receive_json()
        ws.send_bytes(bytes(3200))
        assert ws.receive_json()['header']['error_code'] == 'idle'
    balance = ai.budget.status(user['id'])['day']
    assert balance['held'] == 0 and balance['used'] == 330
    assert supplier.closed


def test_replay_identifier_does_not_bill_twice(relay):
    client, ai, speech, supplier, headers, user = relay
    request = start()
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(request);ws.receive_json()
        ws.send_bytes(bytes(3200));ws.send_json(finish(request['header']['task_id']))
        ws.receive_json();ws.receive_json()
    with client.websocket_connect('/ai/speech', headers=headers) as ws:
        ws.send_json(request)
        assert ws.receive_json()['header']['error_code'] == 'duplicate'
    assert ai.budget.status(user['id'])['day']['used'] == 330


def test_config_preserves_unrelated_settings_and_replaces_duplicates():
    from deploy.configure_shared_speech_key import update_text
    original = '# keep comment\nDUT_HOST=example\nALIYUN_API_KEY=old\nSHARED_SPEECH_ENABLED=0\nALIYUN_API_KEY=old2\n'
    result = update_text(original, 'safe-fixture')
    assert '# keep comment\nDUT_HOST=example\n' in result
    assert result.count('ALIYUN_API_KEY=') == 1
    assert 'ALIYUN_API_KEY=safe-fixture\n' in result
    assert 'SHARED_SPEECH_ENABLED=1\n' in result


def test_config_accepts_legacy_and_long_dotted_workspace_keys():
    from deploy.configure_shared_speech_key import read_key
    # Synthetic values only: no real credential is used by these tests.
    legacy = 'sk-' + 'a' * 32
    workspace = 'sk-ws-' + 'a' * 350 + '.' + 'B' * 250 + '.fake_-/+=~'
    assert read_key(io.StringIO(legacy)) == legacy
    assert read_key(io.StringIO(workspace)) == workspace


def test_config_rejects_whitespace_controls_and_env_injection():
    from deploy.configure_shared_speech_key import read_key
    fake = 'sk-ws-' + 'a' * 32 + '.fake'
    for value in (fake + '\n', fake + '\r\n', '\n' + fake,
                  fake + '\nOTHER=1', fake + '\t', fake + ' ',
                  fake + '\x00', fake + '中文', fake + '$VALUE', fake + '"', fake + '\\'):
        assert read_key(io.StringIO(value)) is None


def test_config_rejects_overlong_stdin_without_accepting_truncated_prefix():
    from deploy.configure_shared_speech_key import MAX_KEY_LENGTH, read_key
    boundary = 'a' * MAX_KEY_LENGTH
    assert read_key(io.StringIO(boundary)) == boundary
    assert read_key(io.StringIO(boundary + 'b')) is None
    assert read_key(io.StringIO(boundary + 'b' * MAX_KEY_LENGTH)) is None
    assert read_key(io.StringIO('a' * 19)) is None

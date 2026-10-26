import asyncio
import json
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient

from app import create_app
from shared_ai import Budget, LIMITS, SharedTextAi, UpstreamRejected, ZONE


def identifier():
    return str(uuid.uuid4())


@pytest.fixture
def shared(tmp_path, monkeypatch):
    monkeypatch.setenv('SHARED_AI_ENABLED', '1')
    monkeypatch.setenv('DEEPSEEK_API_KEY', 'test-provider-secret-never-forward')
    app = create_app(tmp_path, 'test', lambda *args: None)
    service = app.state.service
    user = {'id': identifier(), 'email': 'fixture@mail.dlut.edu.cn', 'nickname': 'fictional fixture'}
    with service.db() as db:
        db.execute('INSERT INTO users(id,email,nickname,password,created) VALUES(?,?,?,?,?)',
                   (user['id'], user['email'], user['nickname'], 'not-a-password', int(time.time())))
        token = service.token(db, user)['token']
    return TestClient(app), app.state.shared_ai, {'Authorization': 'Bearer ' + token}, user


def body(**more):
    return dict(request_id=identifier(), model='expensive-model', max_tokens=200,
                messages=[dict(role='user', content='只回复：收到')], **more)


def test_auth_and_shared_identity(shared):
    client, ai, headers, user = shared
    assert client.get('/ai/status').json()['reason'] == 'login_required'
    assert client.post('/ai/chat/completions', json=body()).status_code == 401
    assert client.get('/ai/status', headers=headers).json()['available'] is True
    assert client.get('/me', headers=headers).json()['email'] == user['email']
    with ai.service.db() as db:
        db.execute('UPDATE users SET email=? WHERE id=?', ('fixture@example.invalid', user['id']))
    assert client.get('/ai/status', headers=headers).status_code == 403


def test_disabled_and_expired(shared, monkeypatch):
    client, ai, headers, user = shared
    monkeypatch.delenv('SHARED_AI_ENABLED')
    assert client.post('/ai/chat/completions', json=body(), headers=headers).status_code == 503
    assert client.get('/ai/status', headers=headers).json()['reason'] == 'disabled'
    with ai.service.db() as db:
        db.execute('UPDATE sessions SET expires=0')
    assert client.get('/ai/status', headers=headers).status_code == 401


def test_payload_caps_and_vision_rejection(shared):
    client, ai, headers, user = shared
    raw = body();raw['messages'][0]['content'] = [{'type': 'image_url', 'image_url': {'url': 'https://example.invalid'}}]
    assert client.post('/ai/chat/completions', json=raw, headers=headers).json()['detail']['code'] == 'invalid_image'
    raw = body();raw['messages'][0]['content'] = 'a' * 200000
    assert client.post('/ai/chat/completions', json=raw, headers=headers).status_code == 413
    raw = body();raw['max_tokens'] = 16000;raw['tools'] = [{'type': 'web_search'}];raw['base_url'] = 'https://evil.invalid'
    _, payload, reserve = ai.payload(raw)
    assert payload['max_tokens'] == 16000 and payload['model'] == 'deepseek-flash'
    assert 'tools' not in payload and 'base_url' not in payload
    assert payload['thinking'] == {'type': 'disabled'} and payload['stream_options']['include_usage']
    assert reserve < LIMITS['day']


def test_stream_usage_settles_before_done_without_secrets(shared):
    client, ai, headers, user = shared
    async def upstream(payload):
        assert payload['model'] == 'deepseek-flash'
        yield {'choices': [{'delta': {'content': '收到'}, 'finish_reason': None}]}
        yield {'choices': [{'delta': {}, 'finish_reason': 'stop'}],
               'usage': {'prompt_tokens': 100, 'prompt_cache_hit_tokens': 50, 'completion_tokens': 10},
               'internal_debug': 'test-provider-secret-never-forward'}
        yield {'_done': True}
    ai.upstream = upstream
    raw = body()
    response = client.post('/ai/chat/completions', json=raw, headers=headers)
    assert response.status_code == 200 and '[DONE]' in response.text and '收到' in response.text
    assert 'secret' not in response.text and 'internal_debug' not in response.text
    status = client.get('/ai/status', headers=headers).json()
    assert status['day']['used'] == 182 and status['day']['held'] == 0
    assert client.post('/ai/chat/completions', json=raw, headers=headers).status_code == 409
    with ai.budget.db() as db:
        assert 'messages' not in {r['name'] for r in db.execute('PRAGMA table_info(requests)')}


def test_rejection_release_and_unknown_charge(shared):
    client, ai, headers, user = shared
    async def reject(payload):
        if False:yield {}
        raise UpstreamRejected()
    ai.upstream = reject
    result = client.post('/ai/chat/completions', json=body(), headers=headers)
    assert 'dapan_error' in result.text
    assert ai.budget.status(user['id'])['day']['used'] == 0
    async def failure(payload):
        yield {'choices': [{'delta': {'content': 'partial'}}]}
        raise ValueError('private upstream stack must not leak')
    ai.upstream = failure
    result = client.post('/ai/chat/completions', json=body(), headers=headers)
    assert 'private upstream' not in result.text and '[DONE]' not in result.text
    assert ai.budget.status(user['id'])['day']['used'] > 0
    assert ai.budget.status(user['id'])['day']['held'] == 0


def test_consumer_closes_full_queue_settles(shared):
    _, ai, _, user = shared
    async def many(payload):
        for i in range(100):yield {'choices': [{'delta': {'content': 'a'}}]}
    ai.upstream = many
    async def scenario():
        key, payload, maximum = ai.payload(body())
        ai.budget.reserve(key, user['id'], maximum)
        stream = ai.stream(key, payload)
        assert 'connected' in await anext(stream)
        await asyncio.sleep(.05)  # producer fills the bounded queue
        await asyncio.wait_for(stream.aclose(), 1)
        status = ai.budget.status(user['id'])
        assert status['day']['held'] == 0 and status['day']['used'] == maximum
    asyncio.run(scenario())


def test_atomic_user_and_global_caps(tmp_path):
    budget = Budget(tmp_path/'quota.db')
    def call(_):
        key = identifier()
        try:
            budget.reserve(key, 'user', 600000)
            budget.settle(key)
            return True
        except HTTPException:return False
    with ThreadPoolExecutor(8) as pool:
        assert sum(pool.map(call, range(20))) == 1
    assert budget.status('user')['day']['used'] == 600000
    for i in range(9):
        key = identifier();budget.reserve(key, str(i), 1000000);budget.settle(key)
    with pytest.raises(HTTPException) as exc:
        budget.reserve(identifier(), 'last', 500000)
    assert exc.value.detail['code'] == 'site_daily_limit'


def test_period_rollovers_and_weekly_limit(tmp_path):
    now = [datetime(2026, 10, 26, 8, tzinfo=ZONE).timestamp()]
    budget = Budget(tmp_path/'quota.db', lambda: now[0])
    for _ in range(3):
        key=identifier();budget.reserve(key,'u',1000000);budget.settle(key)
        now[0] += 86400
    with pytest.raises(HTTPException) as exc:budget.reserve(identifier(),'u',1)
    assert exc.value.detail['code']=='weekly_limit'
    now[0] = datetime(2026, 11, 2, 0, tzinfo=ZONE).timestamp()
    assert all(q['used']==0 for q in budget.status('u').values())


def test_concurrency_idempotence_and_stale(tmp_path):
    now=[datetime(2026,10,24,8,tzinfo=ZONE).timestamp()]
    budget=Budget(tmp_path/'quota.db',lambda:now[0]);key=identifier()
    budget.reserve(key,'u',20000)
    with pytest.raises(HTTPException) as exc:budget.reserve(key,'u',20000)
    assert exc.value.detail['code']=='duplicate'
    second=identifier();budget.reserve(second,'u',20000)
    budget.settle_amount(second,0)
    now[0]+=301
    status=budget.status('u');assert status['day']['used']==20000 and status['day']['held']==0
    for i in range(64):budget.reserve(identifier(),str(i),20000)
    with pytest.raises(HTTPException) as exc:budget.reserve(identifier(),'another',20000)
    assert exc.value.detail['code']=='busy'


def test_speech_and_text_share_budget_but_may_overlap(tmp_path):
    budget=Budget(tmp_path/'quota.db')
    speech,text=identifier(),identifier()
    budget.reserve(speech,'u',20130,kind='asr')
    budget.reserve(text,'u',30000)
    assert budget.status('u')['day']['held']==50130
    other=identifier();budget.reserve(other,'u',20130,kind='asr');budget.settle_amount(other,0)
    budget.settle_amount(speech,3300)
    budget.settle(text,{'prompt_tokens':20,'completion_tokens':10})
    assert budget.status('u')['day']['used']==3420


def test_inflight_reservation_survives_month_boundary(tmp_path):
    now=[datetime(2026,10,31,23,59,50,tzinfo=ZONE).timestamp()]
    budget=Budget(tmp_path/'quota.db',lambda:now[0]);key=identifier()
    budget.reserve(key,'u',900000)
    now[0]+=20
    assert budget.status('u')['siteMonth']['held']==900000
    with pytest.raises(HTTPException):budget.reserve(identifier(),'u',200000,kind='asr')
    budget.settle_amount(key,800000)
    assert budget.status('u')['siteMonth']['used']==800000
    assert budget.status('u')['day']['remaining']==200000

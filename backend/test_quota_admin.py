import asyncio
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import uuid

import pytest
from fastapi import HTTPException
from PIL import Image

from shared_ai import Budget, LIMITS, LIMIT_CODES, ZONE
from test_shared_ai import shared, body, identifier
from test_shared_speech import relay, start, finish
from shared_speech import MICRO_CNY_PER_SECOND, RESERVATION, start_payload, SpeechError
from speech_vocabulary import SpeechVocabulary


@pytest.mark.parametrize('period', list(LIMITS))
def test_every_cap_is_enforced_before_charging(tmp_path, period):
    budget=Budget(tmp_path/'quota.db')
    caps={k:1_000_000_000 for k in LIMITS};caps[period]=500
    budget.configure(caps)
    first=identifier();budget.reserve(first,'u',400);budget.settle_amount(first,400)
    with pytest.raises(HTTPException) as error:
        budget.reserve(identifier(),'u',101)
    assert error.value.detail['code']==LIMIT_CODES[period]
    assert budget.status('u')[period]['remaining']==100
    if period.startswith('site'):
        with pytest.raises(HTTPException):budget.reserve(identifier(),'another',101)
    else:
        budget.reserve(identifier(),'another',100)


def test_admin_overrides_preserve_usage_and_inherit(tmp_path):
    budget=Budget(tmp_path/'quota.db');first=identifier()
    budget.reserve(first,'u',100);budget.settle_amount(first,100)
    budget.configure({'day':250,'week':1000,'month':3000},'u')
    budget.configure({**LIMITS,'day':500})
    assert budget.status('u')['day']['remaining']==150
    assert budget.status('v')['day']['remaining']==500
    budget.configure({},'u')
    assert budget.status('u')['day']['remaining']==400
    assert budget.status('u')['siteDay']['used']==100
    with budget.db() as db:
        assert db.execute('SELECT COUNT(*) FROM quota_audit').fetchone()[0]==3


def test_concurrent_reservations_respect_site_pool(tmp_path):
    budget=Budget(tmp_path/'quota.db')
    budget.configure({**LIMITS,'siteDay':1000})
    def reserve(i):
        try:budget.reserve(identifier(),str(i),300);return True
        except HTTPException:return False
    with ThreadPoolExecutor(12) as pool:
        assert sum(pool.map(reserve,range(20)))==3
    assert budget.status('u')['siteDay']['held']==900


def test_audio_extension_is_atomic_and_lease_keeps_session(tmp_path):
    now=[datetime(2026,10,26,8,tzinfo=ZONE).timestamp()]
    budget=Budget(tmp_path/'quota.db',lambda:now[0]);budget.configure({**LIMITS,'day':1000})
    task=identifier();budget.reserve(task,'u',300,kind='asr')
    for _ in range(4):
        now[0]+=200;budget.extend(task,500)
    assert budget.status('u')['day']['held']==500
    budget.reserve(identifier(),'u',400)
    with pytest.raises(HTTPException):budget.extend(task,601)
    assert budget.status('u')['day']['held']==900
    budget.settle_amount(task,400)
    assert budget.status('u')['day']['used']==400


def test_legacy_database_migrates_without_losing_charges(tmp_path):
    path=tmp_path/'old.db';now=1760000000
    with sqlite3.connect(path) as db:
        db.execute('CREATE TABLE requests(id TEXT PRIMARY KEY,owner TEXT,created INTEGER,day TEXT,week TEXT,month TEXT,reserved INTEGER,charged INTEGER,state TEXT,input_tokens INTEGER,output_tokens INTEGER)')
        db.execute('INSERT INTO requests VALUES(?,?,?,?,?,?,?,?,?,?,?)',('old','u',now,'2025-10-09','2025-10-06','2025-10-01',100,90,'charged',1,1))
    budget=Budget(path,lambda:now)
    with budget.db() as db:
        row=db.execute('SELECT * FROM requests').fetchone()
        assert row['charged']==90 and row['touched']==now and row['kind']=='text'
    assert budget.configuration()['limits']==LIMITS


def test_admin_routes_require_secret_and_accept_exact_email(shared,monkeypatch):
    client,ai,headers,user=shared
    monkeypatch.setenv('ADMIN_TOKEN','fixture-admin-token-not-a-real-key')
    admin={'X-Admin-Token':'fixture-admin-token-not-a-real-key'}
    for path in ['/admin/ai/config','/admin/ai/users','/admin/ai/user?email='+user['email']]:
        assert client.get(path,headers=headers).status_code==403
        assert client.get(path,headers={'X-Admin-Token':'wrong'}).status_code==403
    assert client.get('/admin/ai/config',headers=admin).json()['limits']==LIMITS
    before=identifier();ai.budget.reserve(before,user['id'],100);ai.budget.settle_amount(before,100)
    response=client.put('/admin/ai/user',headers=admin,json={'email':user['email'].upper(),'limits':{'day':200,'week':300,'month':400}})
    assert response.status_code==200 and response.json()['usage']['day']['remaining']==100
    assert client.get('/ai/status',headers=headers).json()['month']['limit']==400
    assert client.put('/admin/ai/user',headers=admin,json={'email':user['email'],'limits':{'siteMonth':100}}).status_code==422
    assert client.put('/admin/ai/user',headers=admin,json={'email':'unknown@mail.dlut.edu.cn','limits':{}}).status_code==404
    assert client.get('/admin/ai/users?q=%25',headers=admin).json()['users']==[]
    assert 'password' not in client.get('/admin/ai/users',headers=admin).text


@pytest.mark.parametrize('value',[-1,True,1.5,'100',1_000_000_000_001])
def test_bad_limits_never_partially_apply(tmp_path,value):
    budget=Budget(tmp_path/'quota.db')
    with pytest.raises(HTTPException):budget.configure({'day':value})
    assert budget.configuration()['limits']==LIMITS


def test_live_held_requests_carry_across_day_week_and_month(tmp_path):
    now=[datetime(2026,11,30,23,59,50,tzinfo=ZONE).timestamp()]
    budget=Budget(tmp_path/'q.db',lambda:now[0]);task=identifier()
    budget.reserve(task,'u',100)
    now[0]+=20
    assert all(row['held']==100 for row in budget.status('u').values())
    budget.settle_amount(task,90)
    assert all(row['used']==90 and row['held']==0 for row in budget.status('u').values())


def test_vision_uses_verified_image_and_conservative_token_bound(shared):
    _,ai,_,_=shared
    image=io.BytesIO();Image.new('RGB',(10,10),'white').save(image,format='PNG')
    url='data:image/png;base64,'+base64.b64encode(image.getvalue()).decode()
    request=body();request['messages'][0]['content']=[{'type':'text','text':'读图'},{'type':'image_url','image_url':{'url':url}}]
    _,payload,reservation=ai.payload(request)
    assert payload['messages'][0]['content'][1]['image_url']['url']==url
    assert reservation >= 1024*2+200*8
    request['messages'][0]['role']='system'
    with pytest.raises(HTTPException):ai.payload(request)
    request['messages'][0]['role']='user';request['messages'][0]['content'][1]['image_url']['url']='data:image/png;base64,bm90YW5pbWFnZQ=='
    with pytest.raises(HTTPException):ai.payload(request)


def test_audio_stops_before_forwarding_unfunded_segment(relay):
    client,ai,speech,supplier,headers,user=relay
    ai.budget.configure({'day':RESERVATION,'week':RESERVATION,'month':RESERVATION},user['id'])
    with client.websocket_connect('/ai/speech',headers=headers) as ws:
        ws.send_json(start());assert ws.receive_json()['header']['event']=='task-started'
        for _ in range(30):ws.send_bytes(bytes(16000))
        assert ws.receive_json()['header']['error_code']=='daily_limit'
    assert supplier.bytes < 10*32000
    assert ai.budget.status(user['id'])['day']['used'] <= RESERVATION
    assert ai.budget.status(user['id'])['day']['held']==0


def test_language_and_true_hotword_payload_is_whitelisted():
    raw=start();raw['payload']['parameters'].update(language_hints=['fr'],vocabulary_id='foreign-key-vocab',max_sentence_silence=800)
    raw['payload']['dapan_hotwords']=['玉兰卡','数科导']
    _,value=start_payload(raw)
    assert value['payload']['parameters']['language_hints']==['fr']
    assert 'vocabulary_id' not in value['payload']['parameters']
    assert value['payload']['dapan_hotwords']==['玉兰卡','数科导']
    raw['payload']['parameters']['language_hints']=['zh','en']
    with pytest.raises(SpeechError):start_payload(raw)


def test_vocabulary_cache_reuses_compilation_and_fails_open(tmp_path):
    budget=Budget(tmp_path/'q.db');calls=[]
    async def provider(action,**args):
        calls.append(action)
        return {'list_vocabulary':{'vocabulary_list':[]},'create_vocabulary':{'vocabulary_id':'vocab-fixture'},'query_vocabulary':{'status':'OK'}}[action]
    vocab=SpeechVocabulary(budget,provider)
    async def scenario():
        first=await vocab.acquire(['玉兰卡']);second=await vocab.acquire(['玉兰卡'])
        assert first==second=='vocab-fixture' and calls.count('create_vocabulary')==1
        vocab.release(first);vocab.release(second)
        async def broken(*args,**kwargs):raise ValueError('SECRET')
        vocab.call=broken
        assert await vocab.acquire(['other']) is None
    asyncio.run(scenario())
    with budget.db() as db:
        assert '玉兰卡' not in str([tuple(row) for row in db.execute('SELECT * FROM speech_vocabulary')])


def test_local_console_rejects_cross_origin_and_rebinding():
    path=Path(__file__).resolve().parents[1]/'tools/admin/quota_console.py'
    if not path.is_file():
        pytest.skip('Desktop console is not shipped to the production backend')
    spec=importlib.util.spec_from_file_location('local_quota_console',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    valid={'Host':'127.0.0.1:18765','Origin':module.ORIGIN,'X-Local-CSRF':'fixture'}
    assert module.valid_request(valid,'PUT','fixture')
    assert not module.valid_request({**valid,'Host':'attacker.invalid'},'PUT','fixture')
    assert not module.valid_request({**valid,'Origin':'https://attacker.invalid'},'PUT','fixture')
    assert not module.valid_request({'Host':valid['Host'],'X-Local-CSRF':'fixture'},'PUT','fixture')
    assert not module.valid_request({**valid,'X-Local-CSRF':'wrong'},'GET','fixture')
    assert module.allowed_path('/admin/ai/users?q=test%40mail.dlut.edu.cn')
    assert not module.allowed_path('http://attacker.invalid/admin/ai/config')
    assert not module.allowed_path('/admin/reports')


def test_large_chat_body_uses_own_limit_without_relaxing_other_endpoints(shared):
    client,ai,headers,user=shared
    payload=b'x'*(4*1024*1024)
    # Authentication still occurs before any provider request or parsing of large input.
    assert client.post('/ai/chat/completions',content=payload).status_code==401
    assert client.post('/auth/login',content=payload).status_code==413
    assert client.post('/ai/chat/completions',content=b'x'*(32*1024*1024+1)).status_code==413

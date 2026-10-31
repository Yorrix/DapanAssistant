import copy
import json
import sqlite3
import uuid
from concurrent.futures import ThreadPoolExecutor

import pytest
from fastapi.testclient import TestClient
from app import create_app
from conversation_feedback import Feedback, MAX_BYTES


@pytest.fixture
def feedback(tmp_path, monkeypatch):
    monkeypatch.setenv('ADMIN_TOKEN', 'synthetic-feedback-admin')
    app = create_app(tmp_path, 'test', lambda *_: None)
    return TestClient(app), app, {'X-Admin-Token': 'synthetic-feedback-admin'}


def report(**changes):
    return dict(schemaVersion=1, reportId=str(uuid.uuid4()), installationId=str(uuid.uuid4()), consent=True,
                appVersion='1.0.1', versionCode=101, device='Synthetic phone', androidVersion='16', screen='日历',
                messages=[dict(role='user', text='上完英语课提醒我取快递', time=1790000000000, operation=''),
                          dict(role='assistant', text='已创建', time=1790000000100, operation='已执行 · 新增 1 项\n提醒：12:00')]) | changes


def test_submit_list_detail_review_and_anonymous_access(feedback):
    client, app, admin = feedback
    body = report()
    response = client.post('/feedback/conversations', json=body)
    assert response.status_code == 200 and response.json()['received'] is True
    id = response.json()['id']
    for path in ['/admin/feedback', '/admin/feedback/'+id]:
        assert client.get(path).status_code == 403
        assert client.get(path, headers={'X-Admin-Token':'wrong'}).status_code == 403
    assert client.put('/admin/feedback/'+id, json={'status':'resolved','revision':0}).status_code == 403
    listing = client.get('/admin/feedback', headers=admin).json()
    assert listing['counts'] == {'new':1}
    assert listing['items'][0]['title'] == body['messages'][0]['text']
    assert 'payload' not in listing['items'][0] and 'owner' not in listing['items'][0]
    detail = client.get('/admin/feedback/'+id, headers=admin).json()
    assert detail['report']['messages'] == body['messages']
    assert 'installationId' not in detail['report']
    assert client.put('/admin/feedback/'+id, json={'status':'reviewing','note':'已复现，正在修复','revision':0}, headers=admin).status_code == 200
    assert client.put('/admin/feedback/'+id, json={'status':'resolved','revision':0}, headers=admin).status_code == 409
    assert client.put('/admin/feedback/'+id, json={'status':'resolved','note':'1.0.2 修复','revision':1}, headers=admin).status_code == 200
    assert client.get('/admin/feedback?status=new', headers=admin).json()['items'] == []
    assert client.get('/admin/feedback/'+id, headers=admin).json()['note'] == '1.0.2 修复'
    with app.state.service.db() as db:
        assert db.execute('SELECT COUNT(*) FROM users').fetchone()[0] == 0


@pytest.mark.parametrize('changes', [{'consent':False},{'consent':1},{'schemaVersion':True},{'schemaVersion':2},
    {'messages':[]},{'undo':{'private':'data'}},{'reportId':'x'*36},
    {'messages':[dict(role='system',text='hidden prompt',time=0)]},
    {'messages':[dict(role='user',text='hello',time=0,evidence='private')]}])
def test_consent_schema_and_private_fields_are_enforced(feedback,changes):
    client, app, _ = feedback
    assert client.post('/feedback/conversations', json=report(**changes)).status_code == 422
    with app.state.feedback.db() as db:
        assert db.execute('SELECT COUNT(*) FROM feedback').fetchone()[0] == 0


def test_retries_are_idempotent_concurrent_and_payload_bound(feedback):
    _, app, _ = feedback
    body = report()
    with ThreadPoolExecutor(8) as pool:
        results = list(pool.map(lambda _: app.state.feedback.submit(Feedback.model_validate(body),'127.0.0.1'),range(8)))
    assert len({r['id'] for r in results}) == 1
    client = TestClient(app)
    bad = copy.deepcopy(body);bad['messages'][0]['text']='different'
    assert client.post('/feedback/conversations',json=bad).status_code==409
    bad = dict(body,installationId=str(uuid.uuid4()))
    assert client.post('/feedback/conversations',json=bad).status_code==409
    with app.state.feedback.db() as db:
        assert db.execute('SELECT COUNT(*) FROM feedback').fetchone()[0]==1


def test_credentials_redacted_but_not_unrelated_conversation(feedback):
    client,app,admin=feedback
    body=report(messages=[dict(role='user',text='玉兰卡 B01 API_KEY: synthetic-sensitive-key Bearer synthetic-access-token sk-abcdefghijklmnopqrstuv',time=0)])
    assert client.post('/feedback/conversations',json=body).status_code==200
    raw=client.get('/admin/feedback/'+body['reportId'],headers=admin).text
    assert '玉兰卡 B01' in raw and '[已隐藏密钥]' in raw
    for secret in ('synthetic-sensitive-key','synthetic-access-token','sk-abcdefghijklmnopqrstuv',body['installationId']):
        assert secret not in raw
        with app.state.feedback.db() as db:
            assert secret not in db.execute('SELECT payload FROM feedback').fetchone()[0]


def test_size_limits_no_partial_storage_or_echo(feedback):
    client,app,admin=feedback
    assert client.post('/feedback/conversations',content=b'x'*(MAX_BYTES+1)).status_code==413
    body=report(messages=[dict(role='user',text='sensitive'*13000,time=0)])
    response=client.post('/feedback/conversations',json=body)
    assert response.status_code==422 and 'sensitive' not in response.text
    with app.state.feedback.db() as db:
        assert db.execute('SELECT COUNT(*) FROM feedback').fetchone()[0]==0


def test_rate_limit_does_not_block_retry_or_old_endpoints(feedback):
    client,app,_=feedback
    installation=str(uuid.uuid4());first=None
    for _ in range(10):
        body=report(installationId=installation);first=first or body
        assert client.post('/feedback/conversations',json=body).status_code==200
    assert client.post('/feedback/conversations',json=report(installationId=installation)).status_code==429
    assert client.post('/feedback/conversations',json=first).status_code==200
    assert client.get('/health').status_code==200
    assert client.get('/ai/status').status_code==200


def test_keyset_pagination_literal_search_and_status_validation(feedback):
    client,app,admin=feedback
    ids=[]
    for _ in range(4):
        body=report();ids.append(body['reportId']);client.post('/feedback/conversations',json=body)
    first=client.get('/admin/feedback?limit=2',headers=admin).json()
    second=client.get('/admin/feedback?limit=2&before='+str(first['next']),headers=admin).json()
    assert [r['id'] for r in first['items']+second['items']]==ids[::-1]
    assert second['next'] is None
    assert client.get('/admin/feedback?q=%25',headers=admin).json()['items']==[]
    assert client.get('/admin/feedback?status=bad',headers=admin).status_code==422


def test_existing_database_and_sessions_survive_module_install(tmp_path,monkeypatch):
    from app import Service
    service=Service(tmp_path,'test',lambda *_:None)
    with service.db() as db:
        user={'id':'existing-user','email':'existing@mail.dlut.edu.cn','nickname':'Existing'}
        db.execute('INSERT INTO users(id,email,nickname,password,created) VALUES(?,?,?,?,?)',('existing-user',user['email'],'Existing','not-used',1))
        token=service.token(db,user)['token']
    app=create_app(tmp_path,'test',lambda *_:None)
    with TestClient(app) as client:
        assert client.get('/me',headers={'Authorization':'Bearer '+token}).json()['id']=='existing-user'
        assert client.get('/health').json()['ok'] is True

import base64, io, time, uuid
import pytest
from fastapi.testclient import TestClient
from PIL import Image
from bs4 import BeautifulSoup
from app import create_app
from crawl_notices import extract,SOURCES

def test_import_time_application_uses_isolated_test_directory():
 import os,pathlib
 from app import app,ROOT
 assert app.state.service.mode=='test'
 assert app.state.service.root.resolve()==pathlib.Path(os.environ['DAPAN_DATA']).resolve()
 assert app.state.service.root.resolve()!=(ROOT/'data').resolve()

def test_external_network_and_real_mail_are_blocked():
 import socket,smtplib
 with pytest.raises(AssertionError,match='external DNS'):
  socket.getaddrinfo('example.invalid',443)
 with pytest.raises(AssertionError,match='external services'):
  with socket.socket() as connection:connection.connect(('192.0.2.1',443))
 with pytest.raises(AssertionError,match='in-memory mail'):
  smtplib.SMTP_SSL('smtp.example.invalid',465)

@pytest.fixture
def setup(tmp_path):
 outbox={}
 app=create_app(tmp_path,'test',lambda email,code,purpose:outbox.__setitem__((email,purpose),code))
 return TestClient(app),app.state.service,outbox

def account(setup):
 client,service,outbox=setup;email=f'{uuid.uuid4().hex[:12]}@mail.dlut.edu.cn'
 assert client.post('/auth/code',json={'email':email}).status_code==200
 code=outbox[email,'register']
 result=client.post('/auth/register',json={'email':email,'code':code,'nickname':'测试同学','password':'testing-password-123'})
 assert result.status_code==200,result.text
 return email,{'Authorization':'Bearer '+result.json()['token']}

def item(**overrides):
 return dict(title='九成新台灯',description='使用一学期，功能正常，无明显瑕疵。',priceCents=2500,category='生活',condition='九成新',location='图书馆门口',contacts={'微信':'demo_contact'},images=[])|overrides

def test_school_domains_are_exact(setup):
 c,_,_=setup
 for email in ['a@qq.com','x@mail.dlut.edu.cn.evil.com','x@evil-dlut.edu.cn','x@sub.dlut.edu.cn']:
  assert c.post('/auth/code',json={'email':email}).status_code==422
 assert c.post('/auth/code',json={'email':'teacher@dlut.edu.cn'}).status_code==200

def test_restored_radar_rejects_obsolete_cache_without_server_error(setup):
 c,s,_=setup
 (s.root/'reset-radar.json').write_text('{"version":1,"items":[]}',encoding='utf-8')
 response=c.get('/reset-radar')
 assert response.status_code==200
 assert response.json()['stale'] is True
 assert response.json()['data'] is None
 assert '/reset-radar' in c.get('/openapi.json').json()['paths']
 assert c.get('/health').json()['ok'] is True

def test_registration_code_is_single_use_and_password_login(setup):
 c,s,out=setup;email,header=account(setup)
 assert 'code' not in c.post('/auth/code',json={'email':'new@mail.dlut.edu.cn'}).json()
 assert c.get('/me',headers=header).json()['email']==email
 assert c.post('/auth/login',json={'email':email,'password':'testing-password-123'}).status_code==200
 assert c.post('/auth/login',json={'email':email,'password':'wrong-password'}).status_code==401
 assert c.post('/auth/register',json={'email':email,'code':out[email,'register'],'nickname':'Again','password':'new-password-123'}).status_code==400
 with s.db() as db:
  assert db.execute('SELECT 1 FROM codes WHERE email=?',(email,)).fetchone() is None
  assert 'testing-password' not in db.execute('SELECT password FROM users WHERE email=?',(email,)).fetchone()[0]

def test_wrong_code_lockout_persists(setup):
 c,s,out=setup;email='lock@mail.dlut.edu.cn';c.post('/auth/code',json={'email':email})
 body={'email':email,'code':'000000' if out[email,'register']!='000000' else '111111','nickname':'测试','password':'testing-password'}
 for _ in range(5):assert c.post('/auth/register',json=body).status_code==400
 body['code']=out[email,'register'];assert c.post('/auth/register',json=body).status_code==400
 with s.db() as db:assert db.execute('SELECT attempts FROM codes WHERE email=?',(email,)).fetchone()[0]==5

def test_expired_code_rejected(setup):
 c,s,out=setup;email='expired@mail.dlut.edu.cn';c.post('/auth/code',json={'email':email})
 with s.db() as db:db.execute('UPDATE codes SET expires=0')
 assert c.post('/auth/register',json={'email':email,'code':out[email,'register'],'nickname':'测试','password':'testing-password'}).status_code==400

def test_resend_rate_limited(setup):
 c,_,_=setup
 assert c.post('/auth/code',json={'email':'slow@mail.dlut.edu.cn'}).status_code==200
 assert c.post('/auth/code',json={'email':'slow@mail.dlut.edu.cn'}).status_code==429

def test_logout_invalidates_session(setup):
 c,_,_=setup;_,h=account(setup)
 assert c.post('/auth/logout',headers=h).status_code==200
 assert c.get('/me',headers=h).status_code==401

def test_reset_revokes_existing_sessions(setup):
 c,s,out=setup;email,h=account(setup)
 with s.db() as db:db.execute('DELETE FROM limits')
 assert c.post('/auth/code',json={'email':email,'purpose':'reset'}).status_code==200
 assert c.post('/auth/reset',json={'email':email,'code':out[email,'reset'],'password':'replacement-password-123'}).status_code==200
 assert c.get('/me',headers=h).status_code==401
 assert c.post('/auth/login',json={'email':email,'password':'replacement-password-123'}).status_code==200

def test_cannot_publish_without_login(setup):
 assert setup[0].post('/listings',json=item()).status_code==401

def test_listing_has_no_account_email_and_selected_contacts_only(setup):
 c,_,_=setup;email,h=account(setup)
 result=c.post('/listings',headers=h,json=item());assert result.status_code==200
 identifier=result.json()['id'];listing=c.get('/listings').json()['items'][0]
 assert 'contacts' not in listing and email not in str(listing)
 detail=c.get('/listings/'+identifier).json();assert detail['contacts']=={'微信':'demo_contact'} and email not in str(detail)

def test_other_user_cannot_edit_or_withdraw(setup):
 c,_,_=setup;_,owner=account(setup);_,other=account(setup)
 identifier=c.post('/listings',headers=owner,json=item()).json()['id']
 assert c.put('/listings/'+identifier,headers=other,json=item()).status_code==403
 assert c.patch('/listings/'+identifier,headers=other,json={'status':'sold'}).status_code==403
 edited=c.put('/listings/'+identifier,headers=owner,json=item(title='更正后的台灯'))
 assert edited.status_code==200 and edited.json()['id']==identifier
 assert c.patch('/listings/'+identifier,headers=owner,json={'status':'sold'}).status_code==200
 assert c.get('/listings/'+identifier).json()['contacts']=={}
 assert c.get('/listings').json()['items']==[]

def test_duplicate_listing_and_contact_validation(setup):
 c,_,_=setup;_,h=account(setup)
 assert c.post('/listings',headers=h,json=item(contacts={})).status_code==422
 assert c.post('/listings',headers=h,json=item(contacts={'手机':'not-a-phone'})).status_code==422
 assert c.post('/listings',headers=h,json=item()).status_code==200
 assert c.post('/listings',headers=h,json=item()).status_code==409

def test_report_is_deduplicated_and_admin_can_hide(setup,monkeypatch):
 c,s,_=setup;_,owner=account(setup);_,other=account(setup)
 identifier=c.post('/listings',headers=owner,json=item()).json()['id']
 for _ in range(2):assert c.post('/listings/'+identifier+'/report',headers=other,json={'reason':'疑似虚假信息，请核查'}).status_code==200
 with s.db() as db:assert db.execute('SELECT COUNT(*) FROM reports').fetchone()[0]==1
 assert c.get('/admin/reports').status_code==403
 monkeypatch.setenv('ADMIN_TOKEN','test-admin-secret')
 assert c.get('/admin/reports',headers={'X-Admin-Token':'test-admin-secret'}).status_code==200
 assert c.post('/admin/listings/'+identifier+'/hide',headers={'X-Admin-Token':'test-admin-secret'}).status_code==200
 assert c.get('/listings/'+identifier).status_code==404

def test_image_normalization_and_ownership(setup):
 c,_,_=setup;_,owner=account(setup);_,other=account(setup)
 raw=io.BytesIO();Image.new('RGB',(200,100),(30,60,90)).save(raw,'PNG')
 result=c.post('/images',headers=owner,json={'data':base64.b64encode(raw.getvalue()).decode()});assert result.status_code==200
 identifier=result.json()['id']
 response=c.get('/images/'+identifier);assert response.status_code==200 and response.content[:2]==b'\xff\xd8'
 assert c.post('/listings',headers=other,json=item(images=[identifier])).status_code==422
 assert c.post('/listings',headers=owner,json=item(images=[identifier])).status_code==200
 assert c.post('/images',headers=owner,json={'data':'not-base64'}).status_code==422

def test_unconfigured_mail_is_not_fake_success(tmp_path,monkeypatch):
 for name in ['SMTP_HOST','SMTP_USER','SMTP_PASSWORD','SMTP_FROM']:monkeypatch.delenv(name,raising=False)
 app=create_app(tmp_path);c=TestClient(app)
 assert c.get('/health').json()['emailConfigured'] is False
 monkeypatch.setenv('SMTP_HOST','smtp.example.invalid')
 assert c.get('/health').json()['emailConfigured'] is False
 assert c.post('/auth/code',json={'email':'test@mail.dlut.edu.cn'}).status_code==503
 with app.state.service.db() as db:assert db.execute('SELECT COUNT(*) FROM codes').fetchone()[0]==0

def test_notice_date_ignores_event_date_inside_body():
 html='<html><meta name="pageTitle" content="活动通知"><span>日期：2026年10月13日</span><div id="vsb_content"><p>比赛时间：2026年10月28日。请同学们报名参加本次活动。</p></div></html>'
 result=extract(BeautifulSoup(html,'html.parser'),'https://dli.dlut.edu.cn/info/1/2.htm',SOURCES[0])
 assert result['date']=='2026-10-13' and result['title']=='活动通知'

def test_notice_missing_body_has_no_invented_summary():
 result=extract(BeautifulSoup('<title>图片通知-学院</title>','html.parser'),'https://dli.dlut.edu.cn/info/1/2.htm',SOURCES[0])
 # Extraction returns source text; AI summaries are generated in the subsequent stage.
 assert result['body']=='' and result['date']=='' and 'summary' not in result

def test_notice_nested_duplicate_ids_preserve_header_date():
 html='<title>讲座通知-部门</title><div id="vsb_content"><h1>讲座通知</h1><span>发布日期：2026-09-21</span><div id="vsb_content"><div class="v_news_content">活动时间：2026-10-01。正文说明有足够长度。</div></div></div>'
 result=extract(BeautifulSoup(html,'html.parser'),'https://pjteach.dlut.edu.cn/info/1/2.htm',SOURCES[5])
 assert result['date']=='2026-09-21' and '发布日期' not in result['body']

def test_notice_bare_date_before_click_counter():
 html='<title>通知-学院</title><span>2026-09-30 点击：[ ]</span><div class="v_news_content">比赛时间：2026-10-03。欢迎参加活动。</div>'
 assert extract(BeautifulSoup(html,'html.parser'),'https://dli.dlut.edu.cn/info/1/2.htm',SOURCES[0])['date']=='2026-09-30'

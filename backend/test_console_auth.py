"""Synthetic administrator-only tests. Never use the real local credentials or server."""
import http.client
import importlib.util
import json
from pathlib import Path
import re
import threading

import pytest

PATH=Path(__file__).resolve().parents[1]/'tools/admin/quota_console.py'
if not PATH.is_file():
    pytest.skip('Desktop-only administrator console',allow_module_level=True)
spec=importlib.util.spec_from_file_location('quota_console_auth_test',PATH)
console=importlib.util.module_from_spec(spec);spec.loader.exec_module(console)
from console_auth import AdminAuth,AuthError,IDLE_SECONDS,SESSION_SECONDS,LOGIN_WINDOW

PASSWORD='Synthetic administrator passphrase 063'


def test_password_hash_single_admin_and_session_revocation(tmp_path):
    auth=AdminAuth(tmp_path/'auth');token=auth.setup('owner',PASSWORD)
    text=auth.file.read_text();assert PASSWORD not in text and 'scrypt' in text
    assert auth.session(token)
    with pytest.raises(AuthError) as error:auth.setup('other',PASSWORD)
    assert error.value.status==409
    new=auth.login('owner',PASSWORD)
    assert auth.session(new) and auth.session(token) is None
    auth.logout(new);assert auth.session(new) is None
    restored=AdminAuth(tmp_path/'auth')
    assert restored.configured and restored.session(new) is None
    assert restored.session(restored.login('owner',PASSWORD))


def test_login_throttling_and_expiry(tmp_path):
    now=[0.0];auth=AdminAuth(tmp_path/'auth',lambda:now[0]);token=auth.setup('owner',PASSWORD)
    for _ in range(5):
        with pytest.raises(AuthError) as error:auth.login('owner','bad')
        assert error.value.status==401
    with pytest.raises(AuthError) as error:auth.login('owner',PASSWORD)
    assert error.value.status==429
    now[0]+=LOGIN_WINDOW+1;token=auth.login('owner',PASSWORD)
    now[0]+=IDLE_SECONDS;assert auth.session(token) is None
    token=auth.login('owner',PASSWORD)
    for _ in range(SESSION_SECONDS//100):
        now[0]+=100;auth.session(token)
    now[0]+=100;assert auth.session(token) is None


def test_corrupt_credentials_fail_closed(tmp_path):
    auth=AdminAuth(tmp_path/'auth');auth.file.write_text('{}')
    with pytest.raises(RuntimeError):AdminAuth(tmp_path/'auth')


@pytest.fixture
def http_console(tmp_path,monkeypatch):
    server=console.Console('synthetic-token-never-on-web',tmp_path/'auth',port=0)
    port=server.server_address[1]
    monkeypatch.setattr(console,'ORIGIN',f'http://127.0.0.1:{port}')
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    def call(method,path,body=None,headers=None):
        connection=http.client.HTTPConnection('127.0.0.1',port,timeout=5)
        try:
            connection.request(method,path,body=json.dumps(body).encode() if body is not None else None,headers=headers or {})
            response=connection.getresponse();return response.status,dict(response.getheaders()),response.read().decode()
        finally:connection.close()
    yield server,call
    server.shutdown();server.server_close();thread.join(timeout=2)


def test_http_requires_login_preserves_csrf_and_secure_cookie(http_console):
    server,call=http_console
    status,headers,html=call('GET','/')
    assert status==200 and "mode='setup'" in html
    assert 'synthetic-token-never-on-web' not in html and '全站用量' not in html
    assert call('GET','/admin/ai/config')[0]==401
    auth_headers={'Origin':console.ORIGIN,'X-Local-CSRF':server.csrf,'Content-Type':'application/json'}
    body={'username':'owner','password':PASSWORD,'confirmPassword':PASSWORD}
    assert call('POST','/auth/setup',body,{**auth_headers,'Origin':'https://evil.invalid'})[0]==403
    assert call('POST','/auth/setup',body,{**auth_headers,'X-Local-CSRF':'wrong'})[0]==403
    status,headers,raw=call('POST','/auth/setup',body,auth_headers)
    assert status==200 and PASSWORD not in raw
    cookie=headers['Set-Cookie'];assert all(x in cookie for x in ['HttpOnly','SameSite=Strict','Path=/','Max-Age=28800'])
    cookie_header={'Cookie':cookie.split(';')[0]}
    status,_,html=call('GET','/',headers=cookie_header)
    assert status==200 and '全站用量' in html and 'synthetic-token-never-on-web' not in html
    csrf=re.search(r"const csrf='([^']+)'",html).group(1)
    assert csrf!=server.csrf
    assert call('PUT','/admin/ai/config',{}, {**cookie_header,**auth_headers})[0]==403
    assert call('POST','/auth/setup',body,auth_headers)[0]==409
    logout_headers={**cookie_header,'Origin':console.ORIGIN,'X-Local-CSRF':csrf}
    assert call('POST','/auth/logout',{},logout_headers)[0]==200
    assert call('GET','/admin/ai/config',headers=cookie_header)[0]==401
    assert "mode='login'" in call('GET','/')[2]

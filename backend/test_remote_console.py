"""Synthetic tests for the public administrator boundary; no real password or records."""
import json
from pathlib import Path
import re
import sys

import httpx
import pytest
from fastapi.testclient import TestClient

SOURCE = Path(__file__).resolve().parents[1] / 'tools/admin'
if not (SOURCE / 'remote_console.py').exists():
    SOURCE = Path(__file__).resolve().parent  # Isolated deployment staging directory.
sys.path.insert(0, str(SOURCE))
from console_auth import AdminAuth, IDLE_SECONDS
from remote_console import create_app, COOKIE

ORIGIN = 'https://admin.example.test'
PASSWORD = 'Synthetic administrator passphrase 101'
TOKEN = 'synthetic-upstream-token-never-in-browser'


@pytest.fixture
def console(tmp_path):
    auth = AdminAuth(tmp_path / 'auth')
    auth.setup('owner', PASSWORD)
    calls = []

    def upstream(request):
        calls.append(request)
        assert request.url.host == '127.0.0.1' and request.url.port == 8765
        assert request.headers['X-Admin-Token'] == TOKEN
        assert 'cookie' not in request.headers and 'authorization' not in request.headers
        return httpx.Response(200, json={'ok': True, 'items': []})

    app = create_app(tmp_path / 'auth', TOKEN, ORIGIN, transport=httpx.MockTransport(upstream))
    with TestClient(app, base_url=ORIGIN) as client:
        yield app, client, calls


def csrf(html):
    return re.search(r"csrf='([^']+)'", html).group(1)


def login(client):
    page = client.get('/manage/feedback')
    response = client.post('/manage/auth/login', json={'username': 'owner', 'password': PASSWORD},
                           headers={'Origin': ORIGIN, 'X-Local-CSRF': csrf(page.text)})
    assert response.status_code == 200
    page = client.get('/manage/feedback')
    return response, {'Origin': ORIGIN, 'X-Local-CSRF': csrf(page.text)}


def test_anonymous_only_login_no_public_setup(console):
    _, client, calls = console
    for path in ['/manage/', '/manage/feedback']:
        page = client.get(path)
        assert page.status_code == 200 and "mode='login'" in page.text
        assert '仅限这台电脑' not in page.text and TOKEN not in page.text
        assert '__BASE__' not in page.text and "fetch('/manage/auth/'" in page.text
        assert 'no-store' in page.headers['cache-control']
        assert "frame-ancestors 'none'" in page.headers['content-security-policy']
        nonce = re.search('nonce="([^"]+)"', page.text).group(1)
        assert 'nonce-' + nonce in page.headers['content-security-policy']
    assert client.get('/manage/feedback', headers={'Sec-Fetch-Site': 'cross-site'}).status_code == 200
    assert client.get('/manage/admin/feedback').status_code == 401
    assert client.get('/manage/admin/ai/config').status_code == 401
    for path in ['/manage/auth/setup', '/manage/docs', '/manage/openapi.json', '/admin/feedback']:
        assert client.get(path).status_code == 404
    assert client.post('/manage/auth/setup', json={}, headers={'Origin': ORIGIN}).status_code == 404
    assert not calls


def test_credentials_cookie_login_and_logout(console):
    _, client, calls = console
    result, headers = login(client)
    cookie = result.headers['set-cookie']
    assert all(part in cookie for part in [COOKIE, 'HttpOnly', 'Secure', 'SameSite=strict', 'Path=/', 'Max-Age=28800'])
    page = client.get('/manage/feedback')
    assert '处理状态' in page.text and 'href="/manage/"' in page.text
    assert "fetch('/manage'+path" in page.text and TOKEN not in page.text
    assert 'href="/manage/feedback"' in client.get('/manage/').text
    assert client.get('/manage/admin/feedback?status=new', headers=headers).status_code == 200
    assert calls[-1].url.path == '/admin/feedback' and calls[-1].url.query == b'status=new'
    assert client.get('/manage/admin/ai/config', headers=headers).status_code == 200
    assert client.post('/manage/auth/logout', headers=headers).status_code == 200
    assert client.get('/manage/admin/feedback', headers=headers).status_code == 401


def test_csrf_origin_host_and_scope_cannot_be_bypassed(console):
    _, client, calls = console
    _, headers = login(client)
    assert client.get('/manage/admin/feedback').status_code == 403
    assert client.get('/manage/admin/feedback', headers={**headers, 'Host': 'evil.test'}).status_code == 403
    assert client.get('/manage/admin/feedback', headers={**headers, 'Origin': 'https://evil.test'}).status_code == 403
    assert client.get('/manage/admin/feedback', headers={**headers, 'Sec-Fetch-Site': 'cross-site'}).status_code == 403
    assert client.put('/manage/admin/ai/config', json={}, headers={'X-Local-CSRF': headers['X-Local-CSRF']}).status_code == 403
    assert client.put('/manage/admin/ai/config', json={}, headers={**headers, 'X-Local-CSRF': 'bad'}).status_code == 403
    for path in ['/manage/admin/env', '/manage/admin/feedback/export', '/manage/admin/feedback/../ai/other',
                 '/manage/admin//evil.test', '/manage/admin/feedback/%2e%2e/env']:
        assert client.get(path, headers=headers).status_code == 404
    assert client.put('/manage/admin/ai/users', json={}, headers=headers).status_code == 404
    assert client.get('http://admin.example.test/manage/feedback').status_code == 403
    assert not calls


def test_proxy_put_limits_and_no_private_headers(console):
    _, client, calls = console
    _, headers = login(client)
    path = '/manage/admin/feedback/00000000-0000-4000-8000-000000000001'
    payload = {'revision': 0, 'status': 'reviewing', 'note': 'Synthetic only'}
    result = client.put(path, json=payload, headers=headers)
    assert result.status_code == 200
    assert json.loads(calls[-1].content) == payload
    assert client.put(path, content=b'x' * 20_001, headers=headers).status_code == 413
    assert len(calls) == 1
    assert client.get('/manage/admin/feedback?q=' + 'a'*2049, headers=headers).status_code == 404


def test_login_errors_and_throttle(console):
    _, client, _ = console
    page = client.get('/manage/feedback')
    headers = {'Origin': ORIGIN, 'X-Local-CSRF': csrf(page.text)}
    assert client.post('/manage/auth/login', json={}, headers={'Origin': ORIGIN}).status_code == 403
    assert client.post('/manage/auth/login', content='[', headers=headers).status_code == 400
    assert client.post('/manage/auth/login', content='x'*8193, headers=headers).status_code == 413
    for _ in range(5):
        assert client.post('/manage/auth/login', json={'username': 'owner', 'password': 'bad'}, headers=headers).status_code == 401
    assert client.post('/manage/auth/login', json={'username': 'owner', 'password': PASSWORD}, headers=headers).status_code == 429


def test_expired_session_rejected(console):
    app, client, _ = console
    _, headers = login(client)
    app.state.auth.clock = lambda: 1e12
    assert client.get('/manage/admin/feedback', headers=headers).status_code == 401
    assert "mode='login'" in client.get('/manage/feedback').text


def test_missing_or_corrupt_credentials_fail_closed(tmp_path):
    with pytest.raises(RuntimeError):
        create_app(tmp_path / 'auth', TOKEN, ORIGIN)
    (tmp_path / 'auth/administrator.json').write_text('{}')
    with pytest.raises(RuntimeError):
        create_app(tmp_path / 'auth', TOKEN, ORIGIN)
    with pytest.raises(RuntimeError):
        create_app(tmp_path / 'auth', TOKEN, 'http://admin.example.test')

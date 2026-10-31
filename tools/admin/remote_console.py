"""HTTPS administrator UI; bind only to loopback behind the existing nginx server.

The saved administrator password hash is provisioned over SSH. There is deliberately
no public setup endpoint. The legacy API token stays in this server process.
"""
from contextlib import asynccontextmanager
import hmac
import json
import os
from pathlib import Path
import re
import secrets
from urllib.parse import urlsplit

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from starlette.concurrency import run_in_threadpool

from console_auth import AdminAuth, AuthError, SESSION_SECONDS

HERE = Path(__file__).resolve().parent
BASE = '/manage'
COOKIE = '__Host-dapan_admin_session'
MAX_REQUEST = 20_000
MAX_RESPONSE = 2_000_000
UUID = r'[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}'


def allowed_path(path, method):
    fixed = {
        '/admin/ai/config': {'GET', 'PUT'}, '/admin/ai/users': {'GET'},
        '/admin/ai/user': {'PUT'}, '/admin/feedback': {'GET'},
    }
    return method in fixed.get(path, set()) or (
        re.fullmatch('/admin/feedback/' + UUID, path) is not None and method in {'GET', 'PUT'})


def create_app(auth_directory, admin_token, origin='https://39.105.161.86', transport=None):
    parsed = urlsplit(origin)
    if parsed.scheme != 'https' or not parsed.netloc or parsed.path or parsed.query or parsed.fragment:
        raise RuntimeError('Administrator origin must be an HTTPS origin')
    if len(admin_token) < 20:
        raise RuntimeError('Administrator upstream credential is missing')
    auth = AdminAuth(auth_directory)
    if not auth.configured:
        raise RuntimeError('Administrator account must be provisioned before starting')
    login_csrf = secrets.token_urlsafe(32)

    @asynccontextmanager
    async def lifespan(app):
        async with httpx.AsyncClient(base_url='http://127.0.0.1:8765',
                                     trust_env=False, timeout=20, follow_redirects=False,
                                     transport=transport) as client:
            app.state.upstream = client
            yield

    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)
    app.state.auth = auth

    def error(status, detail):
        return JSONResponse({'detail': detail}, status_code=status)

    @app.middleware('http')
    async def boundary(request, call_next):
        nonce = secrets.token_urlsafe(24)
        request.state.nonce = nonce
        if (request.headers.get('host') != parsed.netloc or request.url.scheme != 'https'
                or request.headers.get('origin', origin) != origin
                or (request.headers.get('sec-fetch-site') == 'cross-site'
                    and request.url.path not in {BASE, BASE + '/', BASE + '/feedback'})):
            response = error(403, '请从管理网址重新打开')
        elif request.method not in {'GET', 'HEAD'} and request.headers.get('origin') != origin:
            response = error(403, '请求来源不正确，请刷新后重试')
        else:
            response = await call_next(request)
        response.headers.update({
            'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
            'X-Frame-Options': 'DENY', 'Referrer-Policy': 'no-referrer',
            'Content-Security-Policy': "default-src 'none'; connect-src 'self'; "
                f"script-src 'nonce-{nonce}'; style-src 'unsafe-inline'; "
                "frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        })
        return response

    def session(request):
        return auth.session(request.cookies.get(COOKIE, ''))

    def valid_csrf(request, expected):
        return hmac.compare_digest(request.headers.get('X-Local-CSRF', ''), expected)

    async def body(request, limit=MAX_REQUEST):
        chunks = bytearray()
        async for chunk in request.stream():
            chunks.extend(chunk)
            if len(chunks) > limit:
                raise AuthError(413, '请求过大')
        return bytes(chunks)

    @app.get(BASE, include_in_schema=False)
    async def index():
        return RedirectResponse(BASE + '/feedback', status_code=303)

    @app.get(BASE + '/')
    @app.get(BASE + '/feedback')
    async def page(request: Request):
        current = session(request)
        name = ('feedback_console.html' if request.url.path.endswith('/feedback')
                else 'quota_console.html') if current else 'quota_login.html'
        html = (HERE / name).read_text(encoding='utf-8')
        html = (html.replace('__CSRF__', current['csrf'] if current else login_csrf)
                .replace('__NONCE__', request.state.nonce).replace('__MODE__', 'login')
                .replace('__DESTINATION__', request.url.path).replace('__BASE__', BASE)
                .replace('仅限这台电脑访问。服务器密钥不会进入网页。',
                         '仅管理员可登录。请勿向他人提供账号密码。'))
        return HTMLResponse(html)

    @app.post(BASE + '/auth/login')
    async def login(request: Request):
        if not valid_csrf(request, login_csrf):
            return error(403, '页面已过期，请刷新后登录')
        try:
            value = json.loads(await body(request, 8192))
            if not isinstance(value, dict):
                raise ValueError()
            token = await run_in_threadpool(auth.login, value.get('username'), value.get('password'))
        except AuthError as exc:
            return error(exc.status, exc.detail)
        except (ValueError, UnicodeError):
            return error(400, '请求格式不正确')
        response = JSONResponse({'ok': True})
        response.set_cookie(COOKIE, token, max_age=SESSION_SECONDS, path='/',
                            httponly=True, secure=True, samesite='strict')
        return response

    @app.post(BASE + '/auth/logout')
    async def logout(request: Request):
        current = session(request)
        if not current or not valid_csrf(request, current['csrf']):
            return error(403, '请重新登录')
        auth.logout(request.cookies.get(COOKIE, ''))
        response = JSONResponse({'ok': True})
        response.delete_cookie(COOKIE, path='/', httponly=True, secure=True, samesite='strict')
        return response

    @app.api_route(BASE + '/admin/{path:path}', methods=['GET', 'PUT'])
    async def proxy(request: Request, path: str):
        current = session(request)
        if not current:
            return error(401, '请先登录管理员账号')
        if not valid_csrf(request, current['csrf']):
            return error(403, '页面已过期，请刷新后重试')
        target = '/admin/' + path
        if not allowed_path(target, request.method) or len(request.url.query) > 2048:
            return error(404, '管理接口不存在')
        try:
            raw = await body(request)
            # Only the fixed loopback upstream is reachable. Never forward browser credentials.
            async with app.state.upstream.stream(request.method, target,
                    params=request.query_params, content=raw,
                    headers={'X-Admin-Token': admin_token, 'Content-Type': 'application/json'}) as upstream:
                if upstream.status_code not in {200, 400, 404, 409, 422, 429}:
                    return error(502, '服务器暂时无法处理请求，请稍后重试')
                data = bytearray()
                async for chunk in upstream.aiter_bytes():
                    data.extend(chunk)
                    if len(data) > MAX_RESPONSE:
                        return error(502, '记录过大，请联系开发者处理')
                # Reject non-JSON errors; do not echo upstream headers or configuration errors.
                value = json.loads(data)
                return JSONResponse(value, status_code=upstream.status_code)
        except AuthError as exc:
            return error(exc.status, exc.detail)
        except (httpx.HTTPError, ValueError, UnicodeError):
            return error(502, '连接服务器失败，请稍后重试')

    return app


def production_app():
    return create_app(os.environ['DAPAN_ADMIN_AUTH_DIR'], os.environ['ADMIN_TOKEN'],
                      os.environ.get('DAPAN_ADMIN_ORIGIN', 'https://39.105.161.86'))

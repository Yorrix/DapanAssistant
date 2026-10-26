"""Local-only quota console, forwarded through the existing SSH identity.

The administrator token remains in this process and is never served to a browser.
Run using Python 3.11+; no third-party dependencies required.
"""
import argparse
import atexit
import contextlib
import hmac
import http.client
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from http.cookies import SimpleCookie

LOCAL_PORT = 18765
TUNNEL_PORT = 28765
ORIGIN = f'http://127.0.0.1:{LOCAL_PORT}'
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from console_auth import AdminAuth, AuthError, SESSION_SECONDS
SSH_ARGS = ['ssh', '-F', 'NUL' if os.name == 'nt' else '/dev/null', '-i',
            os.environ.get('DAPAN_SSH_KEY', str(Path.home()/'.ssh/id_ed25519')), '-o', 'IdentitiesOnly=yes',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'ProxyCommand=none', '-o', 'ProxyJump=none',
            '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10']
REMOTE = os.environ.get('DAPAN_SSH_HOST', '').strip()
HIDDEN = subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0


def valid_request(headers, method, csrf):
    if headers.get('Host', '') != urllib.parse.urlsplit(ORIGIN).netloc:
        return False
    origin = headers.get('Origin')
    if origin and origin != ORIGIN:
        return False
    if method not in {'GET', 'HEAD'} and origin != ORIGIN:
        return False
    return hmac.compare_digest(headers.get('X-Local-CSRF', ''), csrf)


def allowed_path(path):
    parsed = urllib.parse.urlsplit(path)
    if parsed.scheme or parsed.netloc or parsed.fragment:
        return False
    return parsed.path in {'/admin/ai/config', '/admin/ai/users', '/admin/ai/user'} and len(path) < 2048


class Console(ThreadingHTTPServer):
    daemon_threads = True
    def __init__(self, token, auth_directory=None, port=LOCAL_PORT):
        self.auth = AdminAuth(auth_directory)
        super().__init__(('127.0.0.1', port), Handler)
        self.token = token
        self.csrf = secrets.token_urlsafe(32)
        self.nonce = secrets.token_urlsafe(24)
        self.tunnel = None


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass  # Account addresses and credential-bearing errors do not belong in logs.

    def reply(self, status, value, kind='application/json; charset=utf-8', cookie=None):
        body = value if isinstance(value, bytes) else json.dumps(value, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', kind)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('X-Frame-Options', 'DENY')
        self.send_header('Referrer-Policy', 'no-referrer')
        if cookie is not None:
            self.send_header('Set-Cookie', cookie)
        self.send_header('Content-Security-Policy', f"default-src 'self'; script-src 'nonce-{self.server.nonce}'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.headers.get('Host') != urllib.parse.urlsplit(ORIGIN).netloc or self.headers.get('Origin', ORIGIN) != ORIGIN:
            return self.reply(403, {'detail': '只允许本机访问'})
        if self.path == '/health':
            return self.reply(200, {'application': 'dapan-quota-console', 'version': 2})
        if self.path == '/':
            session = self.server.auth.session(self.cookie_token())
            file = 'quota_console.html' if session else 'quota_login.html'
            csrf = session['csrf'] if session else self.server.csrf
            html = (HERE/file).read_text(encoding='utf-8').replace('__CSRF__', csrf).replace('__NONCE__', self.server.nonce)
            html = html.replace('__MODE__', 'login' if self.server.auth.configured else 'setup')
            return self.reply(200, html.encode(), 'text/html; charset=utf-8')
        self.proxy()

    def do_PUT(self):
        self.proxy()

    def cookie_token(self):
        try:
            cookies = SimpleCookie()
            cookies.load(self.headers.get('Cookie', ''))
            return cookies['dapan_admin_session'].value if 'dapan_admin_session' in cookies else ''
        except Exception:
            return ''

    def read_json(self):
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 < size <= 8192 or self.headers.get('Transfer-Encoding'):
                raise AuthError(413, '请求过大')
            self.connection.settimeout(10)
            value = json.loads(self.rfile.read(size))
            if not isinstance(value, dict):
                raise ValueError()
            return value
        except AuthError:
            raise
        except Exception:
            raise AuthError(400, '请求格式不正确') from None

    def do_POST(self):
        try:
            if self.path == '/auth/logout':
                token = self.cookie_token()
                session = self.server.auth.session(token)
                if not session or not valid_request(self.headers, 'POST', session['csrf']):
                    return self.reply(403, {'detail': '请重新登录'})
                self.server.auth.logout(token)
                return self.reply(200, {'ok': True}, cookie='dapan_admin_session=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0')
            if self.path not in {'/auth/setup', '/auth/login'} or not valid_request(self.headers, 'POST', self.server.csrf):
                return self.reply(403, {'detail': '请求未授权，请重新打开本机入口'})
            body = self.read_json()
            if self.path == '/auth/setup':
                if body.get('password') != body.get('confirmPassword'):
                    raise AuthError(400, '两次密码不一致')
                token = self.server.auth.setup(body.get('username'), body.get('password'))
            else:
                token = self.server.auth.login(body.get('username'), body.get('password'))
            cookie = f'dapan_admin_session={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age={SESSION_SECONDS}'
            self.reply(200, {'ok': True}, cookie=cookie)
        except AuthError as exc:
            self.reply(exc.status, {'detail': exc.detail})
        except Exception:
            self.reply(500, {'detail': '无法保存管理员设置，请检查本机文件权限'})

    def proxy(self):
        session = self.server.auth.session(self.cookie_token())
        if not session:
            return self.reply(401, {'detail': '请先登录管理员账号'})
        if not valid_request(self.headers, self.command, session['csrf']) or not allowed_path(self.path):
            return self.reply(403, {'detail': '请求未授权，请从本机入口重新打开'})
        if self.server.tunnel and self.server.tunnel.poll() is not None:
            return self.reply(503, {'detail': '服务器连接已断开，请关闭管理页并重新打开本机入口'})
        try:
            size = int(self.headers.get('Content-Length', '0'))
            if not 0 <= size <= 8192 or self.headers.get('Transfer-Encoding'):
                return self.reply(413, {'detail': '请求过大'})
            body = self.rfile.read(size) if size else None
            with contextlib.closing(http.client.HTTPConnection('127.0.0.1', TUNNEL_PORT, timeout=20)) as connection:
                connection.request(self.command, self.path, body=body, headers={
                    'X-Admin-Token': self.server.token, 'Content-Type': 'application/json'})
                response = connection.getresponse()
                raw = response.read(2_000_001)
                if len(raw) > 2_000_000:
                    raise ValueError('oversized response')
                data = json.loads(raw)
                self.reply(response.status, data)
        except Exception:
            self.reply(502, {'detail': '连接服务器失败，请检查网络后重试'})


def existing():
    try:
        with urllib.request.urlopen(ORIGIN+'/health', timeout=1) as r:
            return json.load(r).get('application') == 'dapan-quota-console'
    except Exception:
        return False


def start(open_browser=True):
    if not REMOTE:
        raise RuntimeError("请先设置 DAPAN_SSH_HOST 和 DAPAN_SSH_KEY，连接你有权管理的服务器")
    if existing():
        if open_browser:
            webbrowser.open(ORIGIN)
        return
    # Read only the admin token; capture it in memory and never interpolate it in a command.
    remote_script = "import pathlib; from shlex import split; rows=pathlib.Path('/etc/dapan.env').read_text().splitlines(); vals=[split(x.split('=',1)[1])[0] for x in rows if x.startswith('ADMIN_TOKEN=')]; print(vals[-1] if vals else '')"
    import shlex
    result = subprocess.run(SSH_ARGS+[REMOTE, 'python3 -c '+shlex.quote(remote_script)],
                            capture_output=True, timeout=15, creationflags=HIDDEN)
    token = result.stdout.decode('utf-8', 'strict').strip()
    if result.returncode or len(token) < 20 or '\n' in token:
        raise RuntimeError('未能读取服务器管理凭据，请检查 SSH 连接和 ADMIN_TOKEN 配置')
    server = Console(token)  # Bind before starting a tunnel, preventing a second UI process.
    tunnel = subprocess.Popen(SSH_ARGS+['-o', 'ExitOnForwardFailure=yes', '-o', 'ServerAliveInterval=15',
        '-o', 'ServerAliveCountMax=2', '-N', '-L', f'127.0.0.1:{TUNNEL_PORT}:127.0.0.1:8765', REMOTE],
        stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=HIDDEN)
    server.tunnel = tunnel
    atexit.register(tunnel.terminate)
    try:
        for _ in range(50):
            if tunnel.poll() is not None:
                raise RuntimeError('管理连接未能建立，管理端口可能被占用')
            try:
                with socket.create_connection(('127.0.0.1', TUNNEL_PORT), timeout=.2):
                    break
            except OSError:
                time.sleep(.2)
        else:
            raise RuntimeError('连接服务器超时')
        if open_browser:
            webbrowser.open(ORIGIN)
        server.serve_forever()
    finally:
        server.server_close()
        tunnel.terminate()
        server.token = ''


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--no-browser', action='store_true')
    args = parser.parse_args()
    try:
        start(not args.no_browser)
    except Exception as exc:
        # Only our static messages are shown; never print captured SSH stdout/stderr.
        message = str(exc) if isinstance(exc, RuntimeError) else '额度管理启动失败，请检查网络和本机 SSH 配置'
        if os.name == 'nt':
            import ctypes
            ctypes.windll.user32.MessageBoxW(0, message, '大盘助手 · 额度管理', 0x10)
        else:
            print(message)

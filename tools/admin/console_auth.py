"""One local administrator; password hashes on disk, short-lived sessions in memory."""
from collections import deque
import csv
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import threading
import time

IDLE_SECONDS = 30 * 60
SESSION_SECONDS = 8 * 60 * 60
LOGIN_WINDOW = 15 * 60
LOGIN_ATTEMPTS = 5


class AuthError(Exception):
    def __init__(self, status, detail):
        self.status, self.detail = status, detail


def private_directory(path):
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    if os.name == 'nt':
        hidden = subprocess.CREATE_NO_WINDOW
        result = subprocess.run(['whoami', '/user', '/fo', 'csv', '/nh'],
                                capture_output=True, check=True, creationflags=hidden)
        rows = list(csv.reader(result.stdout.decode(errors='replace').splitlines()))
        sid = rows[-1][-1]
        if not re.fullmatch(r'S-1-\d+(?:-\d+)+', sid):
            raise RuntimeError('无法确认本机管理员文件权限')
        subprocess.run(['icacls', str(path), '/inheritance:r', '/grant:r', f'*{sid}:(OI)(CI)F'],
                       capture_output=True, check=True, creationflags=hidden)
    else:
        path.chmod(0o700)


class AdminAuth:
    def __init__(self, directory=None, clock=time.monotonic):
        self.directory = Path(directory) if directory else Path(os.environ.get('LOCALAPPDATA', Path.home()/'.local/share'))/'DapanAssistant'/'quota-admin'
        self.file = self.directory/'administrator.json'
        self.clock = clock
        self.lock = threading.RLock()
        self.failures = deque()
        self.sessions = {}
        self.record = None
        private_directory(self.directory)
        if self.file.exists():
            try:
                record = json.loads(self.file.read_text(encoding='utf-8'))
                assert record['version'] == 1 and record['algorithm'] == 'scrypt'
                assert isinstance(record['username'], str) and 1 <= len(record['username']) <= 64
                assert len(bytes.fromhex(record['salt'])) == 16
                assert len(bytes.fromhex(record['hash'])) == 32
                self.record = record
            except Exception:
                # Never turn a damaged credential file into an unprotected first-run setup.
                raise RuntimeError('管理员配置无法读取，已停止开放管理页面') from None

    @property
    def configured(self):
        return self.record is not None

    @staticmethod
    def derive(password, salt):
        return hashlib.scrypt(password.encode('utf-8'), salt=salt, n=32768, r=8, p=1,
                              maxmem=64*1024*1024, dklen=32).hex()

    def rate_check(self):
        now = self.clock()
        while self.failures and self.failures[0] <= now-LOGIN_WINDOW:
            self.failures.popleft()
        if len(self.failures) >= LOGIN_ATTEMPTS:
            raise AuthError(429, '尝试过于频繁，请在 15 分钟后重试')

    def setup(self, username, password):
        with self.lock:
            if self.configured:
                raise AuthError(409, '管理员已经设置，请登录')
            self.rate_check()
            if not isinstance(username, str) or not 1 <= len(username.strip()) <= 64:
                self.failures.append(self.clock())
                raise AuthError(400, '请输入 1 至 64 个字符的管理员账号')
            if not isinstance(password, str) or not 12 <= len(password) <= 256:
                self.failures.append(self.clock())
                raise AuthError(400, '密码需要 12 至 256 个字符')
            salt = secrets.token_bytes(16)
            record = {'version': 1, 'algorithm': 'scrypt', 'username': username.strip(),
                      'salt': salt.hex(), 'hash': self.derive(password, salt)}
            # The parent directory has a current-user-only ACL before the file is created.
            descriptor = os.open(self.file, os.O_WRONLY|os.O_CREAT|os.O_EXCL, 0o600)
            with os.fdopen(descriptor, 'w', encoding='utf-8') as stream:
                json.dump(record, stream, ensure_ascii=False)
                stream.flush()
                os.fsync(stream.fileno())
            self.record = record
            self.failures.clear()
            return self.issue_session()

    def login(self, username, password):
        with self.lock:
            self.rate_check()
            if not self.configured:
                raise AuthError(409, '请先设置管理员账号')
            self.failures.append(self.clock())
            candidate = password if isinstance(password, str) and len(password) <= 256 else ''
            digest = self.derive(candidate, bytes.fromhex(self.record['salt']))
            valid_user = isinstance(username, str) and hmac.compare_digest(username.strip().encode(), self.record['username'].encode())
            if not (hmac.compare_digest(digest, self.record['hash']) and valid_user):
                raise AuthError(401, '账号或密码不正确')
            self.failures.clear()
            return self.issue_session()

    def issue_session(self):
        token = secrets.token_urlsafe(32)
        now = self.clock()
        self.sessions.clear()  # One administrator, one active login; new login revokes the old one.
        self.sessions[hashlib.sha256(token.encode()).hexdigest()] = {
            'created': now, 'seen': now, 'csrf': secrets.token_urlsafe(32)}
        return token

    def session(self, token):
        if not isinstance(token, str) or len(token) > 128:
            return None
        with self.lock:
            key = hashlib.sha256(token.encode()).hexdigest()
            session = self.sessions.get(key)
            if not session:
                return None
            now = self.clock()
            if now-session['seen'] >= IDLE_SECONDS or now-session['created'] >= SESSION_SECONDS:
                self.sessions.pop(key, None)
                return None
            session['seen'] = now
            return dict(session)

    def logout(self, token):
        with self.lock:
            if isinstance(token, str):
                self.sessions.pop(hashlib.sha256(token.encode()).hexdigest(), None)

"""Opt-in conversation reports; isolated storage, additive API, administrator-only reads."""
import contextlib
import hashlib
import hmac
import json
import os
import re
import sqlite3
import time
from typing import Literal
from uuid import UUID

from fastapi import Header, HTTPException, Query, Request
from pydantic import BaseModel, ConfigDict, Field, ValidationError

MAX_BYTES = 1_000_000
MASK = '[已隐藏密钥]'


def redact(text):
    text = re.sub(r'(?i)\bsk-[a-z0-9._-]{12,}', MASK, text)
    text = re.sub(r'(?i)\bBearer\s+[a-z0-9._~+/-]+=*', 'Bearer '+MASK, text)
    text = re.sub(r'(?i)(["\x27]?(?:api[_ -]?key|access[_ -]?token|password|密码|密钥)["\x27]?\s*[:=：]\s*)["\x27]?[^\s,，;；"\x27}]+', r'\1'+MASK, text)
    return text


class Strict(BaseModel):
    model_config = ConfigDict(extra='forbid', strict=True)


class Message(Strict):
    role: Literal['user', 'assistant']
    text: str = Field(max_length=100_000)
    time: int = Field(ge=0, le=10_000_000_000_000)
    operation: str = Field(default='', max_length=200_000)


class Feedback(Strict):
    schemaVersion: Literal[1]
    reportId: str = Field(min_length=36, max_length=36)
    installationId: str = Field(min_length=36, max_length=36)
    consent: Literal[True]
    appVersion: str = Field(min_length=1, max_length=40)
    versionCode: int = Field(ge=1)
    device: str = Field(max_length=100)
    androidVersion: str = Field(max_length=40)
    screen: str = Field(max_length=80)
    messages: list[Message] = Field(min_length=1, max_length=100)


class Review(Strict):
    status: Literal['new', 'reviewing', 'resolved', 'ignored']
    note: str = Field(default='', max_length=4000)
    revision: int = Field(ge=0)


def identifier(value):
    try:
        if str(UUID(value)) != value:
            raise ValueError()
    except (ValueError, TypeError, AttributeError):
        raise HTTPException(422, '反馈编号无效') from None
    return value


class FeedbackStore:
    def __init__(self, service):
        self.service = service
        self.path = service.root/'conversation-feedback.db'
        with self.db() as db:
            db.executescript('''
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS feedback(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT,
                    id TEXT UNIQUE NOT NULL, owner TEXT NOT NULL, digest TEXT NOT NULL,
                    created INTEGER NOT NULL, updated INTEGER NOT NULL,
                    status TEXT NOT NULL DEFAULT 'new', note TEXT NOT NULL DEFAULT '',
                    revision INTEGER NOT NULL DEFAULT 0, version TEXT NOT NULL,
                    screen TEXT NOT NULL, title TEXT NOT NULL, count INTEGER NOT NULL,
                    payload TEXT NOT NULL);
                CREATE INDEX IF NOT EXISTS feedback_status ON feedback(status,seq);
            ''')

    @contextlib.contextmanager
    def db(self):
        with sqlite3.connect(self.path, timeout=15) as db:
            db.row_factory = sqlite3.Row
            yield db

    def submit(self, value, ip):
        identifier(value.reportId)
        identifier(value.installationId)
        owner = self.service.digest('feedback-install:'+value.installationId)
        # Never retain the raw installation ID, authentication header, IP or server secrets.
        payload = value.model_dump(exclude={'installationId', 'consent'})
        for key in ('device', 'screen'):
            payload[key] = redact(payload[key])
        for message in payload['messages']:
            message['text'] = redact(message['text'])
            message['operation'] = redact(message['operation'])
        if not any(m['text'].strip() or m['operation'].strip() for m in payload['messages']):
            raise HTTPException(422, '没有可提交的对话')
        raw = json.dumps(payload, ensure_ascii=False, separators=(',', ':'))
        digest = hashlib.sha256(raw.encode()).hexdigest()
        with self.db() as db:
            # Duplicate retries return the original receipt, even after a rate limit is reached.
            db.execute('BEGIN IMMEDIATE')
            previous = db.execute('SELECT owner,digest FROM feedback WHERE id=?', (value.reportId,)).fetchone()
            if previous:
                if not hmac.compare_digest(previous['owner'], owner) or previous['digest'] != digest:
                    raise HTTPException(409, '反馈编号已使用，请重新打开反馈窗口')
                return {'id': value.reportId, 'received': True}
            self.service.rate('feedback-install:'+owner, 10, 86400)
            self.service.rate('feedback-ip:'+ip, 60, 3600)
            self.service.rate('feedback-site', 1000, 86400)
            now = int(time.time())
            title = next((m['text'].strip()[:100] for m in reversed(payload['messages']) if m['role']=='user' and m['text'].strip()), '对话问题反馈')
            db.execute('INSERT INTO feedback(id,owner,digest,created,updated,version,screen,title,count,payload) VALUES(?,?,?,?,?,?,?,?,?,?)',
                       (value.reportId, owner, digest, now, now, value.appVersion, payload['screen'], title, len(payload['messages']), raw))
        return {'id': value.reportId, 'received': True}


async def read_body(request, model, maximum):
    chunks = bytearray()
    async for chunk in request.stream():
        chunks.extend(chunk)
        if len(chunks) > maximum:
            raise HTTPException(413, '对话记录过长，本次未提交；请缩短历史后重试')
    try:
        value = json.loads(chunks)
        if model is Feedback and (not isinstance(value, dict) or value.get('consent') is not True or type(value.get('schemaVersion')) is not int):
            raise ValueError()
        return model.model_validate(value)
    except (ValidationError, ValueError, UnicodeError):
        # Pydantic errors can echo private input; never return them to logs/clients.
        raise HTTPException(422, '反馈格式无效，请更新应用后重试') from None


def install_conversation_feedback(app, service):
    store = FeedbackStore(service)
    app.state.feedback = store

    def authorize(token):
        expected = os.environ.get('ADMIN_TOKEN', '')
        if not expected or not hmac.compare_digest(expected.encode(), token.encode()):
            raise HTTPException(403, '管理员认证失败')

    @app.post('/feedback/conversations')
    async def submit(request: Request):
        value = await read_body(request, Feedback, MAX_BYTES)
        # uvicorn accepts forwarded addresses only from our local reverse proxy.
        return store.submit(value, request.client.host if request.client else 'unknown')

    @app.get('/admin/feedback')
    def reports(status: str = '', q: str = Query(default='', max_length=120),
                before: int = Query(default=0, ge=0), limit: int = Query(default=30, ge=1, le=50),
                x_admin_token: str = Header(default='')):
        authorize(x_admin_token)
        if status not in {'', 'new', 'reviewing', 'resolved', 'ignored'}:
            raise HTTPException(422, '状态无效')
        clauses, params = ['1=1'], []
        if status:
            clauses.append('status=?'); params.append(status)
        if q.strip():
            clauses.append('(instr(title,?)>0 OR instr(id,?)>0)'); params.extend([q.strip()]*2)
        if before:
            clauses.append('seq<?'); params.append(before)
        with store.db() as db:
            rows = db.execute('SELECT seq,id,created,updated,status,revision,version,screen,title,count FROM feedback WHERE '+' AND '.join(clauses)+' ORDER BY seq DESC LIMIT ?', params+[limit+1]).fetchall()
            counts = {r['status']: r['n'] for r in db.execute('SELECT status,COUNT(*) n FROM feedback GROUP BY status')}
        return {'items': [dict(r) for r in rows[:limit]], 'next': rows[limit-1]['seq'] if len(rows)>limit else None, 'counts': counts}

    @app.get('/admin/feedback/{report_id}')
    def detail(report_id: str, x_admin_token: str = Header(default='')):
        authorize(x_admin_token)
        with store.db() as db:
            row = db.execute('SELECT * FROM feedback WHERE id=?', (identifier(report_id),)).fetchone()
        if row is None:
            raise HTTPException(404, '反馈不存在')
        return {k: row[k] for k in ('id', 'created', 'updated', 'status', 'note', 'revision')} | {'report': json.loads(row['payload'])}

    @app.put('/admin/feedback/{report_id}')
    async def review(report_id: str, request: Request, x_admin_token: str = Header(default='')):
        authorize(x_admin_token)
        value = await read_body(request, Review, 20_000)
        with store.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute('SELECT revision FROM feedback WHERE id=?', (identifier(report_id),)).fetchone()
            if row is None:
                raise HTTPException(404, '反馈不存在')
            if row['revision'] != value.revision:
                raise HTTPException(409, '这条反馈已被更新，请刷新后重试')
            db.execute('UPDATE feedback SET status=?,note=?,updated=?,revision=revision+1 WHERE id=?',
                       (value.status, redact(value.note), int(time.time()), report_id))
        return {'ok': True, 'revision': value.revision+1}

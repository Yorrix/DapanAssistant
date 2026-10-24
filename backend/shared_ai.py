"""Authenticated, budgeted text AI. No provider credentials or prompts in the ledger.

Amounts are integer micro-yuan. Peak Flash rates are used even off-peak, making
the subsidy meter conservative. Price reference: api-docs.deepseek.com/zh-cn/quick_start/pricing/
Verified 2026-10-24: input miss 2, hit .04, output 8 CNY / million tokens.
"""
import asyncio
import contextlib
import json
import os
import sqlite3
import time
import uuid
from datetime import datetime, timedelta, timezone

import httpx
from fastapi import Header, HTTPException, Request
from fastapi.responses import StreamingResponse

ZONE = timezone(timedelta(hours=8), 'Asia/Shanghai')
LIMITS = {'day': 1_000_000, 'week': 3_000_000, 'siteMonth': 30_000_000}
MESSAGES = {
    'login_required': '请用大工邮箱登录，与二手信息共用一个账号。',
    'disabled': '公用 AI 暂未开放，请稍后再试或使用自己的 API。',
    'daily_limit': '今天的剩余额度不足以处理本次请求。可缩短内容，或等明天恢复。',
    'weekly_limit': '本周的剩余额度不足以处理本次请求。可缩短内容，或等下周一恢复。',
    'monthly_limit': '全站本月剩余额度不足以处理本次请求。可稍后再试，下月恢复。',
    'busy': '公用 AI 正在处理请求，请等本次回复完成后再试。',
    'duplicate': '这次请求已受理，请等待结果；不要重复发送。',
    'too_large': '本次内容超出公用 AI 单次限制，请缩短内容或使用自己的 API。',
    'text_only': '公用 AI 暂只支持文字；图片识别请配置自己的 API。',
    'upstream': '公用 AI 暂时不可用，请稍后再试。',
    'interrupted': '公用 AI 回复中断，尚未执行操作。请检查网络后重试。',
    'invalid': '公用 AI 请求格式不正确。',
}


def reject(code, status=429):
    raise HTTPException(status, {'code': code, 'message': MESSAGES[code]})


def periods(timestamp):
    now = datetime.fromtimestamp(timestamp, ZONE)
    day = now.replace(hour=0, minute=0, second=0, microsecond=0)
    week = day - timedelta(days=day.weekday())
    month = day.replace(day=1)
    next_month = (month.replace(day=28) + timedelta(days=4)).replace(day=1)
    return {'day': (day, day + timedelta(days=1)), 'week': (week, week + timedelta(days=7)),
            'siteMonth': (month, next_month)}


class Budget:
    def __init__(self, path, clock=time.time):
        self.path, self.clock = path, clock
        with self.db() as db:
            db.executescript('''PRAGMA journal_mode=WAL;
              CREATE TABLE IF NOT EXISTS requests(
                id TEXT PRIMARY KEY, owner TEXT NOT NULL, created INTEGER NOT NULL,
                day TEXT NOT NULL, week TEXT NOT NULL, month TEXT NOT NULL,
                reserved INTEGER NOT NULL, charged INTEGER NOT NULL DEFAULT 0,
                state TEXT NOT NULL, input_tokens INTEGER, output_tokens INTEGER);
              CREATE INDEX IF NOT EXISTS requests_owner_date ON requests(owner,created);
              CREATE INDEX IF NOT EXISTS requests_month ON requests(month);
            ''')
            if 'kind' not in {r['name'] for r in db.execute('PRAGMA table_info(requests)')}:
                db.execute("ALTER TABLE requests ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")

    @contextlib.contextmanager
    def db(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def expire(self, db):
        # Unknown outcome after process death retains its worst-case charge.
        dates = periods(self.clock())
        db.execute("UPDATE requests SET state='uncertain',charged=reserved,day=?,week=?,month=? WHERE state='held' AND created<?",
                   (*[dates[k][0].date().isoformat() for k in ('day', 'week', 'siteMonth')], int(self.clock()) - 300))

    def balances(self, db, owner):
        result = {}
        for name, (start, end) in periods(self.clock()).items():
            column = {'day': 'day', 'week': 'week', 'siteMonth': 'month'}[name]
            # Outstanding reservations still consume budget across midnight/month rollover.
            condition, args = f"({column}=? OR state='held')", [start.date().isoformat()]
            if name != 'siteMonth':
                condition += ' AND owner=?'
                args.append(owner)
            row = db.execute(f'''SELECT COALESCE(SUM(charged),0),
                COALESCE(SUM(CASE WHEN state='held' THEN reserved ELSE 0 END),0)
                FROM requests WHERE {condition}''', args).fetchone()
            used, held = row
            result[name] = dict(used=used, held=held, limit=LIMITS[name],
                                remaining=max(0, LIMITS[name] - used - held), resetsAt=end.isoformat())
        return result

    def status(self, owner):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            self.expire(db)
            return self.balances(db, owner)

    def reserve(self, request_id, owner, maximum, kind='text'):
        if kind not in {'text', 'asr'} or type(maximum) is not int or maximum <= 0:
            raise ValueError('invalid reservation')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            self.expire(db)
            if db.execute('SELECT 1 FROM requests WHERE id=?', (request_id,)).fetchone():
                reject('duplicate', 409)
            balances = self.balances(db, owner)
            for name, code in [('day', 'daily_limit'), ('week', 'weekly_limit'), ('siteMonth', 'monthly_limit')]:
                if balances[name]['remaining'] < maximum:
                    reject(code)
            if db.execute("SELECT COUNT(*) FROM requests WHERE state='held' AND owner=? AND kind=?", (owner, kind)).fetchone()[0]:
                reject('busy')
            if db.execute("SELECT COUNT(*) FROM requests WHERE state='held'").fetchone()[0] >= 4:
                reject('busy')
            dates = periods(self.clock())
            db.execute('INSERT INTO requests(id,owner,created,day,week,month,reserved,state,kind) VALUES(?,?,?,?,?,?,?,?,?)',
                       (request_id, owner, int(self.clock()), *[dates[k][0].date().isoformat() for k in ('day', 'week', 'siteMonth')], maximum, 'held', kind))

    def settle_amount(self, request_id, micro_cny, state='charged'):
        if type(micro_cny) is not int or micro_cny < 0 or state not in {'charged', 'uncertain', 'rejected'}:
            raise ValueError('invalid settlement')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            dates = periods(self.clock())
            db.execute("UPDATE requests SET charged=?,state=?,day=?,week=?,month=? WHERE id=? AND state='held'",
                       (micro_cny, state, *[dates[k][0].date().isoformat() for k in ('day', 'week', 'siteMonth')], request_id))

    def settle(self, request_id, usage=None, rejected=False):
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = db.execute("SELECT * FROM requests WHERE id=? AND state='held'", (request_id,)).fetchone()
            if row is None:
                return
            charged, state, prompt, output = row['reserved'], 'uncertain', None, None
            if rejected:
                charged, state = 0, 'rejected'
            elif isinstance(usage, dict):
                prompt, output = usage.get('prompt_tokens'), usage.get('completion_tokens')
                hit = usage.get('prompt_cache_hit_tokens', 0)
                if all(type(x) is int and x >= 0 for x in (prompt, output, hit)) and hit <= prompt:
                    charged = (prompt - hit) * 2 + (hit + 24) // 25 + output * 8
                    state = 'charged'
                else:
                    prompt, output = None, None
            dates = periods(self.clock())
            db.execute('UPDATE requests SET charged=?,state=?,input_tokens=?,output_tokens=?,day=?,week=?,month=? WHERE id=?',
                       (charged, state, prompt, output, *[dates[k][0].date().isoformat() for k in ('day', 'week', 'siteMonth')], request_id))


class UpstreamRejected(Exception):
    pass


class SharedTextAi:
    def __init__(self, service, upstream=None, clock=time.time):
        self.service = service
        self.budget = Budget(service.root / 'shared-ai.db', clock)
        self.upstream = upstream or self.provider

    @property
    def enabled(self):
        return os.environ.get('SHARED_AI_ENABLED') == '1' and bool(os.environ.get('DEEPSEEK_API_KEY'))

    def user(self, authorization):
        try:
            user = self.service.auth(authorization)
        except HTTPException:
            reject('login_required', 401)
        if user['email'].rsplit('@', 1)[-1].lower() not in {'mail.dlut.edu.cn', 'dlut.edu.cn'}:
            reject('login_required', 403)
        return user

    def payload(self, raw):
        if not isinstance(raw, dict):
            reject('invalid', 422)
        try:
            identifier = str(uuid.UUID(raw.get('request_id', '')))
        except (ValueError, TypeError, AttributeError):
            reject('invalid', 422)
        messages = raw.get('messages')
        if not isinstance(messages, list) or not 1 <= len(messages) <= 80:
            reject('invalid', 422)
        clean, prompt_bound = [], 1024
        for message in messages:
            if not isinstance(message, dict) or message.get('role') not in {'system', 'user', 'assistant'}:
                reject('invalid', 422)
            content = message.get('content')
            if not isinstance(content, str):
                reject('text_only', 422)
            prompt_bound += len(content.encode('utf-8')) + 32
            clean.append({'role': message['role'], 'content': content})
        if prompt_bound > 196608:
            reject('too_large', 413)
        maximum = raw.get('max_tokens', 4096)
        if type(maximum) is not int or maximum < 1:
            reject('invalid', 422)
        maximum = min(maximum, 8192)
        body = dict(model='deepseek-flash', messages=clean, stream=True,
                    stream_options={'include_usage': True}, max_tokens=maximum, thinking={'type': 'disabled'})
        if raw.get('response_format') == {'type': 'json_object'}:
            body['response_format'] = {'type': 'json_object'}
        return identifier, body, prompt_bound * 2 + maximum * 8

    async def provider(self, body):
        # Fixed provider/model: callers cannot redirect the server or invoke paid search tools.
        async with httpx.AsyncClient(timeout=httpx.Timeout(230, connect=15), follow_redirects=False) as client:
            async with client.stream('POST', 'https://api.deepseek.com/chat/completions',
                    headers={'Authorization': 'Bearer ' + os.environ['DEEPSEEK_API_KEY']}, json=body) as response:
                if response.status_code != 200:
                    if response.status_code in {400, 401, 402, 403, 404, 413, 422, 429}:
                        raise UpstreamRejected()
                    raise ValueError('provider outcome unknown')
                async for line in response.aiter_lines():
                    if not line.startswith('data:'):
                        continue
                    value = line[5:].strip()
                    if value == '[DONE]':
                        yield {'_done': True}
                        return
                    if len(value) > 1_000_000:
                        raise ValueError('oversized provider frame')
                    yield json.loads(value)
                raise ValueError('provider stream ended early')

    async def stream(self, identifier, body):
        queue = asyncio.Queue(maxsize=8)
        usage, accepted_done = None, False
        async def produce():
            try:
                async with asyncio.timeout(235):
                    async for frame in self.upstream(body):
                        await queue.put(frame)
            except UpstreamRejected:
                await queue.put({'_rejected': True})
            except asyncio.CancelledError:
                raise
            except Exception:
                await queue.put({'_failed': True})
            finally:
                if not asyncio.current_task().cancelling():
                    await queue.put(None)
        task = asyncio.create_task(produce())
        def event(value):
            return 'data: ' + json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n\n'
        try:
            yield ': connected\n\n'
            while True:
                try:
                    frame = await asyncio.wait_for(queue.get(), 10)
                except asyncio.TimeoutError:
                    yield ': keep-alive\n\n'
                    continue
                if frame is None:
                    break
                if frame.get('_rejected'):
                    self.budget.settle(identifier, rejected=True)
                    yield event({'dapan_error': {'code': 'upstream', 'message': MESSAGES['upstream']}})
                    return
                if frame.get('_failed') or frame.get('error'):
                    yield event({'dapan_error': {'code': 'interrupted', 'message': MESSAGES['interrupted']}})
                    return
                if isinstance(frame.get('usage'), dict):
                    usage = frame['usage']
                if frame.get('_done'):
                    accepted_done = True
                    self.budget.settle(identifier, usage)
                    yield 'data: [DONE]\n\n'
                    return
                # Only forward textual deltas and completion state; no provider metadata/errors.
                choices = frame.get('choices', [])
                if choices:
                    choice = choices[0]
                    text = choice.get('delta', {}).get('content')
                    reason = choice.get('finish_reason')
                    yield event({'choices': [{'delta': {'content': text if isinstance(text, str) else ''}, 'finish_reason': reason}]})
            if not accepted_done:
                yield event({'dapan_error': {'code': 'interrupted', 'message': MESSAGES['interrupted']}})
        finally:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
            self.budget.settle(identifier, usage)


def install_shared_ai(app, service, upstream=None, clock=time.time):
    ai = SharedTextAi(service, upstream, clock)
    app.state.shared_ai = ai

    @app.get('/ai/status')
    def status(authorization: str | None = Header(default=None)):
        base = dict(unit='microCny', model='deepseek-flash', pricing='conservative_peak',
                    speechAvailable=os.environ.get('SHARED_SPEECH_ENABLED') == '1' and bool(os.environ.get('ALIYUN_API_KEY')),
                    capabilities=['text_chat', 'tool_actions', 'library_text', 'transcript_summary', 'text_translation'])
        if base['speechAvailable']:
            base['capabilities'].append('push_to_talk')
        if not authorization:
            return dict(**base, available=False, reason='login_required', message=MESSAGES['login_required'], limits=LIMITS)
        user = ai.user(authorization)
        balances = ai.budget.status(user['id'])
        reason = '' if ai.enabled else 'disabled'
        if not reason:
            for name, code in [('day', 'daily_limit'), ('week', 'weekly_limit'), ('siteMonth', 'monthly_limit')]:
                if balances[name]['remaining'] <= 0:
                    reason = code
                    break
        return dict(**base, **balances, available=not reason, reason=reason,
                    message=MESSAGES.get(reason, '公用 AI 可用'))

    @app.post('/ai/chat/completions')
    async def completion(request: Request, authorization: str | None = Header(default=None)):
        user = ai.user(authorization)
        if not ai.enabled:
            reject('disabled', 503)
        service.rate('shared-ai:' + user['id'], 15, 60)
        try:
            raw = await request.json()
        except (ValueError, UnicodeError):
            reject('invalid', 422)
        identifier, body, maximum = ai.payload(raw)
        ai.budget.reserve(identifier, user['id'], maximum)
        return StreamingResponse(ai.stream(identifier, body), media_type='text/event-stream',
                                 headers={'X-Accel-Buffering': 'no', 'Cache-Control': 'no-store'})

    return ai

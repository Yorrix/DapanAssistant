"""Authenticated, budgeted text AI. No provider credentials or prompts in the ledger.

Amounts are integer micro-yuan. Peak Flash rates are used even off-peak, making
the subsidy meter conservative. Price reference: api-docs.deepseek.com/zh-cn/quick_start/pricing/
Verified 2026-10-24: input miss 2, hit .04, output 8 CNY / million tokens.
"""
import asyncio
import base64
import contextlib
import io
import json
import os
import sqlite3
import time
import uuid
from datetime import datetime, timedelta, timezone

import httpx
from fastapi import Header, HTTPException, Request
from fastapi.responses import StreamingResponse
from PIL import Image, UnidentifiedImageError

ZONE = timezone(timedelta(hours=8), 'Asia/Shanghai')
LIMITS = {'day': 1_000_000, 'week': 3_000_000, 'month': 10_000_000,
          'siteDay': 10_000_000, 'siteWeek': 30_000_000, 'siteMonth': 100_000_000}
LIMIT_CODES = {'day': 'daily_limit', 'week': 'weekly_limit', 'month': 'account_monthly_limit',
               'siteDay': 'site_daily_limit', 'siteWeek': 'site_weekly_limit', 'siteMonth': 'monthly_limit'}
MESSAGES = {
    'login_required': '请用大工邮箱登录，与二手信息共用一个账号。',
    'disabled': '公用 AI 暂未开放，请稍后再试或使用自己的 API。',
    'daily_limit': '今天的剩余额度不足以处理本次请求。可缩短内容，或等明天恢复。',
    'weekly_limit': '本周的剩余额度不足以处理本次请求。可缩短内容，或等下周一恢复。',
    'monthly_limit': '全站本月剩余额度不足以处理本次请求。可稍后再试，下月恢复。',
    'account_monthly_limit': '你的本月剩余额度不足，下月恢复，或切换到自备 API。',
    'site_daily_limit': '全站今天的公用额度已不足，明天恢复，或切换到自备 API。',
    'site_weekly_limit': '全站本周的公用额度已不足，下周一恢复，或切换到自备 API。',
    'busy': '公用 AI 正在处理请求，请等本次回复完成后再试。',
    'duplicate': '这次请求已受理，请等待结果；不要重复发送。',
    'too_large': '这次资料太大，请分批处理。',
    'invalid_image': '图片格式无效，请重新添加 JPG、PNG、WebP 或 GIF 图片。',
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
            'month': (month, next_month), 'siteDay': (day, day + timedelta(days=1)),
            'siteWeek': (week, week + timedelta(days=7)), 'siteMonth': (month, next_month)}


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
              CREATE TABLE IF NOT EXISTS quota_limits(
                owner TEXT NOT NULL, name TEXT NOT NULL, amount INTEGER NOT NULL,
                PRIMARY KEY(owner,name));
              CREATE TABLE IF NOT EXISTS quota_audit(
                id INTEGER PRIMARY KEY, changed INTEGER NOT NULL, owner TEXT NOT NULL,
                previous TEXT NOT NULL, current TEXT NOT NULL);
            ''')
            if 'kind' not in {r['name'] for r in db.execute('PRAGMA table_info(requests)')}:
                db.execute("ALTER TABLE requests ADD COLUMN kind TEXT NOT NULL DEFAULT 'text'")
            if 'touched' not in {r['name'] for r in db.execute('PRAGMA table_info(requests)')}:
                db.execute('ALTER TABLE requests ADD COLUMN touched INTEGER NOT NULL DEFAULT 0')
                db.execute('UPDATE requests SET touched=created')

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
        db.execute("UPDATE requests SET state='uncertain',charged=reserved,day=?,week=?,month=? WHERE state='held' AND touched<?",
                   (*[dates[k][0].date().isoformat() for k in ('day', 'week', 'siteMonth')], int(self.clock()) - 300))

    def balances(self, db, owner):
        result = {}
        limits = self.limits(db, owner)
        for name, (start, end) in periods(self.clock()).items():
            column = {'siteDay': 'day', 'siteWeek': 'week', 'siteMonth': 'month'}.get(name, name)
            # Outstanding reservations still consume budget across midnight/month rollover.
            condition, args = f"({column}=? OR state='held')", [start.date().isoformat()]
            if not name.startswith('site'):
                condition += ' AND owner=?'
                args.append(owner)
            row = db.execute(f'''SELECT COALESCE(SUM(charged),0),
                COALESCE(SUM(CASE WHEN state='held' THEN reserved ELSE 0 END),0)
                FROM requests WHERE {condition}''', args).fetchone()
            used, held = row
            result[name] = dict(used=used, held=held, limit=limits[name],
                                remaining=max(0, limits[name] - used - held), resetsAt=end.isoformat())
        return result

    def limits(self, db, owner=''):
        result = dict(LIMITS)
        result.update({r['name']: r['amount'] for r in db.execute('SELECT name,amount FROM quota_limits WHERE owner=?', ('',))})
        if owner:
            result.update({r['name']: r['amount'] for r in db.execute('SELECT name,amount FROM quota_limits WHERE owner=?', (owner,))})
        return result

    def configuration(self, owner=''):
        with self.db() as db:
            return {'limits': self.limits(db, owner), 'overrides': {
                r['name']: r['amount'] for r in db.execute('SELECT name,amount FROM quota_limits WHERE owner=?', (owner,))}}

    def configure(self, values, owner=''):
        allowed = set(LIMITS) if not owner else {'day', 'week', 'month'}
        if not isinstance(values, dict) or not set(values) <= allowed or any(
                type(v) is not int or not 0 <= v <= 1_000_000_000_000 for v in values.values()):
            raise HTTPException(422, '额度必须是非负整数 microCny；账号只可设置日、周、月额度。')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            previous = {r['name']: r['amount'] for r in db.execute('SELECT name,amount FROM quota_limits WHERE owner=?', (owner,))}
            # Replace overrides, never the usage ledger. Empty values restore inherited defaults.
            db.execute('DELETE FROM quota_limits WHERE owner=?', (owner,))
            db.executemany('INSERT INTO quota_limits(owner,name,amount) VALUES(?,?,?)', [(owner, k, v) for k, v in values.items()])
            db.execute('INSERT INTO quota_audit(changed,owner,previous,current) VALUES(?,?,?,?)',
                       (int(self.clock()), owner, json.dumps(previous), json.dumps(values)))
        return self.configuration(owner)

    def check(self, db, owner, amount):
        for name, balance in self.balances(db, owner).items():
            if balance['remaining'] < amount:
                reject(LIMIT_CODES[name])

    def extend(self, request_id, maximum):
        """Atomically reserve the next audio segment before forwarding it to the supplier."""
        if type(maximum) is not int or maximum <= 0:
            raise ValueError('invalid reservation')
        with self.db() as db:
            db.execute('BEGIN IMMEDIATE')
            self.expire(db)
            row = db.execute("SELECT owner,reserved FROM requests WHERE id=? AND state='held'", (request_id,)).fetchone()
            if row is None:
                reject('interrupted', 409)
            if maximum > row['reserved']:
                self.check(db, row['owner'], maximum - row['reserved'])
            db.execute("UPDATE requests SET reserved=?,touched=? WHERE id=? AND state='held'",
                       (max(maximum, row['reserved']), int(self.clock()), request_id))

    def touch(self, request_id):
        with self.db() as db:
            db.execute("UPDATE requests SET touched=? WHERE id=? AND state='held'", (int(self.clock()), request_id))

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
            self.check(db, owner, maximum)
            # Infrastructure bound only: allow independent tools/recording and AI to overlap.
            if db.execute("SELECT COUNT(*) FROM requests WHERE state='held'").fetchone()[0] >= 64:
                reject('busy')
            dates = periods(self.clock())
            db.execute('INSERT INTO requests(id,owner,created,day,week,month,reserved,state,kind,touched) VALUES(?,?,?,?,?,?,?,?,?,?)',
                       (request_id, owner, int(self.clock()), *[dates[k][0].date().isoformat() for k in ('day', 'week', 'siteMonth')], maximum, 'held', kind, int(self.clock())))

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
        clean, prompt_bound, image_count = [], 1024, 0
        for message in messages:
            if not isinstance(message, dict) or message.get('role') not in {'system', 'user', 'assistant'}:
                reject('invalid', 422)
            content = message.get('content')
            if isinstance(content, str):
                prompt_bound += len(content.encode('utf-8')) + 32
            elif isinstance(content, list) and message['role'] == 'user' and 1 <= len(content) <= 64:
                blocks = []
                for block in content:
                    if not isinstance(block, dict):
                        reject('invalid', 422)
                    if block.get('type') == 'text' and isinstance(block.get('text'), str):
                        blocks.append({'type': 'text', 'text': block['text']})
                        prompt_bound += len(block['text'].encode('utf-8')) + 32
                    elif block.get('type') == 'image_url':
                        image_count += 1
                        if image_count > 12:
                            reject('too_large', 413)
                        info = block.get('image_url')
                        url = info.get('url') if isinstance(info, dict) else None
                        if not isinstance(url, str) or len(url) > 12_000_000 or not url.startswith('data:image/') or ';base64,' not in url[:64]:
                            reject('invalid_image', 422)
                        try:
                            data = base64.b64decode(url.split(',', 1)[1], validate=True)
                            if len(data) > 8_388_608:
                                reject('too_large', 413)
                            with Image.open(io.BytesIO(data)) as image:
                                if image.format not in {'PNG', 'JPEG', 'WEBP', 'GIF'} or max(image.size) > 8192:
                                    reject('invalid_image', 422)
                                image.verify()
                        except (ValueError, UnidentifiedImageError, OSError, Image.DecompressionBombError):
                            reject('invalid_image', 422)
                        detail = info.get('detail', 'auto')
                        if detail not in {'auto', 'low', 'high', 'original'}:
                            reject('invalid_image', 422)
                        blocks.append({'type': 'image_url', 'image_url': {'url': url, 'detail': detail}})
                        # Official Vision docs (2026-10-26): at most 1024 tokens/image.
                        prompt_bound += 1024 + 32
                    else:
                        reject('invalid', 422)
                content = blocks
            else:
                reject('invalid', 422)
            clean.append({'role': message['role'], 'content': content})
        if prompt_bound > 196608:
            reject('too_large', 413)
        maximum = raw.get('max_tokens', 4096)
        if type(maximum) is not int or maximum < 1:
            reject('invalid', 422)
        maximum = min(maximum, 32768)
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
                    self.budget.touch(identifier)
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
                    capabilities=['text_chat', 'tool_actions', 'library_text', 'vision', 'transcript_summary', 'text_translation'])
        if base['speechAvailable']:
            base['capabilities'].extend(['push_to_talk', 'long_recording', 'audio_replay', 'realtime_translation', 'classroom_assistant', 'classroom_vocabulary'])
        if not authorization:
            return dict(**base, available=False, reason='login_required', message=MESSAGES['login_required'], limits=ai.budget.configuration()['limits'])
        user = ai.user(authorization)
        balances = ai.budget.status(user['id'])
        reason = '' if ai.enabled else 'disabled'
        if not reason:
            for name, code in LIMIT_CODES.items():
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
        service.rate('shared-ai:' + user['id'], 120, 60)
        try:
            # Reject oversized chunks while reading rather than allocating an unbounded body.
            chunks, size = [], 0
            async for chunk in request.stream():
                size += len(chunk)
                if size > 32 * 1024 * 1024:
                    reject('too_large', 413)
                chunks.append(chunk)
            raw = json.loads(b''.join(chunks))
        except (ValueError, UnicodeError):
            reject('invalid', 422)
        identifier, body, maximum = ai.payload(raw)
        ai.budget.reserve(identifier, user['id'], maximum)
        return StreamingResponse(ai.stream(identifier, body), media_type='text/event-stream',
                                 headers={'X-Accel-Buffering': 'no', 'Cache-Control': 'no-store'})

    from quota_admin import install_quota_admin
    install_quota_admin(app, ai)
    return ai

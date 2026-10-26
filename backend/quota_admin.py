"""Authenticated quota controls. Deployment admin credentials never reach an app client."""
import hmac
import json
import os

from fastapi import Header, HTTPException, Request


def install_quota_admin(app, ai):
    def authorize(token):
        expected = os.environ.get('ADMIN_TOKEN', '')
        if not expected or not hmac.compare_digest(expected, token):
            raise HTTPException(403, '管理员认证失败')

    def account(email):
        if not isinstance(email, str) or len(email) > 254:
            raise HTTPException(422, '邮箱无效')
        with ai.service.db() as db:
            row = db.execute('SELECT id,email,disabled FROM users WHERE email=?', (email.strip().lower(),)).fetchone()
        if row is None:
            raise HTTPException(404, '该邮箱尚未注册大盘助手')
        return dict(row)

    async def payload(request):
        chunks, size = [], 0
        async for chunk in request.stream():
            size += len(chunk)
            if size > 8192:
                raise HTTPException(413, '请求过大')
            chunks.append(chunk)
        try:
            value = json.loads(b''.join(chunks))
        except (ValueError, UnicodeError):
            raise HTTPException(422, '请求格式无效') from None
        if not isinstance(value, dict):
            raise HTTPException(422, '请求格式无效')
        return value

    @app.get('/admin/ai/config')
    def configuration(x_admin_token: str = Header(default='')):
        authorize(x_admin_token)
        return dict(unit='microCny', **ai.budget.configuration(), site={
            k: v for k, v in ai.budget.status('').items() if k.startswith('site')})

    @app.put('/admin/ai/config')
    async def configure(request: Request, x_admin_token: str = Header(default='')):
        authorize(x_admin_token)
        value = await payload(request)
        if set(value) != {'limits'}:
            raise HTTPException(422, '只接受 limits')
        return ai.budget.configure(value['limits'])

    @app.get('/admin/ai/users')
    def users(q: str = '', x_admin_token: str = Header(default='')):
        authorize(x_admin_token)
        if len(q) > 254:
            raise HTTPException(422, '搜索内容过长')
        with ai.service.db() as db:
            # instr treats %, _ and quotes literally. Return only necessary account fields.
            rows = db.execute('SELECT id,email,disabled FROM users WHERE instr(email,?)>0 ORDER BY email LIMIT 100',
                              (q.strip().lower(),)).fetchall()
        return {'users': [dict(email=r['email'], disabled=bool(r['disabled']),
                              **ai.budget.configuration(r['id']), usage=ai.budget.status(r['id'])) for r in rows]}

    @app.get('/admin/ai/user')
    def get_user(email: str, x_admin_token: str = Header(default='')):
        authorize(x_admin_token)
        user = account(email)
        return dict(email=user['email'], disabled=bool(user['disabled']),
                    **ai.budget.configuration(user['id']), usage=ai.budget.status(user['id']))

    @app.put('/admin/ai/user')
    async def configure_user(request: Request, x_admin_token: str = Header(default='')):
        authorize(x_admin_token)
        value = await payload(request)
        if set(value) != {'email', 'limits'}:
            raise HTTPException(422, '需要 email 和 limits')
        user = account(value['email'])
        return dict(email=user['email'], **ai.budget.configure(value['limits'], user['id']),
                    usage=ai.budget.status(user['id']))

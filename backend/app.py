"""Dapan notice/market API. Default binding is localhost; HTTPS is required for public use."""
import base64, contextlib, hashlib, hmac, io, json, os, pathlib, re, secrets, smtplib, sqlite3, ssl, threading, time, uuid
from email.message import EmailMessage
from fastapi import FastAPI, HTTPException, Request, Header
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field, ConfigDict
from PIL import Image, UnidentifiedImageError
from typing import Literal
ROOT=pathlib.Path(__file__).resolve().parent
DOMAINS={'mail.dlut.edu.cn','dlut.edu.cn'}
IMAGE_QUOTA=10*1024*1024

def fail(status,message): raise HTTPException(status,message)
def email_address(value):
 value=value.strip().lower()
 if len(value)>254 or not re.fullmatch(r'[a-z0-9.!#$%&\x27*+/=?^_`{|}~-]+@(mail\.dlut\.edu\.cn|dlut\.edu\.cn)',value): fail(422,'仅支持 @mail.dlut.edu.cn 或 @dlut.edu.cn 邮箱')
 return value

def password_hash(password,salt=None):
 if len(password)<10 or len(password)>128: fail(422,'密码需要 10～128 个字符')
 salt=salt or secrets.token_hex(16)
 digest=hashlib.scrypt(password.encode(),salt=bytes.fromhex(salt),n=131072,r=8,p=1,maxmem=256*1024*1024).hex()
 return salt+':'+digest

def verify_password(password,stored):
 try: return hmac.compare_digest(password_hash(password,stored.split(':')[0]),stored)
 except (HTTPException,ValueError): return False

class Strict(BaseModel): model_config=ConfigDict(extra='forbid')
class SendCode(Strict): email:str=Field(max_length=254);purpose:str='register'
class Register(Strict):
 email:str=Field(max_length=254);code:str=Field(min_length=6,max_length=6);password:str=Field(min_length=10,max_length=128);nickname:str=Field(min_length=1,max_length=24)
class Login(Strict): email:str=Field(max_length=254);password:str=Field(min_length=1,max_length=128)
class Reset(Strict): email:str=Field(max_length=254);code:str=Field(min_length=6,max_length=6);password:str=Field(min_length=10,max_length=128)
class Listing(Strict):
 title:str=Field(min_length=1,max_length=60);description:str=Field(default='',max_length=3000);kind:Literal['buy','sell']='sell'
 # Accept fields from previously installed clients during the transition.
 priceCents:int=Field(default=0,ge=0,le=100000000);category:str=Field(default='',max_length=30)
 condition:str=Field(default='',max_length=30);location:str=Field(default='',max_length=50)
 contacts:dict[str,str];images:list[str]=Field(default_factory=list,max_length=4)
class Status(Strict): status:str
class Report(Strict): reason:str=Field(min_length=2,max_length=500)
class Upload(Strict): data:str=Field(max_length=2900000)

class Service:
 def __init__(self,data_dir,mode='production',sender=None):
  self.root=pathlib.Path(data_dir);self.root.mkdir(parents=True,exist_ok=True);(self.root/'images').mkdir(exist_ok=True)
  self.mode=mode;self.sender=sender;self.lock=threading.RLock()
  keyfile=self.root/'secret.key'
  if not keyfile.exists():
   fd=os.open(keyfile,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
   with os.fdopen(fd,'wb') as f:f.write(secrets.token_bytes(32))
  self.secret=keyfile.read_bytes()
  with self.db() as db: db.executescript('''
   PRAGMA journal_mode=WAL;
   CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY,email TEXT UNIQUE,nickname TEXT,password TEXT,created INTEGER,disabled INTEGER DEFAULT 0);
   CREATE TABLE IF NOT EXISTS codes(email TEXT,purpose TEXT,digest TEXT,expires INTEGER,attempts INTEGER DEFAULT 0,PRIMARY KEY(email,purpose));
   CREATE TABLE IF NOT EXISTS sessions(digest TEXT PRIMARY KEY,user_id TEXT,expires INTEGER);
   CREATE TABLE IF NOT EXISTS limits(k TEXT,bucket INTEGER,n INTEGER,PRIMARY KEY(k,bucket));
   CREATE TABLE IF NOT EXISTS listings(id TEXT PRIMARY KEY,owner TEXT,title TEXT,body TEXT,status TEXT,created INTEGER,updated INTEGER,expires INTEGER);
   CREATE TABLE IF NOT EXISTS images(id TEXT PRIMARY KEY,owner TEXT,created INTEGER);
   CREATE TABLE IF NOT EXISTS reports(id TEXT PRIMARY KEY,listing_id TEXT,reporter TEXT,reason TEXT,created INTEGER,UNIQUE(listing_id,reporter));
  ''')
  with self.db() as db:
   db.execute('BEGIN IMMEDIATE')
   if 'bytes' not in {r['name'] for r in db.execute('PRAGMA table_info(images)')}:
    db.execute('ALTER TABLE images ADD COLUMN bytes INTEGER NOT NULL DEFAULT 0')
   for row in db.execute('SELECT id FROM images WHERE bytes=0').fetchall():
    path=self.root/'images'/f'{row["id"]}.jpg'
    if path.is_file():db.execute('UPDATE images SET bytes=? WHERE id=?',(path.stat().st_size,row['id']))
 @contextlib.contextmanager
 def db(self):
  with self.lock:
   db=sqlite3.connect(self.root/'community.db',timeout=15);db.row_factory=sqlite3.Row
   try: yield db;db.commit()
   except: db.rollback();raise
   finally: db.close()
 def digest(self,text):return hmac.new(self.secret,text.encode(),hashlib.sha256).hexdigest()
 def rate(self,key,maximum,seconds):
  now=int(time.time());bucket=now//seconds;k=self.digest(key)
  with self.db() as db:
   row=db.execute('SELECT n FROM limits WHERE k=? AND bucket=?',(k,bucket)).fetchone()
   if row and row[0]>=maximum:fail(429,'操作太频繁，请稍后再试')
   db.execute('INSERT INTO limits VALUES(?,?,1) ON CONFLICT(k,bucket) DO UPDATE SET n=n+1',(k,bucket))
   db.execute('DELETE FROM limits WHERE bucket<? AND k=?',(bucket-2,k))
 def send(self,email,code,purpose):
  if self.sender:self.sender(email,code,purpose);return
  host=os.environ.get('SMTP_HOST');username=os.environ.get('SMTP_USER');password=os.environ.get('SMTP_PASSWORD');sender=os.environ.get('SMTP_FROM')
  if not all((host,username,password,sender)):fail(503,'邮件服务尚未配置，暂不能发送验证码')
  msg=EmailMessage();msg['Subject']='大盘助手 · 邮箱验证码';msg['From']=sender;msg['To']=email
  msg.set_content(f'你的大盘助手验证码为 {code}，10 分钟有效。用途：'+('注册' if purpose=='register' else '重设密码')+'。请勿告诉他人；如非本人操作，请忽略。')
  with smtplib.SMTP_SSL(host,int(os.environ.get('SMTP_PORT','465')),context=ssl.create_default_context(),timeout=15) as mail:
   mail.login(username,password);mail.send_message(msg)
 def request_code(self,email,purpose,ip):
  email=email_address(email)
  if purpose not in {'register','reset'}:fail(422,'验证码用途错误')
  self.rate('send-ip:'+ip,12,3600);self.rate('send-email:'+email,1,60);self.rate('send-day:'+email,8,86400)
  with self.db() as db:exists=db.execute('SELECT 1 FROM users WHERE email=?',(email,)).fetchone()
  if (purpose=='register' and exists) or (purpose=='reset' and not exists):return {'message':'如果邮箱符合条件，验证码将发送至邮箱，请检查收件箱'}
  code=f'{secrets.randbelow(1000000):06}'
  with self.db() as db:db.execute('INSERT OR REPLACE INTO codes VALUES(?,?,?,?,0)',(email,purpose,self.digest(email+purpose+code),int(time.time())+600))
  try:self.send(email,code,purpose)
  except Exception as exc:
   with self.db() as db:db.execute('DELETE FROM codes WHERE email=? AND purpose=?',(email,purpose))
   if isinstance(exc,HTTPException):raise
   fail(503,'邮件发送失败，请稍后重试')
  return {'message':'如果邮箱符合条件，验证码将发送至邮箱，请检查收件箱'}
 def consume_code(self,db,email,purpose,code):
  row=db.execute('SELECT * FROM codes WHERE email=? AND purpose=?',(email,purpose)).fetchone()
  if not row or row['expires']<time.time() or row['attempts']>=5:fail(400,'验证码无效或已过期，请重新获取')
  if not hmac.compare_digest(row['digest'],self.digest(email+purpose+code)):
   db.execute('UPDATE codes SET attempts=attempts+1 WHERE email=? AND purpose=?',(email,purpose));db.commit();fail(400,'验证码不正确')
  db.execute('DELETE FROM codes WHERE email=? AND purpose=?',(email,purpose))
 def token(self,db,user):
  token=secrets.token_urlsafe(32);db.execute('INSERT INTO sessions VALUES(?,?,?)',(self.digest(token),user['id'],int(time.time())+86400*14))
  return dict(token=token,user=dict(id=user['id'],nickname=user['nickname'],email=user['email']))
 def auth(self,authorization):
  if not authorization or not authorization.startswith('Bearer '):fail(401,'请先登录')
  with self.db() as db:row=db.execute('SELECT u.* FROM sessions s JOIN users u ON u.id=s.user_id WHERE s.digest=? AND s.expires>? AND u.disabled=0',(self.digest(authorization[7:]),int(time.time()))).fetchone()
  if not row:fail(401,'登录已过期，请重新登录')
  return dict(row)
 def public(self,row):
  data=json.loads(row['body']);data.pop('contacts',None)
  if 'kind' not in data:
   # Keep details visible for legacy listings after removing dedicated price/condition fields.
   extra=[]
   if data.get('priceCents') is not None:extra.append('原标价：'+format(data['priceCents']/100,'.2f')+' 元')
   if data.get('condition'):extra.append('成色：'+data['condition'])
   if data.get('location'):extra.append('交易区域：'+data['location'])
   if extra:data['description']+='\n\n'+' · '.join(extra)
  data.setdefault('kind','sell')
  return dict(**data,id=row['id'],seller=row['nickname'],status=row['status'] if row['expires']>time.time() else 'expired',created=row['created'],expires=row['expires'],demo=self.mode=='demo')
 def validate_listing(self,item,owner,db):
  data=item.model_dump();data['title']=data['title'].strip();data['description']=data['description'].strip()
  if not data['title']:fail(422,'请填写主题')
  if not item.contacts or set(item.contacts)-{'QQ','微信','手机'}:fail(422,'请选择至少一种联系方式')
  for kind,value in item.contacts.items():
   value=value.strip();data['contacts'][kind]=value
   if not value.strip() or len(value)>60 or any(ord(c)<32 for c in value):fail(422,'联系方式格式错误')
   if kind=='QQ' and not re.fullmatch(r'[1-9][0-9]{4,11}',value):fail(422,'QQ 号格式不正确')
   if kind=='手机' and not re.fullmatch(r'1[3-9][0-9]{9}',value):fail(422,'手机号格式不正确')
  if len(set(item.images))!=len(item.images):fail(422,'不能重复添加同一张图片')
  for image in item.images:
   if not db.execute('SELECT 1 FROM images WHERE id=? AND owner=?',(image,owner)).fetchone():fail(422,'图片无效或不属于当前账号')
  return data
 def usage(self,db,owner):
  return {'usedBytes':db.execute('SELECT COALESCE(SUM(bytes),0) FROM images WHERE owner=?',(owner,)).fetchone()[0],'limitBytes':IMAGE_QUOTA}
 def release_images(self,owner,ids=None):
  # Serialize reference checks with publication/upload, including other worker processes.
  with self.db() as db:
   db.execute('BEGIN IMMEDIATE')
   referenced={image for row in db.execute('SELECT body FROM listings WHERE owner=?',(owner,)) for image in json.loads(row['body']).get('images',[])}
   for row in db.execute('SELECT id FROM images WHERE owner=?',(owner,)).fetchall():
    identifier=row['id']
    if identifier in referenced or (ids is not None and identifier not in ids):continue
    try:(self.root/'images'/f'{identifier}.jpg').unlink(missing_ok=True)
    except OSError:continue  # Retain the charge if disk removal did not succeed.
    db.execute('DELETE FROM images WHERE id=?',(identifier,))
   return self.usage(db,owner)

def create_app(data_dir=None,mode=None,sender=None):
 service=Service(data_dir or os.environ.get('DAPAN_DATA',ROOT/'data'),mode or os.environ.get('DAPAN_MODE','production'),sender)
 app=FastAPI(title='大盘助手校园服务',docs_url=None,redoc_url=None);app.state.service=service
 from shared_ai import install_shared_ai
 install_shared_ai(app,service)
 from shared_speech import install_shared_speech
 install_shared_speech(app)
 from conversation_feedback import install_conversation_feedback
 install_conversation_feedback(app,service)
 @app.middleware('http')
 async def cap_body(request,call_next):
  # Bound the actual streamed body, not just a client supplied Content-Length.
  if request.method in {'POST','PUT','PATCH'}:
   limit=1_000_000 if request.url.path=='/feedback/conversations' else (32 if request.url.path=='/ai/chat/completions' else 3)*1024*1024
   body=bytearray()
   async for chunk in request.stream():
    body.extend(chunk)
    if len(body)>limit:
     from fastapi.responses import JSONResponse
     return JSONResponse({'detail':'请求过大'},status_code=413)
   request._body=bytes(body)
  response=await call_next(request);response.headers['X-Content-Type-Options']='nosniff';response.headers['Cache-Control']='no-store';return response
 def ip(request):return request.client.host if request.client else 'unknown'
 @app.get('/health')
 def health():return {'ok':True,'demo':service.mode=='demo','emailConfigured':service.sender is not None or all(os.environ.get(k) for k in ('SMTP_HOST','SMTP_USER','SMTP_PASSWORD','SMTP_FROM'))}
 @app.get('/reset-radar')
 def reset_radar():
  from reset_radar import cached
  return cached(service.root/'reset-radar.json')
 @app.get('/notices')
 def notices():
  file=service.root/'notices.json'
  if not file.exists():fail(503,'通知暂未同步')
  return json.loads(file.read_text(encoding='utf-8'))
 @app.post('/auth/code')
 def code(body:SendCode,request:Request):return service.request_code(body.email,body.purpose,ip(request))
 @app.post('/auth/register')
 def register(body:Register,request:Request):
  service.rate('register:'+ip(request),15,3600);email=email_address(body.email)
  nickname=body.nickname.strip()
  if not nickname:fail(422,'请填写昵称')
  with service.db() as db:
   if db.execute('SELECT 1 FROM users WHERE email=?',(email,)).fetchone():fail(400,'无法完成注册，请检查信息或尝试登录')
   service.consume_code(db,email,'register',body.code)
   user=dict(id=str(uuid.uuid4()),email=email,nickname=nickname)
   db.execute('INSERT INTO users(id,email,nickname,password,created) VALUES(?,?,?,?,?)',(user['id'],email,nickname,password_hash(body.password),int(time.time())))
   return service.token(db,user)
 @app.post('/auth/login')
 def login(body:Login,request:Request):
  email=email_address(body.email);service.rate('login-ip:'+ip(request),40,900);service.rate('login-account:'+email,10,900)
  with service.db() as db:
   user=db.execute('SELECT * FROM users WHERE email=?',(email,)).fetchone()
   if not user or user['disabled'] or not verify_password(body.password,user['password']):fail(401,'邮箱或密码不正确')
   return service.token(db,user)
 @app.post('/auth/reset')
 def reset(body:Reset,request:Request):
  email=email_address(body.email);service.rate('reset:'+ip(request),15,3600)
  with service.db() as db:
   service.consume_code(db,email,'reset',body.code)
   user=db.execute('SELECT id FROM users WHERE email=?',(email,)).fetchone()
   if not user:fail(400,'无法重设密码')
   db.execute('UPDATE users SET password=? WHERE email=?',(password_hash(body.password),email));db.execute('DELETE FROM sessions WHERE user_id=?',(user['id'],))
  return {'message':'密码已重设，请重新登录'}
 @app.post('/auth/logout')
 def logout(authorization:str|None=Header(default=None)):
  if authorization:
   with service.db() as db:db.execute('DELETE FROM sessions WHERE digest=?',(service.digest(authorization.removeprefix('Bearer ')),))
  return {'ok':True}
 @app.get('/me')
 def me(authorization:str|None=Header(default=None)):
  user=service.auth(authorization)
  with service.db() as db:return dict(**{k:user[k] for k in ('id','nickname','email')},images=service.usage(db,user['id']))
 @app.get('/listings')
 def listings(request:Request,q:str='',category:str='',kind:str='',mine:bool=False,offset:int=0,authorization:str|None=Header(default=None)):
  service.rate('browse:'+ip(request),120,60)
  if len(q)>100 or offset<0 or offset>1000:fail(422,'查询范围无效')
  if kind not in {'','buy','sell'}:fail(422,'请选择收或出')
  owner=service.auth(authorization)['id'] if mine else None
  with service.db() as db:
   rows=db.execute('SELECT l.*,u.nickname FROM listings l JOIN users u ON u.id=l.owner WHERE u.disabled=0 AND ((? IS NULL AND l.status=\'active\' AND l.expires>?) OR (? IS NOT NULL AND l.owner=? AND l.status!=\'hidden\')) ORDER BY l.created DESC',(owner,int(time.time()),owner,owner)).fetchall()
  result=[service.public(r) for r in rows if r['status']!='deleted' and (not q or q.lower() in (r['title']+json.loads(r['body'])['description']).lower()) and (not kind or json.loads(r['body']).get('kind','sell')==kind) and (not category or json.loads(r['body']).get('category')==category)]
  return {'items':result[offset:offset+30],'hasMore':len(result)>offset+30,'demo':service.mode=='demo'}
 @app.get('/listings/{listing_id}')
 def detail(listing_id:str,request:Request,authorization:str|None=Header(default=None)):
  service.rate('detail:'+ip(request),60,60)
  with service.db() as db:row=db.execute('SELECT l.*,u.nickname FROM listings l JOIN users u ON u.id=l.owner WHERE l.id=? AND u.disabled=0',(listing_id,)).fetchone()
  if not row or row['status'] in {'hidden','deleted'}:fail(404,'信息不存在或已下架')
  result=service.public(row)
  if result['status']=='active':result['contacts']=json.loads(row['body'])['contacts']
  else:result['contacts']={}
  result['mine']=False
  if authorization:result['mine']=service.auth(authorization)['id']==row['owner']
  return result
 @app.post('/listings')
 def publish(body:Listing,authorization:str|None=Header(default=None)):
  user=service.auth(authorization);service.rate('publish:'+user['id'],5,86400);now=int(time.time());identifier=str(uuid.uuid4())
  with service.db() as db:
   db.execute('BEGIN IMMEDIATE');data=service.validate_listing(body,user['id'],db)
   if db.execute('SELECT COUNT(*) FROM listings WHERE owner=? AND status=\'active\' AND expires>?',(user['id'],now)).fetchone()[0]>=20:fail(400,'展示中的信息最多 20 条，请先整理旧信息')
   if db.execute('SELECT 1 FROM listings WHERE owner=? AND title=? AND created>? AND status NOT IN (\'withdrawn\',\'deleted\')',(user['id'],data['title'],now-86400)).fetchone():fail(409,'今天已发布过同主题信息，请编辑原信息')
   db.execute('INSERT INTO listings VALUES(?,?,?,?,?,?,?,?)',(identifier,user['id'],data['title'],json.dumps(data,ensure_ascii=False),'active',now,now,now+86400*30))
  return {'id':identifier}
 @app.put('/listings/{listing_id}')
 def edit(listing_id:str,body:Listing,authorization:str|None=Header(default=None)):
  user=service.auth(authorization);service.rate('edit:'+user['id'],60,3600)
  with service.db() as db:
   db.execute('BEGIN IMMEDIATE');data=service.validate_listing(body,user['id'],db)
   row=db.execute('SELECT owner,status,body FROM listings WHERE id=?',(listing_id,)).fetchone()
   if not row or row['owner']!=user['id']:fail(403,'只能编辑自己的信息')
   if row['status']!='active':fail(400,'已下架的信息不能修改')
   db.execute('UPDATE listings SET title=?,body=?,updated=? WHERE id=?',(data['title'],json.dumps(data,ensure_ascii=False),int(time.time()),listing_id))
   old_images=set(json.loads(row['body']).get('images',[]))-set(data['images'])
  service.release_images(user['id'],old_images)
  return {'ok':True,'id':listing_id}
 @app.delete('/listings/{listing_id}')
 def delete_listing(listing_id:str,authorization:str|None=Header(default=None)):
  user=service.auth(authorization)
  with service.db() as db:
   db.execute('BEGIN IMMEDIATE');row=db.execute('SELECT * FROM listings WHERE id=?',(listing_id,)).fetchone()
   if not row or row['owner']!=user['id']:fail(403,'只能删除自己的信息')
   data=json.loads(row['body']);old_images=set(data.get('images',[]));data['images']=[];data['contacts']={}
   db.execute('UPDATE listings SET status=\'deleted\',body=?,updated=? WHERE id=?',(json.dumps(data,ensure_ascii=False),int(time.time()),listing_id))
  return service.release_images(user['id'],old_images)
 @app.patch('/listings/{listing_id}')
 def status(listing_id:str,body:Status,authorization:str|None=Header(default=None)):
  user=service.auth(authorization)
  if body.status not in {'sold','withdrawn'}:fail(422,'状态无效')
  with service.db() as db:
   db.execute('BEGIN IMMEDIATE')
   row=db.execute('SELECT owner,status FROM listings WHERE id=?',(listing_id,)).fetchone()
   if not row or row['owner']!=user['id']:fail(403,'只能管理自己的信息')
   if row['status'] in {'hidden','deleted'}:fail(403,'此信息已删除或由管理员下架')
   db.execute('UPDATE listings SET status=?,updated=? WHERE id=?',(body.status,int(time.time()),listing_id))
  return {'ok':True}
 @app.post('/listings/{listing_id}/report')
 def report(listing_id:str,body:Report,authorization:str|None=Header(default=None)):
  user=service.auth(authorization);service.rate('report:'+user['id'],10,86400)
  with service.db() as db:
   if not db.execute('SELECT 1 FROM listings WHERE id=?',(listing_id,)).fetchone():fail(404,'信息不存在')
   db.execute('INSERT OR IGNORE INTO reports VALUES(?,?,?,?,?)',(str(uuid.uuid4()),listing_id,user['id'],body.reason,int(time.time())))
  return {'message':'举报已记录，等待管理员核查'}
 @app.post('/images')
 def upload(body:Upload,authorization:str|None=Header(default=None)):
  user=service.auth(authorization);service.rate('images:'+user['id'],24,86400)
  try:
   raw=base64.b64decode(body.data,validate=True)
   if len(raw)>2*1024*1024:fail(413,'图片不能超过 2 MB')
   with Image.open(io.BytesIO(raw)) as img:
    if img.width*img.height>20000000:fail(413,'图片尺寸过大')
    from PIL import ImageOps
    img=ImageOps.exif_transpose(img).convert('RGB');img.thumbnail((1600,1600))
    for quality in (85,75,65,55,45):
     out=io.BytesIO();img.save(out,'JPEG',quality=quality,optimize=True)
     if out.tell()<=500*1024:break
  except (ValueError,UnidentifiedImageError,OSError,Image.DecompressionBombError):fail(422,'图片无法读取')
  payload=out.getvalue();identifier=str(uuid.uuid4());path=service.root/'images'/f'{identifier}.jpg'
  try:
   with service.db() as db:
    db.execute('BEGIN IMMEDIATE');usage=service.usage(db,user['id'])
    if usage['usedBytes']+len(payload)>IMAGE_QUOTA:fail(413,'账号图片总容量不能超过 10 MB，请删除旧图片或清理未发布图片')
    path.write_bytes(payload)
    db.execute('INSERT INTO images(id,owner,created,bytes) VALUES(?,?,?,?)',(identifier,user['id'],int(time.time()),len(payload)))
    usage=service.usage(db,user['id'])
  except BaseException:
   path.unlink(missing_ok=True);raise
  return dict(id=identifier,bytes=len(payload),**usage)
 @app.delete('/images/unused')
 def clean_images(authorization:str|None=Header(default=None)):
  user=service.auth(authorization);return service.release_images(user['id'])
 @app.delete('/images/{image_id}')
 def remove_image(image_id:str,authorization:str|None=Header(default=None)):
  user=service.auth(authorization);return service.release_images(user['id'],{image_id})
 @app.get('/images/{image_id}')
 def image(image_id:str):
  if not re.fullmatch(r'[a-f0-9-]{36}',image_id):fail(404,'图片不存在')
  path=service.root/'images'/f'{image_id}.jpg'
  if not path.exists():fail(404,'图片不存在')
  return FileResponse(path,media_type='image/jpeg')
 @app.get('/admin/reports')
 def admin_reports(x_admin_token:str=Header(default='')):
  expected=os.environ.get('ADMIN_TOKEN','')
  if not expected or not hmac.compare_digest(expected,x_admin_token):fail(403,'管理员认证失败')
  with service.db() as db:return {'items':[dict(r) for r in db.execute('SELECT * FROM reports ORDER BY created DESC LIMIT 100')]}
 @app.post('/admin/listings/{listing_id}/hide')
 def admin_hide(listing_id:str,x_admin_token:str=Header(default='')):
  expected=os.environ.get('ADMIN_TOKEN','')
  if not expected or not hmac.compare_digest(expected,x_admin_token):fail(403,'管理员认证失败')
  with service.db() as db:db.execute('UPDATE listings SET status=\'hidden\',updated=? WHERE id=?',(int(time.time()),listing_id))
  return {'ok':True}
 return app

app=create_app()

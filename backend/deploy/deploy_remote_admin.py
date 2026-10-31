"""Deploy the HTTPS admin UI without restarting or changing the existing App API.

Provision the existing administrator hash over SSH; never print or put it in the
source tree. All endpoints requiring the legacy API token remain publicly blocked.
"""
import base64
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SSH = ['ssh.exe', '-F', 'NUL', '-i', os.environ['DAPAN_SSH_KEY'], '-o', 'IdentitiesOnly=yes',
       '-o', 'StrictHostKeyChecking=yes', '-o', 'ProxyCommand=none', '-o', 'ProxyJump=none',
       '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', os.environ['DAPAN_SSH_HOST']]

REMOTE = r'''
import ast,base64,hashlib,json,os,pathlib,pwd,shutil,subprocess,tempfile,time,urllib.request
P=pathlib.Path;os.umask(0o077);python='/opt/dapan/venv/bin/python'
files={name:base64.b64decode(raw) for name,raw in payload.items()}
credential=base64.b64decode(credential)
record=json.loads(credential)
assert record['algorithm']=='scrypt' and record['version']==1
assert len(bytes.fromhex(record['salt']))==16 and len(bytes.fromhex(record['hash']))==32
for name,raw in files.items():
 if name.endswith('.py'):ast.parse(raw)
stage=P(tempfile.mkdtemp(prefix='remote-admin-stage-',dir='/opt/dapan'))
for name,raw in files.items():(stage/name).write_bytes(raw)
test=subprocess.run([python,'-m','pytest','test_remote_console.py','-q','--tb=short','--basetemp='+str(stage/'tests')],cwd=stage,capture_output=True,text=True,timeout=90)
(stage/'tests.log').write_text(test.stdout+test.stderr)
assert test.returncode==0,'Isolated admin checks failed; production unchanged: '+str(stage)
auth=P('/opt/dapan/admin-auth');root=P('/opt/dapan/admin-console')
if (auth/'administrator.json').exists():
 assert json.loads((auth/'administrator.json').read_text())==record,'Remote account already differs; migration stopped'
nginx=P('/etc/nginx/conf.d/dapan.conf');original=nginx.read_text()
assert 'location ^~ /admin/ { return 404; }' in original
pid=subprocess.check_output(['systemctl','show','--property=MainPID','--value','dapan-api.service'],text=True).strip()
api_source={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in P('/opt/dapan/backend').glob('*.py')}
env=dict(x.split(b'=',1) for x in P('/proc/'+pid+'/environ').read_bytes().split(b'\0') if b'=' in x)
token=env[b'ADMIN_TOKEN'].decode()
assert len(token)>=20 and all(c.isalnum() or c in '_-.' for c in token),'Unexpected token encoding'
backup=P('/opt/dapan/deploy-backups')/('remote-admin-'+time.strftime('%Y%m%dT%H%M%SZ',time.gmtime()));backup.mkdir()
shutil.copy2(nginx,backup/'dapan.conf')
unit=P('/etc/systemd/system/dapan-admin.service');envfile=P('/etc/dapan-admin.env')
existing={str(p):p.exists() for p in (root,unit,envfile)}
if root.exists():shutil.copytree(root,backup/'admin-console')
for p in (unit,envfile):
 if p.exists():shutil.copy2(p,backup/p.name)
try:
 try:owner=pwd.getpwnam('dapan-admin')
 except KeyError:
  subprocess.run(['useradd','--system','--no-create-home','--shell','/usr/sbin/nologin','dapan-admin'],check=True)
  owner=pwd.getpwnam('dapan-admin')
 auth.mkdir(exist_ok=True,mode=0o700);auth.chmod(0o700);os.chown(auth,owner.pw_uid,owner.pw_gid)
 if not (auth/'administrator.json').exists():
  descriptor=os.open(auth/'administrator.json',os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
  with os.fdopen(descriptor,'wb') as stream:stream.write(credential)
 os.chown(auth/'administrator.json',owner.pw_uid,owner.pw_gid)
 root.mkdir(exist_ok=True,mode=0o755);root.chmod(0o755)
 for name,raw in files.items():
  if name.startswith('test_'):continue
  path=root/name;path.write_bytes(raw);path.chmod(0o644)
 envfile.write_text('ADMIN_TOKEN='+token+'\nDAPAN_ADMIN_AUTH_DIR=/opt/dapan/admin-auth\nDAPAN_ADMIN_ORIGIN=https://39.105.161.86\n');envfile.chmod(0o600)
 unit.write_text("""[Unit]
Description=Dapan HTTPS administrator console
After=network.target dapan-api.service

[Service]
Type=simple
User=dapan-admin
Group=dapan-admin
WorkingDirectory=/opt/dapan/admin-console
EnvironmentFile=/etc/dapan-admin.env
Environment=PYTHONDONTWRITEBYTECODE=1
ExecStart=/opt/dapan/venv/bin/python -m uvicorn remote_console:production_app --factory --host 127.0.0.1 --port 8766 --workers 1 --no-access-log --proxy-headers --forwarded-allow-ips=127.0.0.1
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/dapan/admin-auth
ProtectKernelTunables=true
ProtectControlGroups=true
RestrictSUIDSGID=true
CapabilityBoundingSet=
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
MemoryMax=256M
TasksMax=64

[Install]
WantedBy=multi-user.target
""");unit.chmod(0o644)
 subprocess.run(['systemctl','daemon-reload'],check=True)
 subprocess.run(['systemctl','enable','--now','dapan-admin.service'],check=True,capture_output=True)
 if existing[str(root)]:subprocess.run(['systemctl','restart','dapan-admin.service'],check=True)
 def fetch(path):
  request=urllib.request.Request('http://127.0.0.1:8766'+path,headers={'Host':'39.105.161.86','X-Forwarded-Proto':'https'})
  with urllib.request.urlopen(request,timeout=5) as response:return response.read().decode()
 for _ in range(40):
  try:html=fetch('/manage/feedback');break
  except Exception:time.sleep(.25)
 else:raise RuntimeError('Admin service did not start')
 assert "mode='login'" in html and '__BASE__' not in html and token not in html
 block=""" # BEGIN DAPAN REMOTE ADMIN
 location = /manage { return 303 /manage/feedback; }
 location = /manage/auth/login {
  limit_req zone=dapan_admin_login burst=5 nodelay;
  client_max_body_size 8k;
  proxy_pass http://127.0.0.1:8766;
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-For $remote_addr;
  proxy_set_header X-Forwarded-Proto https;
  proxy_read_timeout 25s;
  proxy_connect_timeout 3s;
 }
 location ^~ /manage/ {
  client_max_body_size 20k;
  proxy_pass http://127.0.0.1:8766;
  proxy_set_header Host $host;
  proxy_set_header X-Forwarded-For $remote_addr;
  proxy_set_header X-Forwarded-Proto https;
  proxy_read_timeout 25s;
  proxy_connect_timeout 3s;
 }
 # END DAPAN REMOTE ADMIN
"""
 new=original
 if '# BEGIN DAPAN REMOTE ADMIN' not in new:
  new=new.replace(' location ^~ /admin/',block+' location ^~ /admin/',1)
 if 'zone=dapan_admin_login:10m' not in new:
  new='limit_req_zone $binary_remote_addr zone=dapan_admin_login:10m rate=5r/m;\n'+new
 nginx.write_text(new)
 subprocess.run(['nginx','-t'],check=True,capture_output=True)
 subprocess.run(['systemctl','reload','nginx'],check=True)
 assert subprocess.check_output(['systemctl','show','--property=MainPID','--value','dapan-api.service'],text=True).strip()==pid
 assert api_source=={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in P('/opt/dapan/backend').glob('*.py')}
 # Exercise the complete authenticated proxy against the real API, without altering quotas/feedback.
 smoke="""import json,sys
from fastapi.testclient import TestClient
from remote_console import create_app,COOKIE
app=create_app('/opt/dapan/admin-auth',sys.stdin.read().strip())
with TestClient(app,base_url='https://39.105.161.86') as c:
 token=app.state.auth.issue_session()
 c.cookies.set(COOKIE,token)
 csrf=app.state.auth.session(token)['csrf']
 for p in ['/manage/admin/ai/config','/manage/admin/feedback?limit=1']:
  r=c.get(p,headers={'X-Local-CSRF':csrf})
  assert r.status_code==200
 print(json.dumps({'authenticatedReadOnlyProxy':True}))
"""
 check=subprocess.run([python,'-c',smoke],cwd=root,input=token,capture_output=True,text=True,timeout=20)
 assert check.returncode==0,'Authenticated proxy smoke check failed'
 report={'deployed':True,'url':'https://39.105.161.86/manage/feedback','quotaUrl':'https://39.105.161.86/manage/',
  'tests':test.stdout.splitlines()[-1],'existingAccountPreserved':True,'appApiUnchanged':True,'appApiNotRestarted':True,
  'publicTokenApiStillBlocked':True,'authenticatedReadOnlyProxy':True,'backup':str(backup),'stage':str(stage)}
 (backup/'report.json').write_text(json.dumps(report,indent=2))
 print(json.dumps(report))
except BaseException:
 nginx.write_text(original)
 subprocess.run(['nginx','-t'],capture_output=True)
 subprocess.run(['systemctl','reload','nginx'],capture_output=True)
 subprocess.run(['systemctl','stop','dapan-admin.service'],capture_output=True)
 if existing[str(root)]:shutil.copytree(backup/'admin-console',root,dirs_exist_ok=True)
 for p in (unit,envfile):
  if existing[str(p)]:shutil.copy2(backup/p.name,p)
  elif p.exists():p.unlink()
 subprocess.run(['systemctl','daemon-reload'],capture_output=True)
 if existing[str(unit)]:subprocess.run(['systemctl','start','dapan-admin.service'],capture_output=True)
 raise RuntimeError('Admin deployment verification failed; existing public routes restored: '+str(backup)) from None
'''


if __name__ == '__main__':
    files = {name: (ROOT / 'tools/admin' / name).read_bytes() for name in
             ('remote_console.py', 'console_auth.py', 'quota_login.html', 'quota_console.html', 'feedback_console.html')}
    files['test_remote_console.py'] = (ROOT / 'backend/test_remote_console.py').read_bytes()
    record = Path(os.environ['LOCALAPPDATA']) / 'DapanAssistant/quota-admin/administrator.json'
    credential = base64.b64encode(record.read_bytes()).decode()
    payload = {name: base64.b64encode(raw).decode() for name, raw in files.items()}
    program = 'payload=' + repr(payload) + '\ncredential=' + repr(credential) + '\n' + REMOTE
    result = subprocess.run(SSH + ['python3', '-'], input=program.encode(), capture_output=True, timeout=200)
    if result.returncode:
        print(result.stderr.decode(errors='replace')[-2000:])
        raise SystemExit(result.returncode)
    report = json.loads(result.stdout)
    output = ROOT / 'validation/1.0.1-feedback/remote-admin-deployment.json'
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))

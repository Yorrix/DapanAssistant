"""Deploy the reviewed voice relay and secure credential installer, without supplying a key."""
import base64,json,pathlib,subprocess
from deploy_shared_ai import ROOT,SSH

source=r'''
import ast,base64,json,os,pathlib,shutil,subprocess,tempfile,time,urllib.request
P=pathlib.Path;root=P('/opt/dapan/backend');venv='/opt/dapan/venv/bin/python';os.umask(0o077)
files={name:base64.b64decode(raw) for name,raw in payload.items()}
for name,raw in files.items():
 if name.endswith('.py'):ast.parse(raw)
stage=P(tempfile.mkdtemp(prefix='shared-speech-staging-',dir='/opt/dapan'))
for name in ('app.py','shared_ai.py'):shutil.copy2(root/name,stage/name)
for name,raw in files.items():
 path=stage/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
test=subprocess.run([venv,'-m','pytest','test_shared_ai.py','test_shared_speech.py','-q','--tb=short','--basetemp='+str(stage/'tmp')],cwd=stage,text=True,capture_output=True)
(stage/'tests.txt').write_text(test.stdout+test.stderr)
assert test.returncode==0,'Isolated relay tests failed: '+str(stage)
conf=P('/etc/nginx/conf.d/dapan.conf');original=conf.read_text()
location=''' + repr(''' location = /ai/speech {
  proxy_pass http://127.0.0.1:8765;
  proxy_http_version 1.1;
  proxy_set_header Upgrade $http_upgrade;
  proxy_set_header Connection "upgrade";
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-For $remote_addr;
  proxy_set_header X-Forwarded-Proto https;
  proxy_read_timeout 100s;
  proxy_send_timeout 100s;
  proxy_connect_timeout 5s;
  proxy_buffering off;
 }
''') + r'''
updated=original
if 'location = /ai/speech' not in original:
 anchor=' location ^~ /components/ {'
 assert original.count(anchor)==1
 updated=original.replace(anchor,location+anchor)
backup=P('/opt/dapan/deploy-backups')/('shared-speech-'+str(time.time_ns()));backup.mkdir()
targets={'shared_ai.py':root/'shared_ai.py','shared_speech.py':root/'shared_speech.py','configurator.py':root/'deploy/configure_shared_speech_key.py','nginx.conf':conf}
prior={name:path.exists() for name,path in targets.items()}
for name,path in targets.items():
 if prior[name]:shutil.copy2(path,backup/name)
try:
 for name in ('shared_ai.py','shared_speech.py','deploy/configure_shared_speech_key.py'):
  (root/name).write_bytes(files[name]);(root/name).chmod(0o644)
 conf.write_text(updated);conf.chmod(0o644)
 subprocess.run(['nginx','-t'],check=True,capture_output=True)
 subprocess.run(['systemctl','restart','dapan-api.service'],check=True)
 subprocess.run(['systemctl','reload','nginx'],check=True)
 for i in range(30):
  try:
   with urllib.request.urlopen('http://127.0.0.1:8765/health',timeout=3) as r:assert r.status==200
   break
  except Exception:time.sleep(.2)
 else:raise RuntimeError('API not ready')
 probe="import asyncio,json;from websockets.asyncio.client import connect\nasync def main():\n async with connect('wss://39.105.161.86/ai/speech',open_timeout=10) as ws:\n  r=json.loads(await ws.recv());assert r['header']['error_code']=='login_required';print('WebSocket upgrade and authorization passed')\nasyncio.run(main())"
 check=subprocess.run([venv,'-c',probe],text=True,capture_output=True,check=True)
 print(json.dumps({'tests':test.stdout.splitlines()[-1],'backup':str(backup),'websocket':check.stdout.strip(),'configurator':'/opt/dapan/backend/deploy/configure_shared_speech_key.py','python':venv}))
except BaseException:
 for name,path in targets.items():
  if prior[name]:shutil.copy2(backup/name,path)
  elif path.exists():path.unlink()
 subprocess.run(['systemctl','restart','dapan-api.service']);subprocess.run(['systemctl','reload','nginx'])
 raise RuntimeError('Relay deployment rolled back: '+str(backup)) from None
'''

files=['shared_ai.py','shared_speech.py','test_shared_speech.py','test_shared_ai.py','conftest.py','deploy/configure_shared_speech_key.py']
payload={name:base64.b64encode((ROOT/name).read_bytes()).decode() for name in files}
remote='payload='+repr(payload)+'\n'+source
result=subprocess.run(SSH+['python3','-'],input=remote.encode(),capture_output=True)
print(result.stdout.decode());print(result.stderr.decode())
if result.returncode:raise SystemExit(result.returncode)
(ROOT/'deploy/shared-speech-deployment-result.json').write_text(result.stdout.decode(),encoding='utf-8')

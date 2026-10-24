"""Run locally: staged tests + one tiny authorized provider probe, then backed-up deployment.

No provider credentials leave the server. Defaults to a dry run.
"""
import argparse
import os
import base64
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
_host=os.environ.get('DAPAN_SSH_HOST','').strip()
_key=os.environ.get('DAPAN_SSH_KEY','').strip()
if not _host or not _key:
    raise SystemExit('Set DAPAN_SSH_HOST and DAPAN_SSH_KEY explicitly before deployment')
SSH = ['ssh','-i',_key,'-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes',
       '-o','BatchMode=yes','-o','ConnectTimeout=10',_host]


REMOTE = r'''
import ast,base64,hashlib,json,os,pathlib,pwd,shutil,sqlite3,subprocess,sys,tempfile,time,urllib.request
from datetime import datetime,timezone
P=pathlib.Path
root=P('/opt/dapan/backend');data=P('/opt/dapan/data');venv='/opt/dapan/venv/bin/python'
stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
report={'phase':'preflight','apply':payload['apply']}
original=(root/'app.py').read_text()
anchor=" app=FastAPI(title='大盘助手校园服务',docs_url=None,redoc_url=None);app.state.service=service"
assert original.count(anchor)==1,'Unrecognized app factory'
updated=original
if 'install_shared_ai(app,service)' not in updated:
 updated=updated.replace(anchor,anchor+'\n from shared_ai import install_shared_ai\n install_shared_ai(app,service)')
sources={name:base64.b64decode(value) for name,value in payload['files'].items()}
sources['app.py']=updated.encode()
if 'shared_speech.py' in sources and 'install_shared_speech(app)' not in updated:
 updated=updated.replace(' install_shared_ai(app,service)',' install_shared_ai(app,service)\n from shared_speech import install_shared_speech\n install_shared_speech(app)')
 sources['app.py']=updated.encode()
for name,source in sources.items():
 if name.endswith('.py'):ast.parse(source)
report['files']=sorted(sources)
report['keyAvailable']=any(line.startswith('DEEPSEEK_API_KEY=') and line.split('=',1)[1].strip() for line in P('/etc/dapan-notices.env').read_text().splitlines())
assert report['keyAvailable'],'Existing authorized provider key is unavailable'
if not payload['apply']:
 print(json.dumps(report));sys.exit(0)
os.umask(0o077)
stage=P(tempfile.mkdtemp(prefix='shared-ai-staging-',dir='/opt/dapan'))
for name,source in sources.items():
 target=stage/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(source)
for name in ('test_shared_ai.py','conftest.py'):
 assert (stage/name).is_file()
if 'shared_speech.py' in sources:
 prior_umask=os.umask(0o022)
 try:subprocess.run([venv,'-m','pip','install','websockets==15.0.1'],check=True,stdout=subprocess.DEVNULL)
 finally:os.umask(prior_umask)
 subprocess.run(['runuser','-u','dapan','--',venv,'-c','import websockets; assert websockets.__version__=="15.0.1"'],check=True)
tests=subprocess.run([venv,'-m','pytest','test_shared_ai.py','-q','--tb=short','--basetemp='+str(stage/'pytest-tmp')],cwd=stage,text=True,capture_output=True)
(stage/'test-result.txt').write_text(tests.stdout+tests.stderr)
assert tests.returncode==0,'Staged isolated tests failed; production unchanged: '+str(stage)
report['tests']=tests.stdout.splitlines()[-1]
# Existing key stays in this process and a private service EnvironmentFile only.
key_line=next(line for line in P('/etc/dapan-notices.env').read_text().splitlines() if line.startswith('DEEPSEEK_API_KEY='))
key=key_line.split('=',1)[1].strip().strip(chr(34)).strip(chr(39))
env=os.environ.copy();env.update(DEEPSEEK_API_KEY=key,SHARED_AI_ENABLED='1',DAPAN_DATA=str(stage/'probe-data'),DAPAN_MODE='test')
probe=r"""
import json,uuid
from fastapi.testclient import TestClient
from app import app
ai=app.state.shared_ai
# Isolated service validation identity; no school account is created or impersonated.
ai.user=lambda authorization:{'id':'deployment-probe'}
body={'request_id':str(uuid.uuid4()),'max_tokens':32,'response_format':{'type':'json_object'},
      'messages':[{'role':'user','content':'Reply with exactly this JSON: {"ok":true}'}]}
response=TestClient(app).post('/ai/chat/completions',json=body,headers={'Authorization':'Bearer isolated-probe'})
text='';done=False
for line in response.text.splitlines():
 if not line.startswith('data:'):continue
 raw=line[5:].strip()
 if raw=='[DONE]':done=True;continue
 item=json.loads(raw)
 assert 'dapan_error' not in item,'Provider did not complete the isolated request'
 for choice in item.get('choices',[]):text+=choice.get('delta',{}).get('content','')
print('PROBE_TEXT='+repr(text))
assert response.status_code==200 and done and json.loads(text)=={'ok':True},'Provider result mismatch: '+repr((response.status_code,done,text))
with ai.budget.db() as db:
 row=dict(db.execute('SELECT * FROM requests').fetchone())
 assert row['state']=='charged' and row['charged']>0,'Provider usage missing'
 print(json.dumps({k:row[k] for k in ('id','charged','input_tokens','output_tokens')}))
"""
real=subprocess.run([venv,'-c',probe],cwd=stage,env=env,text=True,capture_output=True)
(stage/'probe-result.txt').write_text((real.stdout+real.stderr).replace(key,'[redacted]'))
assert real.returncode==0,'Isolated real provider probe failed; production unchanged: '+(real.stdout[-500:]+real.stderr[-1500:]).replace(key,'[redacted]')
probe_result=json.loads(real.stdout.strip().splitlines()[-1]);report['realProbe']=probe_result
backup=P('/opt/dapan/deploy-backups')/('shared-ai-'+stamp);backup.mkdir(parents=True)
targets={'app.py':root/'app.py','shared_ai.py':root/'shared_ai.py',
         'shared_speech.py':root/'shared_speech.py','shared-ai.env':P('/etc/dapan-shared-ai.env'),
         'backup.py':root/'deploy/backup.py',
         'shared-ai.conf':P('/etc/systemd/system/dapan-api.service.d/shared-ai.conf')}
present={name:path.exists() for name,path in targets.items()}
for name,path in targets.items():
 if present[name]:shutil.copy2(path,backup/name)
for name in ('community.db','shared-ai.db'):
 if (data/name).exists():
  with sqlite3.connect('file:'+str(data/name)+'?mode=ro',uri=True) as src,sqlite3.connect(backup/name) as dest:src.backup(dest)
notice_before=hashlib.sha256((data/'notices.json').read_bytes()).hexdigest()
uid=pwd.getpwnam('dapan').pw_uid;gid=pwd.getpwnam('dapan').pw_gid
try:
 for name in ('shared_ai.py','shared_speech.py','app.py'):
  if name in sources:
   target=root/name;temporary=target.with_suffix('.deploy.tmp');temporary.write_bytes(sources[name]);temporary.chmod(0o644);temporary.replace(target)
 if 'deploy/backup.py' in sources:
  target=root/'deploy/backup.py';target.write_bytes(sources['deploy/backup.py']);target.chmod(0o644)
 settings=P('/etc/dapan-shared-ai.env');settings.write_text('SHARED_AI_ENABLED=1\n'+key_line+'\n');settings.chmod(0o600)
 dropin=P('/etc/systemd/system/dapan-api.service.d/shared-ai.conf');dropin.parent.mkdir(exist_ok=True)
 dropin.write_text('[Service]\nEnvironmentFile=/etc/dapan-shared-ai.env\n');dropin.chmod(0o644)
 subprocess.run(['systemctl','daemon-reload'],check=True)
 subprocess.run(['systemctl','restart','dapan-api.service'],check=True)
 for i in range(30):
  try:
   with urllib.request.urlopen('http://127.0.0.1:8765/health',timeout=3) as r:assert r.status==200
   break
  except Exception:time.sleep(.3)
 else:raise RuntimeError('Health check failed')
 checks={}
 for route in ('health','notices','ai/status'):
  with urllib.request.urlopen('http://127.0.0.1:8765/'+route,timeout=5) as r:checks[route]=r.status
 assert hashlib.sha256((data/'notices.json').read_bytes()).hexdigest()==notice_before
 # Count the real validation call against the same site budget, without creating an account.
 ledger_script="from shared_ai import Budget;from pathlib import Path;import json,sys;r=json.loads(sys.argv[1]);b=Budget(Path('/opt/dapan/data/shared-ai.db'));b.reserve(r['id'],'deployment-probe',r['charged']);b.settle_amount(r['id'],r['charged'])"
 subprocess.run([venv,'-c',ledger_script,json.dumps(probe_result)],cwd=root,check=True)
 for path in data.glob('shared-ai.db*'):os.chown(path,uid,gid);path.chmod(0o600)
 report.update(phase='deployed',backup=str(backup),stage=str(stage),checks=checks,noticesUnchanged=True)
 (backup/'deployment-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 print(json.dumps(report,ensure_ascii=False))
except BaseException as deployment_error:
 for name,path in targets.items():
  if present[name]:shutil.copy2(backup/name,path)
  elif path.exists():path.unlink()
 subprocess.run(['systemctl','daemon-reload']);subprocess.run(['systemctl','restart','dapan-api.service'])
 raise RuntimeError('Deployment failed; prior code/config restored. Backup: '+str(backup)+'; '+str(deployment_error).replace(key,'[redacted]')) from None
'''


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--apply',action='store_true');args=parser.parse_args()
    files=['shared_ai.py','test_shared_ai.py','conftest.py','deploy/backup.py']
    if (ROOT/'shared_speech.py').exists():files.append('shared_speech.py')
    payload={'apply':args.apply,'files':{name:base64.b64encode((ROOT/name).read_bytes()).decode() for name in files}}
    source='payload='+repr(payload)+'\n'+REMOTE
    result=subprocess.run(SSH+['python3','-'],input=source.encode('utf-8'),capture_output=True)
    if result.returncode:
        print(result.stderr.decode('utf-8',errors='replace'))
        raise SystemExit(result.returncode)
    text=result.stdout.decode('utf-8');print(text)
    if args.apply:
        report=ROOT/'deploy'/'shared-ai-deployment-result.json';report.write_text(text,encoding='utf-8')


if __name__=='__main__':main()

"""Add conversation feedback after isolated regression checks; retain all legacy routes/data."""
import base64
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SSH = ['ssh.exe', '-F', 'NUL', '-i', os.environ['DAPAN_SSH_KEY'], '-o', 'IdentitiesOnly=yes',
       '-o', 'StrictHostKeyChecking=yes', '-o', 'ProxyCommand=none', '-o', 'ProxyJump=none',
       '-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', os.environ['DAPAN_SSH_HOST']]
REMOTE = r'''
import ast,base64,hashlib,json,os,pathlib,shutil,sqlite3,subprocess,tempfile,time,urllib.request,urllib.error,uuid
P=pathlib.Path;root=P('/opt/dapan/backend');data=P('/opt/dapan/data');python='/opt/dapan/venv/bin/python';os.umask(0o077)
files={name:base64.b64decode(raw) for name,raw in payload.items()}
for name,raw in files.items():ast.parse(raw)
previous=hashlib.sha256((root/'app.py').read_bytes()).hexdigest()
assert previous in {'6b4a48ca5f9dfe6819afa8adcf890b8afc85bf496f87a86370d5abe095fc2d01',hashlib.sha256(files['app.py']).hexdigest()}, 'Server source changed; deployment stopped'
stage=P(tempfile.mkdtemp(prefix='feedback101-stage-',dir='/opt/dapan'))
for path in root.glob('*.py'):shutil.copy2(path,stage/path.name)
for name in ('sources.json',):
 if (root/name).exists():shutil.copy2(root/name,stage/name)
for name,raw in files.items():
 target=stage/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw)
test=subprocess.run([python,'-m','pytest','test_conversation_feedback.py','test_backend.py','test_shared_ai.py','test_shared_speech.py','test_quota_admin.py','-q','--tb=short','--basetemp='+str(stage/'test-run')],cwd=stage,text=True,capture_output=True,timeout=150)
(stage/'tests.log').write_text(test.stdout+test.stderr)
assert test.returncode==0,'Staged tests failed; production unchanged. '+str(stage)
report={'stage':str(stage),'tests':test.stdout.splitlines()[-1]}
production=('app.py','conversation_feedback.py')
backup=P('/opt/dapan/deploy-backups')/('feedback101-'+time.strftime('%Y%m%dT%H%M%SZ',time.gmtime()));backup.mkdir()
present={name:(root/name).exists() for name in production}
for name in production:
 if present[name]:shutil.copy2(root/name,backup/name)
config_paths=[P('/etc/nginx/conf.d/dapan.conf')]+list(P('/etc').glob('dapan*.env'))
config_hashes={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in config_paths}
def counts():
 with sqlite3.connect('file:'+str(data/'community.db')+'?mode=ro',uri=True) as db:
  return {name:db.execute('SELECT COUNT(*) FROM '+name).fetchone()[0] for name in ('users','sessions','listings')}
report['before']=counts()
for _ in range(55):
 with sqlite3.connect('file:'+str(data/'shared-ai.db')+'?mode=ro',uri=True) as db:n=db.execute("SELECT COUNT(*) FROM requests WHERE state='held'").fetchone()[0]
 if n==0:break
 time.sleep(1)
else:raise RuntimeError('Active AI requests remain; production unchanged; retry later')
def call(path,method='GET',value=None,token=None):
 headers={'Content-Type':'application/json'}
 if token:headers['X-Admin-Token']=token
 request=urllib.request.Request('http://127.0.0.1:8765'+path,data=json.dumps(value).encode() if value is not None else None,headers=headers,method=method)
 with urllib.request.urlopen(request,timeout=8) as r:return json.load(r)
test_id=str(uuid.uuid4())
try:
 for name in production:
  temp=root/(name+'.feedback101-tmp');temp.write_bytes(files[name]);temp.chmod(0o644);temp.replace(root/name)
 subprocess.run(['systemctl','restart','dapan-api.service'],check=True)
 for _ in range(40):
  try:health=call('/health');break
  except Exception:time.sleep(.25)
 else:raise RuntimeError('Health check failed')
 assert health['ok'] is True
 status_before=call('/ai/status')
 assert status_before.get('limits')
 for path in ('/notices','/reset-radar'):
  assert isinstance(call(path),dict)
 try:call('/admin/feedback')
 except urllib.error.HTTPError as exc:assert exc.code==403
 else:raise RuntimeError('Admin auth missing')
 pid=subprocess.check_output(['systemctl','show','--property=MainPID','--value','dapan-api.service'],text=True).strip()
 env=dict(x.split(b'=',1) for x in P('/proc/'+pid+'/environ').read_bytes().split(b'\0') if b'=' in x)
 token=env[b'ADMIN_TOKEN'].decode()
 sample={'schemaVersion':1,'reportId':test_id,'installationId':str(uuid.uuid4()),'consent':True,'appVersion':'deployment-check','versionCode':101,'device':'Synthetic','androidVersion':'test','screen':'验证','messages':[{'role':'user','text':'SYNTHETIC FEEDBACK DEPLOYMENT CHECK','time':0,'operation':''}]}
 result=call('/feedback/conversations','POST',sample);assert result['received'] and result['id']==test_id
 assert call('/feedback/conversations','POST',sample)==result
 detail=call('/admin/feedback/'+test_id,token=token);assert detail['report']['messages']==sample['messages']
 assert call('/admin/feedback/'+test_id,'PUT',{'status':'resolved','note':'Synthetic verification only','revision':0},token)['ok']
 report.update(deployed=True,health=True,legacyEndpoints=True,adminUnauthorizedStatus=403,roundTrip=True,after=counts(),configUnchanged=all(hashlib.sha256(P(p).read_bytes()).hexdigest()==h for p,h in config_hashes.items()),hashes={name:hashlib.sha256((root/name).read_bytes()).hexdigest() for name in production},backup=str(backup))
 assert report['configUnchanged']
 assert all(report['after'][key]>=value for key,value in report['before'].items())
 (backup/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
 print(json.dumps(report,ensure_ascii=False))
except BaseException:
 for name in production:
  if present[name]:shutil.copy2(backup/name,root/name)
  elif (root/name).exists():(root/name).unlink()
 subprocess.run(['systemctl','restart','dapan-api.service'],check=False)
 raise RuntimeError('Deployment verification failed; previous source restored. '+str(backup)) from None
finally:
 if (data/'conversation-feedback.db').exists():
  with sqlite3.connect(data/'conversation-feedback.db') as db:db.execute('DELETE FROM feedback WHERE id=?',(test_id,))
'''

if __name__ == '__main__':
    files = ('app.py','conversation_feedback.py','test_conversation_feedback.py','test_backend.py',
             'test_shared_ai.py','test_shared_speech.py','test_quota_admin.py','conftest.py','deploy/configure_shared_speech_key.py')
    payload = {name:base64.b64encode((ROOT/name).read_bytes()).decode() for name in files}
    result = subprocess.run(SSH+['python3','-'],input=('payload='+repr(payload)+'\n'+REMOTE).encode(),capture_output=True,timeout=245)
    if result.returncode:
        print(result.stderr.decode(errors='replace')[-1800:])
        raise SystemExit(result.returncode)
    output = result.stdout.decode('utf-8')
    (ROOT.parent/'validation/1.0.1-feedback/deployment.json').write_text(output,encoding='utf-8')
    print(output)

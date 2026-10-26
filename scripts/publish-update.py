"""Publish a signed APK and an atomic public manifest. Requires the owner's explicit SSH credentials.

python scripts/publish-update.py --apk releases/DapanAssistant-0.64.0.apk --code 94 --name 0.64.0 --notes "..."
Set DAPAN_SSH_HOST and DAPAN_SSH_KEY first. Verify package/version/signature using apksigner/aapt before publishing.
"""
import argparse, hashlib, json, os, pathlib, re, subprocess, tempfile

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--apk',type=pathlib.Path,required=True)
    p.add_argument('--code',type=int,required=True)
    p.add_argument('--name',required=True)
    p.add_argument('--notes',required=True)
    args=p.parse_args()
    host=os.environ.get('DAPAN_SSH_HOST','');key=os.environ.get('DAPAN_SSH_KEY','')
    if not re.fullmatch(r'[a-zA-Z0-9_.-]+@[a-zA-Z0-9_.-]+',host) or not pathlib.Path(key).is_file():
        raise SystemExit('Set DAPAN_SSH_HOST=user@host and DAPAN_SSH_KEY to your private key path')
    if args.code<=0 or not re.fullmatch(r'\d+\.\d+\.\d+',args.name) or len(args.notes)>3000:
        raise SystemExit('Invalid version or notes')
    apk=args.apk.resolve();digest=hashlib.sha256(apk.read_bytes()).hexdigest()
    if not 1<=apk.stat().st_size<=180_000_000:raise SystemExit('Invalid APK size')
    filename=f'dapan-{args.code}.apk'
    manifest=dict(schema=1,versionCode=args.code,versionName=args.name,apkUrl=f'https://39.105.161.86/updates/{filename}',bytes=apk.stat().st_size,sha256=digest,minSdk=26,abis=['arm64-v8a'],notes=args.notes)
    options=['-F','NUL' if os.name=='nt' else '/dev/null','-i',key,'-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o','ProxyCommand=none','-o','ProxyJump=none','-o','BatchMode=yes','-o','ConnectTimeout=15']
    def remote(command):subprocess.run(['ssh',*options,host,command],check=True)
    remote('mkdir -p /opt/dapan/updates /opt/dapan/update-staging && chmod 700 /opt/dapan/update-staging')
    subprocess.run(['scp',*options,str(apk),host+':/opt/dapan/update-staging/'+filename],check=True)
    with tempfile.TemporaryDirectory() as tmp:
        local=pathlib.Path(tmp)/'latest.json';local.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
        subprocess.run(['scp',*options,str(local),host+':/opt/dapan/update-staging/latest.json'],check=True)
    # Only a monotonic, verified release may replace the public manifest; APK versions are immutable.
    program='''import hashlib,json,os,pathlib
s=pathlib.Path('/opt/dapan/update-staging');d=pathlib.Path('/opt/dapan/updates')
m=json.loads((s/'latest.json').read_text());name='dapan-%d.apk'%m['versionCode'];apk=s/name
assert apk.stat().st_size==m['bytes'] and hashlib.sha256(apk.read_bytes()).hexdigest()==m['sha256']
old=d/'latest.json'
if old.exists(): assert json.loads(old.read_text())['versionCode']<=m['versionCode'], 'Refusing feed rollback'
dest=d/name
if dest.exists(): assert hashlib.sha256(dest.read_bytes()).hexdigest()==m['sha256'], 'Immutable release already differs'
os.chmod(apk,0o644);os.replace(apk,dest)
os.chmod(s/'latest.json',0o644);os.replace(s/'latest.json',old)
print(json.dumps({'published':m['versionName'],'bytes':m['bytes'],'sha256':m['sha256']}))
'''
    import base64
    encoded=base64.b64encode(program.encode()).decode()
    remote("python3 -c \"import base64;exec(base64.b64decode('"+encoded+"'))\"")

if __name__=='__main__':main()

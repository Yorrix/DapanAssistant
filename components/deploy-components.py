"""Run on the server after uploading payloads and catalogue to a staging directory."""
import hashlib, json, os, pathlib, shutil, subprocess, sys, time

stage = pathlib.Path(sys.argv[1]).resolve()
if stage.parent != pathlib.Path('/opt/dapan/component-staging'):
    raise SystemExit('Unexpected staging location')
records = json.loads((stage/'catalogue.json').read_text())
target = pathlib.Path('/opt/dapan/components')
target.mkdir(mode=0o755, exist_ok=True)
for record in records.values():
    name = record['fileName']
    if pathlib.Path(name).name != name or name.startswith('.'):
        raise SystemExit('Unsafe component filename')
    source = stage / name
    sha = hashlib.sha256(source.read_bytes()).hexdigest()
    if source.stat().st_size != record['bytes'] or sha != record['sha256']:
        raise SystemExit('Payload integrity failure: '+name)
    final = target/name
    if final.exists() and hashlib.sha256(final.read_bytes()).hexdigest() != sha:
        raise SystemExit('Refusing to replace immutable component: '+name)
    temp = target/(name+'.new')
    shutil.copyfile(source,temp);temp.chmod(0o644);os.replace(temp,final)

config = pathlib.Path('/etc/nginx/conf.d/dapan.conf')
original = config.read_text()
anchor = ' location = /openapi.json { return 404; }'
block = '''
 location ^~ /components/ {
  alias /opt/dapan/components/;
  autoindex off;
  default_type application/octet-stream;
  add_header Cache-Control "public, max-age=31536000, immutable";
  add_header X-Content-Type-Options nosniff always;
  limit_except GET { deny all; }
 }'''
if 'location ^~ /components/' not in original:
    if original.count(anchor)!=1:
        raise SystemExit('Unknown nginx config; no changes made')
    backup=pathlib.Path('/opt/dapan/deploy-backups')/('components-'+time.strftime('%Y%m%d-%H%M%S')+'.nginx')
    backup.parent.mkdir(exist_ok=True);backup.write_text(original)
    try:
        config.write_text(original.replace(anchor,anchor+block))
        subprocess.run(['nginx','-t'],check=True)
        subprocess.run(['systemctl','reload','nginx'],check=True)
    except BaseException:
        config.write_text(original)
        subprocess.run(['nginx','-t'],check=True)
        subprocess.run(['systemctl','reload','nginx'],check=True)
        raise
print(json.dumps({'published':[r['fileName'] for r in records.values()]}))

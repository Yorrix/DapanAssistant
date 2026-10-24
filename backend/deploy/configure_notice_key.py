"""Root-only SSH stdin setup. Never prints or logs the credential."""
import json, os, pathlib, re, sys, urllib.request, urllib.error, subprocess

key = sys.stdin.read().strip()
if not re.fullmatch(r'[A-Za-z0-9_-]{20,256}', key):
    print('Invalid key format'); sys.exit(1)
try:
    request = urllib.request.Request('https://api.deepseek.com/models', headers={'Authorization': 'Bearer '+key})
    with urllib.request.urlopen(request, timeout=25) as response:
        models = json.load(response)
    if 'deepseek-flash' not in {m['id'] for m in models['data']}:
        print('Flash model unavailable'); sys.exit(1)
    path = pathlib.Path('/etc/dapan-notices.env')
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(fd, 0o600)
    with os.fdopen(fd, 'w') as out:
        out.write('DEEPSEEK_API_KEY='+key+'\nNOTICE_MODEL=deepseek-flash\nNOTICE_AI_DAILY_LIMIT=400\n')
    print('CONFIGURED')
except urllib.error.HTTPError as error:
    print('API authentication failed: HTTP '+str(error.code)); sys.exit(1)
except Exception:
    print('Configuration failed; check server connectivity'); sys.exit(1)

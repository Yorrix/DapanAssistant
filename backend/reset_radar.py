"""Read-only TIBO collector. No social account access or personal quota queries."""
import argparse
import json
import os
from pathlib import Path
import time
import urllib.request

SOURCE = 'https://tibo.cc/api/v1/status'

def validate(payload):
    if not isinstance(payload, dict) or not isinstance(payload.get('data'), dict):
        raise ValueError('TIBO has no usable status data')
    if not all(k in payload['data'] for k in ('latest_reset', 'scheduled_reset', 'active_watch')):
        raise ValueError('Incomplete TIBO status')
    if not payload.get('fetched_at'):
        raise ValueError('TIBO timestamp missing')
    return payload

def collect(destination):
    with urllib.request.urlopen(urllib.request.Request(SOURCE, headers={'User-Agent': 'DapanAssistant/0.62.0'}), timeout=25) as response:
        payload = validate(json.load(response))
    payload['dapan_fetched_at'] = int(time.time())
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix('.new')
    temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding='utf-8')
    os.replace(temporary, destination)
    return payload

def cached(path, now=None):
    if not Path(path).is_file():
        return {'data': None, 'stale': True, 'error': '尚未取得 tibo 数据'}
    try:
        payload = validate(json.loads(Path(path).read_text(encoding='utf-8')))
        age = (time.time() if now is None else now) - float(payload.get('dapan_fetched_at', 0))
    except (OSError, ValueError, TypeError):
        return {'data': None, 'stale': True, 'error': 'tibo 缓存需要更新'}
    payload['stale'] = bool(payload.get('stale')) or age > 600
    return payload

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', default='/opt/dapan/data/reset-radar.json')
    args = parser.parse_args()
    result = collect(args.output)
    print(json.dumps({'ok': True, 'stale': result.get('stale'), 'fetched_at': result['fetched_at']}))

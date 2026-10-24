"""Run on the server via SSH. Read the secret only from stdin; emit safe codes.

Requires the API service virtualenv (websockets). Verifies the exact Beijing ASR
model by starting a task without audio; never stores/logs audio or credentials.
"""
import asyncio
import contextlib
import json
import os
import pathlib
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid

ENV = pathlib.Path('/etc/dapan.env')
SERVICE = 'dapan-api.service'
MAX_KEY_LENGTH = 4096


def read_key(stream):
    """Accept opaque old/new bearer keys without silently truncating stdin.

    New Model Studio keys begin with sk-ws and can include dot-separated parts.
    Keep the token alphabet safe for both an HTTP header and an unquoted env
    value; reject all whitespace/control characters rather than stripping them.
    """
    key = stream.read(MAX_KEY_LENGTH + 1)
    if not isinstance(key, str) or not 20 <= len(key) <= MAX_KEY_LENGTH:
        return None
    return key if re.fullmatch(r'[A-Za-z0-9._~+/=\-]+', key) else None


async def verify(key):
    from websockets.asyncio.client import connect
    identifier = str(uuid.uuid4())
    async with connect('wss://dashscope.aliyuncs.com/api-ws/v1/inference',
                       additional_headers={'Authorization': 'Bearer ' + key},
                       open_timeout=15, close_timeout=2, max_size=65536) as ws:
        await ws.send(json.dumps({'header': {'action': 'run-task', 'task_id': identifier, 'streaming': 'duplex'},
                                  'payload': {'task_group': 'audio', 'task': 'asr', 'function': 'recognition',
                                              'model': 'fun-asr-realtime', 'parameters': {'format': 'pcm', 'sample_rate': 16000}, 'input': {}}}))
        data = json.loads(await asyncio.wait_for(ws.recv(), 15))
        if data.get('header', {}).get('event') != 'task-started':
            return False
        await ws.send(json.dumps({'header': {'action': 'finish-task', 'task_id': identifier, 'streaming': 'duplex'},
                                  'payload': {'input': {}}}))
        return True


def update_text(original, key):
    updates = {'ALIYUN_API_KEY': key, 'SHARED_SPEECH_ENABLED': '1'}
    lines = []
    for line in original.splitlines():
        name = line.split('=', 1)[0].strip() if '=' in line else ''
        if name not in updates:
            lines.append(line)
    return '\n'.join(lines + [name + '=' + value for name, value in updates.items()]) + '\n'


def atomic_write(value):
    fd, name = tempfile.mkstemp(prefix='.dapan-speech-', dir=ENV.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, 'w', encoding='utf-8') as stream:
            stream.write(value)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(name, ENV)
        parent = os.open(ENV.parent, os.O_DIRECTORY)
        try:
            os.fsync(parent)
        finally:
            os.close(parent)
    finally:
        with contextlib.suppress(FileNotFoundError):
            os.unlink(name)


def restart():
    result = subprocess.run(['systemctl', 'restart', SERVICE], stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, timeout=35)
    if result.returncode:
        return False
    for _ in range(12):
        try:
            with urllib.request.urlopen('http://127.0.0.1:8765/health', timeout=2) as response:
                if response.status == 200:
                    return True
        except Exception:
            time.sleep(0.5)
    return False


def main():
    key = read_key(sys.stdin)
    if key is None:
        return 'INVALID_KEY_FORMAT'
    if os.geteuid() != 0:
        return 'ROOT_REQUIRED'
    try:
        accepted = asyncio.run(verify(key))
    except ImportError:
        return 'SPEECH_RUNTIME_MISSING'
    except Exception:
        return 'VERIFY_FAILED'
    if not accepted:
        return 'ASR_MODEL_NOT_AUTHORIZED'
    original = None
    changed = False
    try:
        original = ENV.read_text(encoding='utf-8')
        atomic_write(update_text(original, key))
        changed = True
        if restart():
            return 'CONFIGURED'
    except Exception:
        pass
    if changed and original is not None:
        try:
            atomic_write(original)
            restart()
            return 'RESTART_FAILED_ROLLED_BACK'
        except Exception:
            return 'ROLLBACK_REQUIRED'
    return 'CONFIG_WRITE_FAILED'


if __name__ == '__main__':
    try:
        code = main()
    except Exception:
        code = 'CONFIG_FAILED'
    print(code)
    sys.exit(0 if code == 'CONFIGURED' else 1)

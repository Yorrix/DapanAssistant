"""Remove only the retired radar endpoint and collector on the existing server.

Read-only unless --apply is supplied. Run as root on the existing Linux host.
Creates a recovery backup; never reads or changes accounts, mail keys or notices.
"""
import argparse
import ast
import os
from pathlib import Path
import shutil
import subprocess
import time


def without_radar_route(source):
    tree = ast.parse(source)
    matches = []
    for node in ast.walk(tree):
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        decorators = [d for d in node.decorator_list if isinstance(d, ast.Call)
                      and isinstance(d.func, ast.Attribute) and d.func.attr == 'get'
                      and isinstance(d.func.value, ast.Name) and d.func.value.id == 'app'
                      and d.args and isinstance(d.args[0], ast.Constant)
                      and d.args[0].value == '/reset-radar']
        if decorators:
            if len(node.decorator_list) != 1 or node.name != 'reset_radar':
                raise ValueError('Unexpected shared route; no files changed')
            matches.append((decorators[0].lineno - 1, node.end_lineno))
    if len(matches) > 1:
        raise ValueError('Duplicate radar routes; no files changed')
    if not matches:
        return source
    start, end = matches[0]
    lines = source.splitlines(keepends=True)
    result = ''.join(lines[:start] + lines[end:])
    compile(result, 'app.py', 'exec')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    app = Path('/opt/dapan/backend/app.py')
    retired = [Path('/etc/systemd/system/dapan-radar.timer'),
               Path('/etc/systemd/system/dapan-radar.service'),
               Path('/opt/dapan/backend/dapan-radar.timer'),
               Path('/opt/dapan/backend/dapan-radar.service'),
               Path('/opt/dapan/backend/reset_radar.py'),
               Path('/opt/dapan/backend/install_radar_route.py'),
               Path('/opt/dapan/data/reset-radar.json')]
    original = app.read_text(encoding='utf-8')
    updated = without_radar_route(original)
    print('API route:', 'remove' if original != updated else 'already absent')
    for path in retired:
        if path.exists():
            print('Retire:', path)
    if not args.apply:
        print('Plan only; run with --apply to stop the collector and remove these files.')
        return
    if os.name != 'posix' or os.geteuid() != 0:
        raise SystemExit('Run --apply as root on the existing Linux server')
    # Resolve each exact file before changing anything; never follow symlinks.
    for path in [app] + retired:
        if path.is_symlink() or (path.exists() and not path.is_file()):
            raise SystemExit(f'Unexpected path type: {path}; no files changed')
    backup = Path('/opt/dapan/deploy-backups') / ('radar-retired-' + str(time.time_ns()))
    backup.mkdir(parents=True)
    for path in [app] + retired:
        if path.exists():
            target = backup / str(path).lstrip('/')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
    def command(*parts):
        return subprocess.run(parts, check=True, text=True, capture_output=True).stdout.strip()
    for unit in ('dapan-radar.timer', 'dapan-radar.service'):
        if command('systemctl', 'show', '--property=LoadState', '--value', unit) != 'not-found':
            if unit.endswith('.timer'):
                command('systemctl', 'disable', '--now', unit)
            else:
                command('systemctl', 'stop', unit)
    if updated != original:
        temporary = app.with_name('app.py.radar-retired')
        temporary.write_text(updated, encoding='utf-8')
        shutil.copystat(app, temporary)
        info = app.stat()
        os.chown(temporary, info.st_uid, info.st_gid)
        temporary.replace(app)
    for path in retired:
        if path.exists():
            path.unlink()
    command('systemctl', 'daemon-reload')
    command('systemctl', 'restart', 'dapan-api.service')
    print('Retired; recovery backup:', backup)
    print('Verify /health and /notices return 200; /reset-radar must return 404.')


if __name__ == '__main__':
    main()

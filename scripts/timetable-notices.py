"""Collect licenses from the exact cached artifacts used by the optional browser.

Does not change or re-sign the published APK. Keep the releaseRuntimeClasspath
report with the build and run this script again when dependency versions change.
"""
from pathlib import Path
from html.parser import HTMLParser
import hashlib
import io
import json
import re
import shutil
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'timetable-component' / 'third-party'
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
REPORT = ROOT / 'validation/0.63.0/browser-dependencies.log'
APK = ROOT / 'components/payloads/dapan-timetable-1-arm64-v8a.apk'
ASSETS = ROOT / 'app/src/main/assets/timetable-license'
EXPECTED = '5eea803282d0ae51f9cd9b9600ce0f01da6201697b7040826ee383c473a1d77e'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


class LicenseText(HTMLParser):
    def __init__(self):
        super().__init__()
        self.skip = 0
        self.parts = []
    def handle_starttag(self, tag, attrs):
        if tag in ('style', 'script', 'head'):
            self.skip += 1
        if not self.skip and tag in ('p', 'div', 'h1', 'h2', 'h3', 'li', 'br', 'pre'):
            self.parts.append('\n')
    def handle_endtag(self, tag):
        if tag in ('style', 'script', 'head'):
            self.skip = max(0, self.skip - 1)
        if not self.skip and tag in ('p', 'div', 'h1', 'h2', 'h3', 'li', 'pre'):
            self.parts.append('\n')
    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def main():
    assert hashlib.sha256(APK.read_bytes()).hexdigest() == EXPECTED, 'Release APK changed; review metadata first'
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / 'licenses').mkdir(exist_ok=True)
    (OUT / 'poms').mkdir(exist_ok=True)
    coords = set()
    for m in re.finditer(r'(?:\+---|\\---) ([\w.-]+):([\w.-]+)(?::([\w.-]+))?(?: -> ([\w.-]+))?', REPORT.read_text(encoding='utf-8')):
        group, name, version, resolved = m.groups()
        if resolved or version:
            coords.add((group, name, resolved or version))
    records = []
    for group, name, version in sorted(coords):
        paths = CACHE / group / name / version
        poms = sorted(paths.glob('*/*.pom'))
        assert poms, f'Missing POM for {group}:{name}:{version}'
        filename = f'{group}_{name}_{version}.pom'
        shutil.copyfile(poms[0], OUT / 'poms' / filename)
        doc = ET.parse(poms[0])
        licenses = [{'name': item.findtext('m:name', '', NS), 'url': item.findtext('m:url', '', NS)}
                    for item in doc.findall('m:licenses/m:license', NS)]
        record = {'coordinate': f'{group}:{name}:{version}', 'pom': f'poms/{filename}',
                  'declaredLicenses': licenses, 'upstreamUrl': doc.findtext('m:url', '', NS),
                  'noticeFiles': [], 'artifacts': []}

        def collect(z, prefix=''):
            for entry in z.namelist():
                if entry.endswith('/'):
                    continue
                base = entry.rsplit('/', 1)[-1]
                if re.search(r'(^|[._-])(LICENSE|LICENCE|NOTICE|COPYING)([._-]|$)', base, re.I):
                    data = z.read(entry)
                    if len(data) > 4_000_000:
                        raise ValueError(f'Oversized notice: {entry}')
                    suffix = re.sub(r'[^a-zA-Z0-9._-]', '_', prefix + entry)
                    output = f'{group}_{name}_{version}_{suffix}'
                    (OUT / 'licenses' / output).write_bytes(data)
                    record['noticeFiles'].append(f'licenses/{output}')
                elif entry == 'classes.jar' or (entry.startswith('libs/') and entry.endswith('.jar')):
                    with zipfile.ZipFile(io.BytesIO(z.read(entry))) as nested:
                        collect(nested, prefix + entry + '_')

        for artifact in sorted(paths.glob('*/*')):
            if artifact.suffix in ('.aar', '.jar') and not artifact.name.endswith(('-sources.jar', '-javadoc.jar')):
                record['artifacts'].append({'name': artifact.name, 'sha256': hashlib.sha256(artifact.read_bytes()).hexdigest()})
                with zipfile.ZipFile(artifact) as z:
                    collect(z)
        records.append(record)
    with zipfile.ZipFile(APK) as apk:
        with zipfile.ZipFile(io.BytesIO(apk.read('assets/omni.ja'))) as omni:
            for name, output in [('chrome/toolkit/content/global/license.html', 'Mozilla-Gecko-licenses.html'),
                                 ('chrome/toolkit/content/global/buildconfig.html', 'Mozilla-Gecko-buildconfig.html')]:
                (OUT / output).write_bytes(omni.read(name))
    (OUT / 'dependency-inventory.json').write_text(json.dumps(records, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    summary = ['课表兼容组件 1.0 · 依赖清单', '',
               '来源：该版本 releaseRuntimeClasspath 与本机已解析的官方 Maven 制品。',
               '清单含 BOM/元数据节点，不能据此声称每个坐标都在 APK 中有独立二进制文件。',
               '声明为空时请看保留的原始 NOTICE、父 POM 或下方项目许可说明。', '']
    for record in records:
        names = '; '.join(item['name'] for item in record['declaredLicenses']) or '参见原始许可/父 POM'
        summary += [record['coordinate'], '  ' + names]
    (OUT / 'DEPENDENCIES.txt').write_text('\n'.join(summary) + '\n', encoding='utf-8')
    ASSETS.mkdir(parents=True, exist_ok=True)
    for file in [OUT / 'DEPENDENCIES.txt', OUT / 'Mozilla-Gecko-licenses.html']:
        shutil.copyfile(file, ASSETS / file.name)
    # Plain-text copies make the original dependency notices readable without executing HTML.
    notices = []
    for file in sorted((OUT / 'licenses').iterdir()):
        notices += ['\n===== ' + file.name + ' =====\n', file.read_text(encoding='utf-8', errors='replace')]
    (OUT / 'DEPENDENCY-NOTICES.txt').write_text('\n'.join(notices), encoding='utf-8')
    shutil.copyfile(OUT / 'DEPENDENCY-NOTICES.txt', ASSETS / 'DEPENDENCY-NOTICES.txt')
    parser = LicenseText()
    parser.feed((OUT / 'Mozilla-Gecko-licenses.html').read_text(encoding='utf-8'))
    plain = re.sub(r'\n[ \t]*\n(?:[ \t]*\n)+', '\n\n', ''.join(parser.parts)).strip() + '\n'
    (ASSETS / 'Mozilla-Gecko-licenses.txt').write_text(plain, encoding='utf-8')
    shutil.copyfile(OUT / 'NOTICE.txt', ASSETS / 'NOTICE.txt')
    print(f'Collected {len(records)} dependency records, {len(list((OUT / "licenses").iterdir()))} original license/notice files. Published APK unchanged.')


if __name__ == '__main__':
    main()

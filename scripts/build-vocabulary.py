"""Build the bundled ECDICT lookup DB. Source is a verified, unmodified CSV snapshot.

Basic exclusion = middle-school tags + top 1000 BNC/COCA ranked words + function words.
Exam selections are cumulative unions, not claims of an official current syllabus.
Run from the project root after downloading vendor/ecdict/ecdict.csv and LICENSE.
"""
import csv, gzip, hashlib, json, re, sqlite3, sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'vendor/ecdict'
target = root / 'app/src/main/assets/vocabulary'
target.mkdir(parents=True, exist_ok=True)
raw = Path(sys.argv[1]) if len(sys.argv)>1 else source / 'ecdict.csv'
checksum = hashlib.sha256(raw.read_bytes()).hexdigest()
if (source/'SOURCE.json').exists():
    assert checksum == json.loads((source/'SOURCE.json').read_text())['sourceSha256'], 'Source checksum changed; review the dictionary update before rebuilding'
basic = set('a an the this that these those i me my mine we us our ours you your yours he him his she her hers it its they them their theirs who whom whose which what where when why how am is are was were be been being have has had having do does did doing will would shall should may might must can could and or but so because if then than as of to in on at by for from with without into over under up down out off not no yes all any some each every either neither both one two three four five six seven eight nine ten first second third only very just also too more most less least good better best bad worse worst go goes went gone going come comes came coming get gets got getting take takes took taken make makes made making say says said know knows knew known think thinks thought see sees saw seen look looks looked looking now today tomorrow yesterday here there about after before during through until while well hello thanks thank please okay ok'.split())
entries = {}
word_pattern = re.compile(r"[a-z]+(?:-[a-z]+)*")
def rank(value):
    return int(value) if value.strip().isdigit() and int(value) > 0 else 99999999

with raw.open(encoding='utf-8-sig', newline='') as f:
    for row in csv.DictReader(f):
        original = row['word'].strip()
        word = original.lower()
        if not (2 <= len(word) <= 45 and word_pattern.fullmatch(word)):
            continue
        translation = row['translation'].replace('\\n', '\n').strip()
        tags = set(row['tag'].split())
        if not translation:
            continue
        # Omit dictionary-only proper names; retain exam-tagged words regardless of case.
        technical_acronym = original.isupper() and len(original) <= 8 and any(t in translation for t in ['[化]', '[医]', '[生化]', '[计]', '核酸', '核糖'])
        if not tags and ((original != word and not technical_acronym) or any(t in translation for t in ['[人名]', '[地名]', '姓氏'])):
            continue
        flags = (1 if word in basic or 'zk' in tags or min(rank(row['bnc']), rank(row['frq'])) <= 1000 else 0)
        flags |= (2 if 'gk' in tags else 0) | (4 if 'cet4' in tags else 0) | (8 if 'cet6' in tags else 0)
        item = (word, row['phonetic'].strip().strip('/[]'), translation, flags, row['exchange'], min(rank(row['frq']), rank(row['bnc'])))
        if word not in entries or original == word:
            entries[word] = item

# Short editorial supplements put common university mathematical senses first.
# Preserve upstream definitions below them and leave source phonetics untouched.
academic = {'isomorphism':'[数学] 同构', 'homomorphism':'[数学] 同态',
            'differentiation':'[数学] 微分；求导', 'derivative':'[数学] 导数',
            'eigenvalue':'[数学] 特征值', 'eigenvector':'[数学] 特征向量',
            'bijection':'[数学] 双射', 'surjection':'[数学] 满射', 'tensor':'[数学] 张量'}
for word, meaning in academic.items():
    if word in entries:
        row=entries[word]
        entries[word]=(*row[:2],meaning+'\n'+row[2],*row[3:])

forms = {}
for word, phonetic, meaning, flags, exchange, frequency in sorted(entries.values(), key=lambda r: -r[5]):
    for field in exchange.split('/'):
        kind, _, values = field.partition(':')
        if kind in {'p', 'd', 'i', '3', 'r', 't', 's'}:
            for form in values.split(','):
                if word_pattern.fullmatch(form) and form != word:
                    forms[form] = word
for word, _, _, _, exchange, _ in entries.values():
    for field in exchange.split('/'):
        kind, _, values = field.partition(':')
        if kind == '0':
            candidates = [v for v in values.split(',') if v in entries and v != word]
            if candidates:
                forms[word] = min(candidates, key=lambda w: entries[w][5])
# Resolve forms in the build, so runtime lookup is a single indexed query.
for form, base in list(forms.items()):
    visited = {form}
    while base in forms and base not in visited:
        visited.add(base)
        base = forms[base]
    if base in visited or base not in entries:
        forms.pop(form, None)
    else:
        forms[form] = base

dbfile = source / 'vocabulary-build.db'
if dbfile.exists():
    dbfile.unlink()
with sqlite3.connect(dbfile) as db:
    db.execute('CREATE TABLE words(word TEXT PRIMARY KEY,phonetic TEXT NOT NULL,meaning TEXT NOT NULL,tags INTEGER NOT NULL) WITHOUT ROWID')
    db.execute('CREATE TABLE forms(form TEXT PRIMARY KEY,lemma TEXT NOT NULL) WITHOUT ROWID')
    db.executemany('INSERT INTO words VALUES(?,?,?,?)', [r[:4] for r in sorted(entries.values())])
    db.executemany('INSERT INTO forms VALUES(?,?)', sorted(forms.items()))
    db.commit()
    assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
    for simple in ['the', 'teachers', 'students', 'running', 'went', 'books', 'studies']:
        base = forms.get(simple, simple)
        assert base in entries and entries[base][3] & 3, (simple, base)
    for term in ['eigenvalue', 'derivative', 'photosynthesis', 'mitochondria', 'isomorphism']:
        base = forms.get(term, term)
        assert base in entries, (term, base)
with (target / 'ecdict-v1.db.gz').open('wb') as out:
    with gzip.GzipFile(filename='', mode='wb', fileobj=out, mtime=0, compresslevel=9) as zipped:
        zipped.write(dbfile.read_bytes())
metadata = dict(source='https://github.com/skywind3000/ECDICT', snapshot='2026-10-23', sourceSha256=checksum,
                words=len(entries), forms=len(forms), basic=sum(bool(r[3]&1) for r in entries.values()),
                gk=sum(bool(r[3]&2) for r in entries.values()), cet4=sum(bool(r[3]&4) for r in entries.values()), cet6=sum(bool(r[3]&8) for r in entries.values()),
                cumulative={str(mask):sum(bool(r[3]&mask) for r in entries.values()) for mask in [1,3,7,15]})
(target/'metadata.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
(target/'LICENSE.txt').write_bytes((source/'LICENSE').read_bytes())
(source/'SOURCE.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps(metadata,ensure_ascii=False,indent=2))
print('Compressed bytes:',(target/'ecdict-v1.db.gz').stat().st_size)

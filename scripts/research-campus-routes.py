"""Cache public official walking routes between nearby campus buildings, without credentials.
Run with --fetch to refresh missing samples; existing evidence is not overwritten.
"""
from pathlib import Path
import json, re, math, time, urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'validation/map-research'
rows=json.loads((OUT/'bd-v1-bound.json').read_text(encoding='utf-8'))['result']
places={}
for b in rows:
    name=b['name'].split('-')[0]
    if re.fullmatch('[A-H][0-9]{2}',name) and name!='B02':
        p=b['bound']['points'][:-1]
        places[name]=(sum(n['x'] for n in p)/len(p),sum(n['y'] for n in p)/len(p))
def dist(a,b):return math.hypot((a[0]-b[0])*.76,a[1]-b[1])
pairs=set()
for name,p in places.items():
    for other in sorted((n for n in places if n!=name),key=lambda n:dist(p,places[n]))[:3]:
        pairs.add(tuple(sorted((name,other))))
for a,b in [('H03','B01'),('B01','A01'),('B12','A01'),('B13','A01'),('B19','A01'),('A01','E01'),('H03','F02')]:
    if a in places and b in places:pairs.add(tuple(sorted((a,b))))
(OUT/'routes').mkdir(exist_ok=True)
def fetch(pair):
    a,b=pair;dest=OUT/'routes'/f'{a}-{b}.json'
    if dest.exists():return
    query=urllib.parse.urlencode(dict(origin=','.join(map(str,places[a])),destination=','.join(map(str,places[b])),mode='walking'))
    url='http://map.dlut.edu.cn/openmap/mapi/direction/v1?'+query
    try:
        with urllib.request.urlopen(url,timeout=20) as f:result=json.load(f)
        assert result.get('status')==0 and result.get('result')
        dest.write_text(json.dumps({'url':url,'data':result},ensure_ascii=False),encoding='utf-8')
    except Exception as e:print(a,b,str(e),flush=True)
    time.sleep(.3)
if __name__=='__main__':
    with ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(fetch,sorted(pairs)))
    print('Official route samples:',len(list((OUT/'routes').glob('*.json'))),'of',len(pairs))

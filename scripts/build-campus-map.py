"""Reproducible, evidence-backed local map supplement. No remote mutations.
Official map uses its own drawn coordinate frame. Align with matching OSM campus
buildings, record residuals, and keep that uncertainty in the map information.
Requires numpy; raw evidence is in validation/map-research.
"""
from pathlib import Path
import json, math, re, xml.etree.ElementTree as E
import numpy as np

ROOT=Path(__file__).resolve().parents[1];EV=ROOT/'validation/map-research'
tree=E.parse(ROOT/'app/src/main/assets/panjin.osm');root=tree.getroot()
nodes={int(n.attrib['id']):(float(n.attrib['lon']),float(n.attrib['lat'])) for n in root.findall('node')}
ways=[]
for w in root.findall('way'):
    ways.append(dict(id=int(w.attrib['id']),ids=[int(n.attrib['ref']) for n in w.findall('nd')],tags={t.attrib['k']:t.attrib['v'] for t in w.findall('tag')}))
official=json.loads((EV/'bd-v1-bound.json').read_text(encoding='utf-8'))['result']
obs={b['name'].split('-')[0]:b for b in official if re.match('^[A-H][0-9]{2}(?:-|$)',b['name'])}
def mean(ps):return np.mean(ps,axis=0)
pairs=[]
for w in ways:
    name=w['tags'].get('name')
    if name in obs and w['tags'].get('building'):
        a=mean([[p['x'],p['y']] for p in obs[name]['bound']['points'][:-1]])
        b=mean([nodes[n] for n in w['ids'][:-1]])
        pairs.append((name,a,b))
a=np.array([p[1] for p in pairs]);b=np.array([p[2] for p in pairs]);origin=a.mean(0)
design=np.column_stack([a-origin,np.ones(len(a))]);keep=np.ones(len(a),dtype=bool)
for _ in range(3):
    fit=np.linalg.lstsq(design[keep],b[keep],rcond=None)[0]
    errors=np.linalg.norm((design@fit-b)*[84400,111320],axis=1);keep=errors<12
def transform(p):return tuple(np.array([p[0]-origin[0],p[1]-origin[1],1])@fit)
def meters(p):return np.array([(p[0]-122.12)*84400,(p[1]-40.686)*111320])
next_id=-1
def node(p):
    global next_id
    i=next_id;next_id-=1;nodes[i]=tuple(p);return i
def way(ps,tags):
    global next_id
    ids=[node(p) for p in ps];w=dict(id=next_id,ids=ids,tags=tags);next_id-=1;ways.append(w);return w
source='DLUT public campus map; locally aligned 2026-10-23'

# The school's 2024 exam notice includes a north/east-oriented first-floor plan:
# https://pjteach.dlut.edu.cn/info/1021/1571.htm (cached exam-2024-1.jpg).
# Four sides surround the courtyard. DO NOT partition the footprint diagonally.
# Anchors are approximate midpoints of the corresponding footprint strips,
# not surveyed doors, indoor routes, or claimed boundaries between buildings.
a01=next(w for w in ways if w['tags'].get('name')=='A01')
poly=[np.array(nodes[n]) for n in a01['ids'][:-1]]
assert len(poly)==11, 'A01 footprint changed: recheck its four sides before rebuilding'
wing_anchor_vertices={'N':[2,3,8,9],'S':[4,5,6,7],'W':[1,2,9,10],'E':[3,4,7,8]}
for label,indices in wing_anchor_vertices.items():
    anchor=mean([poly[i] for i in indices])
    way([anchor],{'name':'A01-'+label,'dapan:label_only':'yes','dapan:approximate':'yes',
        'source':'DLUT A01 first-floor plan (2023-12-20); OSM side midpoint; not an entrance'})

# Official B02 outline follows H03 -> B03 -> B01, including the southern curve.
corridor=obs['B02']['bound']['points'][:-1]
shape=[transform((p['x'],p['y'])) for p in corridor]
way(shape+[shape[0]],{'building':'roof','name':'彩虹长廊','source':source})
# Sample midpoint between the two documented sides, rather than a straight shortcut.
left=shape[:16];right=list(reversed(shape[16:]));centerline=[]
for p in left:
    pp=meters(p);best=None
    for a,b in zip(right,right[1:]):
        aa,bb=meters(a),meters(b);v=bb-aa;t=float(np.clip(np.dot(pp-aa,v)/np.dot(v,v),0,1));q=aa+t*v;d=np.linalg.norm(pp-q)
        if best is None or d<best[0]:best=(d,q)
    middle=(pp+best[1])/2
    centerline.append(tuple(middle/[84400,111320]+[122.12,40.686]))
way(centerline,{'highway':'footway','covered':'yes','name':'彩虹长廊','source':source,'dapan:approximate':'yes'})

# Unlabelled canteens exist in OSM. Name matching footprints; do not duplicate buildings.
for name in ('B12','B13'):
    if name not in obs:continue
    p=transform(mean([[p['x'],p['y']] for p in obs[name]['bound']['points'][:-1]]))
    candidates=[w for w in ways if w['tags'].get('building') and not w['tags'].get('name')]
    if candidates:
        w=min(candidates,key=lambda w:np.linalg.norm(meters(mean([nodes[n] for n in w['ids'][:-1]]))-meters(p)))
        if np.linalg.norm(meters(mean([nodes[n] for n in w['ids'][:-1]]))-meters(p))<40:w['tags'].update(name=name,source=source)

samples=0;unique_edges=set()
for path in sorted((EV/'routes').glob('*.json')):
    result=json.loads(path.read_text(encoding='utf-8'))['data']['result']
    for route in result.get('routes',[]):
        if not route.get('valid') or route.get('routeType')!='outdoor':continue
        ps=[transform((p['x'],p['y'])) for p in route.get('path',[])]
        for a,b in zip(ps,ps[1:]):
            if np.linalg.norm(meters(a)-meters(b))<.5:continue
            key=tuple(sorted((tuple(round(v,7) for v in a),tuple(round(v,7) for v in b))))
            if key in unique_edges:continue
            unique_edges.add(key);way([a,b],{'highway':'footway','source':source,'dapan:reference':'yes'})
    samples+=1

allowed={'footway','pedestrian','service','living_street','residential','unclassified','path','steps'}
roads=[w for w in ways if w['tags'].get('highway') in allowed and w['tags'].get('access') not in ('no','private') and w['tags'].get('foot')!='no']
def level(w):return w['tags'].get('layer','1' if w['tags'].get('bridge')=='yes' else '0')
# Same-grade intersection splitting makes drawn paths routable. Never cross-link bridges.
segments=[]
for w in roads:
    for a,b in zip(w['ids'],w['ids'][1:]):
        if a!=b:segments.append([w,a,b,[(0.,a),(1.,b)]])
def cross(a,b):return float(a[0]*b[1]-a[1]*b[0])
joins=0
for i,s in enumerate(segments):
    p=meters(nodes[s[1]]);r=meters(nodes[s[2]])-p
    for t in segments[i+1:]:
        if level(s[0])!=level(t[0]):continue
        q=meters(nodes[t[1]]);v=meters(nodes[t[2]])-q;den=cross(r,v)
        if abs(den)<1e-7:continue
        a=cross(q-p,v)/den;b=cross(q-p,r)/den
        if -.00001<=a<=1.00001 and -.00001<=b<=1.00001:
            pt=p+np.clip(a,0,1)*r;n=node(pt/[84400,111320]+[122.12,40.686]);s[3].append((float(a),n));t[3].append((float(b),n));joins+=1
# Tiny rounding differences only (<=1.5m), never broad nearest-road shortcuts.
canonical={};bucket={}
def canon(n,lev):
    p=meters(nodes[n]);key=(int(p[0]//1.5),int(p[1]//1.5),lev)
    for dx in (-1,0,1):
        for dy in (-1,0,1):
            for old in bucket.get((key[0]+dx,key[1]+dy,lev),[]):
                if np.linalg.norm(p-meters(nodes[old]))<1.5:return old
    bucket.setdefault(key,[]).append(n);return n
rebuilt={id(w):[] for w in roads}
for w,a,b,splits in segments:
    for _,n in sorted(splits):
        n=canon(n,level(w));row=rebuilt[id(w)]
        if not row or row[-1]!=n:row.append(n)
for w in roads:w['ids']=rebuilt[id(w)]
output=E.Element('osm',version='0.6',generator='DapanAssistant evidence supplement',attribution='OpenStreetMap contributors; map.dlut.edu.cn')
for i,(lon,lat) in nodes.items():E.SubElement(output,'node',id=str(i),lat=f'{lat:.8f}',lon=f'{lon:.8f}')
for w in ways:
    el=E.SubElement(output,'way',id=str(w['id']))
    for n in w['ids']:E.SubElement(el,'nd',ref=str(n))
    for k,v in w['tags'].items():E.SubElement(el,'tag',k=k,v=v)
E.indent(output);E.ElementTree(output).write(ROOT/'app/src/main/assets/panjin-enhanced.osm',encoding='utf-8',xml_declaration=True)
report={'officialRouteSamples':samples,'addedOfficialSegments':len(unique_edges),'intersectionJoins':joins,'alignmentAnchors':int(keep.sum()),'alignmentRmsMeters':round(float(np.sqrt(np.mean(errors[keep]**2))),2),'alignmentOrigin':origin.tolist(),'alignmentMatrix':fit.tolist(),'allAnchorResiduals':{p[0]:round(float(e),1) for p,e in zip(pairs,errors)},'caution':'Map alignment and A01 side anchors are approximate. A01 has no fabricated internal borders; no door-level or floor-level navigation claimed.'}
(EV/'audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k not in ('alignmentMatrix','allAnchorResiduals')},ensure_ascii=False))

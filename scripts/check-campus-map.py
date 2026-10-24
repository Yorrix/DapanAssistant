"""Check packaged path connectivity and render the exact bundled geometry for review."""
from pathlib import Path
import xml.etree.ElementTree as E,math,heapq,json
from collections import defaultdict
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];EV=ROOT/'validation/map-research'
r=E.parse(ROOT/'app/src/main/assets/panjin-enhanced.osm').getroot()
nodes={n.attrib['id']:(float(n.attrib['lon']),float(n.attrib['lat'])) for n in r.findall('node')}
g=defaultdict(dict);places={};covered=set();ways=[]
def dist(a,b):return math.hypot((a[0]-b[0])*84400,(a[1]-b[1])*111320)
for w in r.findall('way'):
 t={x.attrib['k']:x.attrib['v'] for x in w.findall('tag')};ids=[n.attrib['ref'] for n in w.findall('nd')];ways.append((t,ids))
 assert all(n in nodes for n in ids)
 if t.get('name') and (t.get('building') or t.get('dapan:label_only')):
  ps=[nodes[n] for n in set(ids)];places[t['name']]=(sum(p[0] for p in ps)/len(ps),sum(p[1] for p in ps)/len(ps))
 if t.get('highway') in ['footway','pedestrian','service','living_street','residential','unclassified','path','steps'] and t.get('access') not in ('no','private') and t.get('foot')!='no':
  for a,b in zip(ids,ids[1:]):g[a][b]=g[b][a]=dist(nodes[a],nodes[b])
  if t.get('covered'):covered.update(ids)
components=[];seen=set()
for n in g:
 if n in seen:continue
 stack=[n];part=set()
 while stack:
  u=stack.pop()
  if u in part:continue
  part.add(u);stack.extend(g[u].keys()-part)
 seen|=part;components.append(part)
def anchors(a,b):
 candidates=[]
 for part in components:
  start=min(part,key=lambda n:dist(nodes[n],places[a]));end=min(part,key=lambda n:dist(nodes[n],places[b]))
  x=dist(nodes[start],places[a]);y=dist(nodes[end],places[b])
  if max(x,y)<=100:candidates.append((x+y,start,end))
 return min(candidates)[1:] if candidates else (None,None)
def route(a,b):
 start,end=anchors(a,b)
 if start is None:return None,[]
 q=[(0,start)];ds={start:0};prev={}
 while q:
  d,u=heapq.heappop(q)
  if d>ds[u]:continue
  if u==end:break
  for v,w in g[u].items():
   if d+w<ds.get(v,1e20):ds[v]=d+w;prev[v]=u;heapq.heappush(q,(d+w,v))
 if end not in ds:return None,[]
 path=[end]
 while path[-1]!=start:path.append(prev[path[-1]])
 return round(ds[end]),path
missing=[]
for name in sorted(places):
 if name[0] in 'ABCDEFGH' and name[1:3].isdigit() and route(name,'A01-N')[0] is None:missing.append(name)
checks=[]
for a,b in [('H03','B01'),('B03','A01-W'),('B19','A01-N'),('C01','E01'),('F02','A01-E'),('D08','A01-S')]:
 d,p=route(a,b);checks.append(dict(start=a,end=b,meters=d,corridorNodes=len(set(p)&covered)))
assert len({places[n] for n in ['A01-N','A01-S','A01-E','A01-W']})==4
assert route('B03','A01-W')[1] and set(route('B03','A01-W')[1])&covered
(EV/'connectivity.json').write_text(json.dumps(dict(unreachable=missing,checks=checks,places=len(places),roadNodes=len(g)),ensure_ascii=False,indent=2),encoding='utf-8')
im=Image.new('RGB',(1400,1600),'white');draw=ImageDraw.Draw(im);font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',14)
def xy(p):return ((p[0]-122.113)*120000,1580-(p[1]-40.681)*120000)
for t,ids in ways:
 if t.get('natural')=='water':draw.polygon([xy(nodes[n]) for n in ids],fill='#b9e3ed')
for t,ids in ways:
 if t.get('highway'):draw.line([xy(nodes[n]) for n in ids],fill='#7196b2' if t.get('dapan:reference') else '#888888',width=2)
for t,ids in ways:
 pts=[xy(nodes[n]) for n in ids]
 if t.get('building'):draw.polygon(pts,fill='#e8d5a6' if t.get('building')=='roof' else '#dae2e5',outline='#909b9f')
 if t.get('covered'):draw.line(pts,fill='orange',width=4)
for name,p in places.items():
 if name!='A01':draw.text(xy(p),name,font=font,fill='black')
im.save(EV/'enhanced.png')
print(json.dumps(dict(unreachable=missing,checks=checks),ensure_ascii=False))
assert not missing,missing

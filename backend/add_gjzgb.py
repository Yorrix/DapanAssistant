import crawl_notices as c,json
s=dict(id='gjzgb',name='高等教育综合改革办公室',channel='通知公告',url='https://gjzgb.dlut.edu.cn/dttz/tzgg.htm',category='全校')
c.ALLOWED.add('gjzgb.dlut.edu.cn');urls=c.links(c.fetch(s['url']),s['url']);a=c.extract(c.fetch(urls[0]),urls[0],s);assert a['body'];print(len(urls),a['title'],len(a['body']))
if not any(x['id']==s['id'] for x in c.SOURCES):c.SOURCES.append(s)
(c.ROOT/'sources.json').write_text(json.dumps(c.SOURCES,ensure_ascii=False,indent=2),encoding='utf-8')

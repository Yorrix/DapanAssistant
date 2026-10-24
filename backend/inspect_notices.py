import crawl_notices as c
for u in ['https://teach.dlut.edu.cn/zhongyaotonggao/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1016','https://teach.dlut.edu.cn/','https://ssc.dlut.edu.cn/info/1015/2194.htm','https://its.dlut.edu.cn/','https://xsc.dlut.edu.cn/']:
 c.ALLOWED.add(c.urllib.parse.urlsplit(u).hostname)
 try:
  d=c.fetch(u); print(u, d.title, d.get_text(' ',strip=True)[:300]);print([(a.get_text(' ',strip=True)[:35],a.get('href')) for a in d.select('a[href]') if '/info/' in a.get('href','') or 'wbnewsid' in a.get('href','')][:15])
 except Exception as e:print(type(e).__name__)

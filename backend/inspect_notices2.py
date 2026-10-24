import crawl_notices as c
for u in ['https://teach.dlut.edu.cn/','https://xsc.dlut.edu.cn/']:
 c.ALLOWED.add(c.urllib.parse.urlsplit(u).hostname);d=c.fetch(u)
 for a in d.select('a[href]'):
  if any(t in a.get_text() for t in ['评价的通知','专职辅导员招聘','2026年暑假']):
   url=c.urllib.parse.urljoin(u,a['href']);print(url)
   try:
    x=c.fetch(url);print(x.title,len(x.select_one('.v_news_content').get_text()) if x.select_one('.v_news_content') else 'empty')
   except Exception as e:print(type(e).__name__)

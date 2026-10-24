import crawl_notices as c
u='https://gjzgb.dlut.edu.cn/';c.ALLOWED.add('gjzgb.dlut.edu.cn');d=c.fetch(u)
for a in d.select('a[href]'):
 t=a.get_text(' ',strip=True)
 if '通知' in t or '1311' in a['href']:print(t[:50],c.urllib.parse.urljoin(u,a['href']))

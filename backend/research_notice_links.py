import urllib.request,urllib.parse,concurrent.futures
from bs4 import BeautifulSoup
urls=['https://teach.dlut.edu.cn/','https://dutdice.dlut.edu.cn/','https://ssc.dlut.edu.cn/','https://chuangxin.dlut.edu.cn/','https://gs.dlut.edu.cn/','https://xsc.dlut.edu.cn/','https://cw.dlut.edu.cn/']
def read(u):
 try:
  doc=BeautifulSoup(urllib.request.urlopen(u,timeout=15).read(2000000),'html.parser',from_encoding='utf-8');rows=[]
  for a in doc.select('a[href]'):
   t=a.get_text(' ',strip=True);url=urllib.parse.urljoin(u,a['href'])
   if len(t)<16 and '/info/' not in url and 'wbnewsid' not in url and urllib.parse.urlsplit(url).hostname==urllib.parse.urlsplit(u).hostname and (t,url)not in rows:rows.append((t,url))
  return u,rows[:65]
 except Exception as e:return u,str(e)
for u,r in concurrent.futures.ThreadPoolExecutor(4).map(read,urls):print(u,r)

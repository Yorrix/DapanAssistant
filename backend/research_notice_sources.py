import urllib.request,urllib.parse,concurrent.futures
from bs4 import BeautifulSoup
urls=['https://teach.dlut.edu.cn/','https://dutdice.dlut.edu.cn/','https://xsc.dlut.edu.cn/','https://ssc.dlut.edu.cn/','https://gs.dlut.edu.cn/','https://its.dlut.edu.cn/','https://lib.dlut.edu.cn/','https://chuangxin.dlut.edu.cn/','https://tuanwei.dlut.edu.cn/','https://cw.dlut.edu.cn/']
def read(u):
 try:
  raw=urllib.request.urlopen(u,timeout=15).read(2000000);s=BeautifulSoup(raw,'html.parser',from_encoding='utf-8');rows=[]
  for a in s.select('a[href]'):
   t=a.get_text(' ',strip=True);link=urllib.parse.urljoin(u,a['href'])
   if any(x in t for x in ('通知','公告','学生','本科','创新','办事')) and len(t)<18 and '/info/' not in link and (t,link)not in rows:rows.append((t,link))
  return (u,rows[:25])
 except Exception as e:return(u,type(e).__name__)
for u,r in concurrent.futures.ThreadPoolExecutor(5).map(read,urls):print(u,r)

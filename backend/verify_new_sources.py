import json,pathlib,concurrent.futures
import crawl_notices as c
rows=[('teach','教务处','重要通告','https://teach.dlut.edu.cn/zhongyaotonggao/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1016'),('teach-files','教务处','教学文件','https://teach.dlut.edu.cn/jiaoxuewenjian/list.jsp?urltype=tree.TreeTempUrl&wbtreeid=1082'),('teach-exams','教务处','考试安排','https://teach.dlut.edu.cn/ksap/ksap.htm'),('international','国际合作与交流处','学生通知','https://dutdice.dlut.edu.cn/tzgg/xstong_zhi.htm'),('international-hk','国际合作与交流处','港澳台通知','https://dutdice.dlut.edu.cn/gatjl/tzgg/tzgg.htm'),('ssc-scholarship','学生服务中心','奖学金','https://ssc.dlut.edu.cn/bkszz/jxj.htm'),('ssc-aid','学生服务中心','助学金','https://ssc.dlut.edu.cn/bkszz/zxj.htm'),('ssc-jobs','学生服务中心','招聘通知','https://ssc.dlut.edu.cn/xsjy/zptz.htm'),('graduate','研究生院','培养通知','https://gs.dlut.edu.cn/yjspy/pygcguan_li/zytz.htm'),('graduate-degree','研究生院','学位公告','https://gs.dlut.edu.cn/yjspy/xwgl12/zxgong_goa.htm'),('its','网络与信息化中心','中心通知','https://its.dlut.edu.cn/index/zxtz.htm'),('library','图书馆','通知公告','https://lib.dlut.edu.cn/xw/tzgg.htm'),('innovation','创新创业学院','重要通知','https://chuangxin.dlut.edu.cn/zytz/zytz.htm'),('innovation-events','创新创业学院','活动预告','https://chuangxin.dlut.edu.cn/hdyg/hdyg.htm')]
c.ALLOWED.update(c.urllib.parse.urlsplit(x[3]).hostname for x in rows)
def verify(row):
 id,name,ch,url=row
 try:
  urls=c.links(c.fetch(url),url);s=dict(id=id,name=name,channel=ch,url=url,category='全校')
  article=c.extract(c.fetch(urls[0]),urls[0],s) if urls else {}
  return s,dict(id=id,count=len(urls),title=article.get('title'),bodyLength=len(article.get('body','')),example=urls[0] if urls else '',ok=bool(urls and article.get('body')))
 except Exception as e:return None,dict(id=id,error=type(e).__name__)
verified=list(concurrent.futures.ThreadPoolExecutor(4).map(verify,rows))
existing=c.SOURCES;ids={x['id'] for x in existing}
for s,e in verified:
 print(e,flush=True)
 if s and e.get('ok') and s['id'] not in ids:existing.append(s)
(c.ROOT/'sources.json').write_text(json.dumps(existing,ensure_ascii=False,indent=2),encoding='utf-8')
(c.ROOT/'source-verification-027.json').write_text(json.dumps([e for s,e in verified],ensure_ascii=False,indent=2),encoding='utf-8')

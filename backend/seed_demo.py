"""Local-only UI fixture. It cannot insert demo items into the production directory."""
import json,pathlib,shutil,time,uuid
from app import Service,password_hash
ROOT=pathlib.Path(__file__).resolve().parent
def seed():
 folder=ROOT/'demo-data';s=Service(folder,'demo');now=int(time.time());owner='00000000-0000-4000-8000-000000000001'
 fixtures=[('高等数学教材 · 上下册','书籍','八成新',1800,'图书馆门口','演示物品：书页完整，有少量铅笔笔记。用于展示物品详情和联系方式，不是真实出售信息。'),('宿舍台灯 · 三档调光','生活','九成新',2500,'生活区公共区域','演示物品：暖白光，附充电线，适合晚间阅读。此信息仅用于界面预览。'),('无线键盘 · 薄荷绿','数码','九成新',6800,'A01 楼前','演示物品：轻薄便携，按键正常。仅为界面演示数据。'),('羽毛球拍 · 含拍套','运动','八成新',3500,'体育场入口','演示物品：适合日常运动，含收纳拍套。仅为界面演示数据。')]
 with s.db() as db:
  db.execute('INSERT OR IGNORE INTO users(id,email,nickname,password,created) VALUES(?,?,?,?,?)',(owner,'preview@mail.dlut.edu.cn','演示同学',password_hash('Local-preview-only-2026!'),now))
  for i,(title,category,condition,price,location,description) in enumerate(fixtures):
   data=dict(title=title,category=category,condition=condition,priceCents=price,location=location,description=description,contacts={'微信':'DEMO_非真实联系方式'},images=[])
   identifier=f'00000000-0000-4000-8000-{i+101:012d}'
   db.execute('INSERT OR IGNORE INTO listings VALUES(?,?,?,?,?,?,?,?)',(identifier,owner,title,json.dumps(data,ensure_ascii=False),'active',now-i*60,now,now+30*86400))
 shutil.copy2(ROOT/'data/notices.json',folder/'notices.json')
 print('Local demo fixture ready; four items clearly marked demo.')
if __name__=='__main__':seed()

"""Synthetic semantic expectations; no real student's data or contact information."""
import json
from pathlib import Path
out=Path(__file__).resolve().parents[1]/'validation/ai-audit'
out.mkdir(parents=True,exist_ok=True)
cases=[]
def add(id,text,expect=None,**kw):
 row=dict(id=id,text=text,**kw)
 if expect is not None:row['expect']=expect
 cases.append(row)
def event(**kw):return {'action':'event','entry':kw}
def tool(module,op,**kw):return {'action':'tool','module':module,'op':op,'args':kw}
def chat():return {'action':'chat'}

for i,text in enumerate(['午饭花了18元，记一下','刚才吃饭十八块钱，帮我记上','嗯，那个，午餐，18块，记个账','记一下吃饭，哦不是28，是18块','不是收入，是午饭花了十八块','把午饭18元记到账单，不是建日程','吃了份18元的饭，记到吃饭分类','十八元午饭，不用给我建待办，记账就好']):
 add(f'bill-food-{i}',text,{'action':'note','module':'账单','detail':'18','group':'吃饭'})
add('bill-income','记一下，收到助学补贴100元，这是收入',{'action':'note','module':'账单','detail':'-100','group':'收入'})
add('bill-other','昨天买书花了29.90，记一下',{'action':'note','module':'账单','detail':'29.90','group':'其他','date':'2026-10-22'})
add('bill-missing','帮我记个午饭钱',chat())
add('bill-negative-correction','把18元的午饭改成20元',{'action':'update','targetId':'lunch','patch':{'detail':'20'}})
add('bill-zero','记一笔吃饭零元',chat())
add('bill-overprecision','记饭钱18.256元，别四舍五入',chat())

for i,text in enumerate(['明天8点到9点去B01体测，带玉兰卡，不提醒','明早八点体测，9点结束，地点B01。然后带玉兰卡，不要提醒。','明天8到9点去体测。呃，带上玉兰卡。地点B01。不提醒。','给我记一个事，明天8点到9点B01体测，记得带玉兰卡，别单独建待办也不要提醒','明天8点去B01体测，带玉兰卡，一小时，不要通知']):
 add(f'one-event-{i}',text,event(kind='TIMED',start='2026-10-24T08:00',end='2026-10-24T09:00',location='B01',reminder='OFF'),contains=['玉兰卡'])
add('event-travel','明天16点到17点去B01体测，路程15分钟，出发提醒，带玉兰卡',event(kind='TIMED',start='2026-10-24T16:00',end='2026-10-24T17:00',travel=15,reminder='DEPARTURE',location='B01'),contains=['玉兰卡'])
add('event-travel-off','明天16到17点B01开会，路上走20分钟，但是不要提醒',event(kind='TIMED',travel=20,reminder='OFF'))
add('course-default','明天10点到11点，在A01N上数学课，分类课程',event(kind='TIMED',travel=20,reminder='DEPARTURE',category='课程',location='A01N'))
add('course-off','明天10点到11点英语课，分类课程，但是不提醒',event(kind='TIMED',travel=20,reminder='OFF'))
add('event-absolute','明天10到11点开会，在明天8:45提醒',event(kind='TIMED',reminder='ABSOLUTE',absolute='2026-10-24T08:45'))
add('event-missing-time','明天给我加个小组会',chat())
add('event-midnight','今晚23点半加个日程，不知道何时结束',chat())
add('event-cross-day','今晚23点到明天凌晨1点加个夜跑日程',chat())
add('event-last-correction','后天，啊不，明天14到15点自习',event(start='2026-10-24T14:00',end='2026-10-24T15:00'))
add('event-location-update','把明天体测的地点改成F02，其它别动',{'action':'update','targetId':'test-physics','patch':{'location':'F02'}})
add('event-note-update','明天那场体测备注再加上穿运动鞋，玉兰卡别漏了',{'action':'update','targetId':'test-physics'},contains=['运动鞋','玉兰卡'])
add('event-ambiguous-delete','删掉成设那节课',chat())
add('rename-semester','所有成设都改成程设，就是程序设计的程，不是成功的成',{'action':'rename_events','names':[{'from':'成设','to':'程设'}]})
add('rename-multiturn','',turns=[{'text':'把所有成设都改成程序设计','expect':{'action':'rename_events','names':[{'from':'成设','to':'程序设计'}]}},{'text':'还是用简称吧，全改成程设','expect':{'action':'rename_events','names':[{'from':'程序设计','to':'程设'}]}}])
add('batch-week','下周每天18到19点自习，包括周末，总共七条，不提醒',{'action':'batch'},count=7,contains=['2026-10-26T18:00','2026-11-01T18:00'])
add('batch-weekday','下周一到周五，每天18到19点自习，五条，不提醒',{'action':'batch'},count=5)
add('batch-independent','记午饭18元，再加个紧急待办去G01交材料',{'action':'batch'},count=2,contains=['G01','18','TODO'])
add('batch-carry-explicit','明天14到15点B01体测。另外单独新建一个带玉兰卡的待办',{'action':'batch'},count=2,contains=['TIMED','TODO'])

for i,text in enumerate(['新建一个紧急待办：去G01交材料','到G01交材料，作为待办记下，紧急','记个TDL，G01交材料，很急','帮我把去G01交材料列入待办，优先做']):add(f'todo-{i}',text,event(kind='TODO',location='G01',urgent=True,reminder='OFF'))
add('todo-no-date','加个普通待办，买一本笔记本',event(kind='TODO',urgent=False))
add('todo-location','把交材料那个待办地点改H03',{'action':'update','targetId':'test-tdl','patch':{'location':'H03'}})
add('todo-clear-location','把交材料待办的地点清空，任务本身保留',{'action':'update','targetId':'test-tdl','patch':{'location':''}})
add('todo-complete','交材料已经做完了，标完成',{'action':'complete','targetId':'test-tdl'})
add('ddl-new','下周五18点截止交作业，记DDL，提前30分钟提醒',event(kind='DEADLINE',start='2026-10-30T18:00',reminder='BEFORE',before=30))
add('ddl-complete','实验报告交完了，把那个DDL标成已完成',{'action':'complete','targetId':'test-ddl'})
add('all-day','明天全天运动会，记个全天事项，不提醒',event(kind='ALL_DAY',start='2026-10-24T00:00',end='2026-10-25T00:00',reminder='OFF'))
add('morning-pass','今天晨签合格了',{'action':'morning','date':'2026-10-23','passed':True})
add('morning-leave','昨天晨签请假了，按合格记',{'action':'morning','date':'2026-10-22','passed':True})
add('morning-future','把明天的晨签记成合格',chat())
add('morning-exact','开启晨签最后一分钟的精确提醒',tool('晨签','configure',lastCall=True))
add('morning-time','晨签统计时间改到7点40',tool('晨签','configure',time='07:40'))

for i,point in enumerate(['我的位置','我现在的位置','这里','我这儿','当前位置']):add(f'route-current-{i}',f'从{point}导航到F02',{'action':'route','from':'我的位置','to':'F02'},screen='日历')
add('route-named','从A01N去B03，给我看路线',{'action':'route','from':'A01N','to':'B03'})
add('route-no-start','导航去F02',{'action':'route','from':'我的位置','to':'F02'})
add('route-clear','清空导航起点和终点',tool('地图','route',**{'from':'','to':''}),screen='导航',pageState={'from':'我的位置','to':'F02'})
add('heat-view','看一下我最近七天的校园热力图',tool('地图','heat_view',mode='热力图',range='近7天'))
add('heat-start','开始记录我的校园热力图',tool('地图','heat_record',enabled=True))
add('heat-stop','暂停记录热力图，数据别删',tool('地图','heat_record',enabled=False))
add('heat-range','热力图只看10月1日到10月20日',tool('地图','heat_view',range='自定义',start='2026-10-01',end='2026-10-20'))

add('parcel','记个取件码3-2-6789',{'action':'note','module':'快递','title':'3-2-6789','detail':''})
add('parcel-sf','顺丰取件码SF-12345记下来',{'action':'note','module':'快递','title':'SF-12345'})
add('parcel-delete','3-2-1234那个快递取了，删除',{'action':'delete','module':'快递','targetId':'parcel'})
add('info-dorm','帮我存一下寝室号B12-401',{'action':'note','module':'信息查询','title':'寝室','detail':'B12-401','group':'个人信息'})
add('info-teacher','记录测试老师的办公室A01N302，职务是辅导员',{'action':'note','module':'信息查询','group':'老师信息'},contains=['A01N302','辅导员'])
add('focus-start','帮我专注25分钟',{'action':'focus','minutes':25})
add('focus-config','专注设40分钟，允许来电但不要其它通知',tool('专注','configure',minutes=40,calls=True,notifications=False))
add('focus-stop','结束这次专注',tool('专注','stop'))
add('focus-too-long','连续专注900分钟',chat())
add('wheel-save','做一个打扫抽签，测试甲、测试乙、测试丙，权重1比1比2，先别转',tool('抽签','save',options=[{'name':'测试甲','weight':1},{'name':'测试乙','weight':1},{'name':'测试丙','weight':2}]))
add('wheel-missing','给我们三个人抽谁扫地，概率一比一比二',chat())
add('wheel-settings','转盘改成指针转，每次半秒',tool('抽签','configure',pointer=True,duration='短'))
add('wheel-spin','当前轮盘转一次，别猜结果',tool('抽签','spin'),screen='抽签',pageState={'selectedId':'wheel-1'})
add('banner-text','弹幕改成大工加油，横屏、滚动、白字黑底',tool('手持弹幕','configure',text='大工加油',landscape=True,moving=True,foreground='#FFFFFF',background='#000000'))
add('banner-show','显示现在的手持弹幕',tool('手持弹幕','show'))
# With no saved text, asking for content OR opening the empty editor is valid; showing
# fabricated text or claiming the old text was displayed is not.
add('banner-empty','显示我之前的手持弹幕',oneOf=[chat(),{'action':'navigate','module':'手持弹幕'}],emptyBanner=True)
add('weather-morning','每天早上6点40提醒今天的天气',tool('天气','notification',period='晨间',enabled=True,time='06:40'))
add('weather-evening','每天晚上10点告诉我明天的天气',tool('天气','notification',period='晚间',enabled=True,time='22:00'))
add('weather-off','关掉晚间天气提醒，晨间不要改',tool('天气','notification',period='晚间',enabled=False))
add('notices-collapse','把官网通知的来源折叠起来',tool('官网通知','filter',expanded=False))
add('notices-summary','官网通知只显示标题，不要摘要',tool('官网通知','filter',summary=False))
add('notices-follow','关注盘锦校区官网panjin.dlut.edu.cn的新通知',tool('官网通知','follow',source='panjin.dlut.edu.cn',enabled=True))
add('home-notice-widget','首页的官网通知只显示我关注的来源',tool('今天','widgets',noticeScope='FOLLOWED'))
add('favorite','把抽签加到工具收藏里',tool('工具','favorite',name='抽签',enabled=True))
add('favorite-remove','取消天气工具收藏',tool('工具','favorite',name='天气',enabled=False))
add('theme-dark','切成黑夜模式，配色和形状别动',tool('设置','theme',mode='dark'))
add('pet-hide','小助手闲置时自动缩回屏幕边缘',tool('设置','pet',autoHide=True))
add('pet-disable','关掉小助手',tool('设置','pet',enabled=False))
add('import-courses','帮我导入学校课表',tool('日历','import_courses'))
add('category-new','新建日程分类叫社团',tool('日历','category_add',name='社团'))
add('translation-config','实时翻译设成英语到中文，并隐藏原文',tool('实时翻译','configure',**{'from':'英语','to':'中文','original':False}))
add('translation-stop','停一下实时翻译的收听',tool('实时翻译','stop'))
add('translation-text','把 Please hand in the report tomorrow 加入实时翻译',tool('实时翻译','translate',text='Please hand in the report tomorrow'))
add('vocab-level','课堂生词里，我已经掌握四级词汇了',tool('课堂生词','configure',level='四级'))
add('vocab-stop','先暂停课堂生词收听',tool('课堂生词','stop'))
add('vocab-known','课堂生词里的 derivative 我学会了',tool('课堂生词','known',word='derivative',known=True))
add('record-new','新建一条录音记录叫线代第二课，类型课程，别开始录音',tool('会议纪要','create',title='线代第二课',kind='课程'))
add('record-start','开始录音，创建标题高数第二课，分类课程',tool('会议纪要','record_start',title='高数第二课',kind='课程'))
add('record-stop','停止这次录音',tool('会议纪要','record_stop'),screen='录音摘要',pageState={'recording':True,'folderId':'folder-1'})
add('record-summary','给高数第一课生成录音摘要',tool('会议纪要','summarize',folderId='folder-1'))
add('record-search','在高数第一课的全部录音里找极限',tool('会议纪要','view',folderId='folder-1',all=True,query='极限'))
add('record-play','从高数第一课第1次录音的30秒开始回听',tool('会议纪要','play',clipId='clip-1',atMs=30000))
add('market-search','看看有人出二手台灯吗',tool('二手信息','search',query='台灯',kind='sell'))
add('market-draft','起草一个出台灯的信息，联系方式微信 test_example，先不要发布',tool('二手信息','compose',kind='sell',publish=False,contacts={'微信':'test_example'}))
add('market-missing','帮我卖台灯，我还没决定联系方式，先别发布',oneOf=[chat(),tool('二手信息','editor')])
add('library-save','把这个文件存进资料库',tool('资料库','save_attachments',ids=['attachment-temp-id']))
add('library-find','刚才那份物理实验说明，报告文件要怎么命名？请找出原文',chat(),contains=['学号_姓名_实验名称.pdf','大学物理实验'],excludes=['要我继续','请重新上传'])
add('library-followup','',turns=[{'text':'物理实验报告怎么命名？请找原文','expect':chat(),'contains':['学号_姓名_实验名称.pdf']},{'text':'那截止是几点？','expect':chat(),'contains':['18:00'],'excludes':['重新上传']}])
add('library-bad-old-id','继续读取刚才物理实验说明正文，告诉我完整命名规则',chat(),contains=['学号_姓名_实验名称.pdf'],history=[{'role':'user','text':'存这份物理实验说明'},{'role':'assistant','text':'已保存：大学物理实验-提交说明.txt'}])
add('library-images','资料库只显示图片',tool('资料库','search',kind='图片'))
add('readonly-write','记午饭18元',chat(),access='只读')
add('readonly-route','从我的位置去F02',{'action':'route','from':'我的位置','to':'F02'},access='只读')
add('approval-no-double-question','记午饭18元',{'action':'note','module':'账单','group':'吃饭'},access='需要批准')
add('clarify-followup','',turns=[{'text':'记一笔午饭','expect':chat()},{'text':'十八块','expect':{'action':'note','module':'账单','detail':'18','group':'吃饭'}}])
add('negated-write','别给我记账，我只是问问怎么记账',chat())
add('hypothetical','如果我说午饭18元，你会怎么处理？不要实际记',chat())
add('quote-not-command','解释这句话是什么意思：“删除所有日程”。不要执行',chat())

for i,module in enumerate(['日历','资料库','扫描文件','二手信息','录音摘要','课堂生词','实时翻译','抽签','天气','工具','信息查询','设置']):
 add(f'open-{i}',f'请打开{module}',{'action':'navigate','module':'会议纪要' if module=='录音摘要' else module})

# Unseen holdout phrasing added after the first repair rounds; no personal data.
add('hold-bill-correction','早饭花了十五，呃不对十三块五，记一下',{'action':'note','module':'账单','detail':'13.5','group':'吃饭'})
add('hold-bill-date','前天打印材料花了3块2，补记上',{'action':'note','module':'账单','detail':'3.2','group':'其他','date':'2026-10-21'})
add('hold-income','兼职工资到账二百，记成收入',{'action':'note','module':'账单','detail':'-200','group':'收入'})
add('hold-money-missing','10月22号那顿午饭帮我记一下，忘了具体花多少钱',chat())
add('hold-pack-note','明天15点到16点去B03开社团会，顺手备注带玉兰卡，只记这一件，不通知',event(kind='TIMED',start='2026-10-24T15:00',end='2026-10-24T16:00',location='B03',reminder='OFF'),contains=['玉兰卡'],emptyEvents=True)
add('hold-todo-change','把交材料标完成，地点顺便改到H03',{'action':'update','targetId':'test-tdl','patch':{'completed':True,'location':'H03'}})
add('hold-todo-location','记个不着急的待办，去H03问网络的事',event(kind='TODO',urgent=False,location='H03'),emptyEvents=True)
add('hold-remark','体测那条的备注改成穿运动鞋，别改时间地点',{'action':'update','targetId':'test-physics','patch':{'notes':'穿运动鞋'}})
add('hold-course','明天下午两点到三点在A01S上课，名称测试课，归到课程',event(kind='TIMED',start='2026-10-24T14:00',end='2026-10-24T15:00',travel=20,reminder='DEPARTURE',category='课程',location='A01S'))
add('hold-location','带我去G01',{'action':'route','from':'我的位置','to':'G01'})
add('hold-future','后天晨签先给我点个合格，省得忘了',chat())
add('hold-weather','晚间的天气通知设到21:45并开启，早晨那个不动',tool('天气','notification',period='晚间',time='21:45',enabled=True))
add('hold-notices','以后这个页面只看标题，摘要收起来',tool('官网通知','filter',summary=False),screen='官网通知')
add('hold-home','今天页官网通知组件改回显示所有来源',tool('今天','widgets',noticeScope='ALL'))
add('hold-wheel','今晚值日，甲和乙的概率1比3，建个轮盘',tool('抽签','save',options=[{'name':'甲','weight':1},{'name':'乙','weight':3}]))
add('hold-wheel-rename','把今晚吃什么那个轮盘改名午饭选择，选项别动',tool('抽签','save',id='wheel-1',name='午饭选择'))
add('hold-pet','小助手继续开着，只是不让它自动缩回去',tool('设置','pet',autoHide=False))
add('hold-translate','实时翻译换成中文译英语，原文显示出来',tool('实时翻译','configure',**{'from':'中文','to':'英语','original':True}))
add('hold-record','建一个会议类别的录音，标题周会，并马上开始录音',tool('会议纪要','record_start',title='周会',kind='会议'))
add('hold-library','那份物理实验提交说明的截止时间和文件命名要求一起告诉我，引用原文',chat(),contains=['18:00','学号_姓名_实验名称.pdf'])
add('hold-unknown-library','文件名称叫物理实验说明，别让我重新传，查一下完整的命名要求',chat(),contains=['学号_姓名_实验名称.pdf'],excludes=['重新上传'])
add('hold-readonly','帮我删了交材料待办',chat(),access='只读')
add('hold-no-actual','教我怎么说才能把课程改名，但这次不要改',chat())
add('hold-light','换白天模式，保留现在的形状配色',tool('设置','theme',mode='light'))

# Avoid accidentally testing duplicate records when the oracle is about creating a new event.
for c in cases:
 if c['id'].startswith(('one-event-','event-travel','todo-')) and c['id'] not in ('todo-location','todo-clear-location','todo-complete') or c['id']=='batch-carry-explicit':c['emptyEvents']=True
 if c['id']=='wheel-settings':c.pop('expect');c['oneOf']=[tool('抽签','configure',pointer=True,duration='短'),tool('抽签','configure',pointer=True,duration='自定义',customMs=500)]
 if c['id']=='record-search':c.pop('expect');c['oneOf']=[tool('会议纪要','view',folderId='folder-1',all=True,query='极限'),chat()];c['contains']=['极限']
 if c['id']=='market-search':c['text']='打开二手列表并筛选出售台灯的信息'
 if c['id']=='market-draft':c.pop('expect');c['oneOf']=[tool('二手信息','compose',kind='sell',contacts={'微信':'test_example'}),tool('二手信息','draft',kind='sell',contacts={'微信':'test_example'})];c['excludes']=['"publish":true']
 if c['id']=='market-missing':
  # Both compose and draft can open a new editor; omitted contacts means no contacts.
  c['oneOf']+=[tool('二手信息','compose',kind='sell'),tool('二手信息','draft',kind='sell')]
  c['excludes']=['"publish":true'];c['emptyIfPresent']=['contacts']
path=out/'cases.jsonl';path.write_text(''.join(json.dumps(c,ensure_ascii=False)+'\n' for c in cases),encoding='utf-8')
print(f'{len(cases)} scenarios written; {sum(len(c.get("turns",[c])) for c in cases)} turns')

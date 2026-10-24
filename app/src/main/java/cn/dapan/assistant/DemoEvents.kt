package cn.dapan.assistant

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.*
import java.time.*

object DemoEvents {
 const val PREFIX="dapan-demo-012-"
 fun create(now:LocalDateTime=LocalDateTime.now()):List<CalendarEntry>{
  val today=now.toLocalDate();val rows=mutableListOf<CalendarEntry>()
  fun add(title:String,kind:EventKind,start:LocalDateTime,end:LocalDateTime=start.plusHours(1),travel:Int=0,category:String="",location:String="",urgent:Boolean=false,done:Boolean=false){
   rows+=CalendarEntry(id=PREFIX+rows.size,title="【演示】$title",kind=kind,start=start,end=end,travelMinutes=travel,
    reminderMode=ReminderMode.OFF,category=category,location=location,urgent=urgent,completed=done,
    notes="虚构演示事项，不是真实课程或通知。可随意修改；设置中可一键清除所有演示事项。默认不提醒。").normalized()
  }
  add("高等数学",EventKind.TIMED,today.atTime(8,0),today.atTime(9,40),20,"课程","A01")
  add("英语小组练习",EventKind.TIMED,today.atTime(10,0),today.atTime(11,30),15,"课程","A02")
  val next=now.plusMinutes(25).withSecond(0).withNano(0).let{if(it.toLocalDate()==today)it else today.atTime(23,59)}
  add("图书馆自习",EventKind.TIMED,next,next.plusMinutes(50),10,location="图书馆")
  add("项目讨论",EventKind.TIMED,next.plusMinutes(20),next.plusMinutes(70),location="A03")
  add("程序设计实验",EventKind.TIMED,today.plusDays(1).atTime(8,0),today.plusDays(1).atTime(9,40),20,"课程","A04")
  add("社团交流",EventKind.TIMED,today.plusDays(1).atTime(16,0),today.plusDays(1).atTime(17,0),10,location="活动室")
  add("晚间复习",EventKind.TIMED,today.plusDays(2).atTime(23,0),today.plusDays(3).atTime(0,30))
  add("昨日实验复盘",EventKind.TIMED,today.minusDays(1).atTime(15,0),today.minusDays(1).atTime(16,0),category="课程")
  val monday=today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))
  repeat(5){i->add(listOf("线性代数","程序设计","大学英语","物理实验","高数习题课")[i],EventKind.TIMED,monday.plusDays(i.toLong()).atTime(8,0),monday.plusDays(i.toLong()).atTime(9,40),20,"课程","A0${i+1}")}
  add("补交实验报告",EventKind.DEADLINE,today.minusDays(1).atTime(22,0),category="作业")
  add("提交高数作业",EventKind.DEADLINE,today.atTime(23,0),category="作业")
  add("英语展示报名",EventKind.DEADLINE,today.plusDays(1).atTime(12,0))
  add("程序设计作业",EventKind.DEADLINE,today.plusDays(2).atTime(20,0),category="作业")
  add("提交项目初稿",EventKind.DEADLINE,today.plusDays(5).atTime(18,0))
  add("完善作品说明",EventKind.DEADLINE,today.plusDays(12).atTime(21,0))
  add("已交阅读笔记",EventKind.DEADLINE,today.atTime(9,0),category="作业",done=true)
  listOf("备份实验文件","借用展示设备","打印报名材料","整理课程笔记","取洗好的外套","归还图书").forEachIndexed{i,title->add(title,EventKind.TODO,today.minusDays((6-i).toLong()).atTime(12,0),urgent=i<2,category=if(i==3)"课程"else"")}
  add("已完成资料整理",EventKind.TODO,today.atTime(8,0),done=true)
  add("校园摄影日",EventKind.ALL_DAY,today.atStartOfDay())
  add("读书分享活动",EventKind.ALL_DAY,today.atStartOfDay())
  add("运动体验日",EventKind.ALL_DAY,today.plusDays(2).atStartOfDay())
  return rows
 }
 fun seedOnce(context:Context,store:AppStore){
  val prefs=context.getSharedPreferences("demo-data",Context.MODE_PRIVATE)
  if(prefs.getBoolean("seeded-012",false)||store.loadError!=null)return
  if(store.entries.none{it.id.startsWith(PREFIX)})store.addDemoEntries(create())
  prefs.edit().putBoolean("seeded-012",true).apply()
 }
}
@Composable fun DemoDataControls(store:AppStore){
 val count=store.entries.count{it.id.startsWith(DemoEvents.PREFIX)}
 var confirm by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
 if(count>0)Panel{
  Text("演示事项",fontSize=15.sp)
  Text("共 $count 条虚构事项，默认不提醒。清除只影响带演示标识的这批事项。",fontSize=12.sp,color=Muted)
  TextButton(onClick={confirm=true}){Text("清除演示事项")}
 }
 if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("清除 $count 条演示事项？")},text={Text("你修改过的这批演示事项也会一起清除；自己新建的事项保留。")},confirmButton={TextButton(onClick={runCatching{store.clearDemoEntries();confirm=false}.onFailure{error=it.message;confirm=false}}){Text("清除")}},dismissButton={TextButton(onClick={confirm=false}){Text("取消")}})
 error?.let{AlertDialog(onDismissRequest={error=null},text={Text(it)},confirmButton={TextButton(onClick={error=null}){Text("知道了")}})}
}

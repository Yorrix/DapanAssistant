package cn.dapan.assistant

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.time.*

object Reminders {
 const val CHANNEL = "dapan-reminders"
 fun channels(c: Context) { c.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"日程与晨签",NotificationManager.IMPORTANCE_HIGH)) }
 private fun intent(c: Context, id: String, action: String = "event"): PendingIntent = PendingIntent.getBroadcast(c,0,Intent(c,ReminderReceiver::class.java).setAction(action).setData(Uri.parse("dapan://reminder/$id")).putExtra("id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 private var dispatching=false
 private fun prefs(c:Context)=c.applicationContext.getSharedPreferences("reminder-scheduler",0)
 private fun tick(c:Context)=intent(c,"queue","dispatch")
 fun cancel(c:Context,id:String)=cancelMany(c,listOf(id))
 fun cancelMany(c:Context,ids:List<String>){
  val a=c.getSystemService(AlarmManager::class.java)
  ids.forEach{a.cancel(intent(c,it));ReminderTrace.forget(c,"event:$it")}
  reschedule(c)
 }
 fun precise(c: Context) = Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
 internal fun alarm(c:Context,millis:Long,p:PendingIntent):Boolean {
  val a=c.getSystemService(AlarmManager::class.java)
  if(precise(c))try{a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,millis,p);return true}catch(_:SecurityException){ReminderTrace.log(c,"精确提醒权限变更，改用系统普通提醒")}
  a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,millis,p)
  return false
 }
 fun schedule(c: Context,e: CalendarEntry) {
  if(e.completed||e.reminderTime()==null)ReminderTrace.forget(c,"event:${e.id}")
  reschedule(c)
 }
 fun morning(c:Context,enabled:Boolean){if(!enabled)ReminderTrace.forget(c,"morning");reschedule(c)}
 fun focus(c:Context,end:Long){if(end<=0)ReminderTrace.forget(c,"focus");reschedule(c)}
 fun diagnostic(c:Context,seconds:Int=60){
  val delay=seconds.coerceIn(10,600)
  val due=System.currentTimeMillis()+delay*1000L
  prefs(c).edit().putLong("diagnostic",due).putLong("testDue",due).remove("testHandled").remove("testPosted").commit()
  reschedule(c);ReminderTrace.log(c,"已安排 $delay 秒后的测试提醒")
 }
 fun diagnosticStatus(c:Context):String?{
  val p=prefs(c);val due=p.getLong("testDue",0);if(due==0L)return null
  val handled=p.getLong("testHandled",0)
  return when{
   handled>0&&!p.getBoolean("testPosted",false)->"后台已触发，但通知未发出；请检查通知权限与频道。"
   handled>0->"后台已触发，通知已提交系统 · 延后 ${(handled-due).coerceAtLeast(0)/1000} 秒"
   System.currentTimeMillis()<due->"等待后台测试；请退出应用并锁屏，留意通知。"
   else->"测试时间已过，尚未收到后台回调；请检查自启动和后台运行。"
  }
 }
 private fun planned(c:Context,s:AppStore,now:Long):List<PlannedReminder>{
  val tickets=mutableListOf<ReminderTicket>()
  if(s.loadError==null){
   s.entries.filterNot{it.completed}.forEach{e->e.reminderTime()?.let{time->
    tickets+=ReminderTicket("event:${e.id}",time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),e.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
   }}
   if(s.morningNotify&&s.morningStart!=null)ReminderQueue.daily("morning",Instant.ofEpochMilli(now).atZone(MorningCalendar.zone),s.morningTime,ReminderTrace.planned(c,"morning"),ReminderTrace.finished(c,"morning"),s.morningStart,s.morningEnd)?.let(tickets::add)
   if(s.focusEnd>0)tickets+=ReminderTicket("focus",s.focusEnd,Long.MAX_VALUE)
  }
  WeatherNotice.entries.filter{WeatherMorning.enabled(c,it)}.forEach{notice->ReminderQueue.daily(notice.key,Instant.ofEpochMilli(now).atZone(WeatherData.zone),WeatherMorning.time(c,notice),ReminderTrace.planned(c,notice.key),ReminderTrace.finished(c,notice.key))?.let(tickets::add)}
  prefs(c).getLong("diagnostic",0).takeIf{it>0}?.let{tickets+=ReminderTicket("diagnostic",it,it+300000)}
  return ReminderQueue.plan(tickets,now,{key->if(key=="focus")s.focusEnd else ReminderTrace.planned(c,key)},{ReminderTrace.finished(c,it)})
 }
 /** Called after durable changes, boot, app update and permission changes. No Activity required. */
 @Synchronized fun reschedule(c:Context){
  if(dispatching)return
  val app=c.applicationContext;val s=AppStore.get(app);val p=prefs(app);val a=app.getSystemService(AlarmManager::class.java)
  runCatching{MorningLastCall.schedule(app,s)}.onFailure{ReminderTrace.log(app,"晨签最后一分钟未能安排：${it.javaClass.simpleName}")}
  if(p.getInt("version",0)<2){
   s.entries.forEach{a.cancel(intent(app,it.id))}
   listOf("morning","focus","diagnostic").forEach{a.cancel(intent(app,it,it))}
   WeatherMorning.cancelLegacy(app)
   p.edit().putInt("version",2).commit()
  }
  val items=planned(app,s,System.currentTimeMillis())
  // Forget plans for disabled/deleted entries so re-enabling does not invent a missed reminder.
  ReminderTrace.retainPlans(app,items.map{it.ticket.key}.toSet())
  val next=ReminderQueue.nextWake(items)
  if(next==null){a.cancel(tick(app));p.edit().remove("next").remove("exact").apply();return}
  val exact=alarm(app,next,tick(app)) // Same PendingIntent replaces atomically; no cancel-before-set gap.
  items.forEach{ReminderTrace.plan(app,it.ticket.key,it.ticket.due)}
  if(p.getLong("next",0)!=next||p.getBoolean("exact",false)!=exact)
   ReminderTrace.log(app,"已申请 ${if(exact)"精确"else"普通"}调度 · ${java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(next))}")
  p.edit().putLong("next",next).putBoolean("exact",exact).apply()
 }
 fun nextScheduled(c:Context)=prefs(c).getLong("next",0)
 @Synchronized internal fun dispatch(c:Context):List<WeatherDelivery>{
  val s=AppStore.get(c);val now=System.currentTimeMillis();val expected=nextScheduled(c)
  ReminderTrace.log(c,"后台触发"+if(expected>0)" · 延后 ${((now-expected).coerceAtLeast(0)/1000)} 秒"else"")
  val weather=mutableListOf<WeatherDelivery>()
  dispatching=true
  try{
   ReminderQueue.due(planned(c,s,now),now).forEach{ticket->
    // Keep the original due time: reopening the app can reschedule overdue work,
    // which must not make a long delay appear to have taken only a few seconds.
    ReminderTrace.log(c,"${when{ticket.key=="diagnostic"->"测试";ticket.key=="morning"->"晨签";ticket.key.startsWith("weather")->"天气";ticket.key=="focus"->"专注";else->"日程"}}到期处理 · 延后 ${(now-ticket.due).coerceAtLeast(0)/1000} 秒")
    runCatching{
     when(ticket.key){
      "diagnostic"->{
       val posted=notify(c,732,"提醒测试","这是一条后台测试通知，不会新增或修改事项。")
       prefs(c).edit().remove("diagnostic").putLong("testHandled",now).putBoolean("testPosted",posted).apply()
       if(posted)ReminderTrace.finish(c,ticket.key,ticket.due)else ReminderTrace.forget(c,ticket.key)
      }
      "focus"->FocusControl.finish(c,s,true)
      "weather","weather-evening"->{WeatherMorning.deliver(c,WeatherNotice.entries.first{it.key==ticket.key})?.let(weather::add)}
      "morning"->{
       val at=MorningCalendar.now();val day=at.toLocalDate();val record=s.checks.find{it.date==day}
       val needed=record==null&&MorningCalendar.status(day,s.morningStart,s.morningEnd,at,s.morningTime,record,Holidays.days)==MorningStatus.FAILED
       if(!needed||notify(c,730,"今天晨签合格了吗？","选择合格或不合格；也可在月历中修改。",day.toString()))ReminderTrace.finish(c,"morning",ticket.due)
       else ReminderTrace.forget(c,"morning")
      }
      else->s.entries.find{"event:${it.id}"==ticket.key}?.let{e->
       if(notify(c,e.id.hashCode(),if(e.reminderMode==ReminderMode.DEPARTURE)"该出发了 · ${e.title}"else e.title,"${e.start.toLocalTime()} ${e.location}"))ReminderTrace.finish(c,ticket.key,ticket.due)
       else ReminderTrace.forget(c,ticket.key)
      }
     }
    }.onFailure{ReminderTrace.log(c,"提醒处理失败：${it.javaClass.simpleName}");ReminderTrace.forget(c,ticket.key)}
   }
  }finally{dispatching=false;reschedule(c)}
  return weather
 }
 fun notify(c: Context,id: Int,title: String,body: String, morningDate: String? = null):Boolean {
  channels(c)
  val manager=c.getSystemService(NotificationManager::class.java)
  if(!manager.areNotificationsEnabled()||manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE){ReminderTrace.log(c,"日程/晨签未发出：通知权限或频道已关闭");return false}
  val open=PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val b=NotificationCompat.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder).setContentTitle(title).setContentText(body).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
  if(morningDate != null) b.addAction(0,"今天合格",intent(c,morningDate,"check-pass")).addAction(0,"不合格",intent(c,morningDate,"check-fail"))
  manager.notify(id,b.build());ReminderTrace.log(c,if(morningDate==null)"日程提醒已提交系统"else"晨签询问已提交系统");return true
 }
}
class ReminderReceiver: BroadcastReceiver() {
 override fun onReceive(context: Context,intent: Intent) {
  if(intent.action==MorningLastCall.ACTION){MorningLastCall.receive(context.applicationContext,intent);return}
  if(intent.action==MorningLastCall.PASS_ACTION){MorningLastCall.markPassed(context.applicationContext,intent);return}
  if(intent.action in setOf("check-pass","check-fail")){
    val store=AppStore.get(context);if(store.loadError!=null)return
    val date=runCatching{LocalDate.parse(intent.getStringExtra("id"))}.getOrNull()?:return
    val now=MorningCalendar.now()
    if(MorningCalendar.editable(date,store.morningStart,store.morningEnd,now,store.morningTime)&&store.checks.none{it.date==date})
     store.check(CheckDay(date,true,intent.action=="check-pass"))
    context.getSystemService(NotificationManager::class.java).cancel(730)
    return
  }
  val app=context.applicationContext
  val delivery=Reminders.dispatch(app);if(delivery.isEmpty())return
  // The notification and the next alarm are already committed before any network work.
  val result=goAsync()
  CoroutineScope(Dispatchers.IO).launch{try{WeatherMorning.refresh(app,delivery)}finally{result.finish()}}
 }
}
class RestoreReceiver: BroadcastReceiver() { override fun onReceive(c: Context,i: Intent) {
 if(!AppConsent.accepted(c))return
 if(i.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED,AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))return
 ReminderTrace.log(c,"恢复提醒 · ${i.action?.substringAfterLast('.')}")
 RetiredFeatures.clean(c);NoticeSubscriptions.schedule(c);ResetRadar.schedule(c)
 runCatching{Reminders.reschedule(c)}.onFailure{ReminderTrace.log(c,"恢复失败：${it.javaClass.simpleName}")}
} }
object Holidays {
 // Source and annual exceptions are displayed in the morning screen.
 val days = mutableMapOf<LocalDate,Boolean>().apply {
  listOf("01-01" to "01-03","02-15" to "02-23","04-04" to "04-06","05-01" to "05-05","06-19" to "06-21","09-25" to "09-27","10-01" to "10-07").forEach { (a,b) -> var d=LocalDate.parse("2026-$a"); val end=LocalDate.parse("2026-$b"); while(d<=end){put(d,false);d=d.plusDays(1)} }
  listOf("01-04","02-14","02-28","05-09","09-20","10-10").forEach { put(LocalDate.parse("2026-$it"),true) }
 }
 fun required(day: LocalDate) = days[day] ?: (day.dayOfWeek.value <= 5)
}


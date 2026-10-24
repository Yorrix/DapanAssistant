package cn.dapan.assistant

import android.app.*
import android.content.*
import android.net.Uri
import androidx.core.app.NotificationCompat
import java.time.*
import java.time.format.DateTimeFormatter

/** An independent, explicitly opted-in alarm-clock alarm. Ordinary reminders never use this channel. */
internal object MorningLastCall {
 const val ACTION="morning-last-call"
 const val PASS_ACTION="morning-last-call-pass"
 const val NOTIFICATION_ID=733
 private fun prefs(c:Context)=c.applicationContext.getSharedPreferences("morning-last-call",0)
 private fun operation(c:Context,ticket:MorningLastCallTicket?=null)=PendingIntent.getBroadcast(c,0,
  Intent(c,ReminderReceiver::class.java).setAction(ACTION).setData(Uri.parse("dapan://morning/last-minute"))
   .putExtra("date",ticket?.date?.toString()).putExtra("due",ticket?.due?:0).putExtra("deadline",ticket?.deadline?:0),
  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 private fun open(c:Context)=PendingIntent.getActivity(c,NOTIFICATION_ID,
  Intent(c,MainActivity::class.java).setAction("open-morning").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("openMorning",true),
  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 private fun finished(c:Context)=prefs(c).getString("finishedDate",null)?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
 fun scheduledAt(c:Context)=prefs(c).getLong("due",0)
 fun issue(c:Context)=prefs(c).getString("issue",null)
 fun cancelNotification(c:Context)=c.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
 fun clear(c:Context){c.getSystemService(AlarmManager::class.java).cancel(operation(c));prefs(c).edit().clear().commit();cancelNotification(c)}

 @Synchronized fun schedule(c:Context,s:AppStore){
  val manager=c.getSystemService(AlarmManager::class.java);val p=prefs(c)
  val next=if(s.loadError==null)MorningLastCallRules.next(s.morningLastCall,s.morningStart,s.morningEnd,s.morningTime,
   Instant.now().atZone(MorningCalendar.zone),s.checks,Holidays.days,p.getLong("due",0),finished(c))else null
  fun unschedule(issue:String?=null){manager.cancel(operation(c));p.edit().remove("due").remove("deadline").remove("date").putString("issue",issue).commit()}
  if(next==null){unschedule();if(!s.morningLastCall||s.checks.any{it.date==MorningCalendar.now().toLocalDate()})cancelNotification(c);return}
  Reminders.channels(c)
  val notifications=c.getSystemService(NotificationManager::class.java)
  if(!notifications.areNotificationsEnabled()||notifications.getNotificationChannel(Reminders.CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE){
   unschedule("请允许通知，并开启“日程与晨签”通知频道");return
  }
  if(!Reminders.precise(c)){unschedule("请允许“闹钟和提醒”，才能开启精确计时");return}
  try{
   // A restored callback may already be seconds late. Keep the original due in
   // the intent and log; the rules still expire it at the statistics deadline.
   val wake=maxOf(next.due,System.currentTimeMillis()+150)
   manager.setAlarmClock(AlarmManager.AlarmClockInfo(wake,open(c)),operation(c,next))
   if(p.getLong("due",0)!=next.due)ReminderTrace.log(c,"已安排晨签最后一分钟 · ${DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(MorningCalendar.zone).format(Instant.ofEpochMilli(next.due))}")
   p.edit().putLong("due",next.due).putLong("deadline",next.deadline).putString("date",next.date.toString()).remove("issue").commit()
  }catch(_:SecurityException){unschedule("精确计时权限未获允许，请在系统设置中开启")}
 }

 @Synchronized fun receive(c:Context,i:Intent){
  val s=AppStore.get(c);val day=i.getStringExtra("date")?.let{runCatching{LocalDate.parse(it)}.getOrNull()}
  val due=i.getLongExtra("due",0);val deadline=i.getLongExtra("deadline",0);val now=System.currentTimeMillis()
  if(day!=null&&s.loadError==null){
   val ticket=MorningLastCallTicket(day,due,deadline)
   if(MorningLastCallRules.canDeliver(ticket,now,s.morningLastCall,s.morningStart,s.morningEnd,s.morningTime,
     MorningCalendar.zone,s.checks,Holidays.days,finished(c))){
    // Persist before posting: duplicate OS callbacks must not interrupt twice.
    prefs(c).edit().putString("finishedDate",day.toString()).commit()
    runCatching{post(c,ticket,now)}.onFailure{ReminderTrace.log(c,"晨签最后一分钟通知未显示：${it.javaClass.simpleName}")}
    ReminderTrace.log(c,"晨签最后一分钟触发 · 延后 ${(now-due).coerceAtLeast(0)/1000} 秒")
   }
  }
  schedule(c,s)
 }

 fun markPassed(c:Context,i:Intent){
  val s=AppStore.get(c);if(s.loadError!=null)return
  val day=i.getStringExtra("date")?.let{runCatching{LocalDate.parse(it)}.getOrNull()}?:return
  if(day==MorningCalendar.now().toLocalDate()&&MorningLastCallRules.required(day,s.morningStart,s.morningEnd,s.checks,Holidays.days))s.check(CheckDay(day,true,true))
  cancelNotification(c)
 }

 private fun post(c:Context,ticket:MorningLastCallTicket,now:Long){
  Reminders.channels(c)
  val manager=c.getSystemService(NotificationManager::class.java)
  if(!manager.areNotificationsEnabled()||manager.getNotificationChannel(Reminders.CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE){ReminderTrace.log(c,"晨签最后一分钟未显示：通知权限或频道已关闭");return}
  val done=PendingIntent.getBroadcast(c,0,Intent(c,ReminderReceiver::class.java).setAction(PASS_ACTION)
   .setData(Uri.parse("dapan://morning/last-minute/${ticket.date}")).putExtra("date",ticket.date.toString()),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val date=ticket.date;val today=Instant.ofEpochMilli(now).atZone(MorningCalendar.zone).toLocalDate()
  val time=Instant.ofEpochMilli(ticket.deadline).atZone(MorningCalendar.zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
  val seconds=((ticket.deadline-now+999)/1000).coerceIn(1,60)
  val builder=NotificationCompat.Builder(c,Reminders.CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
   .setContentTitle(if(seconds>=55)"晨签结束前 1 分钟"else"晨签即将结束")
   .setContentText("${if(date!=today)"明天 "else""}$time 统计，请及时完成学校晨签。")
   .setContentIntent(open(c)).setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
   .setAutoCancel(true).setTimeoutAfter((ticket.deadline-now).coerceAtLeast(1))
  // A 00:00 deadline warns on the previous day; never mark tomorrow through a notification.
  if(date==today)builder.addAction(0,"我已完成晨签",done)
  manager.notify(NOTIFICATION_ID,builder.build())
 }
}

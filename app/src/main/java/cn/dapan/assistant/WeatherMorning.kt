package cn.dapan.assistant

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.time.*

internal data class WeatherDelivery(val due:Long,val date:LocalDate,val time:LocalTime,val notice:WeatherNotice)

object WeatherMorning {
 internal const val CHANNEL="weather-morning"
 private fun prefs(c:Context,notice:WeatherNotice)=c.applicationContext.getSharedPreferences(notice.preference,0)
 fun enabled(c:Context,notice:WeatherNotice=WeatherNotice.MORNING)=prefs(c,notice).getBoolean("enabled",false)
 fun time(c:Context,notice:WeatherNotice=WeatherNotice.MORNING):LocalTime=runCatching{LocalTime.parse(prefs(c,notice).getString("time",notice.defaultTime.toString()))}.getOrDefault(notice.defaultTime)
 fun configure(c:Context,enabled:Boolean,time:LocalTime,notice:WeatherNotice=WeatherNotice.MORNING){
  check(prefs(c,notice).edit().putBoolean("enabled",enabled).putString("time",time.toString()).commit()){"保存失败"}
  schedule(c);if(!enabled)c.getSystemService(NotificationManager::class.java).cancel(notice.notificationId)
 }
 private fun alarmIntent(c:Context)=PendingIntent.getBroadcast(c,731,Intent(c,WeatherMorningReceiver::class.java).setAction("weather"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
 internal fun cancelLegacy(c:Context){c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c))}
 fun schedule(c:Context){WeatherNotice.entries.filterNot{enabled(c,it)}.forEach{ReminderTrace.forget(c,it.key)};Reminders.reschedule(c)}
 @Synchronized internal fun deliver(c:Context,notice:WeatherNotice):WeatherDelivery?{
  if(!enabled(c,notice))return null
  val now=ZonedDateTime.now(WeatherData.zone);val configuredTime=time(c,notice);val due=now.toLocalDate().atTime(configuredTime).atZone(WeatherData.zone).toInstant().toEpochMilli()
  if(System.currentTimeMillis()<due||ReminderTrace.finished(c,notice.key)==due)return null
  val nm=c.getSystemService(NotificationManager::class.java);nm.createNotificationChannel(NotificationChannel(notice.preference,"${notice.label}天气",NotificationManager.IMPORTANCE_DEFAULT))
  if(!nm.areNotificationsEnabled()||nm.getNotificationChannel(notice.preference)?.importance==NotificationManager.IMPORTANCE_NONE){ReminderTrace.log(c,"${notice.label}天气未发出：通知权限或频道已关闭");ReminderTrace.forget(c,notice.key);return null}
  val delivery=WeatherDelivery(due,now.toLocalDate(),configuredTime,notice)
  val body=cachedBriefing(c,delivery)?.let{"$it（缓存预报）"}?:"正在更新${notice.dayLabel}天气；暂时离线时可点开查看。"
  post(c,delivery,body)
  ReminderTrace.finish(c,notice.key,due);ReminderTrace.log(c,"${notice.label}天气通知已提交系统，后台更新预报")
  return delivery
 }
 private fun cachedBriefing(c:Context,delivery:WeatherDelivery):String?{
  val fetched=c.getSharedPreferences("weather-cache",0).getLong("fetched",0)
  return WeatherData.cached(c)?.takeIf{System.currentTimeMillis()-fetched in 0..6*3600000L}?.let{WeatherData.briefing(it,delivery.notice.forecastDate(delivery.date))}
 }
 private fun post(c:Context,delivery:WeatherDelivery,body:String){
  val notice=delivery.notice;val nm=c.getSystemService(NotificationManager::class.java)
  val open=PendingIntent.getActivity(c,notice.notificationId,Intent(c,MainActivity::class.java).putExtra("openWeather",true),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  nm.notify(notice.notificationId,NotificationCompat.Builder(c,notice.preference).setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("盘锦校区 · ${notice.dayLabel}天气").setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setOnlyAlertOnce(true).setAutoCancel(true).build())
 }
 internal fun refresh(c:Context,deliveries:List<WeatherDelivery>){
  if(!AppConsent.accepted(c))return
  // A single network request also handles morning/evening configured at the same time.
  val fresh=runCatching{WeatherData.fetch(c)}.getOrNull()
  deliveries.forEach{delivery->runCatching{
   val notice=delivery.notice;val nm=c.getSystemService(NotificationManager::class.java)
   // Do not resurrect a dismissed notification or overwrite a newly configured day/time.
   if(!enabled(c,notice)||time(c,notice)!=delivery.time||WeatherData.now().toLocalDate()!=delivery.date||ReminderTrace.finished(c,notice.key)!=delivery.due||!nm.areNotificationsEnabled()||nm.activeNotifications.none{it.id==notice.notificationId})return@forEach
   val body=fresh?.let{WeatherData.briefing(it,notice.forecastDate(delivery.date))}
   if(body!=null){post(c,delivery,body);ReminderTrace.log(c,"${notice.label}天气预报已更新")}
   else {
    post(c,delivery,cachedBriefing(c,delivery)?.let{"$it（缓存预报，暂时无法更新）"}?:"暂时无法获取${notice.dayLabel}天气，点开后刷新。")
    ReminderTrace.log(c,"${notice.label}天气网络不可用，已保留通知")
   }
  }.onFailure{ReminderTrace.log(c,"天气更新失败：${it.javaClass.simpleName}；提醒已独立发出")}}
 }
}
// Kept only for PendingIntents created by older installations.
class WeatherMorningReceiver:BroadcastReceiver(){
 override fun onReceive(c:Context,intent:Intent){
  val app=c.applicationContext;val deliveries=Reminders.dispatch(app);if(deliveries.isEmpty())return
  val result=goAsync()
  CoroutineScope(Dispatchers.IO).launch{try{WeatherMorning.refresh(app,deliveries)}finally{result.finish()}}
 }
}

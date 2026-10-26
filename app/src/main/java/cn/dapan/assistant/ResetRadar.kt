package cn.dapan.assistant

import android.app.*
import android.app.job.*
import android.content.*
import android.util.AtomicFile
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import java.security.MessageDigest

internal object RadarRules {
 data class Next(val label:String,val time:String,val text:String,val confirmed:Boolean=false)
 fun instant(value:String):Instant?=runCatching{Instant.parse(value)}.getOrNull()
 fun time(value:String)=instant(value)?.atZone(ZoneId.of("Asia/Shanghai"))?.format(DateTimeFormatter.ofPattern("MM月dd日 HH:mm"))?:"时间未定"
 fun text(o:JSONObject?):String {
  if(o==null)return ""
  val translation=o.optJSONObject("translation")
  val text=if(translation!=null&&!translation.optBoolean("stale"))translation.optString("text").ifBlank{o.optString("text")}else o.optString("text")
  return text.replace(Regex("https?://\\S+"),"").trim()
 }
 fun stale(root:JSONObject,now:Instant=Instant.now()):Boolean = root.optBoolean("stale",true)||
  instant(root.optString("fetched_at"))?.let{Duration.between(it,now).seconds>900}!=false
 fun next(root:JSONObject,now:Instant=Instant.now()):Next {
  val data=root.optJSONObject("data")?:return Next("暂无","暂无","暂未取得数据")
  if(stale(root,now))return Next("待更新","暂无", "数据已过期，请刷新后查看")
  val scheduled=data.optJSONObject("scheduled_reset")
  if(scheduled!=null){
   val at=instant(scheduled.optString("scheduled_for"))
   if(at!=null&&at>now)return Next("确定 · 来源已给定时间",time(at.toString()),text(scheduled),true)
   if(at==null)return Next("预告","时间未定",text(scheduled))
  }
  val watch=data.optJSONObject("active_watch")
  if(watch!=null&&!root.optJSONObject("provenance").let{it?.optBoolean("watch_expired")==true}){
   val expires=instant(watch.optString("expires_at"))
   if(expires==null||expires>now)return Next("预告","时间未定",text(watch))
  }
  return Next("暂无","暂无","暂时没有有效的下一次重置预告")
 }
 fun signatures(root:JSONObject):Set<String> {
  if(stale(root))return emptySet()
  val data=root.optJSONObject("data")?:return emptySet()
  return listOf("latest_reset","scheduled_reset","active_watch").mapNotNull{kind->
   val item=data.optJSONObject(kind)?:return@mapNotNull null
   if(kind=="active_watch"&&(root.optJSONObject("provenance")?.optBoolean("watch_expired")==true||instant(item.optString("expires_at"))?.isBefore(Instant.now())==true))return@mapNotNull null
   val stable=listOf(kind,item.optString("id"),item.optString("text"),item.optString("scheduled_for"),item.optString("reset_type")).joinToString("|")
   MessageDigest.getInstance("SHA-256").digest(stable.toByteArray()).joinToString(""){"%02x".format(it)}
  }.toSet()
 }
 fun fresh(current:Set<String>,seen:Set<String>?)=if(seen==null)emptySet()else current-seen
}

internal object ResetRadar {
 private const val JOB=7420
 private const val CHANNEL="reset-radar"
 private fun prefs(c:Context)=c.getSharedPreferences("reset-radar",0)
 fun enabled(c:Context)=prefs(c).getBoolean("enabled",false)
 fun cached(c:Context)=runCatching{JSONObject(AtomicFile(File(c.filesDir,"reset-radar-cache.json")).openRead().bufferedReader().use{it.readText()})}.getOrDefault(JSONObject())
 suspend fun refresh(c:Context):JSONObject {
  val data=CommunityClient(c).request("/reset-radar")
  require(data.optJSONObject("data")!=null){"服务器暂未取得 tibo 数据，请稍后再试"}
  withContext(Dispatchers.IO){
   val file=AtomicFile(File(c.filesDir,"reset-radar-cache.json"));val out=file.startWrite()
   try{out.write(data.toString().toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}
   consume(c,data)
  }
  return data
 }
 fun set(c:Context,value:Boolean){
  val p=prefs(c);if(value==enabled(c))return
  check(p.edit().putBoolean("enabled",value).remove("seen").commit())
  schedule(c)
 }
 fun schedule(c:Context){
  val jobs=c.getSystemService(JobScheduler::class.java)
  if(!enabled(c)){jobs.cancel(JOB);jobs.cancel(JOB+1);return}
  if(jobs.getPendingJob(JOB)==null)jobs.schedule(JobInfo.Builder(JOB,ComponentName(c,RadarSyncService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build())
  if(!prefs(c).contains("seen"))jobs.schedule(JobInfo.Builder(JOB+1,ComponentName(c,RadarSyncService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).build())
 }
 @Synchronized private fun consume(c:Context,data:JSONObject){
  if(!enabled(c)||RadarRules.stale(data))return
  val p=prefs(c);val current=RadarRules.signatures(data);val seen=p.getStringSet("seen",null)?.toSet()
  if(RadarRules.fresh(current,seen).isNotEmpty()){
   val manager=c.getSystemService(NotificationManager::class.java)
   manager.createNotificationChannel(NotificationChannel(CHANNEL,"tibo 新公告",NotificationManager.IMPORTANCE_DEFAULT))
   if(!manager.areNotificationsEnabled()||manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE)return
   val next=RadarRules.next(data)
   val body=if(next.label=="暂无")"有新的重置记录，点击查看。"else "${next.label} · ${next.time}"
   val open=PendingIntent.getActivity(c,JOB,Intent(c,MainActivity::class.java).putExtra("destination","tibo雷达").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
   manager.notify("radar:new",JOB,NotificationCompat.Builder(c,CHANNEL).setSmallIcon(c.applicationInfo.icon).setContentTitle("tibo 有新动态").setContentText(body).setContentIntent(open).setAutoCancel(true).build())
  }
  check(p.edit().putStringSet("seen",(seen.orEmpty()+current).takeLastSet(200)).commit())
 }
 private fun Set<String>.takeLastSet(n:Int)=toList().takeLast(n).toSet()
}
class RadarSyncService:JobService(){
 private val tasks=mutableMapOf<Int,Job>()
 override fun onStartJob(p:JobParameters):Boolean {
  if(!AppConsent.accepted(applicationContext))return false
  tasks[p.jobId]=CoroutineScope(Dispatchers.Main).launch{
   var retry=false
   try{ResetRadar.refresh(applicationContext)}catch(e:CancellationException){throw e}catch(_:Exception){retry=true}
   tasks.remove(p.jobId);jobFinished(p,retry)
  };return true
 }
 override fun onStopJob(p:JobParameters):Boolean{tasks.remove(p.jobId)?.cancel();return true}
 override fun onDestroy(){tasks.values.forEach{it.cancel()};tasks.clear();super.onDestroy()}
}

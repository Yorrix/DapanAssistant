package cn.dapan.assistant

import android.app.*
import android.app.job.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.URI

internal object NoticeDelta {
 fun host(item:JSONObject)=runCatching{URI(item.optString("url")).host.orEmpty().lowercase()}.getOrDefault("")
 fun ids(items:List<JSONObject>)=items.mapNotNull{it.optString("id").takeIf(String::isNotBlank)}.toSet()
 fun fresh(items:List<JSONObject>,seen:Set<String>?)=
  if(seen==null)emptyList()else items.distinctBy{it.optString("id")}.filter{it.optString("id").isNotBlank()&&it.optString("id") !in seen}
}

/** Independent of list filtering. The first successful fetch establishes a baseline. */
internal object NoticeSubscriptions {
 private const val JOB=7410
 private const val INITIAL_JOB=7411
 private const val CHANNEL="followed-notices"
 private fun prefs(c:Context)=c.getSharedPreferences("notice-subscriptions",0)
 fun followed(c:Context)=prefs(c).getStringSet("sources",emptySet()).orEmpty().toSet()
 @Synchronized fun set(c:Context,source:String,enabled:Boolean){
  val p=prefs(c);val old=followed(c)
  if((source in old)==enabled)return
  check(p.edit().putStringSet("sources",if(enabled)old+source else old-source).remove("seen:$source").commit()){"关注设置保存失败"}
  schedule(c)
  if(enabled)c.getSystemService(JobScheduler::class.java).schedule(
   JobInfo.Builder(INITIAL_JOB,ComponentName(c,NoticeSyncService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setMinimumLatency(0).build())
 }
 fun schedule(c:Context){
  val jobs=c.getSystemService(JobScheduler::class.java)
  if(followed(c).isEmpty()){jobs.cancel(JOB);jobs.cancel(INITIAL_JOB);return}
  if(jobs.getPendingJob(JOB)==null)jobs.schedule(JobInfo.Builder(JOB,ComponentName(c,NoticeSyncService::class.java))
   .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build())
 }
 @Synchronized fun consume(c:Context,data:JSONObject){
  require(data.optJSONArray("items")!=null&&data.optJSONArray("sources")!=null){"通知响应不完整"}
  val manager=c.getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel(CHANNEL,"关注来源的新通知",NotificationManager.IMPORTANCE_DEFAULT))
  val p=prefs(c)
  followed(c).forEach{source->
   if(data.optJSONArray("sources").objects().none{NoticeDelta.host(it)==source})return@forEach
   val items=data.optJSONArray("items").objects().filter{NoticeDelta.host(it)==source}
   val seen=p.getStringSet("seen:$source",null)?.toSet()
   val fresh=NoticeDelta.fresh(items,seen)
   if(fresh.isNotEmpty()){
    // Do not acknowledge unseen notices if delivery is disabled.
    if(!NotificationManagerCompat.from(c).areNotificationsEnabled()||manager.getNotificationChannel(CHANNEL)?.importance==NotificationManager.IMPORTANCE_NONE)return@forEach
    val name=data.optJSONArray("sources").objects().firstOrNull{NoticeDelta.host(it)==source}?.optString("name")?:source
    val text=fresh.take(4).joinToString("\n"){it.optString("headline").ifBlank{it.optString("title")}}
    val intent=Intent(c,MainActivity::class.java).putExtra("openNotices",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    val tap=PendingIntent.getActivity(c,source.hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification=NotificationCompat.Builder(c,CHANNEL).setSmallIcon(c.applicationInfo.icon)
     .setContentTitle("$name · ${fresh.size} 条新通知").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
     .setContentIntent(tap).setAutoCancel(true).setOnlyAlertOnce(true).build()
    // Stable source ID also prevents duplicate trays when retrying after a process death.
    manager.notify("notice:$source",source.hashCode(),notification)
   }
   check(p.edit().putStringSet("seen:$source",seen.orEmpty()+NoticeDelta.ids(items)).commit()){"通知进度保存失败"}
  }
 }
}

class NoticeSyncService:JobService(){
 private val tasks=mutableMapOf<Int,Job>()
 override fun onStartJob(params:JobParameters):Boolean{
  tasks[params.jobId]=CoroutineScope(Dispatchers.Main).launch{
   var retry=false
   try{
    val data=CommunityClient(applicationContext).request("/notices")
    withContext(Dispatchers.IO){NoticeSubscriptions.consume(applicationContext,data)}
   }catch(e:CancellationException){throw e}
   catch(_:Exception){retry=true}
   finally{tasks.remove(params.jobId)}
   jobFinished(params,retry)
  }
  return true
 }
 override fun onStopJob(params:JobParameters):Boolean{tasks.remove(params.jobId)?.cancel();return true}
 override fun onDestroy(){tasks.values.toList().forEach{it.cancel()};tasks.clear();super.onDestroy()}
}

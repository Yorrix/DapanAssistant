package cn.dapan.assistant

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

object MeetingSummaryState {
 var session by mutableStateOf<String?>(null)
 var progress by mutableStateOf("")
 var message by mutableStateOf<String?>(null)
}
class MeetingSummaryService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private var job:Job?=null
 private var active:String?=null
 override fun onBind(intent:Intent?)=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{
  if(intent?.action=="cancel"){MeetingSummaryState.message="摘要已取消，原有摘要保留";job?.cancel();stopSelf();return START_NOT_STICKY}
  if(active!=null)return START_NOT_STICKY
  val id=intent?.getStringExtra("session")?:run{stopSelf();return START_NOT_STICKY}
  val channel="meeting-summary";getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"摘要生成",NotificationManager.IMPORTANCE_LOW))
  val cancel=PendingIntent.getService(this,812,Intent(this,MeetingSummaryService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE)
  fun notification(text:String)=NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("正在生成录音摘要").setContentText(text).setOngoing(true).addAction(0,"取消",cancel).build()
  if(Build.VERSION.SDK_INT>=29)startForeground(812,notification("正在整理转写"),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else startForeground(812,notification("正在整理转写"))
  active=id;MeetingSummaryState.session=id;MeetingSummaryState.progress="正在整理转写…";MeetingSummaryState.message=null
  job=scope.launch{
   try{
    val library=MeetingLibrary(this@MeetingSummaryService);val all=withContext(Dispatchers.IO){Recordings.clips(this@MeetingSummaryService)}
    val folder=library.folders(all).firstOrNull{it.id==id}?:error("录音记录已不存在")
    val clips=library.order(id,all);require(clips.any{it.lines.isNotEmpty()}){"还没有可总结的转写"}
    require(clips.none{it.id==AudioState.clipId||it.id==TranscriptionState.runningId}){"请先结束录音或转写"}
    val secrets=Secrets(this@MeetingSummaryService);SharedAi.requireTextAvailable(this@MeetingSummaryService)
    val hint=RecordingPreferences(this@MeetingSummaryService).load().summaryHint
    val signature=meetingSignature(clips,folder.kind,hint)
    val summary=AiClient.summarize(secrets.get("deepseek"),secrets.model,meetingSource(clips),folder.kind,hint){text->MeetingSummaryState.progress=text;getSystemService(NotificationManager::class.java).notify(812,notification(text))}
    ensureActive();library.saveSummary(folder,summary,signature,clips.map{it.id});MeetingSummaryState.message="${folder.title} · 摘要已保存"
   }catch(e:CancellationException){throw e}catch(e:Exception){MeetingSummaryState.message=e.message?:"摘要生成失败，原有摘要保留"}finally{stopSelf()}
  }
  return START_NOT_STICKY
 }
 override fun onTimeout(startId:Int,fgsType:Int){MeetingSummaryState.message="系统已停止后台摘要任务，原有摘要保留";job?.cancel();stopSelf()}
 override fun onDestroy(){scope.cancel();if(MeetingSummaryState.session==active)MeetingSummaryState.session=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}

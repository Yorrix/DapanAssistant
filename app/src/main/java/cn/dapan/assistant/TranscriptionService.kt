package cn.dapan.assistant

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import androidx.compose.runtime.*
import androidx.core.app.NotificationCompat

object TranscriptionState {
 var runningId by mutableStateOf<String?>(null)
 var progress by mutableFloatStateOf(0f)
 var message by mutableStateOf<String?>(null)
}

class TranscriptionService:Service() {
 private val handler=Handler(Looper.getMainLooper())
 private var replay:AsrReplay?=null
 private var worker:Thread?=null
 private var activeId:String?=null
 @Volatile private var cancelled=false
 override fun onBind(intent:Intent?)=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="cancel"){cancelled=true;TranscriptionState.message="转写已取消，原文字和音频未改变";replay?.cancel();worker?.interrupt();stopSelf();return START_NOT_STICKY}
  if(TranscriptionState.runningId!=null)return START_NOT_STICKY
  val id=intent?.getStringExtra("clip")
  val clip=Recordings.clips(this).find{it.id==id}
  if(clip==null||AudioState.clipId==id){TranscriptionState.message="该录音不存在或仍在录制";stopSelf();return START_NOT_STICKY}
  val channel="dapan-transcription"
  getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"录音补转写",NotificationManager.IMPORTANCE_LOW))
  val cancel=PendingIntent.getService(this,811,Intent(this,TranscriptionService::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE)
  fun notification(percent:Int)=NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.ic_popup_sync).setContentTitle("转写 · ${runCatching{MeetingLibrary(this).title(clip.session)}.getOrDefault(clip.session)}")
   .setContentText("$percent% · 可取消，原录音保留").setProgress(100,percent,false).setOngoing(true).addAction(0,"取消",cancel).build()
  if(Build.VERSION.SDK_INT>=29)startForeground(811,notification(0),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)else startForeground(811,notification(0))
  activeId=clip.id;TranscriptionState.runningId=clip.id;TranscriptionState.progress=0f;TranscriptionState.message=null
  val key=Secrets(this).get("aliyun")
  val options=RecordingPreferences(this).load()
  val engine=AsrReplay(AsrHotwordPreferences(this).active(),applicationContext);replay=engine
  worker=Thread({
   try {
    val lines=engine.transcribe(clip.audio,key,options){fraction->handler.post{if(!cancelled){TranscriptionState.progress=fraction;getSystemService(NotificationManager::class.java).notify(811,notification((fraction*100).toInt()))}}}
    handler.post{if(!cancelled){try{Recordings.replaceLines(this,clip.id,lines);TranscriptionState.message="转写完成，共 ${lines.size} 段；旧文字已保留一份恢复副本"}catch(e:Exception){TranscriptionState.message="保存失败：${e.message}"};stopSelf()}}
   }catch(e:Exception){handler.post{if(!cancelled&&TranscriptionState.runningId==clip.id){TranscriptionState.message=e.message?:"转写未完成，原文字和音频未改变";stopSelf()}}}
  },"dapan-asr-replay").apply{start()}
  return START_NOT_STICKY
 }
 override fun onTimeout(startId:Int,fgsType:Int){cancelled=true;TranscriptionState.message="系统已结束长时间后台转写，原录音保留";replay?.cancel();worker?.interrupt();stopSelf()}
 override fun onDestroy(){cancelled=true;replay?.cancel();worker?.interrupt();if(TranscriptionState.runningId==activeId)TranscriptionState.runningId=null;stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy()}
}

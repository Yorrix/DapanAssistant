package cn.dapan.assistant

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.*
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.*
import java.io.*
import java.util.UUID
import java.util.concurrent.TimeUnit

object AudioState {
 var running by mutableStateOf(false)
 var ready by mutableStateOf(false)
 var connectionReady by mutableStateOf(false)
 var partial by mutableStateOf("")
 var text by mutableStateOf("")
 var error by mutableStateOf<String?>(null)
 var limitReached by mutableStateOf(false)
 var session by mutableStateOf("")
 var revision by mutableIntStateOf(0)
 var clipId by mutableStateOf<String?>(null)
 var finalLines by mutableStateOf(emptyList<TranscriptLine>())
}
data class TranscriptLine(val text:String,val start:Long,val end:Long)
data class Clip(val id:String,val session:String,val name:String,val created:Long,val lines:List<TranscriptLine>,val audio:File)
object Recordings {
 fun directory(c:Context)=File(c.filesDir,"recordings").apply{mkdirs()}
 fun clips(c:Context):List<Clip> = directory(c).listFiles()?.filter{it.extension=="json"}?.mapNotNull{f->runCatching{val o=JSONObject(f.readText());Clip(o.getString("id"),o.getString("session"),o.getString("name"),o.getLong("created"),o.optJSONArray("lines").objects().map{TranscriptLine(it.getString("text"),it.getLong("start"),it.getLong("end"))},File(f.parentFile,o.getString("id")+".wav"))}.getOrNull()}?.sortedBy{it.created} ?: emptyList()
 private fun metadata(c:Context,id:String):File {require(runCatching{UUID.fromString(id)}.isSuccess){"录音标识无效"};return File(directory(c),"$id.json")}
 @Synchronized fun replaceLines(c:Context,id:String,lines:List<TranscriptLine>) {
  require(AudioState.clipId!=id){"请先结束录音"}
  require(lines.all{it.start>=0&&it.end>=it.start}){"转写时间戳无效"}
  val file=metadata(c,id);val original=file.readText();val o=JSONObject(original)
  File(directory(c),"$id.previous").writeText(original)
  o.put("lines",JSONArray(lines.map{JSONObject().put("text",it.text).put("start",it.start).put("end",it.end)}))
  write(file,o);AudioState.revision++
 }
 fun restorePrevious(c:Context,id:String) {
  require(AudioState.clipId!=id&&TranscriptionState.runningId!=id){"请先结束录音或转写"}
  val previous=File(directory(c),"$id.previous");require(previous.isFile){"没有可恢复的旧转写"}
  val o=JSONObject(previous.readText());val current=metadata(c,id).readText();write(metadata(c,id),o);previous.writeText(current);AudioState.revision++
 }
 fun rename(c:Context,clip:Clip,name:String){require(name.isNotBlank());require(AudioState.clipId!=clip.id&&TranscriptionState.runningId!=clip.id){"请先结束录音或转写"};val file=metadata(c,clip.id);val o=JSONObject(file.readText()).put("name",name.trim());write(file,o);AudioState.revision++}
 fun delete(c:Context,clip:Clip){require(AudioState.clipId!=clip.id&&TranscriptionState.runningId!=clip.id){"请先结束录音或转写"};if(clip.audio.exists())check(clip.audio.delete()){"音频删除失败"};check(metadata(c,clip.id).delete()){"录音信息删除失败"};File(directory(c),"${clip.id}.previous").delete();AudioState.revision++}
 private fun write(file:File,o:JSONObject){val atomic=android.util.AtomicFile(file);val out=atomic.startWrite();try{out.write(o.toString().toByteArray());atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}}
}
class RecordingService:Service() {
 private val main=Handler(Looper.getMainLooper());private var socket:WebSocket?=null;private var recorder:AudioRecord?=null;@Volatile private var capturing=false;private var worker:Thread?=null;private var finished=false;@Volatile private var destroyed=false
 private val task=UUID.randomUUID().toString();private val clipId=UUID.randomUUID().toString();private val created=System.currentTimeMillis();private var session="";private var voice=false;private val lines=linkedMapOf<Int,TranscriptLine>();private var textFile:File?=null
 @Volatile private var remoteReady=false;@Volatile private var remoteFailed=false;private val pending=java.util.concurrent.ConcurrentLinkedQueue<ByteArray>();private var pendingBytes=0
 private val client=OkHttpClient.Builder().readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).build()
 private var finishSent=false
 private var sharedVoice=false
 @Volatile private var remoteTerminal=false
 override fun onBind(intent:Intent?)=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="cancel-voice") {
   if(voice&&intent.getStringExtra("session")==session){
    destroyed=true;remoteFailed=true;pending.clear();AudioState.text="";AudioState.partial="";AudioState.finalLines=emptyList();AudioState.error=null
    socket?.cancel();finishCapture();stopSelf()
   }
   return START_NOT_STICKY
  }
  if(intent?.action=="stop") {if(intent.getStringExtra("session").let{it==null||it==session})finishCapture();return START_NOT_STICKY}
  if(AudioState.running)return START_NOT_STICKY
  if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY}
  val key=Secrets(this).get("aliyun")
  voice=intent?.getBooleanExtra("voice",false)==true;session=intent?.getStringExtra("session") ?: "未命名会议"
  sharedVoice=voice&&SharedSpeech.selected(this)
  val channel="dapan-recording";getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"录音中",NotificationManager.IMPORTANCE_LOW))
  val stop=PendingIntent.getService(this,0,Intent(this,RecordingService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
  startForeground(810,NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle(if(voice)"大盘正在听" else "正在录音 · ${runCatching{MeetingLibrary(this).title(session)}.getOrDefault(session)}").setContentText(if(sharedVoice)"公用语音 · 单次最多 60 秒"else if(key.isBlank())"本地录音中，结束后可补转写"else"音频发送至阿里云进行实时转写").setOngoing(true).addAction(0,"结束",stop).build())
  AudioState.running=true;AudioState.ready=false;AudioState.connectionReady=false;AudioState.partial="";AudioState.text="";AudioState.finalLines=emptyList();AudioState.error=null;AudioState.limitReached=false;AudioState.session=session;AudioState.clipId=if(voice)null else clipId
  if(!voice){textFile=File(Recordings.directory(this),"$clipId.json");saveMetadata()}
  startCapture()
  if(!capturing)return START_NOT_STICKY
  if(key.isBlank()&&!sharedVoice){remoteFailed=true;AudioState.error="未配置语音密钥，当前仅保存本地录音。";return START_NOT_STICKY}
  val recordingOptions=RecordingPreferences(this).load()
  val recognitionLanguage=intent?.getStringExtra("language")?:if(!voice)TranslationLanguages.code(recordingOptions.language)else"zh"
  val recognitionContext=intent?.getStringExtra("asr_context")?:if(!voice)recordingOptions.asrContext else""
  val recognitionHotwords=AsrHotwordPreferences(this).active()
  val request=try{if(sharedVoice)SharedSpeech.request(this)else Request.Builder().url("wss://dashscope.aliyuncs.com/api-ws/v1/inference").header("Authorization","Bearer $key").build()}catch(e:Exception){remoteFailed=true;AudioState.error=SharedSpeech.unavailableMessage(this);finishCapture();return START_NOT_STICKY}
  socket=client.newWebSocket(request,object:WebSocketListener(){
   override fun onOpen(ws:WebSocket,response:Response){
    val parameters=JSONObject().put("format","pcm").put("sample_rate",16000).put("language_hints",JSONArray().put(recognitionLanguage.takeIf{code->TranslationLanguages.values.any{it.code==code}}?:"zh")).put("heartbeat",true)
    if(intent?.getBooleanExtra("translation",false)==true)parameters.put("max_sentence_silence",800).put("multi_threshold_mode_enabled",true)
    val input=AsrHints.input(recognitionContext,recognitionHotwords)
    ws.send(JSONObject().put("header",JSONObject().put("action","run-task").put("task_id",task).put("streaming","duplex")).put("payload",JSONObject().put("task_group","audio").put("task","asr").put("function","recognition").put("model","fun-asr-realtime").put("parameters",parameters).put("input",input)).toString())
   }
   override fun onMessage(ws:WebSocket,text:String){if(destroyed)return;runCatching{val o=JSONObject(text);when(o.getJSONObject("header").optString("event")){
    "task-started"->{remoteReady=true;main.post{if(destroyed)return@post;AudioState.connectionReady=true;if(finished){flushPending();sendFinish()}}}
    "result-generated"->{val sentence=o.optJSONObject("payload")?.optJSONObject("output")?.optJSONObject("sentence") ?: return;val value=sentence.optString("text");if(value.isBlank())return;main.post{if(destroyed)return@post;val id=sentence.optInt("sentence_id");if(sentence.optBoolean("sentence_end")){AudioState.partial="";lines[id]=TranscriptLine(value,sentence.optLong("begin_time"),sentence.optLong("end_time"));saveMetadata();AudioState.finalLines=lines.values.toList();AudioState.text=lines.values.joinToString(""){it.text}}else {AudioState.partial=value;AudioState.text=lines.values.joinToString(""){it.text}+value}}}
    "task-finished"->{remoteTerminal=true;main.post{stopSelf()}}
    "task-failed"->{remoteTerminal=true;main.post{if(destroyed)return@post;remoteFailed=true;AudioState.error=if(sharedVoice)SharedSpeech.error(this@RecordingService,o.getJSONObject("header"))else if(voice)"语音识别失败，请检查百炼权限、余额与地域"else"转写失败，请检查百炼权限、余额与地域。录音继续保存在本机。";if(voice)finishCapture()}}
   }}}
   private fun sharedClosed(){if(!sharedVoice||remoteTerminal)return;main.post{if(destroyed||remoteTerminal)return@post;remoteTerminal=true;remoteFailed=true;AudioState.error="公用语音连接已中断，本次没有发送，请重试";finishCapture()}}
   override fun onClosing(ws:WebSocket,code:Int,reason:String){if(sharedVoice){sharedClosed();ws.close(code,null)}}
   override fun onClosed(ws:WebSocket,code:Int,reason:String){sharedClosed()}
   override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){main.post{if(destroyed||(sharedVoice&&remoteTerminal))return@post;remoteFailed=true;AudioState.error=if(voice)"语音识别连接中断，请重试"else"实时转写中断，录音仍在继续。结束后可回听本地文件。";if(voice)finishCapture()}}
  })
  main.postDelayed({if(AudioState.running && !remoteReady){remoteFailed=true;AudioState.error=if(voice)"语音识别连接超时，请检查网络后重试"else"实时转写连接超时，录音仍在继续。";if(voice)finishCapture()}},15000)
  return START_NOT_STICKY
 }
 @Suppress("MissingPermission") private fun startCapture(){
  if(capturing||finished)return
  try {val size=maxOf(AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*2,6400);recorder=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size);check(recorder?.state==AudioRecord.STATE_INITIALIZED);recorder!!.startRecording();capturing=true;AudioState.ready=true
   worker=Thread{var wav:RandomAccessFile?=null;var total=0L
    try {if(!voice)wav=RandomAccessFile(File(Recordings.directory(this),"$clipId.wav"),"rw");wav?.write(ByteArray(44));val buffer=ByteArray(3200);var checkpoint=0L;while(capturing){val read=recorder?.read(buffer,0,buffer.size) ?: -1;val count=if(sharedVoice&&read>0)minOf(read,(SharedSpeech.MAX_BYTES-total).toInt())else read;if(count>0){wav?.write(buffer,0,count);total+=count
     if(total-checkpoint>32000){wav?.let{writeHeader(it,total);it.seek(total+44)};checkpoint=total}
     if(!remoteFailed){if(!remoteReady){if(pendingBytes<480000){pending.add(buffer.copyOf(count));pendingBytes+=count}}else{flushPending();pendingBytes=0;if((socket?.queueSize()?:0)<480000)socket?.send(buffer.toByteString(0,count))else{remoteFailed=true;main.post{if(!destroyed){AudioState.error=if(voice)"网络积压，已结束收听，请稍后重试"else"网络积压，已停止转写，录音继续保存在本机。";if(voice)finishCapture()}}}}}
     if(sharedVoice&&total>=SharedSpeech.MAX_BYTES){main.post{if(!destroyed){AudioState.limitReached=true;finishCapture()}};break}
    }else if(count<0)break}}
    catch(e:Exception){main.post{AudioState.error="录音写入中断，请检查剩余空间。此前音频已尽量保留。"}}
    finally{runCatching{wav?.let{writeHeader(it,total);it.close()}};main.post{if(remoteReady&&!remoteFailed){flushPending();sendFinish()}else if(voice&&!remoteFailed)main.postDelayed({if(!remoteReady){AudioState.error="语音服务未连接成功，请检查网络后重试";stopSelf()}},15000)else stopSelf()}}
   }.apply{start()}
  }catch(e:Exception){AudioState.error="无法打开麦克风，请检查权限或其他录音应用";finishCapture()}
 }
 private fun saveMetadata(){val o=JSONObject().put("id",clipId).put("session",session).put("name","录音 ${java.time.Instant.ofEpochMilli(created).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)}").put("created",created).put("lines",JSONArray(lines.values.map{JSONObject().put("text",it.text).put("start",it.start).put("end",it.end)}));textFile?.let{val atomic=android.util.AtomicFile(it);val out=atomic.startWrite();try{out.write(o.toString().toByteArray());atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out)}};AudioState.revision++}
 private fun finishCapture(){if(finished)return;finished=true;capturing=false;AudioState.ready=false;runCatching{recorder?.stop()};if(worker==null)stopSelf()}
 private fun flushPending(){while(true){val bytes=pending.poll()?:break;socket?.send(bytes.toByteString())}}
 private fun sendFinish(){if(finishSent||destroyed)return;finishSent=true;socket?.send(JSONObject().put("header",JSONObject().put("action","finish-task").put("task_id",task).put("streaming","duplex")).put("payload",JSONObject().put("input",JSONObject())).toString());main.postDelayed({if(voice&&!destroyed)AudioState.error="识别收尾超时，本次未发送，请重试";stopSelf()},if(sharedVoice)15000 else 6000)}
 override fun onDestroy(){destroyed=true;capturing=false;runCatching{recorder?.stop()};runCatching{worker?.join(1000)};recorder?.release();socket?.cancel();main.removeCallbacksAndMessages(null);client.dispatcher.executorService.shutdown();AudioState.running=false;AudioState.ready=false;AudioState.connectionReady=false;AudioState.partial="";AudioState.clipId=null;AudioState.revision++;super.onDestroy()}
 private fun writeHeader(f:RandomAccessFile,bytes:Long){f.seek(0);fun i(n:Long){f.write(byteArrayOf(n.toByte(),(n shr 8).toByte(),(n shr 16).toByte(),(n shr 24).toByte()))};fun s(n:Int){f.write(byteArrayOf(n.toByte(),(n shr 8).toByte()))};f.writeBytes("RIFF");i(bytes+36);f.writeBytes("WAVEfmt ");i(16);s(1);s(1);i(16000);i(32000);s(2);s(16);f.writeBytes("data");i(bytes)}
}

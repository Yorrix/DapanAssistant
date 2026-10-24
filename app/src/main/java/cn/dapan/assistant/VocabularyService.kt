package cn.dapan.assistant

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.media.*
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.*
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A foreground microphone session. Audio and sentence text are not written to disk. */
class VocabularyService:Service() {
 private val main=Handler(Looper.getMainLooper())
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private val sentences=Channel<String>(64)
 private val gate=VocabularySentenceGate()
 private val client=OkHttpClient.Builder().readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).build()
 private var socket:WebSocket?=null
 private data class AudioConnection(val epoch:Int,val socket:WebSocket)
 @Volatile private var audioConnection:AudioConnection?=null
 private var recorder:AudioRecord?=null
 private var mic:Thread?=null
 private var wake:PowerManager.WakeLock?=null
 private var consumer:Job?=null
 @Volatile private var capturing=false
 @Volatile private var connected=false
 @Volatile private var stopping=false
 @Volatile private var destroyed=false
 private var started=false
 @Volatile private var generation=0
 private var attempts=0
 private var task=""
 private var key=""
 private var hints=""
 private var hotwords=emptyList<String>()
 override fun onBind(intent:Intent?)=null
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="stop"){stopListening();return START_NOT_STICKY}
  if(started)return START_NOT_STICKY
  if(AudioState.running||checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopSelf();return START_NOT_STICKY}
  if(!VocabularyDictionary.installed(this)){VocabularyState.error=VocabularyDictionary.MISSING;VocabularyState.status="尚未安装离线词典";stopSelf();return START_NOT_STICKY}
  key=Secrets(this).get("aliyun")
  if(key.isBlank()){VocabularyState.error="请先配置阿里云百炼 API Key";stopSelf();return START_NOT_STICKY}
  started=true
  hints=VocabularyPreferences(this).context
  hotwords=AsrHotwordPreferences(this).active()
  val manager=getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel("classroom-vocabulary","课堂生词收听",NotificationManager.IMPORTANCE_LOW))
  val open=PendingIntent.getActivity(this,812,Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE)
  val stop=PendingIntent.getService(this,812,Intent(this,VocabularyService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
  startForeground(812,NotificationCompat.Builder(this,"classroom-vocabulary").setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("课堂生词 · 正在收听")
   .setContentText("音频交给阿里云识别，仅在本机保留生词").setContentIntent(open).setOngoing(true).addAction(0,"结束收听",stop).build())
  AudioState.running=true;AudioState.session=VocabularyState.OWNER;AudioState.ready=false;AudioState.connectionReady=false
  AudioState.text="";AudioState.partial="";AudioState.finalLines=emptyList();AudioState.clipId=null;AudioState.error=null
  VocabularyState.running=true;VocabularyState.status="正在准备词库…";VocabularyState.error=null
  wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"dapan:classroom-vocabulary").apply{acquire()}
  scope.launch {
   try {
    val dictionary=VocabularyDictionary.open(this@VocabularyService)
    consumer=scope.launch {
     VocabularyStore(this@VocabularyService).use{store->
      for(sentence in sentences){
       val hits=VocabularyRules.collect(sentence,VocabularyPreferences(this@VocabularyService).level,store.known(),dictionary::lookup)
       if(hits.isNotEmpty()){store.add(hits);withContext(Dispatchers.Main){VocabularyState.revision++}}
      }
     }
    }.also{job->job.invokeOnCompletion{cause->if(cause!=null&&cause !is CancellationException)main.post{fail("生词保存失败，已停止收听，请检查存储空间")}}}
    withContext(Dispatchers.Main){if(!stopping&&!destroyed){startMic();if(capturing)connect()}}
   }catch(e:CancellationException){throw e}catch(e:Exception){withContext(Dispatchers.Main){fail(e.message?:"离线词典未能打开，请在课堂生词设置中重新下载")}}
  }
  return START_NOT_STICKY
 }
 @Suppress("MissingPermission") private fun startMic(){
  try {
   val size=maxOf(6400,AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)*2)
   recorder=AudioRecord(MediaRecorder.AudioSource.MIC,16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,size)
   check(recorder?.state==AudioRecord.STATE_INITIALIZED)
   recorder!!.startRecording();capturing=true;AudioState.ready=true
   mic=Thread({
    try {
     val data=ByteArray(3200)
     while(capturing){
      val count=recorder?.read(data,0,data.size)?:-1
      if(count<0){if(capturing)main.post{fail("麦克风已中断，请重新开始收听")};break}
      if(count>0){
       // Capture one immutable connection: a reconnect must never pair a new epoch
       // with the old socket, nor let the mic thread disable a newer connection.
       val connection=audioConnection
       if(connection!=null){
        if(connection.socket.queueSize()>96000)main.post{retry(connection.epoch,"网络较慢")}
        else if(!connection.socket.send(data.toByteString(0,count)))main.post{retry(connection.epoch,"连接中断")}
       }
      }
     }
    }catch(e:Exception){if(capturing)main.post{fail("麦克风不可用，请检查其他录音应用")}}
   },"classroom-vocabulary-audio").apply{start()}
  }catch(e:Exception){fail("无法打开麦克风，请检查录音权限或其他录音应用")}
 }
 private fun connect(){
  if(stopping||destroyed)return
  val epoch=++generation;task=UUID.randomUUID().toString();val taskId=task
  audioConnection=null;connected=false;AudioState.connectionReady=false
  VocabularyState.status=if(attempts==0)"正在连接语音服务…"else"正在重新连接 · 中断期间未识别"
  socket=client.newWebSocket(Request.Builder().url("wss://dashscope.aliyuncs.com/api-ws/v1/inference").header("Authorization","Bearer $key").build(),object:WebSocketListener(){
   override fun onOpen(ws:WebSocket,response:Response){
    // Omit language_hints for mixed Chinese/English automatic recognition.
    val parameters=JSONObject().put("format","pcm").put("sample_rate",16000).put("heartbeat",true).put("max_sentence_silence",800).put("multi_threshold_mode_enabled",true)
    ws.send(JSONObject().put("header",JSONObject().put("action","run-task").put("task_id",taskId).put("streaming","duplex")).put("payload",JSONObject().put("task_group","audio").put("task","asr").put("function","recognition").put("model","fun-asr-realtime").put("parameters",parameters).put("input",AsrHints.input(hints,hotwords))).toString())
   }
   override fun onMessage(ws:WebSocket,text:String){
    val data=runCatching{JSONObject(text)}.getOrNull()?:return
    main.post{
     if(destroyed||epoch!=generation)return@post
     when(data.optJSONObject("header")?.optString("event")){
      "task-started"->{if(stopping)return@post;connected=true;audioConnection=AudioConnection(epoch,ws);AudioState.connectionReady=true;VocabularyState.status=if(attempts>0)"已恢复收听 · 中断期间未识别"else"正在听 · 仅显示生词";main.postDelayed({if(epoch==generation&&connected)attempts=0},30000)}
      "result-generated"->{
       val s=data.optJSONObject("payload")?.optJSONObject("output")?.optJSONObject("sentence")?:return@post
       if(s.optBoolean("sentence_end")&&gate.accept(epoch,s.optInt("sentence_id"),s.optLong("begin_time"))){
        if(sentences.trySend(s.optString("text").take(16000)).isFailure&&!stopping)fail("生词处理跟不上收听，已暂停，请重新开始")
       }
      }
      "task-failed"->{
       val header=data.optJSONObject("header")
       if(stopping)finishSaving()
       else if(VocabularyAsrFailure.retryable(header?.optString("error_code").orEmpty(),header?.optString("error_message").orEmpty()))retry(epoch,"语音服务暂时中断")
       else fail("语音服务拒绝了本次收听，请检查百炼密钥、额度和地域后重试")
      }
      "task-finished"->{if(stopping)finishSaving()else retry(epoch,"语音会话结束")}
     }
    }
   }
   override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){main.post{if(epoch!=generation||destroyed||stopping)return@post;if(response?.code in listOf(400,401,403))fail("语音服务鉴权失败，请检查百炼密钥和地域")else retry(epoch,"网络中断")}}
   override fun onClosed(ws:WebSocket,code:Int,reason:String){main.post{retry(epoch,"语音连接关闭")}}
  })
  main.postDelayed({if(epoch==generation&&!connected&&!stopping)retry(epoch,"连接超时")},15000)
 }
 private fun retry(epoch:Int,reason:String){
  if(destroyed||stopping||epoch!=generation)return
  generation++;audioConnection=null;connected=false;AudioState.connectionReady=false;socket?.cancel()
  if(attempts>=3){fail("连接未恢复，已停止收听。请检查网络后重新开始");return}
  attempts++;VocabularyState.status="$reason · 重连 $attempts/3 · 中断期间未识别"
  main.postDelayed({connect()},1000L shl (attempts-1))
 }
 private fun fail(message:String){if(destroyed)return;VocabularyState.error=message;stopListening()}
 private fun stopListening(){
  if(stopping)return
  stopping=true;capturing=false;audioConnection=null;AudioState.ready=false;VocabularyState.status="正在结束收听…"
  runCatching{recorder?.stop()}
  if(connected){socket?.send(JSONObject().put("header",JSONObject().put("action","finish-task").put("task_id",task).put("streaming","duplex")).put("payload",JSONObject().put("input",JSONObject())).toString());main.postDelayed({finishSaving()},6000)}else finishSaving()
 }
 private var saving=false
 private fun finishSaving(){
  if(saving)return;saving=true;audioConnection=null;connected=false;generation++;socket?.cancel();sentences.close()
  scope.launch{consumer?.join();withContext(Dispatchers.Main){stopSelf()}}
 }
 override fun onDestroy(){
  destroyed=true;capturing=false;audioConnection=null;connected=false;runCatching{recorder?.stop()};runCatching{mic?.join(400)};recorder?.release();socket?.cancel();sentences.close();scope.cancel();main.removeCallbacksAndMessages(null)
  if(wake?.isHeld==true)wake?.release();client.dispatcher.executorService.shutdown()
  if(started){VocabularyState.running=false;VocabularyState.status=if(VocabularyState.error==null)"已暂停 · 生词保存在本机"else"收听已停止";VocabularyState.revision++}
  if(started&&AudioState.session==VocabularyState.OWNER){AudioState.running=false;AudioState.ready=false;AudioState.connectionReady=false;AudioState.revision++}
  super.onDestroy()
 }
}

package cn.dapan.assistant

import android.content.Context
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.*
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ReplayCancelled:Exception("转写已取消，原文字和音频未改变")
class AsrReplay(private val hotwords:List<String> = emptyList(),private val context:Context?=null) {
 @Volatile private var cancelled=false
 @Volatile private var socket:WebSocket?=null
 fun cancel(){cancelled=true;socket?.cancel()}
 private fun checkCancelled(){if(cancelled||Thread.currentThread().isInterrupted)throw ReplayCancelled()}

 fun transcribe(file:File,key:String,options:RecordingOptions=RecordingOptions(),progress:(Float)->Unit):List<TranscriptLine> {
  val shared=context?.let{SharedSpeech.selected(it)}==true
  val request=context?.let{SharedSpeech.request(it,shared,key)}?:SharedSpeech.personalRequest(key)
  val range=WavPcm.inspect(file)
  val lease=if(!shared&&context!=null)AsrVocabulary.acquire(context,key,hotwords)else null
  val results=mutableListOf<TranscriptLine>()
  val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(0,TimeUnit.MILLISECONDS).pingInterval(20,TimeUnit.SECONDS).build()
  try {
   RandomAccessFile(file,"r").use { input ->
    var consumed=0L
    // Each bounded task has its own timestamps; offsets map it back to the original recording.
    while(consumed<range.byteCount) {
     checkCancelled()
     val count=minOf(32000L*600,range.byteCount-consumed)
     input.seek(range.offset+consumed)
     results+=segment(client,input,request,shared,lease?.id,count,consumed*1000/32000,options){bytes->progress((consumed+bytes).toFloat()/range.byteCount)}
     consumed+=count
    }
   }
   checkCancelled();require(results.isNotEmpty()){"未识别出有效语音，已保留原转写文字"};return results.sortedBy{it.start}
  } finally {lease?.close();socket?.cancel();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
 }

 private fun segment(client:OkHttpClient,input:RandomAccessFile,request:Request,shared:Boolean,vocabularyId:String?,bytes:Long,offset:Long,options:RecordingOptions,progress:(Long)->Unit):List<TranscriptLine> {
  val id=UUID.randomUUID().toString();val started=CountDownLatch(1);val ended=CountDownLatch(1)
  val error=AtomicReference<Exception?>(null);val sentences=java.util.concurrent.ConcurrentHashMap<Int,TranscriptLine>()
  fun fail(e:Exception){error.compareAndSet(null,e);started.countDown();ended.countDown()}
  val ws=client.newWebSocket(request,object:WebSocketListener(){
   override fun onOpen(webSocket:WebSocket,response:Response){webSocket.send(JSONObject().put("header",header("run-task",id)).put("payload",JSONObject().put("task_group","audio").put("task","asr").put("function","recognition").put("model","fun-asr-realtime").put("parameters",JSONObject().put("format","pcm").put("sample_rate",16000).put("heartbeat",true).put("semantic_punctuation_enabled",true).put("language_hints",JSONArray().put(TranslationLanguages.code(options.language)))).put("input",AsrHints.input(options.asrContext,hotwords)).let{AsrVocabulary.attach(it,hotwords,shared,vocabularyId)}).toString())}
   override fun onMessage(webSocket:WebSocket,text:String){try{
    val o=JSONObject(text);if(o.getJSONObject("header").optString("task_id")!=id)return
    when(o.getJSONObject("header").optString("event")) {
     "task-started" -> started.countDown()
     "task-finished" -> ended.countDown()
     "task-failed" -> fail(Exception(if(shared&&context!=null)SharedSpeech.error(context,o.getJSONObject("header"))else"阿里云转写未完成，请检查账号权限、余额和网络"))
     "result-generated" -> {val sentence=o.optJSONObject("payload")?.optJSONObject("output")?.optJSONObject("sentence")?:return
      if(sentence.optBoolean("sentence_end")&&!sentence.optBoolean("heartbeat")) {
       val value=sentence.optString("text");val begin=sentence.optLong("begin_time",-1);val end=sentence.optLong("end_time",-1)
       if(value.isNotBlank()&&begin>=0&&end>=begin)sentences[sentence.getInt("sentence_id")]=TranscriptLine(value,offset+begin,offset+end)
      }
     }
    }
   }catch(e:Exception){fail(Exception("语音服务返回格式异常，原转写未改变",e))}}
   override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){fail(Exception("转写连接中断，原转写未改变"))}
   override fun onClosed(webSocket:WebSocket,code:Int,reason:String){if(ended.count>0)fail(Exception("语音连接提前结束，原转写未改变"))}
  })
  socket=ws
  try {
   require(started.await(40,TimeUnit.SECONDS)){"连接语音服务超时"};checkCancelled();error.get()?.let{throw it}
   val buffer=ByteArray(3200);var sent=0L;var update=0L
   while(sent<bytes){
    checkCancelled();error.get()?.let{throw it}
    val n=minOf(buffer.size.toLong(),bytes-sent).toInt();input.readFully(buffer,0,n)
    require(ws.queueSize()<960000){"上传积压，请在网络稳定后重试"}
    check(ws.send(buffer.toByteString(0,n))){"音频发送失败"};sent+=n
    if(sent-update>=32000||sent==bytes){progress(sent);update=sent}
    Thread.sleep(maxOf(1,n*1000L/32000))
   }
   check(ws.send(JSONObject().put("header",header("finish-task",id)).put("payload",JSONObject().put("input",JSONObject())).toString()))
   require(ended.await(45,TimeUnit.SECONDS)){"等待完整转写结果超时"};checkCancelled();error.get()?.let{throw it}
   return sentences.values.sortedBy{it.start}
  } finally {ws.cancel();socket=null}
 }
 private fun header(action:String,id:String)=JSONObject().put("action",action).put("task_id",id).put("streaming","duplex")
}

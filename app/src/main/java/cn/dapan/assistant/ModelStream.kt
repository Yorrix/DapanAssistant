package cn.dapan.assistant

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException

data class ModelProgress(val reply:String="",val status:String="正在等待模型响应…")

/** SSE is transport only. No action is decoded or dispatched until a clean stop and DONE. */
object ModelStream {
 fun read(reader:BufferedReader,onContent:(String)->Unit={}):String {
  val output=StringBuilder();val event=StringBuilder();var stop=false;var done=false
  fun consume(){
   if(event.isEmpty())return
   val data=event.toString();event.setLength(0)
   if(data.trim()=="[DONE]"){done=true;return}
   val frame=try{JSONObject(data)}catch(_:Exception){throw ModelReplyFormatException("invalid_stream_frame")}
   if(frame.has("dapan_error"))throw SharedAiException(frame.optJSONObject("dapan_error")?.optString("message")?.take(300)?:"公用 AI 回复中断，请重试")
   if(frame.has("error"))throw IOException("模型服务中断，请稍后重试")
   val choice=frame.optJSONArray("choices")?.optJSONObject(0)?:return
   val reason=choice.optString("finish_reason").takeUnless{it=="null"||it.isBlank()}
   if(reason!=null){if(reason!="stop")throw ModelReplyFormatException("stream_$reason");stop=true}
   val delta=choice.optJSONObject("delta")?.opt("content")
   if(delta is String&&delta.isNotEmpty()){
    if(output.length+delta.length>1_000_000)throw ModelReplyFormatException("stream_too_large")
    output.append(delta);onContent(output.toString())
   }
  }
  while(!done){
   val line=reader.readLine()?:break
   if(line.isEmpty())consume()
   else if(line.startsWith("data:")){
    if(event.isNotEmpty())event.append('\n')
    event.append(line.substring(5).removePrefix(" "))
    if(event.length>1_000_000)throw ModelReplyFormatException("stream_frame_too_large")
   }
   // SSE comments/keep-alives, event/id/retry fields contain no answer text.
  }
  if(!done&&event.isNotEmpty())consume()
  if(!done||!stop)throw IOException("回答传输中断，尚未收到完整结果，请重试")
  if(output.isBlank())throw ModelReplyFormatException("empty_content")
  return output.toString()
 }
}

/** Conservative display-only extraction: never repair or execute partial JSON. */
object ChatReplyPreview {
 private val prefix=Regex("^\\s*\\{\\s*\"action\"\\s*:\\s*\"chat\"\\s*,\\s*\"reply\"\\s*:\\s*\"")
 fun text(raw:String):String {
  val match=prefix.find(raw)?:return ""
  val result=StringBuilder();var i=match.range.last+1
  while(i<raw.length){
   val ch=raw[i++]
   if(ch=='"')break
   if(ch!='\\'){result.append(ch);continue}
   if(i>=raw.length)break
   when(val escape=raw[i++]){
    '"','\\','/'->result.append(escape)
    'b'->result.append('\b');'f'->result.append('\u000c');'n'->result.append('\n');'r'->result.append('\r');'t'->result.append('\t')
    'u'->{if(i+4>raw.length)break;val value=raw.substring(i,i+4).toIntOrNull(16)?:break;result.append(value.toChar());i+=4}
    else->break
   }
  }
  if(result.lastOrNull()?.isHighSurrogate()==true)result.setLength(result.length-1)
  return result.toString()
 }
}

object ModelErrors {
 fun message(e:Exception):String {
  val causes=generateSequence<Throwable>(e){it.cause}.take(8).toList()
  return when{
   causes.any{it is java.net.SocketTimeoutException||it is java.io.InterruptedIOException||it.message?.trim()?.equals("timeout",true)==true}->"模型响应超时，尚未执行操作。你的要求已保留，可以点击重试。"
   causes.any{it is java.net.UnknownHostException||it is java.net.ConnectException}->"无法连接模型服务，请检查网络后重试。本次请求尚未执行操作。"
   e is org.json.JSONException->"AI 返回的信息不完整，请重试；尚未执行操作。"
   causes.any{it is java.io.IOException}->"连接中断，尚未执行操作。你的要求已保留，请检查网络后重试。"
   else->e.message?:"请求未完成，请重试"
  }
 }
}

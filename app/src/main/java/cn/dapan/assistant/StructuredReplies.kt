package cn.dapan.assistant

import org.json.*

class ModelReplyFormatException(val reason:String):Exception("AI 返回内容不完整，请重试；尚未执行操作。")

/** Format recovery happens before dispatch. Never repair/execute a truncated action locally. */
object StructuredReplies {
 fun envelope(raw:String):String {
  try {
   val o=JSONObject(raw)
   val choice=o.getJSONArray("choices").getJSONObject(0)
   if(choice.optString("finish_reason")=="length")throw ModelReplyFormatException("truncated")
   val content=choice.getJSONObject("message").opt("content")
   if(content !is String)throw ModelReplyFormatException("missing_content")
   return content
  }catch(e:JSONException){throw ModelReplyFormatException("invalid_envelope")}
 }
 fun parse(raw:String):JSONObject {
  var text=raw.trim().removePrefix("\uFEFF").trim()
  if(text.startsWith("```json")&&text.endsWith("```"))text=text.removePrefix("```json").removeSuffix("```").trim()
  if(text.startsWith("```")&&text.endsWith("```"))text=text.removePrefix("```").removeSuffix("```").trim()
  if(text.isBlank())throw ModelReplyFormatException("empty_content")
  try{
   val tokener=JSONTokener(text);val result=tokener.nextValue()
   if(result !is JSONObject||tokener.nextClean()!='\u0000')throw ModelReplyFormatException("not_one_object")
   if(result.opt("action") !is String||result.getString("action").isBlank())throw ModelReplyFormatException("missing_action")
   return result
  }catch(e:JSONException){throw ModelReplyFormatException("invalid_json")}
 }
 suspend fun request(onInvalid:(Int,String)->Unit={_,_->},generate:suspend (Int)->String):JSONObject {
  for(attempt in 0..1){
   try{return parse(generate(attempt))}catch(e:ModelReplyFormatException){onInvalid(attempt,e.reason);if(attempt==1)throw e}
  }
  error("unreachable")
 }
}

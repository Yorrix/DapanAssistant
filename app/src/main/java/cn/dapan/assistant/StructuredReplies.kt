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
   val tokener=JSONTokener(text);val parsed=tokener.nextValue()
   if(parsed !is JSONObject)throw ModelReplyFormatException("not_one_object")
   val tail=buildString { while(true){val char=tokener.next();if(char=='\u0000')break;append(char)} }.trim()
   if(tail.isNotEmpty()){
    // A complete, read-only chat sometimes has prose or response-format metadata appended.
    // Preserve that one answer without another paid call; never choose among two actions.
    val chatOnly=parsed.optString("action")=="chat"&&parsed.opt("reply") is String&&parsed.keys().asSequence().toSet()==setOf("action","reply")
    val metadata=runCatching{
     val t=JSONTokener(tail);var count=0
     while(t.nextClean()!='\u0000'){
      t.back();val o=t.nextValue() as? JSONObject?:error("extra content")
      val keys=o.keys().asSequence().toSet()
      val repeatedChat=keys==setOf("action","reply")&&o.optString("action")=="chat"&&o.opt("reply") is String
      val echoedFormat=o.optString("type")=="json_object"&&keys in setOf(setOf("type"),setOf("type","reply"))&&(!o.has("reply")||o.opt("reply")==parsed.opt("reply"))
      require(repeatedChat||echoedFormat);require(++count<=3)
     };count>0
    }.getOrDefault(false)
    val prose=tail.length<=2000&&tail.none{it in "{}[]`"}
    if(!chatOnly||(!metadata&&!prose))throw ModelReplyFormatException("not_one_object")
   }
   // Some models echo the response_format wrapper. Unwrap only this exact, complete
   // metadata form; never infer missing action fields or choose between competing actions.
   val result=if(parsed.opt("type")=="json_object"&&parsed.keys().asSequence().toSet()==setOf("type","content"))
    parsed.optJSONObject("content")?:throw ModelReplyFormatException("invalid_content_wrapper")
   else parsed
   if(result.opt("type")=="json_object"&&result.has("action")&&!result.has("content"))result.remove("type")
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

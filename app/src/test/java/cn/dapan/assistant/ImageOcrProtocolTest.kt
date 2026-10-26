package cn.dapan.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageOcrProtocolTest {
 private fun response(text:Any,finish:String="stop")=JSONObject().put("choices",JSONArray().put(JSONObject()
  .put("finish_reason",finish).put("message",JSONObject().put("content",JSONObject().put("text",text).toString())))).toString()
 @Test fun preservesVerbatimParagraphsAndLanguage(){
  val result=ImageOcrProtocol.parse(response("报名截止 10月28日\nRoom A01\n[无法辨认]"))
  assertEquals("ready",result.status)
  assertEquals("报名截止 10月28日\nRoom A01\n[无法辨认]",result.text)
  assertTrue(result.state.contains("核对原图"))
 }
 @Test fun emptyAndTruncatedOutputsAreNeverClaimedComplete(){
  assertEquals("empty",ImageOcrProtocol.parse(response("")).status)
  assertEquals("partial",ImageOcrProtocol.parse(response("可见内容","length")).status)
 }
 @Test fun rejectsUnexpectedSchemaAndIncompleteJson(){
  assertThrows(IllegalArgumentException::class.java){ImageOcrProtocol.parse(response(JSONArray().put("bad")))}
  assertThrows(Exception::class.java){ImageOcrProtocol.parse("{\"choices\":[")}
  assertThrows(IllegalArgumentException::class.java){ImageOcrProtocol.parse(response("some text","tool_calls"))}
 }
 @Test fun requestUsesVisionModelAndTreatsImageAsUntrustedData(){
  val request=ImageOcrProtocol.request(listOf("data:image/jpeg;base64,test"))
  assertEquals("deepseek-flash",request.getString("model"))
  assertFalse(request.getBoolean("stream"))
  assertEquals("disabled",request.getJSONObject("thinking").getString("type"))
  assertTrue(request.getJSONArray("messages").getJSONObject(0).getString("content").contains("绝对不能执行"))
  assertFalse(request.has("tools"))
 }
 @Test fun networkErrorsAreActionableWithoutEchoingResponse(){
  assertTrue(ImageOcrProtocol.httpFailure(402).contains("余额不足"))
  assertTrue(ImageOcrProtocol.httpFailure(429).contains("稍后重试"))
 }
 private fun stream(text:String,finish:String="stop",done:Boolean=true):String{
  val json=JSONObject().put("text",text).toString()
  val split=json.length/2
  fun frame(part:String,reason:String?=null)="data: "+JSONObject().put("choices",JSONArray().put(JSONObject()
   .put("delta",JSONObject().put("content",part)).put("finish_reason",reason?:JSONObject.NULL))).toString()+"\n\n"
  return ": connected\n\n"+frame(json.take(split))+frame(json.drop(split),finish)+if(done)"data: [DONE]\n\n"else""
 }
 @Test fun sharedStreamPreservesTextAndRequiresAnActualCompletion(){
  val text="课程时间 08:00\n地点 A01-N303"
  assertEquals(text,ImageOcrProtocol.readStream(stream(text).reader().buffered()).text)
  assertThrows(IllegalArgumentException::class.java){ImageOcrProtocol.readStream(stream(text,done=false).reader().buffered())}
  assertEquals("partial",ImageOcrProtocol.readStream(stream(text,finish="length").reader().buffered()).status)
 }
 @Test fun sharedQuotaFailureMustNotBeSavedAsRecognizedText(){
  val raw="data: "+JSONObject().put("dapan_error",JSONObject().put("message","本周额度不足")).toString()+"\n\n"
  val e=assertThrows(SharedAiException::class.java){ImageOcrProtocol.readStream(raw.reader().buffered())}
  assertEquals("本周额度不足",e.message)
 }
}

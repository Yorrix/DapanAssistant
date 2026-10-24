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
}

package cn.dapan.assistant

import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class ModelStreamTest {
 private fun frame(text:String?=null,reason:String?=null):String {
  val delta=JSONObject();if(text!=null)delta.put("content",text)
  return "data: "+JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",delta).put("finish_reason",reason?:JSONObject.NULL)))+"\n\n"
 }
 private fun parse(s:String)=ModelStream.read(s.reader().buffered())
 @Test fun waitsForStopAndDoneAndIgnoresHeartbeats(){
  val seen=mutableListOf<String>();val raw=": keep-alive\n\n"+frame()+frame("你")+frame("好")+frame(reason="stop")+"data: [DONE]\n\n"
  assertEquals("你好",ModelStream.read(raw.reader().buffered()){seen+=it});assertEquals(listOf("你","你好"),seen)
 }
 @Test fun interruptedActionNeverReturnsForDispatch(){
  val json="{\"action\":\"delete\",\"target\":\"课程\"}"
  assertThrows(IOException::class.java){parse(frame(json)+frame(reason="stop"))}
  assertThrows(IOException::class.java){parse(frame(json)+"data: [DONE]\n\n")}
 }
 @Test fun tokenLimitRejectsEvenSyntacticallyCompleteJson(){assertThrows(ModelReplyFormatException::class.java){parse(frame("{}")+frame(reason="length")+"data: [DONE]\n\n")}}
 @Test fun malformedFrameDoesNotFallBackToPartialJson(){assertThrows(ModelReplyFormatException::class.java){parse("data: {broken\n\n")}}
 @Test fun emptyCompletionAndErrorFramesAreRejected(){
  assertThrows(ModelReplyFormatException::class.java){parse(frame(reason="stop")+"data: [DONE]\n\n")}
  assertThrows(IOException::class.java){parse("data: {\"error\":{\"message\":\"busy\"}}\n\n")}
 }
 @Test fun splitChineseAndEscapedReplyPreviews(){
  val p="{\"action\":\"chat\",\"reply\":\""
  assertEquals("你好\n\"同学\"",ChatReplyPreview.text(p+"你好\\n\\\"同学\\\""))
  assertEquals("中",ChatReplyPreview.text(p+"\\u4e2d\\u"))
  assertEquals("",ChatReplyPreview.text(p+"\\uD83D"))
  assertEquals("😀",ChatReplyPreview.text(p+"\\uD83D\\uDE00"))
 }
 @Test fun previewsNeverExposeActionPayload(){
  assertEquals("",ChatReplyPreview.text("{\"action\":\"batch\",\"actions\":[{\"reply\":\"secret\"}]}"))
  assertEquals("",ChatReplyPreview.text("{\"reply\":\"out of order\",\"action\":\"chat\"}"))
 }
 @Test fun timeoutIsReadableAndReadTimeoutIsNotDefaultTenSeconds(){
  assertTrue(ModelErrors.message(IOException("outer",java.net.SocketTimeoutException("timeout"))).contains("响应超时"))
  assertEquals(90_000,AiClient.client.readTimeoutMillis)
  assertEquals(240_000,AiClient.client.callTimeoutMillis)
 }
 @Test fun multilineDataFrameAndNoFinalBlankAreSupported(){
  assertEquals("ok",parse("event: message\ndata: {\"choices\":[\ndata: {\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]"))
 }
}

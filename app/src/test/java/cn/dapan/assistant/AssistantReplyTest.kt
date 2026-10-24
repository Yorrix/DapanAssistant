package cn.dapan.assistant

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AssistantReplyTest {
 @Test fun whitespaceFirstReplyRecoversBeforeAnyActionIsReturned()=runBlocking {
  var calls=0
  val result=StructuredReplies.request{calls++;if(it==0)"\n".repeat(12)else "{\"action\":\"chat\",\"reply\":\"请问是哪一天？\"}"}
  assertEquals(2,calls);assertEquals("chat",result.getString("action"))
 }
 @Test fun brokenOutputRetriesOnlyOnceAndNeverReturnsPartialAction()=runBlocking {
  var calls=0
  try{StructuredReplies.request{calls++;"{\"action\":\"delete\","};fail("Must reject truncated operation")}catch(e:ModelReplyFormatException){assertEquals("invalid_json",e.reason)}
  assertEquals(2,calls)
 }
 @Test fun transportOrAuthenticationFailureIsNotRetriedByFormatRecovery()=runBlocking {
  var calls=0
  try{StructuredReplies.request{calls++;throw java.io.IOException("offline")};fail("Must propagate")}catch(_:java.io.IOException){}
  assertEquals(1,calls)
 }
 @Test fun normalReplyUsesOneCallAndPreservesClarification()=runBlocking {
  var calls=0
  val result=StructuredReplies.request{calls++;"{\"action\":\"chat\",\"reply\":\"三位同学的名字是什么？\"}"}
  assertEquals(1,calls);assertTrue(AssistantActions.decode(result) is AssistantAction.Reply)
 }
 @Test fun refusesExtraActionsAndTruncatedEnvelopes(){
  assertThrows(ModelReplyFormatException::class.java){StructuredReplies.parse("{\"action\":\"chat\"}{\"action\":\"delete\"}")}
  assertThrows(ModelReplyFormatException::class.java){StructuredReplies.envelope("{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"{}\"}}]}")}
  assertThrows(ModelReplyFormatException::class.java){StructuredReplies.envelope("\n\n")}
 }
 @Test fun cancelledOrInterruptedVoiceCannotSendLateTranscription(){
  assertFalse(VoiceRelease.shouldSend(true,true,null,"删除日程"))
  assertFalse(VoiceRelease.shouldSend(false,false,null,"服务意外断开前的文字"))
  assertFalse(VoiceRelease.shouldSend(true,false,"连接中断","只收到半句"))
  assertFalse(VoiceRelease.shouldSend(true,false,null,"  "))
  assertTrue(VoiceRelease.shouldSend(true,false,null,"午饭十八元"))
  assertFalse(VoiceRelease.inside(40f,-5f,160f,36f))
  assertTrue(VoiceRelease.inside(40f,18f,160f,36f))
 }
}

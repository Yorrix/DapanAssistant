package cn.dapan.assistant

import kotlinx.coroutines.runBlocking
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class AssistantEvidenceTest {
 @Test fun documentQuestionReadsLibraryBeforeClaimingItIsMissing()=runBlocking{
  var calls=0;var reads=0
  val action=AssistantReasoning.resolve(request={feedback->
   if(calls++==0)JSONObject("""{"action":"chat","reply":"这份只是临时附件，无法读取命名要求。请重新上传。"}""")
   else{assertTrue(feedback.contains("library-real"));JSONObject("""{"action":"chat","reply":"报告按学号_姓名.pdf命名。"}""")}
  },lookup={reads++;assertEquals("资料库",it.module);"""{"items":[{"id":"library-real","name":"说明.txt","text":"学号_姓名.pdf"}]}"""},validate={},groundingRequest="那份报告说明的文件命名要求告诉我，引用原文")
  assertEquals(1,reads);assertEquals(2,calls);assertTrue((action as AssistantAction.Reply).text.contains("学号_姓名.pdf"))
 }
 @Test fun libraryUsageAdviceDoesNotTriggerContentReads(){
  listOf("教我怎么查资料库原文","不要查资料，先解释引用是什么","怎么保存文件到资料库").forEach{assertFalse(it,AssistantGrounding.needsLibrary(it))}
 }
 @Test fun unavailableWheelIdIsLookedUpInsteadOfAskingUser(){
  assertEquals("抽签",AssistantGrounding.lookupFor("把今晚吃什么那个轮盘改名午饭选择，选项别动")?.module)
  assertNull(AssistantGrounding.lookupFor("新建轮盘，三个人参加"))
 }
 @Test fun savedBannerContentIsReadBeforeAskingAgain(){
  assertEquals("手持弹幕",AssistantGrounding.lookupFor("显示现在的手持弹幕")?.module)
  assertNull(AssistantGrounding.lookupFor("怎么使用手持弹幕"))
  assertNull(AssistantGrounding.lookupFor("不要显示弹幕"))
 }
 @Test fun repeatedTranscriptLookupStillShowsExactLocatedText(){
  val observation=JSONObject("""{"module":"录音摘要","result":{"items":[{"title":"高数第一课","clips":[{"name":"第2次录音","lines":[{"text":"极限的定义","start":90000}]}]}]}}""")
  val answer=AssistantLookupFallback.describe(observation)!!
  assertTrue(answer.contains("第2次录音 · 1:30"));assertTrue(answer.contains("极限的定义"))
  assertTrue(answer.contains("没有修改或删除"))
 }
 @Test fun currentToolResultsFollowTheirOriginalQuestion(){
  val messages=AiClient.requestBody("deepseek-flash","报告怎么命名",toolResults="[{\"result\":\"actual-result\"}]").getJSONArray("messages").objects()
  assertTrue(messages.last().getString("content").contains("actual-result"))
  assertEquals("报告怎么命名",messages[messages.lastIndex-1].getString("content"))
 }
 @Test fun invalidAttachmentIdIsExplicitErrorAndCanRecoverInSameTurn()=runBlocking{
  var calls=0;var retained=""
  val action=AssistantReasoning.resolve(request={feedback->
   when(calls++){
    0->JSONObject("""{"action":"lookup","module":"资料库","id":"attachment-temp","query":""}""")
    1->{assertTrue(feedback.contains("\"error\""));JSONObject("""{"action":"lookup","module":"资料库","query":"物理"}""")}
    else->JSONObject("""{"action":"chat","reply":"学号_姓名_实验名称.pdf；来自物理.txt。"}""")
   }
  },lookup={if(it.id.isNotBlank())"""{"error":"文件不存在"}""" else """{"items":[{"id":"library-real","name":"物理.txt","text":"学号_姓名_实验名称.pdf"}]}"""},validate={},onEvidence={retained=it})
  assertTrue(action is AssistantAction.Reply);assertEquals(3,calls)
  assertTrue(retained.contains("library-real"));assertFalse(retained.contains("attachment-temp"))
 }
 @Test fun longAnswerCannotTruncateFollowupFileId(){
  val line=ChatLine("assistant","说明".repeat(5000),evidence="""{"file":{"id":"library-real","name":"物理.txt"}}""")
  assertTrue(line.modelText.contains("library-real"));assertTrue(line.modelText.length<6500)
 }
 @Test fun oversizedEvidenceKeepsValidJsonAndRealIds(){
  val row=JSONObject().put("kind","lookup").put("result",JSONObject().put("file",JSONObject().put("id","doc-real")).put("text","正文".repeat(15000)))
  val saved=AssistantEvidence.retain(listOf(row))
  assertTrue(saved.length<18000);assertEquals("doc-real",JSONArray(saved).getJSONObject(0).getJSONObject("result").getJSONObject("file").getString("id"))
 }
 @Test fun failedLookupsAreNeverRememberedAsSuccessfulEvidence(){
  assertEquals("",AssistantEvidence.retain(listOf(JSONObject().put("error","没有文件"))))
 }
 @Test fun productionPromptKeepsEvidenceAndDistinguishesTemporaryIds(){
  val body=AiClient.requestBody("deepseek-flash","截止是几点",history=listOf(ChatLine("assistant","命名规则如下",evidence="""{"id":"real-file"}""")))
  val messages=body.getJSONArray("messages").objects()
  assertTrue(messages.first().getString("content").contains("附件id只用于save_attachments"))
  assertTrue(messages.any{it.optString("role")=="assistant"&&it.getString("content").contains("real-file")})
 }
}

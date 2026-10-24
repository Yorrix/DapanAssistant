package cn.dapan.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class AssistantRecoveryTest {
 private fun event(id:String,title:String)=CalendarEntry(id=id,title=title,start=LocalDateTime.of(2026,10,20,8,0),end=LocalDateTime.of(2026,10,20,9,0),location="A01")
 private fun snapshot(rows:List<CalendarEntry>)=AssistantSnapshot(rows,emptyList(),emptyList(),listOf("课程"),emptyList(),0)
 @Test fun screenshotBulkRenameIsAtomicAcrossMultipleMappings(){
  val rows=(1..125).map{event("en$it","大学英语二")}+(1..24).map{event("cs$it","程序设计与算法")}
  val raw=JSONObject("""{"action":"batch","actions":[{"action":"rename_events","scope":"all","names":[{"from":"大学英语二","to":"大英二"}]},{"action":"rename_events","scope":"all","names":[{"from":"程序设计与算法","to":"程设"}]}]}""")
  val plan=AssistantPlanner.build(snapshot(rows),AssistantActions.decode(raw))
  assertEquals(149,plan.operationCount)
  assertEquals(rows.map{it.copy(title=if(it.title=="大学英语二")"大英二"else"程设")},plan.after.entries)
  assertEquals(rows,plan.before.entries)
 }
 @Test fun duplicateBulkTargetsRejectWholePlan(){
  val before=snapshot(listOf(event("a","体育一")))
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.build(before,AssistantAction.Batch(listOf(
   AssistantAction.RenameEvents(listOf("体育一" to "体育")),AssistantAction.RenameEvents(listOf("体育一" to "运动")))))}
  assertEquals("体育一",before.entries.single().title)
 }
 @Test fun speechCorrectionIsRecoveredWithoutMakingUserRepeat()=runBlocking{
  val before=snapshot((1..24).map{event("$it","成设")});var calls=0
  val result=AssistantReasoning.resolve(request={feedback->
   if(calls++==0)JSONObject().put("action","chat").put("reply","我这就把这24条统一改成「程设」，时间地点和提醒都不变。")
   else{assertTrue(feedback.contains("action_missing"));JSONObject("""{"action":"rename_events","scope":"all","names":[{"from":"成设","to":"程设"}]}""")}
  },lookup={error("not needed")},validate={if(it !is AssistantAction.Reply)AssistantPlanner.build(before,it)})
  assertEquals(2,calls)
  val plan=AssistantPlanner.build(before,result)
  assertEquals(24,plan.operationCount);assertTrue(plan.after.entries.all{it.title=="程设"})
 }
 @Test fun repeatedPromisesStopWithHonestUnexecutedStatus()=runBlocking{
  var calls=0
  val result=AssistantReasoning.resolve(request={calls++;JSONObject("""{"action":"chat","reply":"我马上帮你添加日程。"}""")},lookup={""},validate={})
  assertEquals(3,calls);assertTrue((result as AssistantAction.Reply).text.contains("尚未修改"))
 }
 @Test fun questionsExamplesAndPreviousReceiptsDoNotTriggerPromiseRepair(){
  listOf("你要改成程设吗？","例如你可以说我现在就把课表改成简称。","如果你批准，我会修改。","我无法修改这些资料。","已经修改24项。","我不会删除你的课程。").forEach{assertFalse(it,AssistantReplyGuard.promisesAction(it))}
 }
 @Test fun everyPageOperationHasAReachableModuleAndValidatedFields(){
  val catalogue=AssistantCatalogue.json();val context=JSONObject().put("pageOperations",catalogue)
  catalogue.keys().forEach{module->
   assertTrue(module,module in AssistantActions.destinations)
   val rows=catalogue.getJSONArray(module).objects()
   assertEquals(rows.size,rows.map{it.getString("op")}.distinct().size)
   rows.forEach{op->
    runCatching{AssistantToolContract.validate(AssistantAction.Tool(module,op.getString("op"),JSONObject()),context)}.exceptionOrNull()?.let{
     assertTrue(it.message.orEmpty(),it is IllegalArgumentException&&it.message.orEmpty().isNotBlank())
    }
    assertThrows(IllegalArgumentException::class.java){AssistantToolContract.validate(AssistantAction.Tool(module,op.getString("op"),JSONObject().put("__unsupported",true)),context)}
   }
  }
 }
}

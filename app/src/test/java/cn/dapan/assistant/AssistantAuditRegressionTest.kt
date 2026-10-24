package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AssistantAuditRegressionTest {
 private val empty=AssistantSnapshot(emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),0)
 @Test fun approvalModeDoesNotInterruptQueriesOrNavigation(){
  AssistantGate.check(AssistantAccess.CONFIRM,false,false)
  assertThrows(IllegalStateException::class.java){AssistantGate.check(AssistantAccess.CONFIRM,true,false)}
 }
 @Test fun explicitNewExpenseSurvivesSwitchingTabsWhileThinking(){
  AssistantRequestGuard.check(AssistantAction.Note(NoteItem(module="账单",title="午饭",detail="18")),"今天","日历","old","new")
 }
 @Test fun explicitIdEditSurvivesSwitchingTabsButImplicitSelectionDoesNot(){
  AssistantRequestGuard.check(AssistantAction.Change("update","日历","体测",JSONObject().put("notes","带玉兰卡"),"event-1"),"日历","工具","a","b")
  assertThrows(IllegalStateException::class.java){AssistantRequestGuard.check(AssistantAction.Change("delete","日历","这个",JSONObject()),"日历","工具","a","b")}
 }
 @Test fun futureMorningMustFailDuringPlanningBeforePreview(){
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.build(empty,AssistantAction.Morning(CheckDay(LocalDate.now().plusDays(1),true,true)))}
 }
 @Test fun conflictingDuplicateJsonKeysMustNotChooseOneSilently(){
  assertThrows(Exception::class.java){StructuredReplies.parse("""{"action":"chat","reply":"不要执行","action":"delete","module":"日历","target":"全部"}""")}
 }
 @Test fun stringNullCannotBecomeRealRecordTitle(){
  assertThrows(Exception::class.java){AssistantActions.decode(JSONObject("""{"action":"note","module":"信息查询","title":null,"detail":"123"}"""))}
 }
 @Test fun incomeAmountAndDateSurviveCorrection(){
  val original=NoteItem(id="bill",module="账单",title="补贴",detail="-100.00",group="收入")
  val action=AssistantActions.decode(JSONObject("""{"action":"update","module":"账单","targetId":"bill","patch":{"detail":"-150.00","date":"2026-10-20"}}"""))
  val plan=AssistantPlanner.build(empty.copy(notes=listOf(original)),action)
  assertEquals("-150.00",plan.after.notes.single().detail)
  assertEquals("收入",plan.after.notes.single().group)
  assertEquals(LocalDate.of(2026,10,20),Billing.day(plan.after.notes.single()))
 }
 @Test fun filenameHeadingSearchMustCarryFollowingRuleAsExactContext(){
  val body="大学物理实验报告提交说明\n\n二、文件命名\n统一使用：学号_姓名_实验名称.pdf。\n\n三、提交检查\n包含原始数据。"
  val doc=LibraryDocument("library-real-id","物理实验.txt","text/plain","/test",100,0,"",body,"正文已索引","测试")
  val raw=LibraryRetrieval.document(doc,"文件命名").toString()
  assertTrue("Heading without its next paragraph is not an answer",raw.contains("学号_姓名_实验名称.pdf"))
  assertTrue(raw.contains("library-real-id"))
 }
 @Test fun changingOnlyLocationNeverReschedulesEvent(){
  val event=CalendarEntry(id="e",title="体测",start=LocalDateTime.of(2026,10,24,8,0),location="B01",notes="带玉兰卡")
  val plan=AssistantPlanner.build(empty.copy(entries=listOf(event)),AssistantAction.Change("update","日历","体测",JSONObject().put("location","F02"),"e"))
  assertEquals(event.copy(location="F02"),plan.after.entries.single())
 }
}

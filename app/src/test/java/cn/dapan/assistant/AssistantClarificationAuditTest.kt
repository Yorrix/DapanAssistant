package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

/** Independent regressions for the clarification loop seen in real conversations. */
class AssistantClarificationAuditTest {
 private val today=LocalDate.of(2027,4,8)
 private fun event(hour:Int,minute:Int=0,date:LocalDate=today)=CalendarEntry(title="交材料",start=date.atTime(hour,minute),end=date.atTime(hour,minute).plusMinutes(10))
 private fun snapshot(events:List<CalendarEntry>)=AssistantSnapshot(events,emptyList(),emptyList(),emptyList(),emptyList(),0)
 private fun validate(value:CalendarEntry,text:String,history:List<ChatLine> = emptyList(),records:List<CalendarEntry> = emptyList())=
  AssistantRequestChecks.validate(AssistantAction.Event(value),text,history,JSONObject(),"完全控制",today,snapshot(records))
 private fun allLocations()=AssistantActions.decode(JSONObject("""{"action":"calendar_bulk","op":"update","scope":"all","titles":["实验课"],"patch":{"location":"B01"}}"""))

 @Test fun selectedTimeSurvivesLocationAndAnotherConfirmation(){
  val history=listOf(
   ChatLine("user","帮我安排材料整理"),
   ChatLine("assistant","①今天15:10开始；②今天16:20开始。选哪种？"),
   ChatLine("user","二"),ChatLine("assistant","地点在哪？"),
   ChatLine("user","在宿舍"),ChatLine("assistant","按刚才选的时间，地点在宿舍，可以执行吗？"))
  validate(event(16,20),"对",history)
  assertThrows(IllegalArgumentException::class.java){validate(event(15,10),"对",history)}
 }
 @Test fun multiTurnPlaceAnswerRetainsOriginalExplicitClock(){
  val history=listOf(ChatLine("user","明天8点去体测"),ChatLine("assistant","在哪个场地？"),
   ChatLine("user","风雨操场"),ChatLine("assistant","还有要带的东西吗？"))
  validate(event(8,date=today.plusDays(1)).copy(location="B01",notes="带玉兰卡"),"带玉兰卡",history)
 }
 @Test fun durationAnswerWithArrangeVerbDoesNotStartAnotherTask(){
  val history=listOf(ChatLine("user","明天8点去体测"),ChatLine("assistant","安排多久？"),
   ChatLine("user","就安排一小时"),ChatLine("assistant","请告诉我地点。"))
  validate(event(8,date=today.plusDays(1)).copy(location="B01"),"B01",history)
 }
 @Test fun approvalDoesNotTurnProposalEndIntoStart(){
  val history=listOf(ChatLine("user","安排整理材料"),ChatLine("assistant","今天15:30开始，16:30结束，可以吗？"))
  assertThrows(IllegalArgumentException::class.java){validate(event(16,30),"对",history)}
  validate(event(15,30),"对",history)
 }
 @Test fun approvalDoesNotMoveTomorrowProposalToToday(){
  val history=listOf(ChatLine("user","安排整理材料"),ChatLine("assistant","明天15:30开始，整理十分钟，可以吗？"))
  assertThrows(IllegalArgumentException::class.java){validate(event(15,30),"对",history)}
  validate(event(15,30,date=today.plusDays(1)),"对",history)
 }
 @Test fun commonShortApprovalsDoNotNeedTimeRepeated(){
  val history=listOf(ChatLine("user","安排整理材料"),ChatLine("assistant","今天15:30开始，可以吗？"))
  listOf("好","行","可以的").forEach{validate(event(15,30),it,history)}
 }
 @Test fun abandonedTaskCannotSupplyAnotherTaskClock(){
  val history=listOf(ChatLine("user","明天8点去体测"),ChatLine("assistant","体测在哪？"))
  val text="不用管体测了，明天提醒我交材料"
  assertThrows(IllegalArgumentException::class.java){validate(event(8,date=today.plusDays(1)),text,history)}
 }
 @Test fun completedOperationSeparatesNewClarificationThread(){
  val operation=AssistantOperation(OperationStatus.DONE,"已新增",emptyList())
  val history=listOf(ChatLine("user","明天8点体测"),ChatLine("assistant","已新增",operation=operation),
   ChatLine("user","提醒我交材料"),ChatLine("assistant","你想几点提醒？"))
  assertThrows(IllegalArgumentException::class.java){validate(event(8,date=today.plusDays(1)),"在宿舍",history)}
 }
 @Test fun currentConversationReferenceUsesUniqueActualEnd(){
  val source=event(13,56).copy(title="跟外教1V1聊天",end=today.atTime(14,16))
  val request="等会儿跟外教聊完就提醒我登录"
  validate(event(14,16).copy(reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=today.atTime(14,16)),request,records=listOf(source))
  assertThrows(IllegalArgumentException::class.java){validate(event(14,17),request,records=listOf(source))}
  assertThrows(IllegalArgumentException::class.java){validate(event(14,16),request,records=listOf(source,source.copy(id="other")))}
 }
 @Test fun relativeWeekMustNotBecomeWholeSemester(){
  assertThrows(IllegalArgumentException::class.java){
   AssistantRequestChecks.validate(allLocations(),"把下周所有实验课的地点改成B01",emptyList(),JSONObject(),"完全控制",today)
  }
 }
 @Test fun negatedAllMustNotAuthorizeWholeSemester(){
  assertThrows(IllegalArgumentException::class.java){
   AssistantRequestChecks.validate(allLocations(),"不要改所有实验课，只改下周五那次的地点到B01",emptyList(),JSONObject(),"完全控制",today)
  }
 }
 @Test fun renameHasTheSameRelativeWeekBoundary(){
  val action=AssistantAction.RenameEvents(listOf("实验课" to "实验"))
  assertThrows(IllegalArgumentException::class.java){
   AssistantRequestChecks.validate(action,"下周所有实验课统一改名为实验",emptyList(),JSONObject(),"完全控制",today)
  }
  AssistantRequestChecks.validate(action,"所有实验课统一改名为实验",emptyList(),JSONObject(),"完全控制",today)
 }
 @Test fun dateRangeCannotExpandPastRequestedWeek(){
  val action=AssistantAction.RenameEvents(listOf("实验课" to "实验"),LocalDate.of(2027,4,12),LocalDate.of(2027,5,31))
  assertThrows(IllegalArgumentException::class.java){
   AssistantRequestChecks.validate(action,"下周所有实验课统一改名为实验",emptyList(),JSONObject(),"完全控制",today)
  }
 }
 @Test fun bulkDateRangeDoesNotLeakIntoOtherMonthsAcross139Records(){
  val entries=List(139){i->event(10,date=today.plusDays(i.toLong())).copy(id="course-$i",title="实验课",location="A01",notes="原备注")}
  val action=AssistantActions.decode(JSONObject("""{"action":"calendar_bulk","op":"update","scope":"date_range","from":"2027-04-12","through":"2027-04-18","titles":["实验课"],"shiftMinutes":-60,"patch":{"location":"B01"}}"""))
  val before=snapshot(entries)
  val plan=AssistantPlanner.build(before,action,today.atStartOfDay())
  assertEquals(7,plan.operationCount)
  entries.forEach{old->
   val updated=plan.after.entries.single{it.id==old.id}
   if(old.start.toLocalDate() in LocalDate.of(2027,4,12)..LocalDate.of(2027,4,18)){
    assertEquals(old.copy(start=old.start.minusHours(1),end=old.end.minusHours(1),location="B01"),updated)
   }else assertEquals(old,updated)
  }
  assertEquals(entries,before.entries)
 }
}

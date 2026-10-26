package cn.dapan.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AssistantRelativeReminderTest {
 private val today=LocalDate.of(2026,10,26)
 private val course=CalendarEntry(id="course",title="习概",start=today.plusDays(1).atTime(8,0),end=today.plusDays(1).atTime(9,35),location="S606",notes="来源课程备注")
 private val request="在明天习概下课的时候。设一个1分钟事件来提醒我去英语课的路上想一下 assignment 怎么写"
 private val reminder=CalendarEntry(title="想一下 assignment 怎么写",start=course.end,end=course.end.plusMinutes(1),reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=course.end)
 private fun records(rows:List<CalendarEntry>)=AssistantSnapshot(rows,emptyList(),emptyList(),emptyList(),emptyList(),0)
 @Test fun screenshotRequestUsesRealEndAndOneMinuteDuration()=runBlocking{
  assertEquals("习概",AssistantConversationContext.referenceTitle(request))
  var calls=0
  val action=AssistantReasoning.resolve(request={calls++;JSONObject().put("action","event").put("entry",entryToJson(reminder))},lookup={error("Already in record context")},
   validate={AssistantRequestChecks.validate(it,request,emptyList(),JSONObject(),"完全控制",today,records(listOf(course)))},groundingRequest=request)
  assertEquals(1,calls)
  val plan=AssistantPlanner.build(records(listOf(course)),action)
  assertEquals(1,plan.operationCount)
  val added=plan.after.entries.single{it.id!=course.id}
  assertEquals(course.end,added.start);assertEquals(course.end.plusMinutes(1),added.end)
  assertEquals(course.end,added.reminderTime());assertEquals("",added.location);assertEquals("",added.notes)
 }
 @Test fun conversationalPrefixesAndWordOrderResolveSameSource(){
  listOf(request,"在明天的习概下课时提醒我", "等明天习概结束之后提醒我", "明天上完习概后提醒我", "明日习概下课就提醒", "周二习概下课后提醒", "10月27日习概结束后提醒", "2026-10-27习概下课时提醒").forEach{
   assertTrue(it,AssistantConversationContext.anchoredTime(it,course.end,listOf(course),today))
  }
 }
 @Test fun missingWrongDayAndAmbiguousCoursesNeverSupplyTime(){
  listOf(emptyList(),listOf(course.copy(start=course.start.plusDays(1),end=course.end.plusDays(1))),listOf(course,course.copy(id="another",start=course.start.plusHours(3),end=course.end.plusHours(3)))).forEach{
   assertFalse(AssistantConversationContext.anchoredTime(request,course.end,it,today))
  }
  assertFalse(AssistantConversationContext.anchoredTime(request,course.end.plusMinutes(1),listOf(course),today))
 }
 @Test fun explicitOffsetsBelongToAnchorNotNewEventDurationOrNotification(){
  listOf("明天习概下课后10分钟提醒我","明天习概下课10分钟后提醒我").forEach{
   assertTrue(it,AssistantConversationContext.anchoredTime(it,course.end.plusMinutes(10),listOf(course),today))
  }
  assertTrue(AssistantConversationContext.anchoredTime("明天习概下课前10分钟提醒我",course.end.minusMinutes(10),listOf(course),today))
  assertTrue(AssistantConversationContext.anchoredTime(request+"，提前10分钟通知",course.end,listOf(course),today))
 }
 @Test fun startsAndEndsAndExactTitlesRemainDistinct(){
  assertTrue(AssistantConversationContext.anchoredTime("明天习概开始时提醒我",course.start,listOf(course),today))
  assertFalse(AssistantConversationContext.anchoredTime("明天习概开始时提醒我",course.end,listOf(course),today))
  assertTrue(AssistantConversationContext.anchoredTime(request,course.end,listOf(course,course.copy(id="tutorial",title="习概辅导",end=course.end.plusHours(2))),today))
 }
 @Test fun shortConfirmationKeepsActualRelativeReference(){
  val history=listOf(ChatLine("user",request),ChatLine("assistant","明天习概下课时提醒，对吗？"))
  AssistantRequestChecks.validate(AssistantAction.Event(reminder),"确定，就是一下课就提醒",history,JSONObject(),"完全控制",today,records(listOf(course)))
 }
 @Test fun streamDraftsNeverLeakPromisesQuestionsOrPartialReplies(){
  listOf("{\"action\":\"chat\",\"reply\":\"我这就", "{\"action\":\"chat\",\"reply\":\"几点开始？\"}","{\"action\":\"event\"").forEach{
   assertEquals("",AiClient.receivingProgress(it).reply)
   assertTrue(AiClient.receivingProgress(it).status.contains("正在处理"))
  }
 }
 @Test fun changedInvalidProposalsStillHaveBoundedRecovery()=runBlocking{
  var calls=0
  try{AssistantReasoning.resolve(request={JSONObject().put("action","focus").put("minutes",900+(calls++))},lookup={error("unused")},validate={});fail("reject")}
  catch(_:AssistantValidationFailure){assertEquals(3,calls)}
 }
 @Test fun sameInvalidProposalWithReorderedKeysStopsEarly()=runBlocking{
  var calls=0
  try{AssistantReasoning.resolve(request={if(calls++==0)JSONObject("""{"action":"focus","minutes":900}""")else JSONObject("""{"minutes":900,"action":"focus"}""")},lookup={error("unused")},validate={});fail("reject")}
  catch(_:AssistantValidationFailure){assertEquals(2,calls)}
 }
 @Test fun reminderSearchIsAnInternalLookupNotAnExtraUserFacingResponse()=runBlocking{
  var calls=0;var reads=0
  val result=AssistantReasoning.resolve(request={if(calls++==0)JSONObject("""{"action":"search","query":"习概"}""")else JSONObject("""{"action":"chat","reply":"没有找到明天的习概课，请告诉我下课时间。"}""")},
   lookup={reads++;assertEquals("日历",it.module);"""{"items":[]}"""},validate={},groundingRequest=request)
  assertEquals(2,calls);assertEquals(1,reads);assertTrue(result is AssistantAction.Reply)
 }
}

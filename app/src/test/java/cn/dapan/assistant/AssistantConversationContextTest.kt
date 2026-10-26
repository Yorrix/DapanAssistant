package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AssistantConversationContextTest {
 private val day=LocalDate.of(2027,4,8)
 private fun entry(hour:Int,minute:Int=0)=CalendarEntry(title="准备材料",start=day.atTime(hour,minute),end=day.atTime(hour,minute).plusHours(1))
 private fun snapshot(events:List<CalendarEntry>)=AssistantSnapshot(events,emptyList(),emptyList(),emptyList(),emptyList(),0)
 private fun validate(e:CalendarEntry,text:String,history:List<ChatLine> = emptyList(),records:List<CalendarEntry> = emptyList())=
  AssistantRequestChecks.validate(AssistantAction.Event(e),text,history,JSONObject(),"完全控制",day,snapshot(records))
 @Test fun relativeEndUsesExactlyOneRealRecord(){
  val source=entry(15).copy(title="跟辅导员谈话",end=day.atTime(15,30))
  validate(entry(15,30),"等会儿跟辅导员谈话结束后提醒我交材料",records=listOf(source))
  assertThrows(IllegalArgumentException::class.java){validate(entry(15,31),"跟辅导员谈话结束后提醒我交材料",records=listOf(source))}
  assertThrows(IllegalArgumentException::class.java){validate(entry(15,30),"跟辅导员谈话结束后提醒我交材料",records=listOf(source,source.copy(id="other")))}
 }
 @Test fun unrelatedRecordCannotSupplyAnOmittedTime(){
  assertThrows(IllegalArgumentException::class.java){validate(entry(15),"明天加个准备材料的日程",records=listOf(entry(15)))}
 }
 @Test fun confirmedProposalDoesNotDemandLiteralTimeAgain(){
  val history=listOf(ChatLine("user","谈话结束后提醒交材料"),ChatLine("assistant","谈话15:30结束，15:30提醒交材料，可以吗？"))
  validate(entry(15,30),"对",history)
  validate(entry(15,30),"可以",history)
  assertThrows(IllegalArgumentException::class.java){validate(entry(16),"对",history)}
 }
 @Test fun choiceApprovesOnlySelectedOption(){
  val history=listOf(ChatLine("user","帮我安排材料整理"),ChatLine("assistant","①15:10开始；②16:20开始。选哪种？"))
  validate(entry(16,20),"二",history)
  assertThrows(IllegalArgumentException::class.java){validate(entry(15,10),"二",history)}
  assertThrows(IllegalArgumentException::class.java){validate(entry(16,20),"对",history)}
 }
 @Test fun multipleClarificationsRetainOriginalTime(){
  val history=listOf(ChatLine("user","明天8点整理材料"),ChatLine("assistant","地点在哪？"),ChatLine("user","在宿舍"),ChatLine("assistant","可以按默认1小时安排吗？"))
  // The immediate answer isn't itself a clock; the bounded thread still has the real request.
  val request=AssistantConversationContext.userRequest("可以",history)
  assertTrue(request.contains("明天8点"))
 }
 @Test fun interruptedUserContentIsStillSentAsContext(){
  val history=listOf(ChatLine("user","https://example.org/student 记一下选课链接",includeInModel=false),ChatLine("assistant","请求已停止"))
  val body=AiClient.requestBody("deepseek-flash","这个记到个人信息里",history=history)
  assertTrue(body.getJSONArray("messages").toString().contains("https://example.org/student"))
 }
 @Test fun atStartReminderIsValidButAfterStartIsNot(){
  val e=entry(15)
  assertNull(e.copy(reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=e.start).error())
  assertNotNull(e.copy(reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=e.start.plusMinutes(1)).error())
 }
}

package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AssistantSpokenInputTest {
 private val day=LocalDate.of(2026,10,26)
 private val course=CalendarEntry(id="english-am",title="大英一",start=day.plusDays(1).atTime(10,5),end=day.plusDays(1).atTime(11,40),travelMinutes=20,reminderMode=ReminderMode.DEPARTURE,category="课程",location="F02-211")
 private val state=AssistantSnapshot(listOf(course),emptyList(),emptyList(),listOf("课程"),emptyList(),0)
 private val spoken="明天英语课。早上那节出发的时候，呃设一个时间。就是看。就是写个assignment怎么写？"
 private val proposal="明天早上那节英语课（大英一，10:05开始）出发时提醒，该在9:45左右。请问：这个1分钟的“想assignment怎么写”提醒设在9:45可以吗？"
 private val history=listOf(ChatLine("user",spoken),ChatLine("assistant",proposal))
 @Test fun repeatedSpokenAgreementApprovesButCorrectionDoesNot(){
  listOf("可以可以。","对，对，对。","嗯好好的","行行行。","好，可以，执行。","嗯，可以的啊。").forEach{assertEquals(it,OperationReply.APPROVE,OperationReply.parse(it))}
  listOf("可以可以，不过改成下午","对对，不是明天是后天","可以删了吗？","不不不可以","嗯嗯","好的，别删那个","可以，顺便删全部").forEach{assertNull(it,OperationReply.parse(it))}
 }
 @Test fun newEnglishHotwordDoesNotOverwriteCustomizedVocabulary(){
  assertTrue(AsrHotwordRules.defaults.contains("assignment"))
  assertNull(AsrHotwordRules.error(AsrHotwordRules.defaults))
  assertEquals(AsrHotwordRules.defaults,AsrHotwordRules.upgrade(AsrHotwordRules.previousDefaults))
  assertEquals(listOf("自定义名字"),AsrHotwordRules.upgrade(listOf("自定义名字")))
 }
 @Test fun actualPhoneApprovalUsesReminderClockNotTheCourseStart(){
  assertEquals(setOf(day.plusDays(1).atTime(9,45)),AssistantConversationContext.approvedStartTimes("可以可以。",history,day))
  val entry=CalendarEntry(title="想assignment怎么写",start=day.plusDays(1).atTime(9,45),end=day.plusDays(1).atTime(9,46),reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=day.plusDays(1).atTime(9,45))
  AssistantRequestChecks.validate(AssistantAction.Event(entry),"可以可以。",history,JSONObject(),"完全控制",day,state)
  assertThrows(IllegalArgumentException::class.java){AssistantRequestChecks.validate(AssistantAction.Event(entry.copy(start=course.start,end=course.start.plusMinutes(1))),"可以可以。",history,JSONObject(),"完全控制",day,state)}
 }
 @Test fun departureReminderIsComputedFromRealCourseAndActuallyCreated(){
  val raw=JSONObject("""{"action":"event","timeReference":{"eventId":"english-am","anchor":"DEPARTURE","offsetMinutes":0},"entry":{"title":"想想assignment","kind":"TIMED","start":"2026-10-27T10:05","end":"2026-10-27T11:05","reminder":"ABSOLUTE","absolute":"2026-10-27T10:05"}}""")
  val text="把那个英语的。明天早上那节，嗯出发的时候提醒我想想assignment，别给我改课程备注"
  val action=AssistantIntent.referenceReminder(AssistantActions.decode(raw),text,state.entries,day) as AssistantAction.Event
  AssistantRequestChecks.validate(action,text,emptyList(),JSONObject(),"完全控制",day,state)
  assertEquals(day.plusDays(1).atTime(9,45),action.value.start)
  assertEquals(action.value.start,action.value.reminderTime())
  val plan=AssistantPlanner.build(state,action,day.atTime(20,0))
  assertEquals(2,plan.after.entries.size)
  assertEquals(course,plan.after.entries.first{it.id==course.id})
  assertTrue(plan.after.entries.any{it.id!=course.id&&it.reminderTime()==day.plusDays(1).atTime(9,45)})
 }
 @Test fun referenceMustBeRealAndRequested(){
  val entry=CalendarEntry(title="交材料",start=course.end)
  fun normalized(ref:AssistantTimeReference,text:String)=AssistantReferencedTime.normalize(AssistantAction.Event(entry,ref),text,state.entries,day)
  assertThrows(IllegalArgumentException::class.java){normalized(AssistantTimeReference("made-up","END"),"明天英语下课后提醒我")}
  assertThrows(IllegalArgumentException::class.java){normalized(AssistantTimeReference(course.id,"END"),"提醒交材料")}
  assertThrows(IllegalArgumentException::class.java){normalized(AssistantTimeReference(course.id,"END"),"后天英语下课后提醒我")}
  assertThrows(IllegalArgumentException::class.java){AssistantTimeReference.decode(JSONObject("""{"eventId":"english-am","anchor":"END","offsetMinutes":1.5}"""))}
 }
 @Test fun taskNameSearchAcceptsSpacedKeywordsButDoesNotChooseBetweenDuplicates(){
  assertTrue(AssistantRecordSearch.matches("准备英语课assignment","英语 assignment"))
  assertTrue(AssistantRecordSearch.matches("大英一 F02-211","英语"))
  assertFalse(AssistantRecordSearch.matches("数学作业","英语 assignment"))
  val t=CalendarEntry(title="准备英语assignment",kind=EventKind.TODO,start=day.atStartOfDay())
  val s=state.copy(entries=listOf(t,t.copy(id="second")))
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.build(s,AssistantAction.Change("delete","待办",t.title,JSONObject()))}
 }
 @Test fun damagedActionVerbGetsOneTargetedQuestionInsteadOfWrongCompletion(){
  val change=AssistantAction.Change("complete","待办","准备英语课assignment",JSONObject(),"tdl")
  val result=AssistantSpokenIntent.clarify(change,"首先把音乐 Simon 的那个 TDL 解了。英语 assignment。") as AssistantAction.Reply
  assertTrue(result.text.contains("删除"));assertTrue(result.text.contains("完成"))
  assertFalse(result.text.contains("准确名称"))
  assertEquals(change,AssistantSpokenIntent.clarify(change,"刚才那个TDL解了，意思是标为完成。"))
 }
 @Test fun correctedClockCannotBeOverriddenByOldCourseReference(){
  val target=course.start.toLocalDate().atTime(9,30)
  val event=AssistantAction.Event(CalendarEntry(title="想assignment",start=target,reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=target),AssistantTimeReference(course.id,"DEPARTURE",-60))
  val result=AssistantReferencedTime.normalize(event,spoken+"\n可以可以，不过别九点四十五，改成明天九点半。",state.entries,day) as AssistantAction.Event
  assertEquals(target,result.value.start);assertEquals(target,result.value.reminderTime());assertNull(result.timeReference)
 }
 @Test fun ambiguousReferencedCourseCannotBeSilentlyChosen(){
  val both=listOf(course,course.copy(id="afternoon",start=course.start.withHour(15),end=course.end.withHour(16)))
  val action=AssistantAction.Event(CalendarEntry(title="想assignment",start=course.start),AssistantTimeReference(course.id,"DEPARTURE"))
  assertThrows(IllegalArgumentException::class.java){AssistantReferencedTime.normalize(action,"明天英语出发的时候提醒我",both,day)}
  val resolved=AssistantReferencedTime.normalize(action,"明天早上英语出发的时候提醒我",both,day) as AssistantAction.Event
  assertEquals(course.start.minusMinutes(20),resolved.value.start)
  val deniedAfternoon=AssistantReferencedTime.normalize(action,"明天，英语课我说早上的那个啊，不是下午。要出发了那个时候提醒我。",both,day) as AssistantAction.Event
  assertEquals(course.start.minusMinutes(20),deniedAfternoon.value.start)
  val deniedDate=AssistantReferencedTime.normalize(action,"明天早上英语出发提醒，不是后天。",both,day) as AssistantAction.Event
  assertEquals(course.start.minusMinutes(20),deniedDate.value.start)
 }
 @Test fun linkedReminderDoesNotRequireASecondJourneyOrInventAnOffset(){
  val raw=JSONObject("""{"action":"event","timeReference":{"eventId":"english-am","anchor":"DEPARTURE"},"entry":{"title":"想assignment","start":"2026-10-27T09:45","end":"2026-10-27T10:45","reminder":"DEPARTURE","travel":0}}""")
  val action=AssistantReferencedTime.normalize(AssistantActions.decode(raw),"明天早上英语出发时提醒我",state.entries,day) as AssistantAction.Event
  assertEquals(0,action.value.travelMinutes);assertEquals(course.start.minusMinutes(20),action.value.reminderTime())
  assertThrows(IllegalArgumentException::class.java){AssistantReferencedTime.normalize(action.copy(timeReference=AssistantTimeReference(course.id,"DEPARTURE",-60)),"明天英语出发时提醒我",state.entries,day)}
 }
}

package cn.dapan.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class AssistantReasoningTest {
 @Test fun invalidOperationIsRepairedBeforeReturningAnyAction()=runBlocking{
  var attempts=0;var validations=0
  val action=AssistantReasoning.resolve(request={feedback->
   attempts++
   if(attempts==1)JSONObject("""{"action":"focus","minutes":900}""")
   else {assertTrue(feedback.contains("validation_error"));assertTrue(feedback.contains("\"executed\":false"));JSONObject("""{"action":"focus","minutes":25}""")}
  },lookup={error("not a lookup")},validate={validations++})
  assertEquals(2,attempts);assertEquals(1,validations)
  assertEquals(25,(action as AssistantAction.Focus).minutes)
 }
 @Test fun ambiguousTargetProducesQuestionWithoutExecuting()=runBlocking{
  var calls=0
  val action=AssistantReasoning.resolve(request={
   if(calls++==0)JSONObject("""{"action":"delete","module":"日历","target":"数学"}""")
   else {assertTrue(it.contains("两条"));JSONObject("""{"action":"chat","reply":"你要删除周一还是周三的数学？"}""")}
  },lookup={error("unexpected")},validate={if(it is AssistantAction.Change)throw IllegalArgumentException("匹配两条数学")})
  assertTrue(action is AssistantAction.Reply)
 }
 @Test fun repeatedLookupDoesNotReadOrLoopForever()=runBlocking{
  var reads=0;var calls=0
  val result=AssistantReasoning.resolve(request={calls++;JSONObject("""{"action":"lookup","module":"日历","query":"数学"}""")},
   lookup={reads++;"""{"items":[]}"""},validate={})
  assertEquals(1,reads);assertEquals(3,calls);assertTrue(result is AssistantAction.Reply)
 }
 @Test fun consecutiveLibraryPagesAreNotRejectedAsRepeatedLookup()=runBlocking{
  var calls=0;val offsets=mutableListOf<Int>()
  val result=AssistantReasoning.resolve(request={
   when(calls++){
    0->JSONObject("""{"action":"lookup","module":"资料库","query":"","id":"known","offset":0}""")
    1->JSONObject("""{"action":"lookup","module":"资料库","query":"","id":"known","offset":10000}""")
    else->JSONObject("""{"action":"chat","reply":"已读到第二段原文。"}""")
   }
  },lookup={offsets+=it.offset;"""{"text":"原文"}"""},validate={})
  assertEquals(listOf(0,10000),offsets);assertTrue(result is AssistantAction.Reply)
 }
 @Test fun invalidAttemptsStopAfterTwoRepairs()=runBlocking{
  var calls=0
  try{
   AssistantReasoning.resolve(request={calls++;JSONObject("""{"action":"focus","minutes":900}""")},lookup={""},validate={})
   fail("must not execute")
  }catch(_:IllegalArgumentException){assertEquals(3,calls)}
 }
 @Test fun networkFailureIsNotRetried()=runBlocking{
  var calls=0
  try{AssistantReasoning.resolve(request={calls++;throw java.io.IOException()},lookup={""},validate={});fail()}
  catch(_:java.io.IOException){assertEquals(1,calls)}
 }
 @Test fun catalogueRejectsUnknownToolsAndParameters(){
  val context=JSONObject("""{"pageOperations":{"天气":[{"op":"notification","fields":["period","time","enabled"]}]}}""")
  AssistantToolContract.validate(AssistantAction.Tool("天气","notification",JSONObject("""{"period":"晚间","time":"22:00"}""")),context)
  assertThrows(IllegalArgumentException::class.java){AssistantToolContract.validate(AssistantAction.Tool("天气","invent",JSONObject()),context)}
  assertThrows(IllegalArgumentException::class.java){AssistantToolContract.validate(AssistantAction.Tool("天气","notification",JSONObject("""{"secret":"bad"}""")),context)}
 }
 @Test fun recentScheduleAndTodosAreNotBuriedByFutureSemester(){
  val now=LocalDateTime.of(2026,10,20,8,0)
  val soon=CalendarEntry(title="今天课程",start=now.plusHours(1),end=now.plusHours(2))
  val todo=CalendarEntry(title="待办",kind=EventKind.TODO,start=now.minusDays(100))
  val far=(1..600).map{CalendarEntry(title="远期",start=now.plusDays(it.toLong()+20),end=now.plusDays(it.toLong()+20).plusHours(1))}
  val selected=AssistantRecordSelection.calendar(far+soon+todo,now)
  assertEquals(500,selected.size);assertTrue(soon in selected);assertTrue(todo in selected)
 }
}

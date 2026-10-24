package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class MorningBatchTest {
 private val today=LocalDate.of(2026,10,24)
 private val from=today.withDayOfMonth(1)
 private val original=AssistantSnapshot(emptyList(),emptyList(),listOf(
  CheckDay(from,null,true),CheckDay(today.withDayOfMonth(8),false,true),
  CheckDay(today.withDayOfMonth(12),false,false),CheckDay(today,true,true)),emptyList(),emptyList(),0,today.withDayOfMonth(15))
 private fun action()=AssistantAction.MorningRange(from,today,true,"workdays",true)
 private fun plan(a:AssistantAction=action(),state:AssistantSnapshot=original)=AssistantPlanner.build(state,a,today.atTime(8,14))
 private val utterance="我我们的晨签从10月1号开始。从10月1号到今天所有的日期你都给我设成。合格不包含休息日。"
 @Test fun screenshotRequestUpdatesStartWorkdaysAndRestDaysAtomically(){
  val result=plan()
  assertEquals(from,result.after.morningStart)
  val expected=setOf(8,9,10,12,13,14,15,16,19,20,21,22,23)
  assertEquals(expected,result.after.checks.filter{it.passed}.map{it.date.dayOfMonth}.toSet())
  assertTrue(result.after.checks.filter{it.date.dayOfMonth !in expected}.all{it.required==false&&!it.passed})
  val stats=MorningCalendar.stats(result.after.morningStart,null,today.atTime(8,14),LocalTime.of(7,30),result.after.checks,Holidays.days)
  assertEquals(MorningStats(13,13),stats)
  assertTrue(result.items.any{it.title=="晨签统计起点"})
 }
 @Test fun replayIsIdempotentAndUndoRestoresWrongPriorStateExactly(){
  val result=plan();assertTrue(plan(state=result.after).noOp)
  val undo=AssistantUndo.capture(result.before,result.after)!!
  assertEquals(original,AssistantUndo.fromJson(JSONObject(undo.json().toString())).plan(result.after).after)
  assertThrows(IllegalStateException::class.java){undo.plan(result.after.copy(morningStart=from.plusDays(1)))}
 }
 @Test fun undoPreservesUnrelatedNewSettingsAndRecords(){
  val result=plan();val later=CheckDay(today.minusMonths(1),true,true)
  val current=result.after.copy(checks=result.after.checks+later,morningEnd=today.plusDays(10))
  val reverted=AssistantUndo.capture(result.before,result.after)!!.plan(current).after
  assertEquals(original.checks.toSet()+later,reverted.checks.toSet())
  assertEquals(original.morningStart,reverted.morningStart)
  assertEquals(current.morningEnd,reverted.morningEnd)
 }
 @Test fun plainBackfillKeepsStartAndNotificationRange(){
  val result=plan(action().copy(setStart=false),original.copy(morningEnd=today))
  assertEquals(original.morningStart,result.after.morningStart);assertEquals(today,result.after.morningEnd)
 }
 @Test fun futureReversedAndUnverifiedHolidayYearsAreRejected(){
  assertThrows(IllegalArgumentException::class.java){plan(action().copy(through=today.plusDays(1)))}
  assertThrows(IllegalArgumentException::class.java){plan(action().copy(from=today,through=from))}
  assertThrows(IllegalArgumentException::class.java){plan(action().copy(from=from.minusYears(1)))}
 }
 @Test fun explicitAllIncludesHolidaysWhileRequiredPreservesExclusions(){
  assertEquals(24,plan(action().copy(dayScope="all")).after.checks.count{it.passed})
  val result=plan(action().copy(dayScope="required"))
  assertEquals(original.checks.first{it.date.dayOfMonth==8},result.after.checks.first{it.date.dayOfMonth==8})
 }
 @Test fun decoderRequiresScopeAndTypes(){
  val json=JSONObject("""{"action":"morning_range","from":"2026-10-01","through":"2026-10-24","passed":true,"dayScope":"workdays","setStart":true}""")
  assertEquals(action(),AssistantActions.decode(json))
  assertThrows(IllegalArgumentException::class.java){AssistantActions.decode(JSONObject(json.toString()).put("setStart","true"))}
  assertThrows(IllegalArgumentException::class.java){AssistantActions.decode(JSONObject(json.toString()).put("dayScope","weekday"))}
 }
 @Test fun originalSpeechCannotDropStartOrRestDayRuleOrDateRange(){
  fun check(a:AssistantAction)=AssistantRequestChecks.validate(a,utterance,emptyList(),JSONObject(),"完全控制",today)
  check(action())
  assertThrows(IllegalArgumentException::class.java){check(AssistantAction.Batch(listOf(AssistantAction.Morning(CheckDay(from,null,true)))))}
  assertThrows(IllegalArgumentException::class.java){check(action().copy(setStart=false))}
  assertThrows(IllegalArgumentException::class.java){check(action().copy(dayScope="all"))}
  assertThrows(IllegalArgumentException::class.java){check(action().copy(through=today.minusDays(1)))}
  assertThrows(IllegalArgumentException::class.java){check(action().copy(from=from.plusDays(1)))}
 }
 @Test fun spokenChineseDatesAndReadOnlyAreSupported(){
  val spoken=utterance.replace("10月1号","十月一号")
  AssistantRequestChecks.validate(action(),spoken,emptyList(),JSONObject(),"完全控制",today)
  assertThrows(IllegalArgumentException::class.java){AssistantRequestChecks.validate(action(),spoken,emptyList(),JSONObject(),"只读",today)}
  AssistantRequestChecks.validate(AssistantAction.Reply("只读模式不能修改。"),spoken,emptyList(),JSONObject(),"只读",today)
 }
 @Test fun duplicateDatesAcrossActionsCannotPartiallyCommit(){
  assertThrows(IllegalArgumentException::class.java){plan(AssistantAction.Batch(listOf(action(),AssistantAction.Morning(CheckDay(today,true,true)))))}
  assertThrows(IllegalArgumentException::class.java){plan(AssistantAction.Batch(listOf(action(),AssistantAction.Morning(CheckDay(today,true,true)))),plan().after)}
 }
 @Test fun oldUndoWithoutMorningSettingsKeepsCurrentStart(){
  val old=original.copy(morningStart=null);val after=old.copy(checks=emptyList())
  val json=AssistantUndo.capture(old,after)!!.json()
  json.getJSONObject("before").remove("morningStart");json.getJSONObject("after").remove("morningStart")
  assertEquals(from,AssistantUndo.fromJson(json).plan(after.copy(morningStart=from)).after.morningStart)
 }
 @Test fun explicitlyMarkingSingleDayOverridesPriorExclusion(){
  val day=today.withDayOfMonth(8)
  assertEquals(CheckDay(day,true,true),plan(AssistantAction.Morning(CheckDay(day,null,true))).after.checks.first{it.date==day})
  assertEquals(CheckDay(day,false,false),plan(AssistantAction.Morning(CheckDay(day,false,true))).after.checks.first{it.date==day})
 }
}

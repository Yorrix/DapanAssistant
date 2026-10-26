package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AssistantCalendarBulkTest {
 private val first=LocalDate.of(2027,4,1)
 private fun entries(count:Int)=List(count){i->val start=first.plusDays(i.toLong()).atTime(10,0);CalendarEntry(id="entry-$i",title="实验课",start=start,end=start.plusHours(1),location="原教室",notes="带实验报告",reminderMode=ReminderMode.BEFORE,beforeMinutes=10)}
 private fun snapshot(events:List<CalendarEntry>)=AssistantSnapshot(events,emptyList(),emptyList(),listOf("课程"),emptyList(),0)
 private fun action(json:String)=AssistantActions.decode(JSONObject(json))
 @Test fun allMeansAllEvenAbove64Entries(){
  val data=snapshot(entries(139))
  val a=action("""{"action":"calendar_bulk","op":"update","scope":"all","titles":["实验课"],"patch":{"location":"A01-N303"}}""")
  val plan=AssistantPlanner.build(data,a,first.atStartOfDay())
  assertEquals(139,plan.operationCount)
  assertTrue(plan.after.entries.all{it.location=="A01-N303"&&it.notes=="带实验报告"})
  assertTrue(data.entries.all{it.location=="原教室"})
 }
 @Test fun weekdayAndInclusiveRangeAreAppliedLocally(){
  val data=snapshot(entries(28))
  val a=action("""{"action":"calendar_bulk","op":"update","scope":"date_range","from":"2027-04-01","through":"2027-04-15","titles":["实验课"],"weekdays":[4],"patch":{"location":"B01"}}""")
  val plan=AssistantPlanner.build(data,a,first.atStartOfDay())
  assertEquals(listOf(1,8,15),plan.after.entries.filter{it.location=="B01"}.map{it.start.dayOfMonth}.sorted())
 }
 @Test fun shiftKeepsDurationAndRelativeReminders(){
  val a=action("""{"action":"calendar_bulk","op":"update","scope":"all","titles":["实验课"],"shiftMinutes":-60}""")
  val plan=AssistantPlanner.build(snapshot(entries(80)),a,first.atStartOfDay())
  assertEquals(80,plan.operationCount)
  assertTrue(plan.after.entries.all{it.start.hour==9&&it.end.hour==10&&it.reminderTime()==it.start.minusMinutes(10)})
 }
 @Test fun missingTitlesCannotBeSilentlyDropped(){
  val a=action("""{"action":"calendar_bulk","op":"update","scope":"all","titles":["实验课","不存在的课程"],"patch":{"location":"B01"}}""")
  val plan=AssistantPlanner.build(snapshot(entries(2)),a,first.atStartOfDay())
  assertEquals(listOf("不存在的课程"),plan.unmatched)
 }
 @Test fun allScopeRequiresExplicitBatchIntent(){
  val a=action("""{"action":"calendar_bulk","op":"delete","scope":"all","titles":["实验课"]}""")
  assertThrows(IllegalArgumentException::class.java){AssistantRequestChecks.validate(a,"删除这节实验课",emptyList(),JSONObject(),"完全控制")}
  AssistantRequestChecks.validate(a,"把所有实验课都删掉",emptyList(),JSONObject(),"完全控制")
 }
 @Test fun refusesAmbiguousOrUnsupportedBulkPayload(){
  listOf(
   """{"action":"calendar_bulk","op":"update","scope":"all","titles":["实验课"],"patch":{"start":"2027-04-01T12:00"}}""",
   """{"action":"calendar_bulk","op":"delete","scope":"all","titles":["实验课"],"shiftMinutes":10}""",
   """{"action":"calendar_bulk","op":"update","scope":"all","titles":["实验课"],"weekdays":[8],"patch":{"location":"B01"}}"""
  ).forEach{assertThrows(IllegalArgumentException::class.java){action(it)}}
 }
}

package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AssistantPlannerTest {
 private val day=LocalDate.of(2026,10,16)
 private fun event(id:String,title:String)=CalendarEntry(id=id,title=title,start=day.atTime(8,0),end=day.atTime(9,35),location="A01",category="课程",travelMinutes=20,reminderMode=ReminderMode.DEPARTURE)
 private fun snapshot(vararg entries:CalendarEntry)=AssistantSnapshot(entries.toList(),emptyList(),emptyList(),listOf("课程"),emptyList(),0)
 private fun rename(e:CalendarEntry,title:String)=AssistantAction.Change("update","日历",e.title,JSONObject().put("title",title),e.id)
 private fun bulk(json:String)=AssistantActions.decode(JSONObject(json))

 @Test fun unchangedCourseDoesNotAbortOtherRenames(){
  val pe=event("pe","体育");val en=event("en","大学英语1");val before=snapshot(pe,en)
  val plan=AssistantPlanner.build(before,AssistantAction.Batch(listOf(rename(pe,"体育"),rename(en,"大英一"))))
  assertEquals(1,plan.unchanged);assertEquals(1,plan.operationCount);assertTrue(plan.writes)
  assertEquals(pe,plan.after.entries.first{it.id=="pe"})
  assertEquals(en.copy(title="大英一"),plan.after.entries.first{it.id=="en"})
  assertEquals(listOf(pe,en),before.entries)
  assertTrue(plan.receipt().contains("1 项原本已符合"))
 }
 @Test fun replayingSameChangeIsInformationalAndDoesNotWrite(){
  val old=event("en","大学英语1");val first=AssistantPlanner.build(snapshot(old),rename(old,"大英一"))
  val replay=AssistantPlanner.build(first.after,rename(old,"大英一"))
  assertTrue(replay.noOp);assertFalse(replay.writes);assertEquals(first.after,replay.after)
  assertEquals(0,replay.operationCount);assertTrue(replay.receipt().startsWith("未改动数据"))
 }
 @Test fun actualInvalidPatchStillRejectsWholeBatch(){
  val pe=event("pe","体育1");val en=event("en","大学英语1");val before=snapshot(pe,en)
  val invalid=AssistantAction.Change("update","日历",en.title,JSONObject().put("end",day.atTime(7,0).toString()),en.id)
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.build(before,AssistantAction.Batch(listOf(rename(pe,"体育"),invalid)))}
  assertEquals(listOf(pe,en),before.entries)
 }
 @Test fun ambiguousSingleEditDoesNotSilentlyBecomeBulk(){
  val before=snapshot(event("1","体育"),event("2","体育"))
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.build(before,AssistantAction.Change("update","日历","体育",JSONObject().put("title","体育课")))}
 }
 @Test fun duplicateCompletionAndMorningRecordAreNoOps(){
  val todo=event("todo","交材料").copy(kind=EventKind.TODO,completed=true).normalized()
  val note=NoteItem(id="parcel",module="快递",title="1-2-3456",done=true)
  val check=CheckDay(day,true,true)
  val before=snapshot(todo).copy(notes=listOf(note),checks=listOf(check))
  val plan=AssistantPlanner.build(before,AssistantAction.Batch(listOf(
   AssistantAction.Change("complete","待办",todo.title,JSONObject(),todo.id),
   AssistantAction.Change("complete","快递",note.title,JSONObject(),note.id),AssistantAction.Morning(check))))
  assertTrue(plan.noOp);assertFalse(plan.writes);assertEquals(3,plan.unchanged);assertEquals(before,plan.after)
 }
 @Test fun bulkRenameHandlesWholeTermWithoutChangingOtherAttributes(){
  val entries=(0..119).map{i->event("en$i","大学英语1").copy(start=day.plusDays(i.toLong()).atTime(8,0),end=day.plusDays(i.toLong()).atTime(9,35))}
  val plan=AssistantPlanner.build(snapshot(*entries.toTypedArray()),bulk("""{"action":"rename_events","scope":"all","names":[{"from":"大学英语1","to":"大英一"}]}"""))
  assertEquals(120,plan.operationCount);assertEquals(1,plan.items.size)
  assertEquals(entries.map{it.copy(title="大英一")},plan.after.entries)
  assertTrue(plan.items.single().context.contains("120 条日程"))
 }
 @Test fun bulkDateRangeIsInclusiveAndOnlyTouchesExactTimedTitles(){
  val entries=listOf(event("1","英语"),event("2","英语").copy(start=day.plusDays(1).atTime(8,0),end=day.plusDays(1).atTime(9,35)),event("3","英语竞赛"),event("4","英语").copy(kind=EventKind.DEADLINE,travelMinutes=0,reminderMode=ReminderMode.OFF).normalized())
  val plan=AssistantPlanner.build(snapshot(*entries.toTypedArray()),bulk("""{"action":"rename_events","scope":"date_range","from":"2026-10-16","through":"2026-10-16","names":[{"from":"英语","to":"大英"}]}"""))
  assertEquals(1,plan.operationCount)
  entries.drop(1).forEach{assertEquals(it,plan.after.entries.first{row->row.id==it.id})}
 }
 @Test fun mappingsMatchOriginalSnapshotWithoutCascading(){
  val plan=AssistantPlanner.build(snapshot(event("1","A"),event("2","B")),AssistantAction.RenameEvents(listOf("A" to "B","B" to "C")))
  assertEquals("B",plan.after.entries.first{it.id=="1"}.title);assertEquals("C",plan.after.entries.first{it.id=="2"}.title)
 }
 @Test fun missingSourceIsReportedWithoutClaimingItWasRenamed(){
  val plan=AssistantPlanner.build(snapshot(event("1","体育")),AssistantAction.RenameEvents(listOf("体育1" to "体育")))
  assertTrue(plan.noOp);assertFalse(plan.writes);assertEquals(0,plan.unchanged);assertEquals(listOf("体育1"),plan.unmatched)
  assertTrue(plan.receipt().contains("未找到原名称：体育1"));assertFalse(plan.receipt().contains("已执行"))
 }
 @Test fun invalidOrAmbiguousBulkScopesAreRejected(){
  listOf(
   """{"action":"rename_events","names":[{"from":"A","to":"B"}]}""",
   """{"action":"rename_events","scope":"all","from":"2026-10-16","names":[{"from":"A","to":"B"}]}""",
   """{"action":"rename_events","scope":"date_range","from":"2026-10-17","through":"2026-10-16","names":[{"from":"A","to":"B"}]}""",
   """{"action":"rename_events","scope":"all","names":[{"from":"A","to":"B"},{"from":"A","to":"C"}]}"""
  ).forEach{assertThrows(Exception::class.java){bulk(it)}}
 }
 @Test fun metadataEditAllowsAlreadyElapsedDepartureButNewInvalidReminderFails(){
  val now=LocalDateTime.of(2026,10,16,7,50);val old=event("1","英语")
  AssistantPlanner.validateReminderChange(old,old.copy(title="大英"),now)
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.validateReminderChange(old,old.copy(travelMinutes=30),now)}
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.validateReminderChange(null,old,now)}
 }
}

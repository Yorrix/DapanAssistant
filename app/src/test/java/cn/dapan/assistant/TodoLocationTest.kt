package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class TodoLocationTest {
 private val time=LocalDateTime.of(2026,10,23,8,0)
 private fun todo()=CalendarEntry(id="todo",title="交材料",kind=EventKind.TODO,start=time,
  location="G01",notes="带玉兰卡",category="作业",urgent=true).normalized()
 private fun snapshot(vararg entries:CalendarEntry)=AssistantSnapshot(entries.toList(),emptyList(),emptyList(),listOf("作业"),emptyList(),0)

 @Test fun normalizationAndStoredRoundTripPreserveLocation(){
  val normalized=todo().copy(travelMinutes=20,reminderMode=ReminderMode.DEPARTURE).normalized()
  assertEquals("G01",normalized.location)
  assertEquals(0,normalized.travelMinutes)
  assertEquals(ReminderMode.OFF,normalized.reminderMode)
  assertNull(normalized.reminderTime())
  assertEquals(normalized,entryFromJson(JSONObject(entryToJson(normalized).toString())).normalized())
 }

 @Test fun assistantCreatesUndatedTodoWithLocationInPreviewAndReceipt(){
  val action=AssistantActions.decode(JSONObject("""{"action":"event","entry":{"title":"交材料","kind":"TODO","location":"G01","notes":"带玉兰卡","urgent":true}}"""))
  val plan=AssistantPlanner.build(snapshot(),action)
  val saved=plan.after.entries.single()
  assertEquals(EventKind.TODO,saved.kind)
  assertEquals("G01",saved.location)
  assertEquals("带玉兰卡",saved.notes)
  assertNull(saved.reminderTime())
  assertEquals("G01",plan.items.single().after["地点"])
  assertFalse(plan.items.single().after.containsKey("时间"))
  assertTrue(plan.receipt().contains("地点：G01"))
 }

 @Test fun legacyNoteActionDoesNotDiscardStructuredLocation(){
  val action=AssistantActions.decode(JSONObject("""{"action":"note","module":"待办","title":"交材料","location":"G01","detail":"带玉兰卡","group":"紧急","category":"作业"}"""))
  val saved=AssistantPlanner.build(snapshot(),action).after.entries.single()
  assertEquals(EventKind.TODO,saved.kind)
  assertEquals("G01",saved.location)
  assertEquals("带玉兰卡",saved.notes)
  assertEquals("作业",saved.category)
  assertTrue(saved.urgent)
 }

 @Test fun updatingTodoLocationChangesOnlyLocationAndHasReadableDiff(){
  val before=todo()
  val action=AssistantActions.decode(JSONObject("""{"action":"update","module":"待办","targetId":"todo","patch":{"location":"H03"}}"""))
  val plan=AssistantPlanner.build(snapshot(before),action)
  assertEquals(before.copy(location="H03"),plan.after.entries.single())
  assertEquals(mapOf("地点" to "G01"),plan.items.single().before)
  assertEquals(mapOf("地点" to "H03"),plan.items.single().after)
 }

 @Test fun todoLocationCanBeClearedWithoutLosingNotes(){
  val before=todo()
  val patch=AssistantActions.todoPatch(JSONObject().put("location",""))
  assertEquals(before.copy(location=""),AssistantActions.patchEvent(before,patch))
 }

 @Test fun completingAndReopeningTodoRetainsLocation(){
  val before=todo()
  val completed=AssistantPlanner.build(snapshot(before),AssistantAction.Change("complete","待办",before.title,JSONObject(),before.id))
  assertEquals(before.copy(completed=true),completed.after.entries.single())
  val reopened=AssistantPlanner.build(completed.after,AssistantAction.Change("update","待办",before.title,JSONObject().put("completed",false),before.id))
  assertEquals(before,reopened.after.entries.single())
 }

 @Test fun legacyTodoUpgradeKeepsLocationAfterFurtherEdits(){
  val old=NoteItem(id="legacy",module="待办",title="交材料",detail="带玉兰卡")
  val initial=snapshot().copy(notes=listOf(old))
  val located=AssistantPlanner.build(initial,AssistantAction.Change("update","待办",old.title,JSONObject().put("location","G01"),old.id))
  val renamed=AssistantPlanner.build(located.after,AssistantAction.Change("update","待办",old.title,JSONObject().put("title","交申请材料"),old.id))
  assertEquals(1,renamed.after.calendar.size)
  assertEquals("G01",renamed.after.calendar.single().location)
  assertEquals("交申请材料",renamed.after.calendar.single().title)
 }

 @Test fun locationSupportDoesNotIntroduceTodoTimeOrTravel(){
  listOf("start" to time.toString(),"travel" to 20,"reminder" to "BEFORE").forEach{(key,value)->
   assertThrows(IllegalArgumentException::class.java){AssistantActions.todoPatch(JSONObject().put("location","G01").put(key,value))}
  }
 }
}

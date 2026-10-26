package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class TodoDeferredTest {
 private val now=LocalDateTime.of(2026,10,26,12,0)
 private fun todo()=CalendarEntry(id="task",title="整理资料",kind=EventKind.TODO,start=now,location="寝室",urgent=true).normalized()
 @Test fun storedAndLegacyTasksKeepTheirPriority(){
  val old=entryToJson(todo()).apply{remove("deferred")}
  assertEquals("紧急",entryFromJson(old).todoPriority)
  val paused=todo().copy(deferred=true).normalized()
  assertEquals("搁置",entryFromJson(entryToJson(paused)).todoPriority)
  assertFalse(paused.completed);assertFalse(paused.urgent)
  assertEquals("搁置",NoteItem(module="待办",title="资料",group="搁置").asTodo().todoPriority)
 }
 @Test fun aiPauseAndResumePreserveTheTask(){
  val old=todo()
  val paused=AssistantActions.patchEvent(old,JSONObject().put("deferred",true))
  assertEquals("搁置",paused.todoPriority);assertFalse(paused.completed)
  assertEquals(old.title,paused.title);assertEquals(old.location,paused.location)
  assertEquals("紧急",AssistantActions.patchEvent(paused,JSONObject().put("urgent",true)).todoPriority)
  val ordinary=AssistantActions.patchEvent(paused,AssistantActions.todoPatch(JSONObject().put("group","一般")))
  assertEquals("一般",ordinary.todoPriority)
  assertFalse(ordinary.deferred);assertFalse(ordinary.urgent)
 }
 @Test fun aiCreationAndReceiptKnowDeferred(){
  val action=AssistantActions.decode(JSONObject("""{"action":"event","entry":{"title":"整理资料","kind":"TODO","deferred":true}}"""))
  val before=AssistantSnapshot(emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),0)
  val plan=AssistantPlanner.build(before,action,now)
  assertTrue(plan.after.entries.single().deferred)
  assertEquals("搁置",plan.items.single().after["优先级"])
  assertTrue(TodayAgenda.choose(plan.after.entries,now).isEmpty())
  assertTrue(TodayAgenda.choose(listOf(todo().copy(urgent=false)),now).isNotEmpty())
 }
 @Test fun deferredCannotBeAppliedToATimedEvent(){
  assertThrows(IllegalArgumentException::class.java){AssistantActions.patchEvent(todo().copy(kind=EventKind.TIMED),JSONObject().put("deferred",true))}
 }
}

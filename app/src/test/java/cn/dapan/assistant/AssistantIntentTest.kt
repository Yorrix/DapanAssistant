package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class AssistantIntentTest {
 private fun event(notes:String="带玉兰卡")=AssistantAction.Event(CalendarEntry(title="体测",start=LocalDateTime.of(2026,10,24,8,0),end=LocalDateTime.of(2026,10,24,9,0),location="B01",notes=notes))
 private fun todo(title:String="带玉兰卡",notes:String="")=AssistantAction.Event(CalendarEntry(title=title,kind=EventKind.TODO,start=LocalDateTime.of(2026,10,24,8,0),notes=notes))
 @Test fun packingNoteIsNotAlsoAnUnrequestedTodo(){
  val schedule=event();val plan=AssistantAction.Batch(listOf(schedule,todo()))
  val result=AssistantIntent.normalize(plan,"明天8点去体测，然后带玉兰卡，去B01")
  assertEquals(schedule,result);assertEquals("B01",(result as AssistantAction.Event).value.location)
  assertEquals("带玉兰卡",result.value.notes)
 }
 @Test fun sameProtectionWorksBeforeHotwordHasCorrectedTheRecognition(){
  val schedule=event("带预览卡")
  assertEquals(schedule,AssistantIntent.normalize(AssistantAction.Batch(listOf(schedule,todo("带预览卡"))),"明天提醒我8点去体测。然后带预览卡。去B01地点。"))
 }
 @Test fun explicitSeparateTodoRemains(){
  val plan=AssistantAction.Batch(listOf(event(),todo()))
  listOf("体测带玉兰卡，另外给我单独建个带玉兰卡的待办","体测带玉兰卡，带玉兰卡单独提醒我一次","建立两个任务：体测，带玉兰卡").forEach{assertSame(plan,AssistantIntent.normalize(plan,it))}
 }
 @Test fun ExplicitlyRejectingASeparateTodoDoesNotPreserveADuplicate(){
  val schedule=event("带玉兰卡，穿运动鞋")
  assertEquals(schedule,AssistantIntent.normalize(AssistantAction.Batch(listOf(schedule,todo())),"体测带玉兰卡、穿运动鞋，不要给我单独建一个待办"))
 }
 @Test fun unrelatedWorkAndOtherChangesAreNotDropped(){
  val schedule=event();val shopping=todo("买水")
  val bill=AssistantAction.Note(NoteItem(module="账单",title="午饭",detail="18"))
  val result=AssistantIntent.normalize(AssistantAction.Batch(listOf(schedule,todo(),shopping,bill)),"体测带玉兰卡，然后买水，午饭18元记一下")
  assertEquals(listOf(schedule,shopping,bill),(result as AssistantAction.Batch).actions)
 }
 @Test fun longerOrDifferentTodoDetailsAreKept(){
  val plan=AssistantAction.Batch(listOf(event(),todo(notes="出门前请室友检查是否带齐")))
  assertSame(plan,AssistantIntent.normalize(plan,"体测带玉兰卡，出门前请室友检查是否带齐"))
  val another=AssistantAction.Batch(listOf(event("带玉兰卡和身份证"),todo()))
  assertSame(another,AssistantIntent.normalize(another,"体测带玉兰卡和身份证"))
 }
 @Test fun ambiguousOrUnstatedAssociationDoesNotLoseTasks(){
  val plan=AssistantAction.Batch(listOf(event(),event(),todo()))
  assertSame(plan,AssistantIntent.normalize(plan,"体测带玉兰卡"))
  val single=AssistantAction.Batch(listOf(event(),todo()))
  assertSame(single,AssistantIntent.normalize(single,"好的"))
 }
 @Test fun legacyNoteTodoAndWrittenPackingVariantsAreSupported(){
  val schedule=event("带上玉兰卡。穿运动鞋")
  val note=AssistantAction.Note(NoteItem(module="待办",title="携带玉兰卡",detail="穿运动鞋"))
  assertEquals(schedule,AssistantIntent.normalize(AssistantAction.Batch(listOf(schedule,note)),"体测携带玉兰卡，穿运动鞋"))
 }
 @Test fun singleReminderDoesNotAlsoCreateDuplicateTodo(){
  val reminder=AssistantAction.Event(event("").value.copy(title="交申请提醒",reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=event().value.start))
  val batch=AssistantAction.Batch(listOf(todo("交申请"),reminder))
  assertEquals(reminder,AssistantIntent.normalize(batch,"今天沟通结束后提醒我交申请"))
  assertSame(batch,AssistantIntent.normalize(batch,"今天沟通结束后提醒我交申请，并单独建一个待办"))
  val detailed=AssistantAction.Batch(listOf(todo("交申请","还需打印两份"),reminder))
  assertSame(detailed,AssistantIntent.normalize(detailed,"今天沟通结束后提醒我交申请"))
 }
 @Test fun noOpRenameCannotSubstituteForARequestedLocationChange(){
  assertThrows(IllegalArgumentException::class.java){AssistantIntent.normalize(AssistantAction.RenameEvents(listOf("成设" to "成设")),"把所有成设的地点都改成A01-N303")}
  val rename=AssistantAction.RenameEvents(listOf("成设" to "程设","体育" to "体育"))
  assertSame(rename,AssistantIntent.normalize(rename,"成设改程设，体育不用变"))
 }
}

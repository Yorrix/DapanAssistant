package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AssistantUndoTest {
 @Test fun briefRepliesHandleConfirmationButNeverSwallowCorrections(){
  assertEquals(OperationReply.APPROVE,OperationReply.parse("对，没有问题。"))
  assertEquals(OperationReply.REJECT,OperationReply.parse("取消这次操作"))
  assertEquals(OperationReply.UNDO,OperationReply.parse("撤销刚才的修改。"))
  assertNull(OperationReply.parse("可以，但是只改今天的"))
  assertNull(OperationReply.parse("不要取消，改成九点"))
  assertNull(OperationReply.parse("确认一下明天有没有课"))
 }
 private fun event(id:String,title:String)=CalendarEntry(id=id,title=title,start=LocalDate.of(2026,10,16).atTime(8,0),location="A01")
 private fun snapshot(vararg entries:CalendarEntry)=AssistantSnapshot(entries.toList(),emptyList(),emptyList(),emptyList(),emptyList(),0)
 @Test fun renameUndoPreservesUnrelatedLaterEdits(){
  val old=event("1","英语");val other=event("2","体育");val before=snapshot(old,other);val after=snapshot(old.copy(title="大英"),other)
  val undo=AssistantUndo.capture(before,after)!!
  val newer=after.copy(entries=after.entries.map{if(it.id=="2")it.copy(location="操场")else it}+event("3","新日程"))
  val reverted=undo.plan(newer)
  assertEquals(old,reverted.after.entries.first{it.id=="1"})
  assertEquals("操场",reverted.after.entries.first{it.id=="2"}.location)
  assertTrue(reverted.after.entries.any{it.id=="3"});assertTrue(reverted.undoing)
 }
 @Test fun changedTargetBlocksUndoInsteadOfOverwriting(){
  val old=event("1","英语");val after=old.copy(title="大英");val undo=AssistantUndo.capture(snapshot(old),snapshot(after))!!
  assertThrows(IllegalStateException::class.java){undo.plan(snapshot(after.copy(location="A02")))}
  assertThrows(IllegalStateException::class.java){undo.plan(snapshot())}
 }
 @Test fun creationAndDeletionBothUndoAndRejectIdReuse(){
  val e=event("1","课程")
  assertTrue(AssistantUndo.capture(snapshot(),snapshot(e))!!.plan(snapshot(e)).after.entries.isEmpty())
  val deletion=AssistantUndo.capture(snapshot(e),snapshot())!!
  assertEquals(listOf(e),deletion.plan(snapshot()).after.entries)
  assertThrows(IllegalStateException::class.java){deletion.plan(snapshot(e.copy(title="其他")))}
 }
 @Test fun mixedNoteAndMorningUndoRoundTripsWithoutUnrelatedPrivateData(){
  val day=LocalDate.of(2026,10,16);val note=NoteItem(id="bill",module="账单",title="午饭",detail="18",created=1000)
  val before=snapshot(event("untouched","别的事项")).copy(checks=listOf(CheckDay(day,true,false)))
  val after=before.copy(notes=listOf(note),checks=listOf(CheckDay(day,true,true)))
  val captured=AssistantUndo.capture(before,after)!!
  assertTrue(captured.before.entries.isEmpty());assertFalse(captured.json().toString().contains("别的事项"))
  val restored=AssistantUndo.fromJson(JSONObject(captured.json().toString())).plan(after).after
  assertEquals(before,restored)
 }
 @Test fun newerFocusCannotBeUndoneByOldCard(){
  val before=snapshot();val after=before.copy(focusEnd=System.currentTimeMillis()+60000)
  val undo=AssistantUndo.capture(before,after)!!
  assertEquals(0L,undo.plan(after).after.focusEnd)
  assertThrows(IllegalStateException::class.java){undo.plan(after.copy(focusEnd=after.focusEnd+10000))}
 }
 @Test fun storedDoneCardRetainsUndoButPendingExpiresOnRestart(){
  val before=snapshot(event("1","英语"));val after=snapshot(event("1","大英"));val undo=AssistantUndo.capture(before,after)
  val done=AssistantOperation(OperationStatus.DONE,"修改 1 项",listOf(OperationPreview("修改","英语",mapOf("名称" to "英语"),mapOf("名称" to "大英"))),undo=undo)
  val restored=AssistantOperation.fromJson(JSONObject(done.json().toString()))
  assertEquals(done,restored);assertNotNull(restored.undo)
  val expired=AssistantOperation.fromJson(done.copy(status=OperationStatus.PENDING,undo=null).json())
  assertEquals(OperationStatus.EXPIRED,expired.status)
  assertTrue(ChatLine("assistant","等待批准",operation=expired).modelText.contains("旧清单不能继续执行"))
 }
}

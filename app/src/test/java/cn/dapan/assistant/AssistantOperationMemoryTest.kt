package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AssistantOperationMemoryTest {
 private fun event(id:String,title:String)=CalendarEntry(id=id,title=title,start=LocalDate.of(2026,10,26).atTime(8,0),location="B01")
 private fun state(vararg events:CalendarEntry)=AssistantSnapshot(events.toList(),emptyList(),emptyList(),emptyList(),emptyList(),0)
 private fun operation(before:AssistantSnapshot,after:AssistantSnapshot):AssistantOperation =
  AssistantOperation.fromPlan(AssistantPlan(before,after,listOf(OperationPreview("修改","测试事项"))),OperationStatus.DONE,AssistantUndo.capture(before,after))
 @Test fun createdEventCanBeLocatedAfterSwitchingPageAndSeveralChatTurns(){
  val e=event("real-event-id","体测");val op=operation(state(),state(e))
  val lines=listOf(ChatLine("user","明天8点去体测"),ChatLine("assistant","已添加",operation=op))+List(30){ChatLine("assistant","别的聊天 $it")}
  val request=AiClient.requestBody("model","刚才那个加上带玉兰卡",history=lines).getJSONArray("messages").toString()
  assertTrue(request.contains("real-event-id"));assertTrue(request.contains("B01"));assertTrue(request.contains("lookup"))
 }
 @Test fun memorySurvivesUndoRetentionAndRestart(){
  val old=event("english-1","大学英语");val op=operation(state(old),state(old.copy(title="大英"))).copy(undo=null)
  val restored=AssistantOperation.fromJson(JSONObject(op.json().toString()))
  val receipt=AssistantOperationMemory.receipt(ChatLine("assistant","已修改",operation=restored))
  val row=receipt.getJSONArray("records").getJSONObject(0)
  assertEquals("english-1",row.getString("id"));assertEquals("大学英语",row.getJSONObject("before").getString("title"));assertEquals("大英",row.getJSONObject("after").getString("title"))
  assertFalse(receipt.getBoolean("canUndo"))
 }
 @Test fun undoneAndFailedPlansAreNeverPresentedAsExecuted(){
  val old=event("1","成设");val op=operation(state(old),state(old.copy(title="程设")))
  listOf(OperationStatus.UNDONE,OperationStatus.FAILED,OperationStatus.PENDING,OperationStatus.EXPIRED).forEach{status->
   val line=ChatLine("assistant","过期的成功文案",operation=op.copy(status=status,undo=null))
   val receipt=AssistantOperationMemory.receipt(line)
   assertEquals(status.name,receipt.getString("status"));assertFalse(receipt.getBoolean("canUndo"));assertFalse(line.modelText.contains("过期的成功文案"))
   if(status==OperationStatus.UNDONE)assertTrue(receipt.getString("stateHint").contains("恢复before"))
  }
 }
 @Test fun wholeBatchCanBeReadWithoutLosingTargetsBeyondPreview(){
  val before=state(*(1..149).map{event("id-$it","大学英语")}.toTypedArray())
  val after=before.copy(entries=before.entries.map{it.copy(title="大英")});val line=ChatLine("assistant","已修改149项",operation=operation(before,after),id="op-149")
  assertTrue(AssistantOperationMemory.receipt(line).getBoolean("recordsTruncated"))
  var offset=0;val found=mutableListOf<String>()
  do{
   val page=JSONObject(AssistantOperationMemory.lookup(listOf(line),line.id,"",offset,1600))
   found+=page.getJSONArray("records").objects().map{it.getString("id")}
   val next=if(page.isNull("nextOffset"))-1 else page.getInt("nextOffset");assertTrue(next==-1||next>offset);offset=next
  }while(offset>=0)
  assertEquals(149,found.size);assertEquals(149,found.distinct().size);assertEquals(before.entries.map{it.id}.toSet(),found.toSet())
 }
 @Test fun deletedAndDuplicateNamesRetainDistinctRecordIds(){
  val a=event("today","英语");val b=event("tomorrow","英语").copy(start=a.start.plusDays(1))
  val op=operation(state(a,b),state(b));val row=AssistantOperationMemory.receipt(ChatLine("assistant","已删",operation=op)).getJSONArray("records").getJSONObject(0)
  assertEquals("today",row.getString("id"));assertTrue(row.isNull("after"));assertEquals("deleted",row.getString("change"))
 }
 @Test fun emptyHistoryCannotInventPreviousOperations(){
  assertEquals(0,JSONObject(AssistantOperationMemory.context(emptyList())).getJSONArray("operations").length())
  assertTrue(JSONObject(AssistantOperationMemory.lookup(emptyList(),"missing","",0,1000)).has("error"))
 }
 @Test fun oldVersionUndoStillProvidesRecordIdentity(){
  val a=event("legacy","旧名称");val op=operation(state(a),state(a.copy(title="新名称"))).copy(memory="")
  assertEquals("legacy",AssistantOperationMemory.receipt(ChatLine("assistant","旧回执",operation=op)).getJSONArray("records").getJSONObject(0).getString("id"))
 }
 @Test fun unmodifiedPrivateNotesAreNotCopiedIntoReceipts(){
  val a=event("event","事情").copy(notes="无关的私密长备注");val op=operation(state(a),state(a.copy(location="A01")))
  assertFalse(op.memory.contains("无关的私密长备注"));assertTrue(op.memory.contains("A01"))
 }
 @Test fun explicitNoteAdditionsPreserveBothNewAndExistingInformation(){
  val old=event("e","体测").copy(notes="带玉兰卡");val text="刚才那个再加上备注：穿运动鞋，其他都不变"
  fun change(notes:String)=AssistantAction.Change(operation="update",module="日历",target="",targetId="e",patch=JSONObject().put("notes",notes))
  assertThrows(IllegalArgumentException::class.java){AssistantNoteAppend.validate(change("穿玉兰卡"),text,state(old))}
  assertThrows(IllegalArgumentException::class.java){AssistantNoteAppend.validate(change("穿运动鞋"),text,state(old))}
  AssistantNoteAppend.validate(change("带玉兰卡；穿运动鞋"),text,state(old))
  AssistantNoteAppend.validate(change("穿运动鞋"),"备注改成穿运动鞋",state(old))
 }
 @Test fun classroomParametersAreUsableAndStrict(){
  AssistantToolArguments.validate("课堂助手","configure",JSONObject("""{"level":4,"mode":"讨论发言"}"""))
  listOf("""{"level":4.5}""","""{"level":8}""","""{"level":"4"}""","""{"mode":"汇报"}""").forEach{json->assertThrows(IllegalArgumentException::class.java){AssistantToolArguments.validate("课堂助手","configure",JSONObject(json))}}
  assertThrows(IllegalArgumentException::class.java){AssistantToolArguments.validate("课堂助手","add",JSONObject())}
 }
}

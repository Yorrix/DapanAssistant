package cn.dapan.assistant

import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AssistantBackupTest {
 private val e=CalendarEntry(id="local",title="课程",start=LocalDateTime.of(2026,10,14,8,0),travelMinutes=20,reminderMode=ReminderMode.DEPARTURE)
 private fun backup()=JSONObject().put("version",1).put("entries",JSONArray()).put("notes",JSONArray()).put("checks",JSONArray())
 @Test fun aiCannotChooseStoredEventIdentity(){
  val action=AssistantActions.decode(JSONObject().put("action","event").put("entry",entryToJson(e))) as AssistantAction.Event
  assertNotEquals(e.id,action.value.id);assertEquals(e.start,action.value.start)
 }
 @Test fun rejectsUnsupportedActionAndNavigation(){
  listOf("""{"action":"shell","command":"anything"}""","""{"action":"navigate","module":"不存在"}""").forEach{raw->assertThrows(RuntimeException::class.java){AssistantActions.decode(JSONObject(raw))}}
 }
 @Test fun rejectsAfterStartReminderFromModel(){
  val invalid=e.copy(reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=e.start.plusMinutes(1))
  assertThrows(IllegalArgumentException::class.java){AssistantActions.decode(JSONObject().put("action","event").put("entry",entryToJson(invalid)))}
 }
 @Test fun changeCannotModifyIdsOrAttachments(){
  val input=JSONObject().put("action","update").put("module","待办").put("target","任务").put("patch",JSONObject().put("id","other"))
  assertThrows(IllegalArgumentException::class.java){AssistantActions.decode(input)}
 }
 @Test fun notePatchPreservesAttachmentAndIdentity(){
  val old=NoteItem(module="信息查询",title="证明",attachments=listOf("content://documents/123"))
  val result=AssistantActions.patchNote(old,JSONObject().put("title","新名称"))
  assertEquals(old.id,result.id);assertEquals(old.attachments,result.attachments)
 }
 @Test fun changingTimePreservesTravelAndDepartureReminder(){
  val newStart=e.start.plusHours(1)
  val result=AssistantActions.patchEvent(e,JSONObject().put("start",newStart.toString()).put("end",newStart.plusHours(2).toString()))
  assertEquals(20,result.travelMinutes);assertEquals(newStart.minusMinutes(20),result.reminderTime())
 }
 @Test fun rejectsInvalidAmountsAndPreservesExplicitFutureMarks(){
  assertThrows(IllegalArgumentException::class.java){AssistantActions.decode(JSONObject("""{"action":"note","module":"账单","title":"午饭","detail":"不知道"}"""))}
  val future=JSONObject().put("action","morning").put("date",LocalDate.now().plusDays(1).toString()).put("passed",true)
  assertEquals(LocalDate.now().plusDays(1),(AssistantActions.decode(future) as AssistantAction.Morning).day.date)
 }
 @Test fun searchMaySpecifyOnlyDate(){
  val action=AssistantActions.decode(JSONObject("""{"action":"search","date":"2026-10-14"}""")) as AssistantAction.Search
  assertEquals(LocalDate.of(2026,10,14),action.date)
 }
 @Test fun importRetainsCurrentRecordsAndSettings(){
  val incoming=backup().put("entries",JSONArray(listOf(entryToJson(e),entryToJson(e.copy(id="new",title="新增"))))).put("morningNotify",false).put("categories",JSONArray(listOf("自建")))
  val current=backup().put("entries",JSONArray(listOf(entryToJson(e.copy(title="本机修改"))))).put("morningNotify",true).put("categories",JSONArray(listOf("课程")))
  val merged=BackupPreview.parse(incoming.toString()).mergeInto(current)
  assertEquals(2,merged.getJSONArray("entries").length());assertEquals("本机修改",merged.getJSONArray("entries").getJSONObject(0).getString("title"))
  assertTrue(merged.getBoolean("morningNotify"));assertEquals(2,merged.getJSONArray("categories").length())
 }
 @Test fun importRejectsDuplicateIdsAndUnknownVersions(){
  val duplicate=backup().put("entries",JSONArray(listOf(entryToJson(e),entryToJson(e))))
  assertThrows(IllegalArgumentException::class.java){BackupPreview.parse(duplicate.toString())}
  assertThrows(IllegalArgumentException::class.java){BackupPreview.parse(backup().put("version",99).toString())}
 }
 @Test fun importRejectsUnsupportedAttachmentSchemes(){
  val note=JSONObject().put("id","x").put("title","附件").put("module","信息查询").put("attachments",JSONArray(listOf("file:///etc/passwd")))
  assertThrows(IllegalArgumentException::class.java){BackupPreview.parse(backup().put("notes",JSONArray(listOf(note))).toString())}
 }
}


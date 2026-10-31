package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ConversationFeedbackTest {
 private val installation="00000000-0000-4000-8000-000000000001"
 private fun payload(lines:List<ChatLine>)=ConversationFeedback.payload(lines,installation,"1.0.1",101,"Synthetic phone","16","日历")
 @Test fun visibleHistoryRetainedInOrderWithoutPrivateModelOrUndoData(){
  val secret=CalendarEntry(id="private-db-id",title="未公开日程",start=LocalDate.of(2026,10,27).atTime(8,0))
  val before=AssistantSnapshot(listOf(secret),emptyList(),emptyList(),emptyList(),emptyList(),0)
  val op=AssistantOperation(OperationStatus.DONE,"新增 1 项",listOf(OperationPreview("新增","体测",after=mapOf("地点" to "B01","备注" to "带玉兰卡"))),undo=AssistantUndo(before,before,false),memory="private memory",toolRequest="private arguments")
  val lines=listOf(ChatLine("user","明天去体测",updatedAt=1),ChatLine("assistant","已执行",operation=op,evidence="private evidence",updatedAt=2))
  val value=payload(lines);val text=value.toString()
  assertEquals(2,value.getJSONArray("messages").length())
  assertTrue(text.contains("B01"));assertTrue(text.contains("玉兰卡"));assertTrue(text.contains("已执行"))
  listOf("private-db-id","未公开日程","private memory","private arguments","private evidence","undo","includeInModel").forEach{assertFalse(it,text.contains(it))}
 }
 @Test fun sameSnapshotGetsSameReceiptIdButChangedHistoryDoesNot(){
  val lines=listOf(ChatLine("user","示例",updatedAt=10))
  assertEquals(payload(lines).getString("reportId"),payload(lines).getString("reportId"))
  assertNotEquals(payload(lines).getString("reportId"),payload(lines+ChatLine("assistant","新回复",updatedAt=20)).getString("reportId"))
 }
 @Test fun keysAreMaskedWithoutLosingSchoolWords(){
  val value=ConversationFeedback.redact("玉兰卡 B01 sk-abcdefghijklmnopqrstuv API_KEY: synthetic-token Bearer synthetic-access-token password=secret")
  assertTrue(value.contains("玉兰卡 B01"));assertTrue(value.contains("[已隐藏密钥]"))
  listOf("sk-abcdefghijklmnopqrstuv","synthetic-token","synthetic-access-token","secret").forEach{assertFalse(value.contains(it))}
 }
 @Test fun longOrEmptyHistoryIsRejectedWithoutSilentClipping(){
  assertThrows(IllegalArgumentException::class.java){payload(emptyList())}
  assertThrows(IllegalArgumentException::class.java){payload(List(101){ChatLine("user","test")})}
  assertThrows(IllegalArgumentException::class.java){payload(listOf(ChatLine("user","x".repeat(100001))))}
  assertThrows(IllegalArgumentException::class.java){payload(List(10){ChatLine("user","汉".repeat(50000))})}
 }
}

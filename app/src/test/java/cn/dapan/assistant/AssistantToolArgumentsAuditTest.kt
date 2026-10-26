package cn.dapan.assistant

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AssistantToolArgumentsAuditTest {
 private fun valid(module:String,op:String,json:String="{}")=AssistantToolArguments.validate(module,op,JSONObject(json))
 private fun invalid(module:String,op:String,json:String="{}"){
  try{valid(module,op,json);fail("Accepted invalid $module/$op: $json")}
  catch(e:Exception){assertTrue("Validation must give a repairable explanation",!e.message.isNullOrBlank())}
 }

 @Test fun wheelPartialUpdatesAndDefaultNameStayUsable(){
  valid("抽签","save","""{"id":"wheel-1","name":"谁值日"}""")
  valid("抽签","save","""{"id":"wheel-1","options":[{"name":"小明"},{"name":"小红","weight":2}]}""")
  valid("抽签","save","""{"options":[{"name":"小明"},{"name":"小红","weight":2}],"spinAfter":true}""")
  valid("抽签","save","""{"options":[{"name":"暂停","weight":0},{"name":"开始","weight":1}]}""")
  invalid("抽签","save","""{"name":"谁值日"}""")
  listOf("[]","[\"小明\"]","[{\"weight\":1}]","[{\"name\":\"\"}]","[{\"name\":\"甲\",\"weight\":-1}]","[{\"name\":\"甲\",\"weight\":0}]","[{\"name\":\"甲\",\"weight\":\"很多\"}]")
   .forEach{invalid("抽签","save","""{"options":$it}""")}
 }

 @Test fun selectedRecordingDefaultsAreNotMadeMandatory(){
  listOf("view","record_start","summarize","pause","summary_cancel","record_stop").forEach{valid("会议纪要",it)}
  valid("会议纪要","record_start","""{"folderId":"lecture-1"}""")
  valid("会议纪要","record_start","""{"title":"物理","kind":"课程"}""")
  valid("会议纪要","create","""{"title":"物理","kind":"课程"}""")
  invalid("会议纪要","create","""{"title":"物理"}""")
  invalid("会议纪要","create","""{"title":"物理","kind":"上课"}""")
 }

 @Test fun renameExportAndSelectionCannotLoseTheirTarget(){
  valid("会议纪要","rename","""{"folderId":"f","name":"新课名"}""")
  valid("会议纪要","rename","""{"clipId":"c","name":"第一节"}""")
  invalid("会议纪要","rename","""{"name":"第一节"}""")
  invalid("会议纪要","rename","""{"folderId":"f","clipId":"c","name":"第一节"}""")
  invalid("会议纪要","rename","""{"folderId":"f"}""")
  valid("会议纪要","export","""{"folderId":"f","format":"summary"}""")
  valid("会议纪要","export","""{"clipId":"c","format":"audio"}""")
  invalid("会议纪要","export","""{"folderId":"f","format":"audio"}""")
  invalid("会议纪要","export","""{"format":"transcript"}""")
  invalid("会议纪要","view","""{"all":true,"clipId":"c"}""")
  valid("会议纪要","view","""{"all":false,"clipId":"c"}""")
  valid("会议纪要","reorder","""{"folderId":"f","clipIds":[]}""")
  invalid("会议纪要","reorder","""{"folderId":"f","clipIds":["c","c"]}""")
 }

 @Test fun marketDraftDoesNotRequireInventedContactAndPublicationDoes(){
  valid("二手信息","compose","""{"kind":"sell","title":"出一本高数书"}""")
  valid("二手信息","compose","""{"kind":"buy","title":"收一本书","contacts":{}}""")
  valid("二手信息","draft","""{"contacts":{"微信":""}}""")
  valid("二手信息","publish") // existing editor consent is checked by its handler
  valid("二手信息","compose","""{"kind":"sell","title":"出书","contacts":{"QQ":"10001"},"publish":true,"consent":true}""")
  invalid("二手信息","compose","""{"kind":"sell","title":"出书","publish":true,"consent":true}""")
  invalid("二手信息","compose","""{"kind":"sell","title":"出书","contacts":{"QQ":"10001"},"publish":true}""")
  invalid("二手信息","compose","""{"kind":"sell","title":"出书","contacts":{"QQ":10001}}""")
  invalid("二手信息","draft","""{"contacts":{"邮箱":"a@example.com"}}""")
  invalid("二手信息","status","""{"id":"1","status":"active"}""")
  invalid("二手信息","detail","""{"id":"../me"}""")
 }

 @Test fun typesAreNotSilentlyCoercedIntoUnchangedDefaults(){
  val switches=listOf(Triple("天气","notification","enabled"),Triple("专注","configure","calls"),Triple("地图","heat_record","enabled"),Triple("课堂生词","known","known"),Triple("设置","pet","autoHide"))
  switches.forEach{(module,op,key)->
   listOf("\"关闭\"","1","null","{}").forEach{value->
    val word=if(module=="课堂生词")",\"word\":\"tensor\""else""
    invalid(module,op,"{\"$key\":$value$word}")
   }
  }
  valid("天气","notification","""{"enabled":false}""")
  valid("设置","pet","""{"autoHide":false}""")
  valid("课堂生词","known","""{"word":"tensor"}""")
  invalid("专注","configure","""{"minutes":"二十五"}""")
  invalid("专注","configure","""{"minutes":25.5}""")
  invalid("地图","route_select","""{"index":1.5}""")
  invalid("资料库","edit","""{"id":"f","name":false}""")
 }

 @Test fun explicitClearsAndOptionalDefaultsRemainValid(){
  valid("地图","route","""{"from":"","to":""}""")
  valid("地图","route","""{"to":"F02"}""")
  valid("地图","route_select")
  valid("晨签","configure","""{"start":"","end":""}""")
  valid("资料库","edit","""{"id":"f","remark":""}""")
  valid("官网通知","filter","""{"sources":[]}""")
  valid("今天","widgets","""{"enabled":[]}""")
  valid("实时翻译","configure","""{"translationHint":"","asrContext":""}""")
  valid("专注","configure","""{"notifications":false}""")
  valid("天气","notification","""{"period":"晚间","time":"22:00"}""")
  valid("会议纪要","edit_line",JSONObject().put("clipId","c").put("startMs",0).put("text","字".repeat(6000)).toString())
 }

 @Test fun datesEnumsAndRangesAreCheckedBeforePageNavigation(){
  invalid("账单","month");invalid("晨签","month")
  invalid("账单","month","""{"month":"2026-13"}""")
  invalid("日历","view","""{"date":"2026-02-30"}""")
  invalid("晨签","configure","""{"start":"2026-10-20","end":"2026-10-01"}""")
  invalid("天气","notification","""{"time":"25:30"}""")
  invalid("地图","heat_view","""{"range":"七天"}""")
  invalid("地图","view","""{"zoom":8}""")
  invalid("实时翻译","configure","""{"from":"英语","to":"英语"}""")
  invalid("实时翻译","configure","""{"to":"en"}""")
  invalid("手持弹幕","configure","""{"foreground":"#FFF"}""")
  invalid("抽签","configure","""{"customMs":99}""")
  invalid("课堂生词","configure","""{"level":"大学"}""")
  valid("手持弹幕","configure","""{"foreground":"#00FF00","size":240,"shake":0}""")
  valid("设置","theme","""{"shape":"纸页","palette":"大工蓝","mode":"dark"}""")
  invalid("设置","theme","""{"shape":"纸页","style":"GEEK"}""")
  invalid("日历","category_delete","""{"name":" 课程 "}""")
 }

 @Test fun arrayElementsAndEmptyContainersCannotMasqueradeAsRequiredFields(){
  invalid("资料库","save_attachments","""{"ids":[]}""")
  invalid("资料库","save_attachments","""{"ids":["f","f"]}""")
  invalid("资料库","save_attachments","""{"ids":[12]}""")
  invalid("官网通知","filter","""{"sources":[{}]}""")
  invalid("今天","widgets","""{"enabled":true}""")
  invalid("今天","widgets","""{"order":[]}""")
  invalid("今天","widgets","""{"order":["天气","天气"]}""")
  invalid("今天","widgets","""{"enabled":["不存在"]}""")
  valid("今天","widgets","""{"order":["天气","日程"],"noticeScope":"FOLLOWED"}""")
  valid("资料库","save_attachments","""{"ids":["f1","f2"]}""")
 }

 @Test fun everyGeneratedPageOperationHasAUsableArgumentShape(){
  // A fixture per operation catches new catalogue fields accidentally treated as required.
  val fixtures=mapOf(
   "课堂助手/configure" to """{"mode":"讨论发言","level":4}""", "课堂助手/add" to """{"text":"同学观点"}""",
   "tibo雷达/notification" to """{"enabled":false}""",
   "今天/widgets" to """{"enabled":[]}""",
   "账单/month" to """{"month":"2026-10"}""", "晨签/month" to """{"month":"2026-10"}""",
   "天气/notification" to """{"enabled":false}""",
   "手持弹幕/configure" to """{"text":"你好"}""", "专注/configure" to """{"minutes":25}""",
   "抽签/save" to """{"options":[{"name":"甲"}]}""", "抽签/select" to """{"id":"w"}""", "抽签/delete" to """{"id":"w"}""", "抽签/configure" to """{"pointer":true}""",
   "官网通知/follow" to """{"source":"pjxq.dlut.edu.cn","enabled":true}""", "官网通知/select" to """{"id":"n"}""", "官网通知/open" to """{"id":"n"}""",
   "实时翻译/configure" to """{"original":false}""", "实时翻译/translate" to """{"text":"hello"}""",
   "课堂生词/configure" to """{"level":"四级"}""", "课堂生词/known" to """{"word":"tensor"}""",
   "地图/heat_record" to """{"enabled":false}""",
   "会议纪要/configure" to """{"language":"英语"}""", "会议纪要/create" to """{"title":"物理课","kind":"课程"}""",
   "会议纪要/play" to """{"clipId":"c"}""", "会议纪要/rename" to """{"folderId":"f","name":"物理课"}""",
   "会议纪要/reorder" to """{"folderId":"f","clipIds":[]}""", "会议纪要/transcribe" to """{"clipId":"c"}""",
   "会议纪要/edit_line" to """{"clipId":"c","startMs":0,"text":"修正"}""", "会议纪要/restore_text" to """{"clipId":"c"}""",
   "会议纪要/export" to """{"folderId":"f","format":"transcript"}""", "会议纪要/delete_folder" to """{"folderId":"f"}""", "会议纪要/delete_clip" to """{"clipId":"c"}""",
   "二手信息/detail" to """{"id":"123"}""", "二手信息/compose" to """{"kind":"buy","title":"收书"}""",
   "二手信息/status" to """{"id":"123","status":"sold"}""", "二手信息/delete" to """{"id":"123"}""",
   "资料库/select" to """{"id":"f"}"""
  )
  val catalogue=AssistantCatalogue.json()
  var count=0
  catalogue.keys().forEach{module->catalogue.getJSONArray(module).objects().forEach{tool->
   val op=tool.getString("op");val json=JSONObject(fixtures["$module/$op"]?:"{}")
   try{AssistantToolContract.validate(AssistantAction.Tool(module,op,json),JSONObject().put("pageOperations",catalogue))}
   catch(e:Exception){throw AssertionError("Catalogue operation $module/$op rejected fixture $json",e)}
   count++
  }}
  assertTrue("Audit should cover all generated operations, not only calendar",count>=68)
 }

 @Test fun attachmentIdentityIsStableAndSeparateFromContentIdentity(){
  val first=LibraryStore.attachmentDocumentId("attachment-a")
  assertEquals(first,LibraryStore.attachmentDocumentId("attachment-a"))
  assertNotEquals("attachment-a",first)
  assertNotEquals(first,LibraryStore.attachmentDocumentId("attachment-b"))
  assertEquals(100,(0 until 100).map{LibraryStore.attachmentDocumentId("attachment-$it")}.distinct().size)
  assertThrows(IllegalArgumentException::class.java){LibraryStore.attachmentDocumentId("")}
 }

 @Test fun partialLibraryReceiptPreservesRealIdsAndFailureScope(){
  val doc=LibraryDocument("persistent-id","说明.txt","text/plain","/unused",20,1,"","真实正文","正文已索引","测试")
  val report=LibraryAttachmentSaveReport(
   listOf(LibrarySavedAttachment("temporary-id",doc,true)),
   listOf(LibraryAttachmentFailure("missing-id","丢失.txt","原附件已失效"))
  )
  val evidence=JSONObject(report.evidence())
  assertEquals(OperationStatus.PARTIAL,AssistantToolOutcome.status(report.evidence()))
  assertEquals(1,evidence.getInt("successCount"));assertEquals(1,evidence.getInt("failedCount"))
  val saved=evidence.getJSONArray("savedFiles").getJSONObject(0)
  assertEquals("persistent-id",saved.getString("id"));assertEquals("temporary-id",saved.getString("attachmentId"))
  assertTrue(saved.getBoolean("alreadySaved"))
  val failure=evidence.getJSONArray("failedFiles").getJSONObject(0)
  assertEquals("missing-id",failure.getString("attachmentId"));assertFalse(failure.has("id"))
  assertTrue(report.receipt().contains("部分完成"));assertTrue(report.receipt().contains("1 个未保存"));assertTrue(report.receipt().contains("未重复添加"))
  val card=AssistantOperation(OperationStatus.PARTIAL,"收纳 2 个文件",emptyList(),detail=report.receipt())
  assertEquals(OperationStatus.PARTIAL,AssistantOperation.fromJson(card.json()).status)
 }

 @Test fun allFailedLibrarySaveCannotReturnSuccessReceipt(){
  val report=LibraryAttachmentSaveReport(emptyList(),listOf(LibraryAttachmentFailure("missing-id","丢失.txt","原附件已失效")))
  assertEquals(OperationStatus.FAILED,AssistantToolOutcome.status(report.evidence()))
  assertEquals(0,JSONObject(report.evidence()).getJSONArray("savedFiles").length())
  assertThrows(IllegalStateException::class.java){report.receipt()}
  val doc=LibraryDocument("persistent-id","说明.txt","text/plain","/unused",20,1,"","真实正文","正文已索引","测试")
  val success=LibraryAttachmentSaveReport(listOf(LibrarySavedAttachment("temporary-id",doc,false)),emptyList())
  assertEquals(OperationStatus.DONE,AssistantToolOutcome.status(success.evidence()))
  assertFalse(success.receipt().contains("部分完成"))
 }
}

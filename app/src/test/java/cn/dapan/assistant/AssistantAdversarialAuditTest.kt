package cn.dapan.assistant

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime

/** Adversarial contracts: no silent field loss, no stale callback, no partial write. */
class AssistantAdversarialAuditTest {
 private val day=LocalDate.of(2026,9,21)
 private fun event()=CalendarEntry(id="target",title="体测",start=day.atTime(8,0),end=day.atTime(9,0),location="B01",travelMinutes=20,reminderMode=ReminderMode.DEPARTURE)
 private fun state(e:CalendarEntry=event())=AssistantSnapshot(listOf(e),emptyList(),emptyList(),listOf("课程"),emptyList(),0)
 private fun decoded(json:String)=AssistantActions.decode(JSONObject(json))

 @Test fun completeCannotSilentlyDiscardLocationPatch(){
  assertThrows(IllegalArgumentException::class.java){decoded("""{"action":"complete","module":"待办","targetId":"target","patch":{"location":"F02"}}""")}
 }
 @Test fun deleteCannotSilentlyDiscardEditPatch(){
  assertThrows(IllegalArgumentException::class.java){decoded("""{"action":"delete","module":"日历","targetId":"target","patch":{"title":"只是改名"}}""")}
 }
 @Test fun malformedTravelMustNotSilentlyClearExistingDepartureTravel(){
  assertThrows(IllegalArgumentException::class.java){AssistantActions.patchEvent(event(),JSONObject().put("travel","20分钟"))}
 }
 @Test fun fractionalTravelMustNotBeTruncated(){
  assertThrows(IllegalArgumentException::class.java){AssistantActions.patchEvent(event(),JSONObject().put("travel",2.5))}
 }
 @Test fun invalidUrgencyMustNotSilentlyMakeUrgentTodoOrdinary(){
  val todo=event().copy(kind=EventKind.TODO,urgent=true).normalized()
  assertThrows(IllegalArgumentException::class.java){AssistantActions.patchEvent(todo,JSONObject().put("urgent","maybe"))}
 }
 @Test fun fractionalFocusMinutesMustNotBeTruncated(){
  assertThrows(IllegalArgumentException::class.java){decoded("""{"action":"focus","minutes":1.5}""")}
 }
 @Test fun oversizedMinuteValuesCannotWrapAroundToValidDurations(){
  assertThrows(IllegalArgumentException::class.java){decoded("""{"action":"focus","minutes":4294967297}""")}
  assertThrows(IllegalArgumentException::class.java){AssistantActions.patchEvent(event(),JSONObject().put("travel",4294967296L))}
 }
 @Test fun wholeDecimalMinutesRemainValid(){
  assertEquals(20,AssistantActions.patchEvent(event(),JSONObject().put("travel",20.0)).travelMinutes)
  assertEquals(AssistantAction.Focus(25),decoded("""{"action":"focus","minutes":25.0}"""))
 }
 @Test fun stringBooleansCannotSilentlyBecomeFlags(){
  assertThrows(IllegalArgumentException::class.java){decoded("""{"action":"morning","date":"2026-09-21","passed":"true"}""")}
  val parcel=NoteItem(module="快递",title="3-2-1234")
  assertThrows(IllegalArgumentException::class.java){AssistantActions.patchNote(parcel,JSONObject().put("done","false"))}
 }
 @Test fun malformedPatchMustNotBecomeAnEmptyNoOp(){
  assertThrows(IllegalArgumentException::class.java){decoded("""{"action":"update","module":"日历","targetId":"target","patch":[{"title":"运动"}]}""")}
 }
 @Test fun batchCannotSilentlyDiscardAnExtraOperationField(){
  assertThrows(IllegalArgumentException::class.java){decoded("""{"action":"batch","actions":[{"action":"focus","minutes":25}],"patch":{"title":"被忽略的修改"}}""")}
 }
 @Test fun eventCannotSilentlyDiscardMisspelledReminderField(){
  val entry=entryToJson(event()).put("remind","OFF")
  assertThrows(IllegalArgumentException::class.java){AssistantActions.decode(JSONObject().put("action","event").put("entry",entry))}
 }
 @Test fun nonTodoNoteCannotSilentlyDiscardStructuredLocation(){
  assertThrows(IllegalArgumentException::class.java){decoded("""{"action":"note","module":"信息查询","title":"寝室","location":"B12-301"}""")}
 }
 @Test fun combinedCompletionAndEditUsesOneUpdate(){
  val todo=event().copy(kind=EventKind.TODO).normalized()
  val plan=AssistantPlanner.build(state(todo),decoded("""{"action":"update","module":"待办","targetId":"target","patch":{"completed":true,"location":"F02"}}"""))
  assertEquals(todo.copy(completed=true,location="F02"),plan.after.entries.single())
  assertEquals(1,plan.operationCount)
 }
 @Test fun noteEditDateMustNotLeakIntoAnotherModule(){
  val note=NoteItem(id="n",module="信息查询",title="寝室",detail="B12-301")
  assertThrows(IllegalArgumentException::class.java){AssistantActions.patchNote(note,JSONObject().put("date","2026-10-01"))}
 }
 @Test fun batchAmbiguityRejectsEarlierWritesWithoutMutatingInput(){
  val before=state().copy(entries=listOf(event(),event().copy(id="other",title="体测",start=day.plusDays(1).atTime(8,0),end=day.plusDays(1).atTime(9,0))))
  val action=AssistantAction.Batch(listOf(AssistantAction.Note(NoteItem(module="账单",title="午饭",detail="18")),AssistantAction.Change("delete","日历","体测",JSONObject())))
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.build(before,action)}
  assertEquals(2,before.entries.size);assertTrue(before.notes.isEmpty())
 }
 @Test fun independentExplicitBatchSurvivesTabSwitch(){
  val action=AssistantAction.Batch(listOf(AssistantAction.Change("update","日历","体测",JSONObject().put("notes","带玉兰卡"),"target"),AssistantAction.Note(NoteItem(module="账单",title="午饭",detail="18"))))
  AssistantRequestGuard.check(action,"日历","地图","old selection","new selection")
 }
 @Test fun implicitMemberMakesWholeBatchSelectionDependent(){
  val action=AssistantAction.Batch(listOf(AssistantAction.Change("delete","日历","这个",JSONObject()),AssistantAction.Note(NoteItem(module="账单",title="午饭",detail="18"))))
  assertThrows(IllegalStateException::class.java){AssistantRequestGuard.check(action,"日历","地图","old","new")}
 }
 @Test fun explicitNamedToolsSurviveTabSwitchWithoutChangingTheirArguments(){
  val cases=listOf(
   Triple("天气","notification","""{"period":"晚间","enabled":true,"time":"22:00"}"""),
   Triple("晨签","configure","""{"time":"07:30"}"""),
   Triple("设置","theme","""{"mode":"dark"}"""),
   Triple("设置","pet","""{"enabled":true}"""),
   Triple("今天","widgets","""{"noticeScope":"FOLLOWED"}"""),
   Triple("工具","favorite","""{"name":"天气","enabled":true}"""),
   Triple("日历","category_add","""{"name":"比赛"}"""),
   Triple("日历","category_delete","""{"name":"比赛"}"""),
   Triple("资料库","save_attachments","""{"ids":["attachment-1"]}"""),
   Triple("资料库","edit","""{"id":"file-1","name":"实验报告.pdf"}"""),
   Triple("资料库","delete","""{"id":"file-1"}"""),
   Triple("资料库","select","""{"id":"file-1"}"""),
   Triple("资料库","search","""{"kind":"图片"}"""),
   Triple("官网通知","follow","""{"source":"dlut.edu.cn","enabled":true}"""),
   Triple("抽签","save","""{"name":"值日","options":[{"name":"小王","weight":1},{"name":"小李","weight":2}]}"""),
   Triple("抽签","save","""{"id":"wheel-1","name":"周五值日"}"""),
   Triple("抽签","select","""{"id":"wheel-1"}"""),
   Triple("抽签","delete","""{"id":"wheel-1"}"""),
   Triple("会议纪要","record_start","""{"title":"英语课","kind":"课程"}"""),
   Triple("会议纪要","record_start","""{"folderId":"folder-1"}"""),
   Triple("会议纪要","summarize","""{"folderId":"folder-1"}"""),
   Triple("会议纪要","rename","""{"clipId":"clip-1","name":"第二节"}"""),
   Triple("会议纪要","view","""{"folderId":"folder-1","all":true,"query":"极限"}"""),
   Triple("二手信息","status","""{"id":"listing-1","status":"sold"}"""),
   Triple("二手信息","compose","""{"kind":"sell","title":"计算器","contacts":{"QQ":"123"}}""")
  )
  cases.forEach{(module,op,json)->
   val args=JSONObject(json);val original=args.toString()
   AssistantRequestGuard.check(AssistantAction.Tool(module,op,args),"今天","地图","old","new")
   assertEquals("$module $op",original,args.toString())
  }
 }
 @Test fun selectionDependentToolsStillRejectTabOrSelectionChange(){
  val cases=listOf(
   Triple("抽签","spin","{}"),Triple("抽签","delete","{}"),
   Triple("会议纪要","record_start","{}"),Triple("会议纪要","summarize","{}"),
   Triple("会议纪要","view","""{"query":"极限"}"""),
   Triple("会议纪要","view","""{"folderId":"folder-1"}"""),
   Triple("会议纪要","record_stop","{}"),Triple("实时翻译","clear","{}"),
   Triple("手持弹幕","show","{}"),Triple("二手信息","draft","""{"title":"计算器"}"""),
   Triple("二手信息","publish","""{"consent":true}"""),Triple("地图","route_select","""{"index":2}"""),
   Triple("地图","route","""{"to":""}"""),Triple("地图","heat_clear","{}"),
   Triple("未知模块","configure","""{"enabled":true}""")
  )
  cases.forEach{(module,op,json)->
   val action=AssistantAction.Tool(module,op,JSONObject(json))
   assertThrows("$module $op",IllegalStateException::class.java){AssistantRequestGuard.check(action,"今天","地图","old","new")}
  }
 }
 @Test fun inheritedRouteEndpointRemainsBoundToTheSendingPage(){
  val before=JSONObject().put("current",JSONObject().put("screen","地图").put("state",JSONObject().put("from","B01").put("to","H03")))
  val normalized=AssistantRoutes.normalize(AssistantAction.Tool("地图","route",JSONObject().put("to","F02")),before)
  assertEquals(AssistantAction.Route("B01","F02"),normalized)
  AssistantRequestGuard.check(normalized,"地图","今天","from=B01","from=A01N")
 }
 private fun folders()=listOf(MeetingFolder("old-folder","旧英语课","课程",1),MeetingFolder("other-folder","例会","会议",2))
 @Test fun explicitNewRecordingDoesNotInheritSelectedFolder(){
  val args=JSONObject().put("title","新物理课").put("kind","课程")
  AssistantToolArguments.validate("会议纪要","record_start",args)
  val target=AssistantRecordingTarget.resolve(args,"old-folder",folders())
  assertEquals(RecordingTarget(null,"新物理课","课程"),target)
 }
 @Test fun explicitExistingRecordingDoesNotFollowSelection(){
  val target=AssistantRecordingTarget.resolve(JSONObject().put("folderId","other-folder"),"old-folder",folders())
  assertEquals(RecordingTarget("other-folder","例会","会议"),target)
 }
 @Test fun recordingWithoutTargetCanStillUseCurrentFolder(){
  assertEquals(RecordingTarget("old-folder","旧英语课","课程"),AssistantRecordingTarget.resolve(JSONObject(),"old-folder",folders()))
 }
 @Test fun missingRecordingIdNeverFallsBackToSelectedFolder(){
  assertThrows(IllegalArgumentException::class.java){AssistantRecordingTarget.resolve(JSONObject().put("folderId","no-such-folder"),"old-folder",folders())}
 }
 @Test fun newRecordingNeedsBothTitleAndKindBeforeExecution(){
  listOf(JSONObject().put("title","物理课"),JSONObject().put("kind","课程")).forEach{args->
   assertThrows(IllegalArgumentException::class.java){AssistantToolArguments.validate("会议纪要","record_start",args)}
  }
 }
 @Test fun recordingWithBothNewTitleAndExistingIdRequiresRepair(){
  val args=JSONObject().put("folderId","old-folder").put("title","新物理课").put("kind","课程")
  assertThrows(IllegalArgumentException::class.java){AssistantToolArguments.validate("会议纪要","record_start",args)}
  assertThrows(IllegalArgumentException::class.java){AssistantRecordingTarget.resolve(args,"other-folder",folders())}
 }
 @Test fun noSelectedRecordingDoesNotInventAFolder(){
  assertThrows(IllegalArgumentException::class.java){AssistantRecordingTarget.resolve(JSONObject(),null,folders())}
 }
 @Test fun preparedPageCallbackCannotRunAfterOwnerDisposed()=runBlocking{
  val owner="adversarial-owner";var ran=false
  val tool=surfaceTool("select","选择录音","id",false){PreparedTool("选择","目标",{ran=true;"ok"})}
  try{
   AssistantSurfaces.put(SurfaceBinding(owner,"会议纪要",0,JSONObject().put("folderId","folder-a"),listOf(tool)))
   val prepared=AssistantSurfaces.prepare("会议纪要","select",JSONObject().put("id","clip-a")).second
   AssistantSurfaces.remove(owner)
   assertThrows(IllegalStateException::class.java){prepared.guard()};assertFalse(ran)
  }finally{AssistantSurfaces.remove(owner)}
 }
 @Test fun preparedPageCallbackCannotFollowChangedSelection(){
  val owner="adversarial-selection"
  val tool=surfaceTool("rename","改名","name"){PreparedTool("改名","目标",{"ok"})}
  try{
   AssistantSurfaces.put(SurfaceBinding(owner,"会议纪要",0,JSONObject().put("folderId","folder-a"),listOf(tool)))
   val prepared=AssistantSurfaces.prepare("会议纪要","rename",JSONObject().put("name","课堂")).second
   AssistantSurfaces.put(SurfaceBinding(owner,"会议纪要",0,JSONObject().put("folderId","folder-b"),listOf(tool)))
   assertThrows(IllegalStateException::class.java){prepared.guard()}
  }finally{AssistantSurfaces.remove(owner)}
 }
 @Test fun requestFingerprintIgnoresRecompositionOwnerButPreparedGuardDoesNot(){
  val one="adversarial-one";val two="adversarial-two"
  val tool=surfaceTool("rename","改名","name"){PreparedTool("改名","目标",{"ok"})}
  val page=JSONObject().put("folderId","folder-a")
  try{
   AssistantSurfaces.put(SurfaceBinding(one,"会议纪要",0,page,listOf(tool)))
   val fingerprint=AssistantSurfaces.fingerprint("会议纪要")
   val prepared=AssistantSurfaces.prepare("会议纪要","rename",JSONObject().put("name","课堂")).second
   AssistantSurfaces.remove(one);AssistantSurfaces.put(SurfaceBinding(two,"会议纪要",0,page,listOf(tool)))
   assertEquals(fingerprint,AssistantSurfaces.fingerprint("会议纪要"))
   assertThrows(IllegalStateException::class.java){prepared.guard()}
  }finally{AssistantSurfaces.remove(one);AssistantSurfaces.remove(two)}
 }
 @Test fun undoDeletionRefusesAnIdReusedByLaterRecord(){
  val before=state();val plan=AssistantPlanner.build(before,AssistantAction.Change("delete","日历","体测",JSONObject(),"target"))
  val later=plan.after.copy(entries=listOf(event().copy(title="另一个事项")))
  assertThrows(IllegalStateException::class.java){AssistantUndo.capture(before,plan.after)!!.plan(later)}
  assertEquals("另一个事项",later.entries.single().title)
 }
 @Test fun undoCreationRefusesSubsequentModification(){
  val before=state().copy(entries=emptyList());val plan=AssistantPlanner.build(before,AssistantAction.Event(event()))
  val later=plan.after.copy(entries=listOf(event().copy(location="F02")))
  assertThrows(IllegalStateException::class.java){AssistantUndo.capture(before,plan.after)!!.plan(later)}
 }
 @Test fun undoRoundTripKeepsAttachmentsAndNullableMorningOverride(){
  val note=NoteItem(id="info",module="信息查询",title="老师",detail="办公室 A01",attachments=listOf("/test/a.pdf","/test/b.jpg"))
  val check=CheckDay(day,null,true)
  val before=state().copy(notes=listOf(note),checks=listOf(check))
  val after=before.copy(notes=listOf(note.copy(detail="办公室 F02")),checks=listOf(check.copy(passed=false)))
  val stored=AssistantUndo.fromJson(JSONObject(AssistantUndo.capture(before,after)!!.json().toString()))
  val restored=stored.plan(after).after
  assertEquals(listOf(note),restored.notes);assertEquals(listOf(check),restored.checks)
 }
 @Test fun restartedPendingOrRunningCardsNeverResumeAutomatically(){
  listOf(OperationStatus.PENDING,OperationStatus.RUNNING).forEach{status->
   val original=AssistantOperation(status,"修改 1 项",listOf(OperationPreview("修改","体测")))
   assertEquals(OperationStatus.EXPIRED,AssistantOperation.fromJson(JSONObject(original.json().toString())).status)
  }
 }
 @Test fun sseDoneWithoutStopCannotCommitEvenWithCompleteJson(){
  val frame=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content","""{"action":"delete","module":"日历","targetId":"target"}"""))))
  assertThrows(IOException::class.java){ModelStream.read("data: $frame\n\ndata: [DONE]\n\n".reader().buffered())}
 }
 @Test fun sseLengthTerminationCannotCommitCompletePrefix(){
  val frame=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content","""{"action":"delete","module":"日历","targetId":"target"}""")).put("finish_reason","length")))
  assertThrows(ModelReplyFormatException::class.java){ModelStream.read("data: $frame\n\ndata: [DONE]\n\n".reader().buffered())}
 }
 @Test fun failedLookupCanBeRecoveredWithoutLeakingFailureAsEvidence()=runBlocking{
  var call=0;var seen=""
  val result=AssistantReasoning.resolve(request={feedback->
   when(call++){
    0->JSONObject("""{"action":"lookup","module":"资料库","id":"bad-id","query":"物理实验"}""")
    1->{assertTrue(feedback.contains("重新lookup"));JSONObject("""{"action":"lookup","module":"资料库","query":"物理实验"}""")}
    else->{assertTrue(feedback.contains("real-id"));JSONObject("""{"action":"chat","reply":"按学号_姓名命名。"}""")}
   }
  },lookup={if(it.id=="bad-id")"""{"error":"not found"}""" else """{"files":[{"id":"real-id","name":"物理实验.txt","text":"按学号_姓名命名。"}]}"""},validate={},onEvidence={seen=it})
  assertEquals("按学号_姓名命名。",(result as AssistantAction.Reply).text)
  assertTrue(seen.contains("real-id"));assertFalse(seen.contains("not found"));assertEquals(3,call)
 }
 @Test fun cancellationNeverBecomesAnAutomaticRetry()=runBlocking{
  var calls=0
  try{
   AssistantReasoning.resolve(request={calls++;throw CancellationException("cancel")},lookup={"{}"},validate={})
   fail("Cancellation must propagate")
  }catch(_:CancellationException){assertEquals(1,calls)}
 }
 @Test fun cancellationDuringLookupPropagatesWithoutAskingModelAgain()=runBlocking{
  var calls=0
  try{
   AssistantReasoning.resolve(request={calls++;JSONObject("""{"action":"lookup","module":"资料库","query":"课程"}""")},lookup={throw CancellationException("cancel")},validate={})
   fail("Cancellation must propagate")
  }catch(_:CancellationException){assertEquals(1,calls)}
 }
}

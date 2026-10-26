package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AssistantSelfCheckTest {
 private val day=LocalDate.of(2026,10,23)
 private val event=CalendarEntry(id="course",title="程序设计",start=day.plusDays(1).atTime(8,0),end=day.plusDays(1).atTime(9,0),notes="带玉兰卡")
 private val state=AssistantSnapshot(listOf(event),emptyList(),emptyList(),emptyList(),emptyList(),0)
 private fun validate(a:AssistantAction,text:String,history:List<ChatLine> = emptyList())=AssistantRequestChecks.validate(a,text,history,JSONObject(),"完全控制",day,state)
 private fun decode(s:String)=AssistantActions.decode(JSONObject(s))
 private fun note(value:String)=AssistantAction.Change("update","日历",event.title,JSONObject().put("notes",value),event.id)
 @Test fun colloquialAllKeepsAWholeBatchAfterPreviousOperation(){
  val rename=decode("""{"action":"rename_events","scope":"all","names":[{"from":"程序设计","to":"程设"}]}""")
  listOf("还是用简称吧，全改成程设","这些课全改成程设").forEach{validate(rename,it)}
  assertEquals("程设",AssistantPlanner.build(state,rename).after.entries.single().title)
 }
 @Test fun colloquialAllStillCannotExpandLimitedDateRange(){
  val rename=decode("""{"action":"rename_events","scope":"all","names":[{"from":"程序设计","to":"程设"}]}""")
  assertThrows(IllegalArgumentException::class.java){validate(rename,"只把下周的全改成程设")}
  assertThrows(IllegalArgumentException::class.java){validate(rename,"不要全部改，只改明天")}
 }
 @Test fun explicitNoteReplacementDoesNotRetainOldContent(){
  val text="程序设计那条的备注改成穿运动鞋，别改时间地点"
  validate(note("穿运动鞋"),text)
  assertThrows(IllegalArgumentException::class.java){validate(note("带玉兰卡，穿运动鞋"),text)}
  val after=AssistantPlanner.build(state,note("穿运动鞋")).after.entries.single()
  assertEquals(event.copy(notes="穿运动鞋"),after)
 }
 @Test fun appendAndCompoundReplacementRemainDistinct(){
  validate(note("带玉兰卡，穿运动鞋"),"加个备注穿运动鞋")
  assertThrows(IllegalArgumentException::class.java){validate(note("穿运动鞋"),"加个备注穿运动鞋")}
  validate(note("带玉兰卡，穿运动鞋"),"备注改成保留玉兰卡并且加上穿运动鞋")
  validate(note("穿运动鞋"),"不要把备注改成带书")
  validate(note("带书，穿运动鞋"),"备注改成带书，穿运动鞋")
 }
 private fun save()=AssistantAction.Tool("资料库","save_attachments",JSONObject("""{"ids":["attachment-temp-id"]}"""))
 @Test fun readingSavedMaterialCannotSilentlySaveAnAttachmentAgain(){
  val history=listOf(ChatLine("user","存这份实验说明"),ChatLine("assistant","已保存：实验说明.txt"))
  assertThrows(IllegalArgumentException::class.java){validate(save(),"继续读取正文，告诉我命名规则",history)}
  assertThrows(IllegalArgumentException::class.java){validate(save(),"不用保存，查一下里面的截止时间")}
 }
 @Test fun explicitSavingAndApprovedSaveRemainPossible(){
  listOf("把这个文件存进资料库","帮我收好这份材料","放到资料库里","这个帮我存一下","存这份说明","放入资料库","先扔资料库里，以后再看").forEach{validate(save(),it)}
  val history=listOf(ChatLine("user","把附件保存到资料库"),ChatLine("assistant","保存这一个文件，对吗？"))
  validate(save(),"对",history)
 }
 @Test fun parcelDeletionCannotBeReplacedByCompletedStatus(){
  val complete=AssistantAction.Change("complete","快递","3-2-1234",JSONObject(),"parcel")
  assertThrows(IllegalArgumentException::class.java){validate(complete,"3-2-1234那个快递取了，删除")}
  validate(complete.copy(operation="delete"),"3-2-1234那个快递取了，删除")
  validate(complete,"标完成，别删除")
 }
 @Test fun meaninglessConfirmationCannotOverrideAnActualCourseReference(){
  assertEquals("程序设计",AssistantConversationContext.referenceTitle("明天程序设计下课后提醒\n确定，就是一下课就提醒"))
  assertTrue(AssistantConversationContext.anchoredTime("明天程序设计下课后提醒\n确定，就是一下课就提醒",event.end,listOf(event),day))
 }
 @Test fun spokenMinuteOffsetsUseTheActualCourseEnd(){
  listOf("十" to 10L,"十五" to 15L,"二十" to 20L,"两" to 2L,"二十五" to 25L).forEach{(word,minutes)->
   assertTrue(AssistantConversationContext.anchoredTime("明天程序设计下课后${word}分钟提醒我",event.end.plusMinutes(minutes),listOf(event),day))
   assertTrue(AssistantConversationContext.anchoredTime("明天上完程序设计后${word}分钟提醒我",event.end.plusMinutes(minutes),listOf(event),day))
  }
  assertTrue(AssistantConversationContext.anchoredTime("明天程序设计下课前五分钟提醒我",event.end.minusMinutes(5),listOf(event),day))
 }
 @Test fun explicitSingleEventDurationOverridesTheDefault(){
  val source=AssistantAction.Event(event)
  listOf("设1分钟","时长一分钟","持续十五分钟").zip(listOf(1L,1L,15L)).forEach{(text,minutes)->
   val normalized=AssistantIntent.normalize(source,"明天下课后提醒我问作业，$text") as AssistantAction.Event
   assertEquals(event.start.plusMinutes(minutes),normalized.value.end)
  }
  listOf("提醒设1分钟","路程设一分钟","不要设1分钟","下课后十分钟提醒我","备注是看一段持续十五分钟的视频","标题叫“持续十五分钟”").forEach{assertEquals(source,AssistantIntent.normalize(source,it))}
  val multiple=AssistantAction.Batch(listOf(source,source.copy(value=event.copy(id="other"))))
  assertEquals(multiple,AssistantIntent.normalize(multiple,"其中一个设一分钟"))
 }
 @Test fun groundedEndReminderUsesTheRequestedInstant(){
  val reminder=AssistantAction.Event(event.copy(id="reminder",title="交作业",start=event.end.plusMinutes(10),end=event.end.plusMinutes(11),reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=event.end.plusMinutes(9)))
  val text="明天上完程序设计后十分钟提醒我交作业，设一分钟"
  val result=AssistantIntent.referenceReminder(reminder,text,listOf(event),day) as AssistantAction.Event
  assertEquals(reminder.value.start,result.value.absoluteReminder)
  assertEquals(reminder.value.start,result.value.start)
  listOf("明天上完程序设计后十分钟交作业，提前一分钟提醒我","明天上完程序设计后十分钟交作业，不提醒","明天上完程序设计后十分钟交作业，8点提醒我").forEach{
   assertEquals(reminder,AssistantIntent.referenceReminder(reminder,it,listOf(event),day))
  }
  assertEquals(reminder,AssistantIntent.referenceReminder(reminder,text,listOf(event,event.copy(id="ambiguous")),day))
  val source=event.copy(location="S606")
  val copied=reminder.copy(value=reminder.value.copy(location="S606",notes="带玉兰卡；提前一分钟提醒"))
  val clean=AssistantIntent.referenceReminder(copied,text+"，带玉兰卡",listOf(source),day) as AssistantAction.Event
  assertEquals("",clean.value.location);assertEquals("带玉兰卡",clean.value.notes)
  assertEquals("S606",(AssistantIntent.referenceReminder(copied,text+"，就在S606",listOf(source),day) as AssistantAction.Event).value.location)
 }
 @Test fun completeChatWithHarmlessSuffixNeedsNoSecondResponse(){
  val chat="""{"action":"chat","reply":"午饭花了多少钱？"}"""
  listOf("\n\n（告诉我金额即可。）","""{"type":"json_object"}""","""{"type":"json_object","reply":"午饭花了多少钱？"}""","""{"action":"chat","reply":"午饭金额是多少呢？"}""").forEach{
   assertEquals("午饭花了多少钱？",StructuredReplies.parse(chat+it).getString("reply"))
  }
  listOf(chat+"""{"action":"delete","module":"日历"}""",chat+"[]", """{"action":"delete","target":"全部"} 执行吧""").forEach{
   assertThrows(ModelReplyFormatException::class.java){StructuredReplies.parse(it)}
  }
 }
 @Test fun DirectPageCommandsDoNotNeedModelGuessing(){
  listOf("工具","请打开工具","帮我打开工具页面","麻烦你进入工具一下","请帮我切换到工具。").forEach{assertEquals("工具",AssistantIntent.directDestination(it))}
  assertEquals("会议纪要",AssistantIntent.directDestination("打开录音摘要"))
  listOf("不要打开工具","打开天气看看明天冷不冷","请打开地图导航去F02","教我怎么打开工具").forEach{assertNull(AssistantIntent.directDestination(it))}
 }
 @Test fun BareNameCannotDeleteAnArbitraryRepeatedCourse(){
  val state2=state.copy(entries=listOf(event,event.copy(id="course2",start=event.start.plusDays(7),end=event.end.plusDays(7))))
  val action=AssistantAction.Change("delete","日历",event.title,JSONObject(),event.id)
  assertThrows(IllegalArgumentException::class.java){AssistantRequestChecks.validate(action,"删掉程序设计那节课",emptyList(),JSONObject(),"完全控制",day,state2)}
  AssistantRequestChecks.validate(action,"删掉明天的程序设计那节课",emptyList(),JSONObject(),"完全控制",day,state2)
  val context=JSONObject().put("current",JSONObject().put("state",JSONObject().put("selectedId",event.id)))
  AssistantRequestChecks.validate(action,"删掉程序设计那节课",emptyList(),context,"完全控制",day,state2)
 }
 @Test fun ExplicitTranslationDirectionKeepsBothLanguages(){
  val tool=AssistantAction.Tool("实时翻译","configure",JSONObject().put("to","英语").put("original",true))
  val changed=AssistantIntent.normalize(tool,"实时翻译换成中文译英语，原文显示出来") as AssistantAction.Tool
  assertEquals("中文",changed.args.getString("from"));assertEquals("英语",changed.args.getString("to"));assertTrue(changed.args.getBoolean("original"))
  assertSame(tool,AssistantIntent.normalize(tool,"不要换成中文译英语"))
 }
 @Test fun ShortFileUnmatchedKeywordStillSuppliesActualText(){
  val doc=LibraryDocument("doc","说明.txt","text/plain","/fictional",30,0,"","文件命名\n使用学号_姓名.pdf","正文已索引","测试")
  val result=LibraryRetrieval.document(doc,"文件名格式")
  assertEquals(doc.text,result.getString("text"));assertFalse(result.getBoolean("keywordMatched"));assertFalse(result.getBoolean("hasMore"));assertTrue(result.getJSONArray("locations").length()>0)
  val search=LibraryRetrieval.search(listOf(LibrarySearchResult(doc,LibrarySearch.match(doc.name,"",doc.text,"文件名格式"))),"文件名格式")
  assertEquals(doc.text,search.getJSONArray("items").getJSONObject(0).getJSONObject("preview").getString("text"))
 }
 @Test fun LibraryQuestionCannotBecomeAnUnrequestedWrite()=kotlinx.coroutines.runBlocking {
  val question="文件名称叫物理实验说明，别让我重新传，查一下完整的命名要求"
  assertTrue(AssistantGrounding.libraryQuestionOnly(question))
  assertFalse(AssistantGrounding.libraryQuestionOnly("保存文件到资料库，再告诉我命名规则"))
  assertFalse(AssistantGrounding.libraryQuestionOnly("打开资料库里的文件原文"))
  var calls=0;var reads=0
  val action=AssistantReasoning.resolve(request={calls++;if(calls==1)JSONObject("""{"action":"note","module":"信息查询","title":"物理实验说明","detail":"待查"}""")else JSONObject("""{"action":"chat","reply":"使用学号_姓名.pdf"}""")},lookup={reads++;"""{"text":"使用学号_姓名.pdf"}"""},validate={assertTrue(it is AssistantAction.Reply)},groundingRequest=question)
  assertEquals(2,calls);assertEquals(1,reads);assertEquals("使用学号_姓名.pdf",(action as AssistantAction.Reply).text)
 }
 @Test fun TitleOnlyBulkUpdateMatchesTheRenamePlan(){
  val many=state.copy(entries=(0..23).map{event.copy(id="course-$it",start=event.start.plusDays(it.toLong()),end=event.end.plusDays(it.toLong()))})
  val rename=AssistantAction.RenameEvents(listOf("程序设计" to "程设"))
  val bulk=decode("""{"action":"calendar_bulk","op":"update","scope":"all","titles":["程序设计"],"patch":{"title":"程设"}}""")
  assertEquals(AssistantPlanner.build(many,rename).after,AssistantPlanner.build(many,bulk).after)
 }
}

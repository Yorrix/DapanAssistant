package cn.dapan.assistant

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** Opt-in, synthetic fixtures only. The loopback runner enforces a hard shared spend ceiling.
 * Uses production prompts, stream parser, intent normalizer, reasoning loop and data planner.
 * Android page callbacks and public publishing are deliberately NOT executed here. */
class AssistantLiveEvaluationTest {
 private val now=LocalDateTime.of(2026,10,23,12,0)
 private val client=OkHttpClient.Builder().callTimeout(250,TimeUnit.SECONDS).readTimeout(95,TimeUnit.SECONDS).build()
 private fun fixture():AssistantSnapshot{
  val entries=listOf(
   CalendarEntry(id="test-physics",title="体测",start=now.plusDays(1).withHour(8),location="B01",notes="带玉兰卡"),
   CalendarEntry(id="test-tdl",title="交材料",kind=EventKind.TODO,start=now,location="G01").normalized(),
   CalendarEntry(id="test-ddl",title="交实验报告",kind=EventKind.DEADLINE,start=now.plusDays(7).withHour(18)).normalized()
  )+(0..23).map{CalendarEntry(id="cs-$it",title="成设",start=now.plusDays(it.toLong()+1).withHour(10),location="A01N")}
  return AssistantSnapshot(entries,listOf(NoteItem(id="lunch",module="账单",title="午饭",detail="18.00",group="吃饭"),
   NoteItem(id="parcel",module="快递",title="3-2-1234")),emptyList(),listOf("课程","作业"),emptyList(),0)
 }
 private fun context(state:AssistantSnapshot)=JSONObject().put("categories",JSONArray(state.categories))
  .put("calendar",JSONArray(state.entries.map(::entryToJson)))
  .put("calendarTitles",JSONArray(state.entries.filter{it.kind==EventKind.TIMED}.groupBy{it.title}.map{(title,rows)->JSONObject().put("title",title).put("count",rows.size)}))
  .put("notes",JSONArray(state.notes.map{JSONObject().put("id",it.id).put("module",it.module).put("title",it.title).put("detail",it.detail).put("group",it.group)})).toString()
 private val bodyText="演示资料（虚构）\n大学物理实验报告提交说明\n一、提交截止\n2026年10月30日18:00。\n二、文件命名\n统一使用：学号_姓名_实验名称.pdf。\n三、提交前检查\n包含原始数据、计算过程和误差分析。"
 private val doc=LibraryDocument("library-real-id","大学物理实验-提交说明.txt","text/plain","/fictional",100,0,"",bodyText,"正文已索引","测试")
 private fun lookup(a:AssistantAction.Lookup,state:AssistantSnapshot):String=when(AssistantSurfaces.canonical(a.module)){
  "资料库"->if(a.id.isNotBlank()){
   if(a.id==doc.id)LibraryRetrieval.document(doc,a.query,a.offset,a.limit).toString()else JSONObject().put("error","文件不存在，请重新检索真实 id").toString()
  }else LibraryRetrieval.search(listOf(LibrarySearchResult(doc,LibrarySearch.match(doc.name,"",doc.text,a.query))),a.query,a.offset).toString()
  "日历","待办"->JSONObject(context(state)).put("items",JSONArray(state.entries.filter{a.id.isBlank()||it.id==a.id}.map(::entryToJson))).toString()
  "抽签"->"""{"selected":"wheel-1","items":[{"id":"wheel-1","name":"今晚吃什么","options":[{"name":"面条","weight":1},{"name":"米饭","weight":1}]}]}"""
  "地图"->"""{"places":["A01","A01N","A01S","A01W","A01E","B01","B03","F02","G01","H03"]}"""
  "手持弹幕"->"""{"options":{"text":"加油","landscape":true,"moving":false}}"""
  "会议纪要"->"""{"items":[{"id":"folder-1","title":"高数第一课","kind":"课程","clips":[{"id":"clip-1","name":"第1次录音","durationMs":120000,"lines":[{"text":"今天讲极限的定义。","start":30000}]}]}]}"""
  "官网通知"->"""{"sources":[{"id":"panjin.dlut.edu.cn","name":"盘锦校区"}],"items":[{"id":"notice-1","title":"虚构讲座通知","date":"2026-10-23","summary":"明天下午讲座","source":"panjin.dlut.edu.cn"}]}"""
  "天气"->"""{"forecast":{"temperature":18,"apparent":16,"description":"晴"},"cachedAt":1792728000000}"""
  else->"""{"items":[],"hint":"隔离测试中没有更多记录，不可编造。"}"""
 }
 private fun view(a:AssistantAction):JSONObject=when(a){
  is AssistantAction.Event->JSONObject().put("action","event").put("entry",entryToJson(a.value))
  is AssistantAction.Note->JSONObject().put("action","note").put("module",a.value.module).put("title",a.value.title).put("detail",a.value.detail).put("group",a.value.group).put("date",Billing.day(a.value).toString())
  is AssistantAction.Tool->JSONObject().put("action","tool").put("module",AssistantSurfaces.canonical(a.module)).put("op",a.operation).put("args",a.args)
  is AssistantAction.Reply->JSONObject().put("action","chat").put("reply",a.text)
  is AssistantAction.Route->JSONObject().put("action","route").put("from",a.from).put("to",a.to)
  is AssistantAction.Navigate->JSONObject().put("action","navigate").put("module",AssistantSurfaces.canonical(a.module))
  is AssistantAction.Morning->JSONObject().put("action","morning").put("date",a.day.date.toString()).put("passed",a.day.passed)
  is AssistantAction.Focus->JSONObject().put("action","focus").put("minutes",a.minutes)
  is AssistantAction.Change->JSONObject().put("action",a.operation).put("module",a.module).put("targetId",a.targetId).put("target",a.target).put("patch",a.patch)
  is AssistantAction.Batch->JSONObject().put("action","batch").put("actions",JSONArray(a.actions.map(::view)))
  is AssistantAction.RenameEvents->JSONObject().put("action","rename_events").put("names",JSONArray(a.names.map{JSONObject().put("from",it.first).put("to",it.second)}))
  is AssistantAction.Search->JSONObject().put("action","search").put("query",a.query).put("date",a.date?.toString())
  else->error("Unexpected final lookup")
 }
 private fun subset(expected:Any,actual:Any,path:String="result"){
  when(expected){
   is JSONObject->{assertTrue("$path object",actual is JSONObject)
    if(path=="result.patch")assertEquals("Only requested fields may change",expected.keys().asSequence().toSet(),(actual as JSONObject).keys().asSequence().toSet())
    expected.keys().forEach{key->
    val a=actual as JSONObject;assertTrue("$path.$key missing in $a",a.has(key));subset(expected.get(key),a.get(key),"$path.$key")}}
   is JSONArray->{assertTrue("$path array",actual is JSONArray);actual as JSONArray;assertEquals("$path size",expected.length(),actual.length());for(i in 0 until expected.length())subset(expected.get(i),actual.get(i),"$path[$i]")}
   is Number->assertEquals(path,expected.toDouble(),(actual as Number).toDouble(),0.000001)
   else->{
    val amount=if(path.endsWith(".detail"))expected.toString().toBigDecimalOrNull()else null
    if(amount!=null)assertEquals(path,0,amount.compareTo(actual.toString().substringBefore('\n').toBigDecimal()))
    else assertEquals(path,expected,actual)
   }
  }
 }
 @Test fun evaluateRealNaturalLanguage()=runBlocking{
  val url=System.getenv("DAPAN_EVAL_URL").orEmpty();assumeTrue("Run scripts/ai-live-eval.py for paid live evaluation",url.startsWith("http://127.0.0.1:"))
  val cases=File(System.getenv("DAPAN_EVAL_CASES")).readLines().filter{it.isNotBlank()}.map(::JSONObject)
  val output=File(System.getenv("DAPAN_EVAL_RESULTS"));output.parentFile.mkdirs();output.writeText("")
  val filter=System.getenv("DAPAN_EVAL_FILTER").orEmpty().split(',').filter{it.isNotBlank()}.toSet()
  var failures=0
  for(case in cases.filter{filter.isEmpty()||it.getString("id") in filter}){
   var state=fixture();val history=mutableListOf<ChatLine>();var evidence="";var calls=0
   if(case.optBoolean("emptyEvents"))state=state.copy(entries=emptyList())
   if(case.optBoolean("emptyNotes"))state=state.copy(notes=emptyList())
   case.optJSONArray("history")?.objects()?.forEach{history+=ChatLine(it.getString("role"),it.getString("text"),evidence=it.optString("evidence"))}
   val page=JSONObject().put("screen",case.optString("screen","今天")).put("state",case.optJSONObject("pageState")?:JSONObject())
   val surface=JSONObject().put("current",page).put("pageOperations",AssistantCatalogue.json())
    .put("globalOperations",JSONObject(File(System.getenv("DAPAN_EVAL_GLOBALS")).readText()))
    .put("attachments",JSONArray("""[{"id":"attachment-temp-id","name":"大学物理实验-提交说明.txt","hint":"临时文本附件"}]"""))
   val turns=case.optJSONArray("turns")?.objects()?:listOf(case)
   for((index,turn) in turns.withIndex()){
    val text=turn.getString("text");val log=JSONObject().put("id",case.getString("id")).put("turn",index).put("text",text)
    val responses=JSONArray();val started=System.nanoTime()
    try{
     val action=AssistantReasoning.resolve(request={results->
      check(calls++<12){"Per-scenario request limit"}
      val payload=AiClient.requestBody("deepseek-flash",text,history=history,recordContext=context(state),surfaceContext=surface.toString(),toolResults=results,
       accessLabel=case.optString("access","完全控制"),now=now).put("stream",true).put("stream_options",JSONObject().put("include_usage",true)).put("max_tokens",System.getenv("DAPAN_EVAL_MAX_OUTPUT")?.toIntOrNull()?:4500)
      var rawResponse=""
      val parsed=StructuredReplies.request(onInvalid={attempt,reason->
       val bad=log.optJSONArray("invalidResponses")?:JSONArray().also{log.put("invalidResponses",it)}
       bad.put(JSONObject().put("attempt",attempt).put("reason",reason).put("text",rawResponse.take(2000)))
      }) {attempt->
       val request=Request.Builder().url(url).header("X-Evaluation-Token",System.getenv("DAPAN_EVAL_TOKEN")).post(AiClient.withFormatRetry(payload,attempt).toString().toRequestBody("application/json".toMediaType())).build()
       client.newCall(request).execute().use{r->check(r.isSuccessful){"Evaluation transport ${r.code}: ${r.body?.string()?.take(200)}"};ModelStream.read(r.body!!.charStream().buffered()).also{rawResponse=it}}}
      responses.put(parsed);parsed
     },lookup={if(case.optBoolean("emptyBanner")&&it.module=="手持弹幕")"""{"options":{"text":""}}""" else lookup(it,state)},validate={a->
      AssistantRequestChecks.validate(a,text,history,surface,case.optString("access","完全控制"),now.toLocalDate())
      if(AssistantRoutes.isRoute(a))AssistantRoutes.validate(a,setOf("A01","A01N","A01S","A01W","A01E","B01","B03","F02","G01","H03"))
      when(a){
       is AssistantAction.Tool->AssistantToolContract.validate(a,surface)
       is AssistantAction.Reply,is AssistantAction.Search,is AssistantAction.Navigate->Unit
       else->{val p=AssistantPlanner.build(state,a,now);require(p.unmatched.isEmpty()){"原名称未匹配：${p.unmatched.joinToString()}"}}
     }},normalize={AssistantIntent.normalize(AssistantRoutes.normalize(it,surface),text)},onEvidence={evidence=it},groundingRequest=text)
     var actual=view(action)
     // Single-item batches and unique name targeting have the same observable effect.
     if(action is AssistantAction.Batch&&action.actions.size==1&&turn.optJSONObject("expect")?.optString("action")!="batch")actual=view(action.actions.single())
     if(actual.optString("action") in setOf("update","delete","complete")&&actual.optString("targetId").isBlank()){
      val module=actual.optString("module");val title=actual.optString("target")
      val ids=if(module in setOf("日历","待办"))state.entries.filter{it.title==title}.map{it.id}else state.notes.filter{it.module==module&&it.title==title}.map{it.id}
      if(ids.size==1)actual.put("targetId",ids.single())
     }
     log.put("actual",actual)
     val choices=turn.optJSONArray("oneOf")?.objects()?:listOf(turn.getJSONObject("expect"))
     val matches=choices.map{expected->runCatching{subset(expected,actual)}}
     if(matches.none{it.isSuccess})throw AssertionError(matches.mapNotNull{it.exceptionOrNull()?.message}.joinToString(" OR "))
     turn.optJSONArray("contains")?.objectsAsText()?.forEach{assertTrue("Missing $it in $actual",actual.toString().contains(it))}
     turn.optJSONArray("excludes")?.objectsAsText()?.forEach{assertFalse("Unexpected $it in $actual",actual.toString().contains(it))}
     turn.optJSONArray("emptyIfPresent")?.objectsAsText()?.forEach{key->
      val args=actual.optJSONObject("args")
      if(args?.has(key)==true)assertEquals("Cannot invent $key",0,args.getJSONObject(key).length())
     }
     if(turn.has("count"))assertEquals(turn.getInt("count"),(action as AssistantAction.Batch).actions.size)
     history+=ChatLine("user",text)
     when(action){
      is AssistantAction.Reply->history+=ChatLine("assistant",action.text,evidence=evidence)
      is AssistantAction.Tool->history+=ChatLine("assistant","测试只核对了工具参数，未在设备执行。")
      is AssistantAction.Search,is AssistantAction.Navigate->history+=ChatLine("assistant",actual.toString())
      else->{val plan=AssistantPlanner.build(state,action,now);state=plan.after;history+=ChatLine("assistant",plan.receipt())}
     }
     log.put("passed",true)
    }catch(t:Throwable){failures++;log.put("passed",false).put("error",t.message?:t.javaClass.simpleName)}
    log.put("responses",responses).put("seconds",(System.nanoTime()-started)/1e9)
    output.appendText(log.toString()+"\n")
    println("${case.getString("id")}/$index: ${log.optBoolean("passed")}")
   }
  }
  assertEquals("See ${output.absolutePath}",0,failures)
 }
}

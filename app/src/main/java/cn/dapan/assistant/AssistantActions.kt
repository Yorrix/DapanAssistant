package cn.dapan.assistant

import org.json.JSONObject
import java.time.LocalDate

sealed interface AssistantAction {
 data class Tool(val module:String,val operation:String,val args:JSONObject):AssistantAction
 data class Lookup(val module:String,val query:String,val id:String="",val offset:Int=0,val limit:Int=10000):AssistantAction
 data class Reply(val text:String):AssistantAction
 data class Navigate(val module:String):AssistantAction
 data class Event(val value:CalendarEntry):AssistantAction
 data class Note(val value:NoteItem):AssistantAction
 data class Search(val query:String,val date:LocalDate?):AssistantAction
 data class Route(val from:String,val to:String):AssistantAction
 data class Morning(val day:CheckDay):AssistantAction
 data class MorningRange(val from:LocalDate,val through:LocalDate,val passed:Boolean,val dayScope:String,val setStart:Boolean=false):AssistantAction
 data class Focus(val minutes:Int):AssistantAction
 data class Change(val operation:String,val module:String,val target:String,val patch:JSONObject,val targetId:String=""):AssistantAction
 data class Batch(val actions:List<AssistantAction>):AssistantAction
 data class RenameEvents(val names:List<Pair<String,String>>,val from:LocalDate?=null,val through:LocalDate?=null):AssistantAction
}

object AssistantActions {
 val destinations=setOf("官网通知","二手信息","今天","日历","待办","晨签","信息查询","录音","校园热力图","热力图","导航","实时翻译","课堂生词","会议纪要","账单","快递","取件码","扫描文件","资料库","天气","抽签","手持弹幕","专注","地图","设置","工具","录音摘要","会议摘要")
 private val eventStrings=setOf("id","title","kind","start","end","reminder","absolute","category","location","notes")
 private val eventIntegers=setOf("travel","before")
 private val eventBooleans=setOf("completed","urgent")
 private fun JSONObject.onlyFields(allowed:Set<String>){
  val extra=keys().asSequence().filter{it !in allowed}.toList()
  require(extra.isEmpty()){"包含不支持的参数 ${extra.joinToString()}；请保留用户要求并改用正确字段，不得忽略"}
 }
 // Storage readers remain compatible with old data. AI proposals must not silently coerce
 // malformed fields to defaults, truncate fractions, or wrap a large integer.
 private fun JSONObject.fieldTypes(strings:Set<String> = emptySet(),integers:Set<String> = emptySet(),booleans:Set<String> = emptySet()){
  strings.filter(::has).forEach{require(get(it) is String){"$it 必须是文本"}}
  integers.filter(::has).forEach{integer(it)}
  booleans.filter(::has).forEach{require(get(it) is Boolean){"$it 必须是 true 或 false，不能是字符串"}}
 }
 private fun JSONObject.integer(key:String):Int{
  val value=get(key)
  require(value is Number){"$key 必须是整数，不要附带单位"}
  return try{java.math.BigDecimal(value.toString()).intValueExact()}catch(_:NumberFormatException){throw IllegalArgumentException("$key 必须是有效整数")}
   catch(_:ArithmeticException){throw IllegalArgumentException("$key 必须是有效整数，不可取整或溢出")}
 }
 private fun validateEntry(o:JSONObject){
  o.onlyFields(eventStrings+eventIntegers+eventBooleans)
  o.fieldTypes(eventStrings,eventIntegers,eventBooleans)
 }
 private fun validateEnvelope(o:JSONObject){
  val fields=when(o.requiredText("action",50)){
   "batch"->setOf("actions")
   "tool"->setOf("module","op","args")
   "rename_events"->setOf("scope","from","through","names")
   "lookup"->setOf("module","query","id","offset","limit")
   "chat"->setOf("reply")
   "navigate"->setOf("module")
   "event"->setOf("entry")
   "note"->setOf("module","title","detail","group","date","location","category","urgent")
   "search"->setOf("query","date")
   "route"->setOf("from","to")
   "morning"->setOf("date","passed","required")
   "morning_range"->setOf("from","through","passed","dayScope","setStart")
   "focus"->setOf("minutes")
   "update","delete","complete"->setOf("module","target","targetId","patch")
   else->return
  }
  o.onlyFields(fields+"action")
  o.fieldTypes(fields intersect setOf("module","op","scope","from","through","query","id","reply","title","detail","group","date","location","category","to","target","targetId","dayScope"),fields intersect setOf("offset","limit","minutes"),fields intersect setOf("urgent","passed","required","setStart"))
 }
 private fun JSONObject.requiredText(key:String,max:Int=2000):String {
  val value=getString(key).trim()
  require(value.isNotBlank() && value.length<=max){"助手返回的 $key 不完整，请补充说明后重试"}
  return value
 }
 fun decode(o:JSONObject):AssistantAction = decodeOne(o,true)
 private fun decodeOne(o:JSONObject,allowBatch:Boolean):AssistantAction {
  validateEnvelope(o)
  return when(o.requiredText("action",50)) {
  "batch" -> {
   require(allowBatch){"不支持嵌套批量操作"}
   val rows=o.getJSONArray("actions");require(rows.length() in 1..64){"一批支持 1～64 项操作，请缩小日期范围"}
   AssistantAction.Batch(rows.objects().map{decodeOne(it,false).also{action->require(action is AssistantAction.Event||action is AssistantAction.Note||action is AssistantAction.Change||action is AssistantAction.Morning||action is AssistantAction.MorningRange||action is AssistantAction.Focus||action is AssistantAction.RenameEvents){"批量清单只能包含数据修改"}}})
  }
  "tool" -> {
   require(o.keys().asSequence().all{it in setOf("action","module","op","args")}){"tool 参数必须放在 args 对象中，不能使用 fields 或把参数放在顶层"}
   require(!o.has("args")||o.optJSONObject("args")!=null){"args 必须是 JSON 对象"}
   AssistantAction.Tool(o.requiredText("module",30).also{require(!RetiredFeatures.removed(it)){"这个功能已移除"}},o.requiredText("op",50),o.optJSONObject("args")?:JSONObject())
  }
  "rename_events" -> {
   val scope=o.requiredText("scope",20);require(scope in setOf("all","date_range")){"改名必须明确全部日程或日期范围"}
   val from=if(scope=="date_range")LocalDate.parse(o.requiredText("from",10))else null
   val through=if(scope=="date_range")LocalDate.parse(o.requiredText("through",10))else null
   require(from==null||!through!!.isBefore(from)){"日期范围的结束不能早于开始"}
   require(scope!="all"||(!o.has("from")&&!o.has("through"))){"全部范围不能同时指定日期，请明确范围"}
   val names=o.getJSONArray("names").objects().map{it.onlyFields(setOf("from","to"));it.requiredText("from",200) to it.requiredText("to",200)}
   require(names.size in 1..32){"一次支持 1～32 组名称"}
   require(names.map{it.first}.distinct().size==names.size){"同一个原名称只能指定一个新名称"}
   AssistantAction.RenameEvents(names,from,through)
  }
  "lookup" -> AssistantAction.Lookup(o.requiredText("module",30).also{require(!RetiredFeatures.removed(it)){"这个功能已移除"}},o.optString("query").take(400),o.optString("id").take(100),o.optInt("offset",0).coerceIn(0,200000),o.optInt("limit",10000).coerceIn(500,16000))
  "chat" -> AssistantAction.Reply(o.requiredText("reply",16000))
  "navigate" -> AssistantAction.Navigate(o.requiredText("module",20).also{require(it in destinations){"这个工具尚未接通"}}.let{if(it in setOf("录音摘要","会议摘要"))"会议纪要"else it})
  "event" -> AssistantAction.Event(entryFromJson(o.getJSONObject("entry").also(::validateEntry)).copy(id=java.util.UUID.randomUUID().toString()).normalized().also{require(it.error()==null){it.error()!!}})
  "note" -> {
   val module=o.requiredText("module",20);require(module in listOf("待办","账单","快递","信息查询")){"不能向这个模块添加记录"}
   require(module=="待办"||listOf("location","category","urgent").none(o::has)){"只有待办接受 location/category/urgent；信息查询的地点等资料请写入 detail，不能丢弃字段"}
   require(module=="账单"||!o.has("date")){"只有账单接受记账 date；待办或信息查询不能忽略日期，请选择合适的事项类型"}
   val detail=o.optString("detail");if(module=="账单")require(Billing.amount(detail)?.signum()?.let{it!=0}==true){"金额需大于0，最多两位小数，且不超过999999999.99"}
   val created=if(module=="账单"&&o.optString("date").isNotBlank())LocalDate.parse(o.getString("date")).atTime(12,0).atZone(Billing.zone).toInstant().toEpochMilli()else System.currentTimeMillis()
   val note=NoteItem(module=module,title=o.requiredText("title",200),detail=detail.take(20000),group=o.optString("group","一般").take(100),created=created)
   // Older model replies still use note for TDL; keep its structured location when upgrading.
   if(module=="待办")AssistantAction.Event(note.asTodo().copy(location=o.optString("location"),category=o.optString("category"),urgent=o.optBoolean("urgent",note.group=="紧急")))
   else AssistantAction.Note(note)
  }
  "search" -> {val query=o.optString("query").trim();val date=o.optString("date").takeIf{it.isNotBlank()}?.let(LocalDate::parse);require(query.isNotBlank()||date!=null){"请说明要查找什么，或指定日期"};AssistantAction.Search(query,date)}
  "route" -> AssistantAction.Route(o.optString("from").trim().take(80),o.requiredText("to",80))
  "morning" -> {val date=LocalDate.parse(o.requiredText("date",10));AssistantAction.Morning(CheckDay(date,if(o.has("required"))o.getBoolean("required")else null,o.getBoolean("passed")))}
  "morning_range" -> AssistantAction.MorningRange(LocalDate.parse(o.requiredText("from",10)),LocalDate.parse(o.requiredText("through",10)),o.getBoolean("passed"),o.requiredText("dayScope",20).also{require(it in setOf("workdays","all","required")){"dayScope 必须是 workdays/all/required"}},o.optBoolean("setStart",false))
  "focus" -> AssistantAction.Focus(o.integer("minutes").also{require(it in 1..480){"专注时长需为 1～480 分钟"}})
  "update","delete","complete" -> {
   val module=o.requiredText("module",20);require(module in listOf("日历","待办","快递","账单","信息查询")){"不能修改这个模块"}
   require(!o.has("patch")||o.optJSONObject("patch")!=null){"patch 必须是 JSON 对象，不能忽略修改内容"}
   val patch=o.optJSONObject("patch")?:JSONObject()
   require(o.getString("action")=="update"||patch.length()==0){"delete/complete 不能同时带修改字段；完成并修改请用 update，合并 completed/done 与其它字段"}
   require(patch.keys().asSequence().all{it in setOf("title","kind","start","end","travel","reminder","before","absolute","category","location","notes","detail","group","urgent","completed","done","date")}){"助手返回了不支持修改的字段"}
   patch.fieldTypes((eventStrings-"id")+setOf("detail","group","date"),eventIntegers,eventBooleans+"done")
   val id=o.optString("targetId").take(100);val target=o.optString("target").trim().take(200);require(id.isNotBlank()||target.isNotBlank()){"请指定要修改的记录"}
   AssistantAction.Change(o.getString("action"),module,target,patch,id)
  }
  else -> error("助手返回了未支持的操作，请换一种说法")
  }
 }
 fun todoPatch(patch:JSONObject):JSONObject {
  require(patch.keys().asSequence().all{it in setOf("title","location","notes","detail","group","urgent","category","completed")}){"TDL 只支持名称、地点、备注、分类、优先级和完成状态"}
  return JSONObject().also{result->patch.keys().forEach{key->when(key){
   "group"->result.put("urgent",patch.getString(key)=="紧急")
   "detail"->result.put("notes",patch.getString(key))
   else->result.put(key,patch.get(key))
  }}}
 }
 fun patchEvent(old:CalendarEntry,patch:JSONObject):CalendarEntry {
  patch.onlyFields((eventStrings-"id")+eventIntegers+eventBooleans)
  patch.fieldTypes(eventStrings,eventIntegers,eventBooleans)
  require(!patch.has("kind")||patch.getString("kind")==old.kind.name){"已有事项不能更改类型，请新建事项"}
  require(!patch.has("done")){"日历完成状态请使用 completed"}
  require(!patch.has("completed")||old.kind in listOf(EventKind.TODO,EventKind.DEADLINE)){"只有 DDL / TDL 可以设置完成状态"}
  if(patch.has("completed"))patch.getBoolean("completed")
  val merged=entryToJson(old)
  patch.keys().forEach{key->require(key !in listOf("detail","group","date"));merged.put(key,patch.get(key))}
  return entryFromJson(merged).normalized().also{require(it.error()==null){it.error()!!}}
 }
 fun patchNote(old:NoteItem,patch:JSONObject):NoteItem {
  require(patch.keys().asSequence().all{it in setOf("title","detail","group","done","date")}){"记录不支持这个修改字段"}
  patch.fieldTypes(setOf("title","detail","group","date"),booleans=setOf("done"))
  require(!patch.has("date")||old.module=="账单"){"只有账单支持修改记账日期"}
  require(!patch.has("done")||old.module=="快递"){"只有快递记录支持完成状态"}
  val created=if(patch.has("date"))LocalDate.parse(patch.getString("date")).atTime(12,0).atZone(Billing.zone).toInstant().toEpochMilli()else old.created
  return old.copy(done=if(patch.has("done"))patch.getBoolean("done")else old.done,title=patch.optString("title",old.title),detail=patch.optString("detail",old.detail),group=patch.optString("group",old.group),created=created).also {
   require(it.title.isNotBlank()){"记录名称不能为空"}
   if(it.module=="账单")require(Billing.amount(it.detail)?.signum()?.let{sign->sign!=0}==true){"金额需大于0，最多两位小数，且不超过999999999.99"}
  }
 }
}

data class PendingChange(val description:String,val apply:()->Unit)


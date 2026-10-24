package cn.dapan.assistant

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Read/validate only. Execution stays outside this loop, so retries cannot double-write. */
internal object AssistantReasoning {
 suspend fun resolve(
  request:suspend(String)->JSONObject,
  lookup:suspend(AssistantAction.Lookup)->String,
  validate:suspend(AssistantAction)->Unit,
  progress:(String)->Unit={},
  normalize:(AssistantAction)->AssistantAction={it},
  onEvidence:(String)->Unit={},
  groundingRequest:String=""
 ):AssistantAction {
  val observations=mutableListOf<JSONObject>()
  val lookups=mutableSetOf<String>()
  var repairs=0
  var promiseRepairs=0
  var repeatedLookups=0
  repeat(8){
   val raw=request(JSONArray(observations).toString())
   var action=try{
    normalize(AssistantActions.decode(raw)).also{if(it !is AssistantAction.Lookup)validate(it)}
   }catch(e:Exception){
    if(e is CancellationException)throw e
    if(e !is IllegalArgumentException&&e !is IllegalStateException&&e !is org.json.JSONException&&e !is java.time.DateTimeException)throw e
    if(repairs++>=2)throw IllegalArgumentException("操作信息仍不完整，尚未执行：${e.message}",e)
    progress("正在核对操作信息…")
    observations+=JSONObject().put("kind","validation_error").put("executed",false).put("proposal",raw)
     .put("reason",e.message).put("instruction","仅修复参数或查找真实对象，不改变用户意图、范围和数量；仍缺信息时直接询问。")
    return@repeat
   }
   // A document question cannot be dismissed based only on the visible screen or temporary
   // attachment list. Read the local library once before accepting a text-only answer.
   val requiredLookup=if(action is AssistantAction.Reply)AssistantGrounding.lookupFor(groundingRequest)else null
   if(requiredLookup!=null&&observations.none{it.optString("kind")=="lookup"&&AssistantSurfaces.canonical(it.optString("module"))==requiredLookup.module})action=requiredLookup
   if(action is AssistantAction.Reply&&AssistantReplyGuard.promisesAction(action.text)){
    if(promiseRepairs++>=2)return AssistantAction.Reply("操作指令未能生成，尚未修改任何数据。你刚才的要求已保留，可以重试。")
    progress("正在生成可执行操作…")
    observations+=JSONObject().put("kind","action_missing").put("executed",false).put("proposal",raw)
     .put("instruction","这段回答承诺执行修改，但没有提交操作。按用户原要求及最新纠正返回真实结构化操作，沿用原范围和数量；不要再次要求已经给过的批准。确实缺少必填信息时只问缺失项。禁止把承诺当成执行结果。")
    return@repeat
   }
   if(action !is AssistantAction.Lookup)return action
   val key="${action.module}\u0000${action.query}\u0000${action.id}\u0000${action.offset}\u0000${action.limit}"
   if(!lookups.add(key)){
    if(++repeatedLookups>=2){
     val found=observations.lastOrNull{it.has("result")&&AssistantSurfaces.canonical(it.optString("module"))==AssistantSurfaces.canonical(action.module)}
     return AssistantAction.Reply(found?.let(AssistantLookupFallback::describe)?:"查询没有取得新的结果，尚未执行修改。请补充具体名称或日期，帮助我找到目标。")
    }
    observations+=JSONObject().put("kind","lookup_repeated").put("module",action.module).put("query",action.query)
     .put("instruction","相同查询已执行，请利用已有结果、更换短关键词或向用户澄清；不要重复查询。")
    return@repeat
   }
   progress("正在查找${action.module}资料…")
   val observation=JSONObject().put("kind","lookup").put("module",action.module).put("query",action.query).put("id",action.id).put("offset",action.offset).put("limit",action.limit)
   try{
    val result=JSONObject(lookup(action))
    if(result.has("error"))observation.put("error",result.get("error")).put("instruction","当前ID读取失败，不代表文件正文不存在。用已知文件名、空id重新lookup，取得真实资料库id后继续读取；不要让用户重发文件。")
    else observation.put("result",result)
   }catch(e:CancellationException){throw e}
   catch(e:Exception){observation.put("error",e.message?:"资料读取失败").put("instruction","尚未得到资料；不要声称查到或执行成功。可换关键词；不可凭空补齐。")}
   observations+=observation
   onEvidence(AssistantEvidence.retain(observations))
   // Drop whole old results instead of cutting through JSON or silently losing the current one.
   while(observations.size>1&&JSONArray(observations).toString().length>100000)observations.removeAt(0)
  }
  return AssistantAction.Reply("这次还没找到足够明确的信息，尚未执行修改。请补充要操作的名称、日期或具体内容。")
 }
}

internal object AssistantGrounding {
 fun lookupFor(text:String):AssistantAction.Lookup?=when{
  needsLibrary(text)->AssistantAction.Lookup("资料库","")
  text.contains("弹幕")&&Regex("显示|展示|打开|播放").containsMatchIn(text)&&!Regex("教我|怎么|不要|别显示|别打开|不显示").containsMatchIn(text)->AssistantAction.Lookup("手持弹幕","")
  Regex("轮盘|转盘").containsMatchIn(text)&&Regex("改名|重命名|删除|删掉|选择|选中").containsMatchIn(text)&&!Regex("教我|不要|别删|不改|怎么").containsMatchIn(text)->AssistantAction.Lookup("抽签","")
  Regex("录音|转写").containsMatchIn(text)&&Regex("查找|查一下|搜索|找.{0,12}|回听|生成.{0,8}摘要").containsMatchIn(text)&&!Regex("教我|不要查|别查|怎么").containsMatchIn(text)->AssistantAction.Lookup("会议纪要","")
  else->null
 }
 fun needsLibrary(text:String):Boolean =
  !Regex("教我|怎么(?:使用|操作|上传|导入|保存)|不要查|别查|不用查|不查").containsMatchIn(text)&&
  Regex("资料库|文件|文档|附件|报告|说明|资料").containsMatchIn(text)&&
  Regex("原文|引用|查证|找出|命名|截止|里面.{0,8}(说|写)|告诉我").containsMatchIn(text)
}

/** A stalled model can still show actual transcript matches without inventing an answer. */
internal object AssistantLookupFallback {
 fun describe(observation:JSONObject):String?{
  if(AssistantSurfaces.canonical(observation.optString("module"))!="会议纪要")return null
  val matches=observation.optJSONObject("result")?.optJSONArray("items").objects().flatMap{folder->
   folder.optJSONArray("clips").objects().flatMapIndexed{index,clip->clip.optJSONArray("lines").objects().mapNotNull{line->
    line.optString("text").takeIf{it.isNotBlank()}?.let{quote->
     val seconds=(line.optLong("start").coerceAtLeast(0)/1000)
     "${folder.optString("title")} · ${clip.optString("name").ifBlank{"第${index+1}次录音"}} · ${seconds/60}:${(seconds%60).toString().padStart(2,'0')}\n$quote"
    }
   }}
  }.orEmpty().take(12)
  return matches.takeIf{it.isNotEmpty()}?.joinToString("\n\n",prefix="检索到这些原文片段：\n\n",postfix="\n\n以上来自已检索的转写；没有修改或删除录音。")?.take(12000)
 }
}

/** A recovery trigger, never an executor: only structured, validated actions can write. */
internal object AssistantReplyGuard {
 private val commitment=Regex("(我(?:这就|马上|现在就|会|将|来)|现在就|这就|马上)(?!不|没|无法|不能)[^。！？?\\n]{0,90}(修改|改成|改为|改名|重命名|删除|添加|新建|记入|记到|保存|执行|开启|关闭)")
 fun promisesAction(text:String):Boolean = text.split(Regex("[。！？?\\n]")).any{sentence->
  val s=sentence.trim()
  s.isNotBlank()&&!Regex("如果|假如|例如|比如|示例|你可以说|是否|要不要|确认后|同意后|批准后|不能|无法|不会|不再|没有|尚未|已经|已完成").containsMatchIn(s)&&
   commitment.containsMatchIn(s)
 }
}

internal object AssistantToolContract {
 fun writes(action:AssistantAction.Tool,context:JSONObject):Boolean{
  val module=AssistantSurfaces.canonical(action.module)
  return listOf("globalOperations","pageOperations").firstNotNullOfOrNull{key->
   context.optJSONObject(key)?.let{catalogue->catalogue.keys().asSequence().filter{AssistantSurfaces.canonical(it)==module}
    .flatMap{catalogue.optJSONArray(it).objects().asSequence()}.firstOrNull{it.optString("op")==action.operation}?.optBoolean("writes",true)}
  }?:true
 }
 fun validate(action:AssistantAction.Tool,context:JSONObject){
  val module=AssistantSurfaces.canonical(action.module)
  val operations=linkedMapOf<String,JSONObject>()
  listOf("pageOperations","globalOperations").forEach{key->
   context.optJSONObject(key)?.let{catalogue->catalogue.keys().forEach{name->
    if(AssistantSurfaces.canonical(name)==module)catalogue.optJSONArray(name).objects().forEach{operations[it.getString("op")]=it}
   }}
  }
  context.optJSONObject("current")?.let{current->
   if(AssistantSurfaces.canonical(current.optString("screen"))==module)
    current.optJSONArray("operations").objects().forEach{operations[it.getString("op")]=it}
  }
  val operation=operations[action.operation]?:throw IllegalArgumentException(
   "$module 不支持 ${action.operation}；可用操作：${operations.keys.joinToString()}，不得编造接口")
  val fields=(operation.optJSONArray("fields")?:JSONArray()).objectsAsText()
  val extra=action.args.keys().asSequence().filter{it !in fields}.toList()
  require(extra.isEmpty()){"${action.operation} 不支持参数 ${extra.joinToString()}；允许参数：${fields.joinToString()}"}
  if(module=="资料库"&&action.operation!="save_attachments"&&action.args.has("id")){
   require(context.optJSONArray("attachments").objects().none{it.optString("id")==action.args.optString("id")}){
    "这是临时附件ID，不能读取/修改资料库。先lookup资料库，用空id和文件名检索真实id，再继续完成用户请求。"
   }
  }
  AssistantToolArguments.validate(module,action.operation,action.args)
 }
}

internal object AssistantRecordSelection {
 fun calendar(entries:List<CalendarEntry>,now:java.time.LocalDateTime,limit:Int=500):List<CalendarEntry> =
  entries.sortedWith(compareBy<CalendarEntry>(
   {if(it.kind==EventKind.TODO&&!it.completed)0 else if(!it.completed&&it.end>=now)1 else 2},
   {kotlin.math.abs(java.time.Duration.between(now,it.start).toMinutes())}
  )).take(limit).sortedBy{it.start}
}


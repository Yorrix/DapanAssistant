package cn.dapan.assistant

import java.util.Locale

/** Conservative de-duplication of an event's own packing note, before preview or execution. */
internal object AssistantIntent {
 fun directDestination(request:String):String? {
  val text=request.trim().trimEnd('。','！','!','.')
  val target=if(text in AssistantActions.destinations)text else
   Regex("^(?:(?:请你|请|麻烦你|麻烦|帮我|给我)\\s*)*(?:打开|进入|切换到|跳到)\\s*(.+?)(?:页面|功能)?(?:一下)?$").matchEntire(text)?.groupValues?.get(1)?:return null
  return target.takeIf{it in AssistantActions.destinations}?.let{if(it in setOf("录音摘要","会议摘要"))"会议纪要"else it}
 }
 private val separators=Regex("[\\s\\p{P}]+")
 private fun key(text:String)=text.lowercase(Locale.ROOT).replace(separators,"")
  .replace("带上","带").replace("带着","带").replace("携带","带").replace("拿上","带")
 private val carrying=Regex("^(?:(?:记得|记住|别忘了|别忘|不要忘记|需要|要|请|一定要|记得要)*)?(?:带|携带|拿上).+")
 private val independent=Regex("待办|待办事项|任务清单|打包清单|行李清单|物品清单|tdl|to[ -]?do|(?:单独|独立|另建|另加|再建|再加|额外).{0,12}(?:提醒|事项|任务|记录|清单)|(?:两|2|二)个(?:事项|任务|提醒)",RegexOption.IGNORE_CASE)
 private fun wantsIndependent(request:String)=independent.findAll(request).any{match->
  val preceding=request.substring(maxOf(0,match.range.first-14),match.range.first).substringAfterLast('，').substringAfterLast('。')
  !Regex("不要|不用|不需要|不必|别|无需|无须|不是|不用再|don't|do not|without",RegexOption.IGNORE_CASE).containsMatchIn(preceding)
 }
 private fun noteParts(text:String)=text.split(Regex("[\n；;。，,]+"))
  .map{key(it).removePrefix("备注").removePrefix("记得").removePrefix("别忘了").removePrefix("别忘")}

 fun normalize(action:AssistantAction,request:String):AssistantAction = translationDirection(explicitDuration(normalizePacking(action,request),request),request)

 private fun translationDirection(action:AssistantAction,request:String):AssistantAction {
  if(action !is AssistantAction.Tool||action.module!="实时翻译"||action.operation!="configure")return action
  val names=TranslationLanguages.names.joinToString("|",transform=Regex::escape)
  val match=Regex("($names)\\s*(?:翻译成|翻译为|翻成|译成|译|转成|到)\\s*($names)").findAll(request).toList().singleOrNull()?:return action
  val prefix=request.substring(0,match.range.first).substringAfterLast('，').substringAfterLast('。')
  if(Regex("不|别|不要").containsMatchIn(prefix))return action
  return action.copy(args=org.json.JSONObject(action.args.toString()).put("from",match.groupValues[1]).put("to",match.groupValues[2]))
 }

 /** A reminder tied to one real event is due at that referenced instant, not a minute earlier. */
 fun referenceReminder(action:AssistantAction,request:String,entries:List<CalendarEntry>,today:java.time.LocalDate):AssistantAction {
  if((if(action is AssistantAction.Batch)action.actions else listOf(action)).any{it is AssistantAction.Event&&it.timeReference!=null})return AssistantReferencedTime.normalize(action,request,entries,today)
  if(!Regex("提醒|叫我|通知我").containsMatchIn(request)||Regex("提前|出发|不提醒|不要.{0,4}提醒|别.{0,4}提醒|\\d{1,2}[:：]\\d{2}|[0-9零〇一二两三四五六七八九十]+点").containsMatchIn(request))return action
  val reference=AssistantConversationContext.timeReferences(request,entries,today).singleOrNull()?:return action
  val actions=if(action is AssistantAction.Batch)action.actions else listOf(action)
  val event=actions.filterIsInstance<AssistantAction.Event>().singleOrNull()?.takeIf{it.value.kind==EventKind.TIMED&&it.value.start==reference.time}?:return action
  val sourceLocation=reference.source.location
  val copiedLocation=sourceLocation.isNotBlank()&&event.value.location==sourceLocation&&!request.contains(sourceLocation)&&!Regex("原地|同一个地方|教室|那里|那儿").containsMatchIn(request)
  val notes=event.value.notes.split(Regex("[；;\\n]")).filterNot{Regex("^提前.{1,12}(?:分钟|小时)提醒$").matches(it.trim())}.joinToString("；")
  val updated=event.copy(value=event.value.copy(reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=reference.time,location=if(copiedLocation)""else event.value.location,notes=notes))
  return if(action is AssistantAction.Batch)AssistantAction.Batch(actions.map{if(it===event)updated else it})else updated
 }

 /** A single explicitly stated duration is data, not a reason for another model round. */
 private fun explicitDuration(action:AssistantAction,request:String):AssistantAction {
  val actions=if(action is AssistantAction.Batch)action.actions else listOf(action)
  val timed=actions.filterIsInstance<AssistantAction.Event>().singleOrNull()?.takeIf{it.value.kind==EventKind.TIMED}?:return action
  val amounts=Regex("(?:时长(?:为|是)?|持续|占用|用时|设为|设成|设)\\s*([0-9零〇一二两三四五六七八九十]+)\\s*(分钟|小时)").findAll(request).filter{m->
   val prefix=request.substring(0,m.range.first).substringAfterLast('，').substringAfterLast('。')
   !Regex("不要|不用|别|提醒|提前|路程|通知|备注|标题|名称|录音|视频|[“\"]").containsMatchIn(prefix)
  }.toList()
  val match=amounts.singleOrNull()?:return action
  val n=AssistantConversationContext.minuteNumber(match.groupValues[1])?:return action
  val minutes=if(match.groupValues[2]=="小时")runCatching{Math.multiplyExact(n,60)}.getOrNull()?:return action else n
  if(minutes !in 1..1440)return action
  val updated=timed.copy(value=timed.value.copy(end=timed.value.start.plusMinutes(minutes)))
  return if(action is AssistantAction.Batch)AssistantAction.Batch(actions.map{if(it===timed)updated else it})else updated
 }

 private fun normalizePacking(action:AssistantAction,request:String):AssistantAction {
  val renames=if(action is AssistantAction.Batch)action.actions.filterIsInstance<AssistantAction.RenameEvents>() else listOfNotNull(action as? AssistantAction.RenameEvents)
  require(renames.none{it.names.all{(from,to)->from==to}}||
   !Regex("(?:地点|教室|备注|时间).{0,16}(?:改|换|提前|推迟)|(?:改|换).{0,16}(?:地点|教室|备注|时间)").containsMatchIn(request)){
   "用户要求修改日程属性，但返回了名称不变的改名操作。批量修改地点/备注/时间请用calendar_bulk及对应patch或shiftMinutes；不要返回无效rename_events。"
  }
  if(action !is AssistantAction.Batch||wantsIndependent(request))return action
  // Do not guess which event a packing note belongs to when several events were requested.
  val event=action.actions.filterIsInstance<AssistantAction.Event>().map{it.value}
   .filter{it.kind in setOf(EventKind.TIMED,EventKind.ALL_DAY)}.singleOrNull()?:return action
  val eventNotes=noteParts(event.notes)
  val requestKey=key(request)
  val retained=action.actions.filterNot{candidate->
   val title:String;val detail:String
   when(candidate){
    is AssistantAction.Event->{if(candidate.value.kind!=EventKind.TODO)return@filterNot false;title=candidate.value.title;detail=candidate.value.notes}
    is AssistantAction.Note->{if(candidate.value.module!="待办")return@filterNot false;title=candidate.value.title;detail=candidate.value.detail}
    else->return@filterNot false
   }
   val packing=key(title).removePrefix("记得").removePrefix("别忘了").removePrefix("别忘")
   // A timed reminder already represents the requested task. Keep additional detail or an
   // explicitly requested to-do, but do not create a second copy solely because it is a reminder.
   val sameTask=event.kind==EventKind.TIMED&&event.reminderMode!=ReminderMode.OFF&&
    key(title).length>=2&&key(title)==key(event.title).removeSuffix("提醒")&&
    requestKey.contains(key(title))&&Regex("提醒|叫我|通知我").containsMatchIn(request)&&
    (detail.isBlank()||noteParts(detail).all{it in eventNotes})
   // Exact whole note matching prevents swallowing a separate shopping/preparation task or a
   // longer instruction which merely mentions the same object. User input must also contain it.
   sameTask||carrying.matches(title.trim())&&packing.length>=3&&requestKey.contains(packing)&&
    packing in eventNotes&&(detail.isBlank()||noteParts(detail).all{it in eventNotes})
  }
  return when{retained.size==action.actions.size->action;retained.size==1->retained.single();else->AssistantAction.Batch(retained)}
 }
}

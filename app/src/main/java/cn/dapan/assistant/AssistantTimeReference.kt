package cn.dapan.assistant

import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** The model selects a real record and relation; the device computes the clock.
 * This avoids requiring a spoken sentence to match one hard-coded course-name grammar. */
data class AssistantTimeReference(val eventId:String,val anchor:String,val offsetMinutes:Int=0) {
 companion object {
  fun decode(o:JSONObject):AssistantTimeReference {
   require(o.keys().asSequence().all{it in setOf("eventId","anchor","offsetMinutes")}){"timeReference只接受eventId、anchor、offsetMinutes"}
   require(o.get("eventId") is String&&o.getString("eventId").isNotBlank()){"关联时间需要真实日程eventId"}
   require(o.get("anchor") is String&&o.getString("anchor") in setOf("START","END","DEPARTURE")){"anchor为START/END/DEPARTURE"}
   val offset=if(o.has("offsetMinutes")){
    require(o.get("offsetMinutes") is Number){"offsetMinutes必须为整数"}
    runCatching{java.math.BigDecimal(o.get("offsetMinutes").toString()).intValueExact()}.getOrElse{throw IllegalArgumentException("offsetMinutes必须为整数")}
   }else 0
   require(offset in -1440..1440){"关联时间偏移超出一天，请说明日期"}
   return AssistantTimeReference(o.getString("eventId"),o.getString("anchor"),offset)
  }
 }
 fun source(entries:List<CalendarEntry>)=entries.singleOrNull{it.id==eventId&&it.kind==EventKind.TIMED}
  ?:throw IllegalArgumentException("关联日程不存在或不是有时段的日程，请查询真实记录后重新选择")
 fun time(entries:List<CalendarEntry>):LocalDateTime {
  val e=source(entries)
  val base=when(anchor){"START"->e.start;"END"->e.end;else->e.start.minusMinutes(e.travelMinutes.toLong())}
  return base.plusMinutes(offsetMinutes.toLong())
 }
 fun validate(request:String,entries:List<CalendarEntry>,today:LocalDate) {
  val e=source(entries)
  val pattern=when(anchor){
   "DEPARTURE"->"出发|路上|动身|走的时候"
   "END"->"结束|下课|聊完|上完|开完|完了|完事"
   else->"开始|上课|开课|那节|这节|同一时间"
  }
  require(Regex(pattern).containsMatchIn(request)){"用户没有要求关联该日程的${anchor}时间，不能用其它记录补出未说的时间"}
  val relative=AssistantUtterance.mentions(Regex("大后天|后天|明天|明日|今天|今日|昨天|昨日"),request).lastOrNull()?.value
  val day=when(relative){"大后天"->today.plusDays(3);"后天"->today.plusDays(2);"明天","明日"->today.plusDays(1);"今天","今日"->today;"昨天","昨日"->today.minusDays(1);else->null}
  require(day==null||e.start.toLocalDate()==day){"关联的是${e.start.toLocalDate()}日程，用户要的是$day，请查找正确日期"}
  var candidates=entries.filter{it.kind==EventKind.TIMED&&it.title==e.title&&it.start.toLocalDate()==e.start.toLocalDate()}.sortedBy{it.start}
  val period=AssistantUtterance.mentions(Regex("早上|上午|下午|晚上|夜里"),request).lastOrNull()?.value
  if(period!=null)candidates=candidates.filter{when(period){"早上","上午"->it.start.hour<12;"下午"->it.start.hour in 12..17;else->it.start.hour>=18}}
  val ordinal=AssistantUtterance.mentions(Regex("第([一二三123])节"),request).lastOrNull()?.groupValues?.get(1)
  if(ordinal!=null)candidates=listOfNotNull(candidates.getOrNull(when(ordinal){"一","1"->0;"二","2"->1;else->2}))
  require(candidates.any{it.id==e.id}){"关联课程不符合用户指定的时段或第几节，请重新定位"}
  val clock=Regex("[0-9零〇一二两三四五六七八九十]+(?:[:：点时])")
  require(candidates.size<=1||clock.containsMatchIn(request)||Regex("选中|这个|这节|刚才那").containsMatchIn(request)){
   "当天有多节${e.title}：${candidates.joinToString{it.start.toLocalTime().toString()}}。用户未区分哪一节，只询问上午/下午或具体一节，不能随意挑选。"
  }
  if(offsetMinutes!=0)require(Regex("(?:提前|之前|之后|前|后|过|再等|再过)\\s*[0-9零〇一二两三四五六七八九十]+\\s*分钟|[0-9零〇一二两三四五六七八九十]+\\s*分钟(?:前|后)").containsMatchIn(request)){
   "没有指定相对偏移分钟数，应使用offsetMinutes=0；用户改成明确时刻时，移除timeReference，直接采用新时刻。"
  }
 }
}

internal object AssistantReferencedTime {
 fun normalize(action:AssistantAction,request:String,entries:List<CalendarEntry>,today:LocalDate):AssistantAction {
  if(action is AssistantAction.Batch)return action.copy(actions=action.actions.map{normalize(it,request,entries,today)})
  if(action !is AssistantAction.Event||action.timeReference==null)return action
  val latest=request.substringAfterLast('\n')
  if(Regex("改成|改为|改到|换成|换到|不是|不对").containsMatchIn(latest)&&Regex("[0-9零〇一二两三四五六七八九十]+(?:[:：点时])").containsMatchIn(latest))return action.copy(timeReference=null)
  require(action.value.kind==EventKind.TIMED){"关联时间仅用于普通日程"}
  val ref=action.timeReference
  ref.validate(request,entries,today)
  val start=ref.time(entries)
  val duration=Duration.between(action.value.start,action.value.end)
  val e=action.value
  // A simple “then remind me” means a notification at that instant, not another
  // arbitrary ten-minute lead time. Explicit lead/disabled reminders remain explicit.
  val atTime=Regex("提醒|叫我|通知我").containsMatchIn(request)&&!Regex("不提醒|不要.{0,4}提醒|别.{0,4}提醒|提前.{0,8}(?:提醒|叫我|通知)").containsMatchIn(request)
  val value=e.copy(start=start,end=start.plus(duration),
   travelMinutes=if(atTime&&!Regex("(?:路程|路上|步行|走路).{0,6}[0-9零〇一二两三四五六七八九十]+\\s*分").containsMatchIn(request))0 else e.travelMinutes,
   reminderMode=if(atTime)ReminderMode.ABSOLUTE else e.reminderMode,
   absoluteReminder=if(atTime)start else e.absoluteReminder?.plus(Duration.between(e.start,start)))
  require(value.error()==null){value.error()!!}
  return action.copy(value=value)
 }
}

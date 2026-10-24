package cn.dapan.assistant

import java.time.*
import java.util.UUID

enum class EventKind { TIMED, DEADLINE, TODO, ALL_DAY }
enum class ReminderMode { OFF, DEPARTURE, BEFORE, ABSOLUTE }
data class CalendarEntry(
 val id: String = UUID.randomUUID().toString(), val title: String,
 val kind: EventKind = EventKind.TIMED, val start: LocalDateTime,
 val end: LocalDateTime = minOf(start.plusHours(1),start.toLocalDate().atTime(23,59)), val travelMinutes: Int = 0,
 val reminderMode: ReminderMode = ReminderMode.OFF, val beforeMinutes: Int = 10,
 val absoluteReminder: LocalDateTime? = null, val category: String = "",
 val location: String = "", val notes: String = "", val completed: Boolean = false,
 val urgent: Boolean = false
) {
 fun normalized():CalendarEntry = when(kind) {
  EventKind.ALL_DAY -> copy(start=start.toLocalDate().atStartOfDay(),end=start.toLocalDate().plusDays(1).atStartOfDay(),travelMinutes=0)
  EventKind.DEADLINE -> copy(end=start,travelMinutes=0)
  EventKind.TODO -> copy(end=start,travelMinutes=0,reminderMode=ReminderMode.OFF,absoluteReminder=null)
  else -> this
 }
 fun reminderTime(): LocalDateTime? = if(kind==EventKind.TODO) null else when(reminderMode) {
  ReminderMode.OFF -> null
  ReminderMode.DEPARTURE -> if (kind == EventKind.TIMED && travelMinutes > 0) start.minusMinutes(travelMinutes.toLong()) else null
  ReminderMode.BEFORE -> start.minusMinutes(beforeMinutes.toLong())
  ReminderMode.ABSOLUTE -> absoluteReminder
 }
 val crossesDay: Boolean get() = kind==EventKind.TIMED && start.toLocalDate()!=end.toLocalDate()
 fun error(): String? = when {
  title.isBlank() -> "请填写名称"
  kind == EventKind.TODO && reminderMode != ReminderMode.OFF -> "TDL 不设置时间提醒"
  crossesDay -> "日程不能跨天，请将开始和结束时间设在同一天"
  kind == EventKind.TIMED && !end.isAfter(start) -> "结束时间必须晚于开始时间"
  travelMinutes !in 0..1440 -> "路程时间请输入 0～1440 分钟"
  kind != EventKind.TIMED && travelMinutes != 0 -> "只有普通日程可设置路程"
  reminderMode == ReminderMode.DEPARTURE && (kind != EventKind.TIMED || travelMinutes <= 0) -> "出发提醒需要设置路程时间"
  reminderMode == ReminderMode.BEFORE && beforeMinutes <= 0 -> "提前时间必须大于 0"
  reminderMode == ReminderMode.ABSOLUTE && absoluteReminder == null -> "请选择提醒时间"
  reminderTime()?.let { !it.isBefore(start) } == true -> "提醒必须早于事项开始时间"
  else -> null
 }
}
fun NoteItem.asTodo() = CalendarEntry(id=id,title=title,kind=EventKind.TODO,
 start=LocalDateTime.ofInstant(Instant.ofEpochMilli(created),ZoneId.systemDefault()),
 notes=detail,completed=done,urgent=group=="紧急").normalized()
data class DaySpan(val startMinute:Float,val endMinute:Float)
data class EventDaySlices(val body:DaySpan?,val travel:DaySpan?)
object CalendarSlices {
 fun forDay(e:CalendarEntry,date:LocalDate):EventDaySlices {
  val from=date.atStartOfDay();val until=date.plusDays(1).atStartOfDay()
  fun clip(start:LocalDateTime,end:LocalDateTime):DaySpan? {
   val a=maxOf(start,from);val b=minOf(end,until)
   if(a>=b)return null
   return DaySpan(Duration.between(from,a).seconds/60f,Duration.between(from,b).seconds/60f)
  }
  if(e.kind!=EventKind.TIMED)return EventDaySlices(null,null)
  return EventDaySlices(clip(e.start,e.end),if(e.travelMinutes>0)clip(e.start.minusMinutes(e.travelMinutes.toLong()),e.start)else null)
 }
}
data class NoteItem(val id: String = UUID.randomUUID().toString(), val module: String, val title: String, val detail: String = "", val group: String = "一般", val done: Boolean = false, val created: Long = System.currentTimeMillis(),val attachments:List<String> = emptyList())
data class CheckDay(val date: LocalDate, val required: Boolean? = null, val passed: Boolean = false)
data class MorningStats(val required: Int, val passed: Int) { val missed get() = required - passed; val rate get() = if (required == 0) null else passed.toDouble()/required }
object Rules {
 data class Lane(val index: Int,val count: Int)
 fun lanes(entries: List<CalendarEntry>): Map<String,Lane> {
  val result=mutableMapOf<String,Lane>(); val sorted=entries.sortedBy{it.start}; var group=mutableListOf<CalendarEntry>(); var end:LocalDateTime?=null
  fun flush(){if(group.isEmpty())return;val ends=mutableListOf<LocalDateTime>();val assigned=mutableMapOf<String,Int>();group.forEach{e->var lane=ends.indexOfFirst{!it.isAfter(e.start)};if(lane<0){lane=ends.size;ends.add(e.end)}else ends[lane]=e.end;assigned[e.id]=lane};assigned.forEach{(id,index)->result[id]=Lane(index,ends.size)};group=mutableListOf()}
  sorted.forEach{e->if(end!=null&&!e.start.isBefore(end)){flush();end=null};group.add(e);end=maxOf(end?:e.end,e.end)};flush();return result
 }
 // Official annual overrides are populated separately; weekdays are only the fallback.
 fun required(date: LocalDate, overrides: Map<LocalDate, Boolean>, holidays: Map<LocalDate, Boolean>): Boolean = overrides[date] ?: holidays[date] ?: (date.dayOfWeek.value <= 5)
 fun morning(start: LocalDate, today: LocalDate, records: List<CheckDay>, holidays: Map<LocalDate, Boolean>): MorningStats {
  val byDay = records.associateBy { it.date }; var required = 0; var passed = 0; var day = start
  while (!day.isAfter(today)) {
   if (byDay[day]?.required ?: holidays[day] ?: (day.dayOfWeek.value <= 5)) { required++; if (byDay[day]?.passed == true) passed++ }
   day = day.plusDays(1)
  }; return MorningStats(required, passed)
 }
 fun deadlineBucket(time: LocalDateTime, now: LocalDateTime): String = when {
  time.isBefore(now) -> "已逾期"
  time.toLocalDate() == now.toLocalDate() -> "今天"
  time.toLocalDate().isBefore(now.toLocalDate().plusDays(7)) -> "七天内"
  else -> "更晚"
 }
 fun weightedIndex(weights: List<Double>, random: Double): Int {
  require(weights.isNotEmpty() && weights.all { it.isFinite() && it >= 0 } && weights.sum().isFinite() && weights.sum() > 0)
  require(random >= 0 && random < 1)
  val target = random * weights.sum(); var sum = 0.0
  weights.forEachIndexed { index, value -> sum += value; if (target < sum) return index }
  return weights.indexOfLast { it > 0 }
 }
}

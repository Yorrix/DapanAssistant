package cn.dapan.assistant

import java.time.LocalDate

data class CalendarDayItem(val entry:CalendarEntry,val body:DaySpan?,val travel:List<DaySpan>,val lane:Rules.Lane)

/** Reuse overlap calculations when returning from a tool. Data identity invalidates the cache. */
internal class CalendarRenderCache {
 private var records:List<CalendarEntry>?=null
 private var week:LocalDate?=null
 private var days:Map<LocalDate,List<CalendarDayItem>> = emptyMap()
 private var overlaps:Map<LocalDate,List<CalendarOverlapGroup>> = emptyMap()
 fun groups(entries:List<CalendarEntry>,start:LocalDate):Map<LocalDate,List<CalendarOverlapGroup>>{week(entries,start);return overlaps}
 fun week(entries:List<CalendarEntry>,start:LocalDate):Map<LocalDate,List<CalendarDayItem>> {
  if(records!==entries||week!=start){
   val grouped=entries.filter{!it.crossesDay&&it.kind==EventKind.TIMED}.groupBy{it.start.toLocalDate()}
   days=(0 until 7).associate{
    val date=start.plusDays(it.toLong())
    // A same-day event may still require travel starting on the previous evening.
    val candidates=grouped[date].orEmpty()+grouped[date.plusDays(1)].orEmpty().filter{it.travelMinutes>0}
    date to CalendarDayLayout.build(candidates,date)
   }
   overlaps=days.mapValues{CalendarOverlap.groups(it.value)}
   records=entries;week=start
  }
  return days
 }
}

/** Only actual event bodies occupy lanes. Travel is drawn in otherwise free time. */
object CalendarDayLayout {
 internal fun visible(items:List<CalendarDayItem>,axis:CalendarTimeAxis):List<CalendarDayItem>{
  if(axis.startHour==0)return items
  val clipped=items.map{it.copy(body=axis.clip(it.body),travel=it.travel.mapNotNull(axis::clip))}
   .filter{it.body!=null||it.travel.isNotEmpty()}
  // Hidden early events must not leave empty lanes in the visible part of the day.
  val lanes=Rules.lanes(clipped.filter{it.body!=null}.map{it.entry})
  return clipped.map{it.copy(lane=lanes[it.entry.id]?:Rules.Lane(0,1))}
 }
 fun build(entries:List<CalendarEntry>,date:LocalDate):List<CalendarDayItem> {
  val sliced=entries.asSequence().filter{it.kind==EventKind.TIMED}
   .map{it to CalendarSlices.forDay(it,date)}.filter{it.second.body!=null||it.second.travel!=null}.toList()
  val bodies=sliced.mapNotNull{it.second.body}
  val lanes=Rules.lanes(sliced.filter{it.second.body!=null}.map{it.first})
  return sliced.map{(entry,slices)->
   var free=listOfNotNull(slices.travel)
   bodies.forEach{occupied->free=free.flatMap{span->
    if(occupied.endMinute<=span.startMinute||occupied.startMinute>=span.endMinute)listOf(span)
    else buildList {
     if(span.startMinute<occupied.startMinute)add(DaySpan(span.startMinute,occupied.startMinute))
     if(span.endMinute>occupied.endMinute)add(DaySpan(occupied.endMinute,span.endMinute))
    }
   }}
   CalendarDayItem(entry,slices.body,free,lanes[entry.id]?:Rules.Lane(0,1))
  }
 }
}

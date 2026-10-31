package cn.dapan.assistant

import java.time.LocalDate

data class CalendarDayItem(val entry:CalendarEntry,val body:DaySpan?,val travel:List<DaySpan>,val lane:Rules.Lane)

/** Index records once, then reuse per-day layouts as a continuous viewport moves. */
internal class CalendarRenderCache {
 private var records:List<CalendarEntry>?=null
 private var grouped:Map<LocalDate,List<CalendarEntry>> = emptyMap()
 private var window:Pair<LocalDate,Int>?=null
 private val layouts=object:LinkedHashMap<LocalDate,List<CalendarDayItem>>(64,.75f,true){
  override fun removeEldestEntry(eldest:MutableMap.MutableEntry<LocalDate,List<CalendarDayItem>>?)=size>64
 }
 private var days:Map<LocalDate,List<CalendarDayItem>> = emptyMap()
 private var overlaps:Map<LocalDate,List<CalendarOverlapGroup>> = emptyMap()
 fun groups(entries:List<CalendarEntry>,start:LocalDate,count:Int=7):Map<LocalDate,List<CalendarOverlapGroup>>{range(entries,start,count);return overlaps}
 fun week(entries:List<CalendarEntry>,start:LocalDate)=range(entries,start,7)
 fun range(entries:List<CalendarEntry>,start:LocalDate,count:Int):Map<LocalDate,List<CalendarDayItem>> {
  require(count in 1..32)
  if(records!==entries){
   grouped=entries.filter{!it.crossesDay&&it.kind==EventKind.TIMED}.groupBy{it.start.toLocalDate()}
   records=entries;window=null;layouts.clear()
  }
  if(window!=(start to count)){
   days=(0 until count).associate{
    val date=start.plusDays(it.toLong())
    // A same-day event may still require travel starting on the previous evening.
    date to layouts.getOrPut(date){
     val candidates=grouped[date].orEmpty()+grouped[date.plusDays(1)].orEmpty().filter{it.travelMinutes>0}
     CalendarDayLayout.build(candidates,date)
    }
   }
   overlaps=days.mapValues{CalendarOverlap.groups(it.value)}
   window=start to count
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

package cn.dapan.assistant

import java.time.*

/** A single system wake-up drains every due item, including coincident reminders.
 * This avoids hundreds of registered alarms and per-app idle quotas between items. */
internal data class ReminderTicket(val key:String,val due:Long,val expires:Long)
internal data class PlannedReminder(val ticket:ReminderTicket,val wakeAt:Long)

internal object ReminderQueue {
 fun plan(tickets:List<ReminderTicket>,now:Long,previous:(String)->Long,finished:(String)->Long):List<PlannedReminder> =
  tickets.mapNotNull{ticket->
   ReminderTiming.target(ticket.due,now,previous(ticket.key),finished(ticket.key),ticket.expires)?.let{PlannedReminder(ticket,it)}
  }
 fun nextWake(items:List<PlannedReminder>)=items.minOfOrNull{it.wakeAt}
 fun due(items:List<PlannedReminder>,now:Long)=items.map{it.ticket}.filter{it.due<=now&&now<it.expires}
 fun daily(key:String,now:ZonedDateTime,time:LocalTime,previous:Long,finished:Long,start:LocalDate?=null,end:LocalDate?=null):ReminderTicket?{
  var day=maxOf(now.toLocalDate(),start?:now.toLocalDate())
  fun ticket(date:LocalDate)=ReminderTicket(key,date.atTime(time).atZone(now.zone).toInstant().toEpochMilli(),date.plusDays(1).atStartOfDay(now.zone).toInstant().toEpochMilli())
  var next=ticket(day)
  if(ReminderTiming.target(next.due,now.toInstant().toEpochMilli(),previous,finished,next.expires)==null){
   day=day.plusDays(1);next=ticket(day)
  }
  return next.takeIf{end==null||day<=end}
 }
}

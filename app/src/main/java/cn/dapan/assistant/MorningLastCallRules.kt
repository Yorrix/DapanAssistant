package cn.dapan.assistant

import java.time.*

/** The date belongs to the sign-in deadline, even when its one-minute warning is the night before. */
internal data class MorningLastCallTicket(val date:LocalDate,val due:Long,val deadline:Long)

internal object MorningLastCallRules {
 fun required(date:LocalDate,start:LocalDate?,end:LocalDate?,records:List<CheckDay>,holidays:Map<LocalDate,Boolean>):Boolean =
  start!=null && date>=start && (end==null||date<=end) && records.none{it.date==date} && (holidays[date]?: (date.dayOfWeek.value<=5))

 fun ticket(date:LocalDate,time:LocalTime,zone:ZoneId):MorningLastCallTicket {
  val deadline=date.atTime(time).atZone(zone).toInstant().toEpochMilli()
  return MorningLastCallTicket(date,deadline-60_000,deadline)
 }

 fun next(enabled:Boolean,start:LocalDate?,end:LocalDate?,time:LocalTime,now:ZonedDateTime,
          records:List<CheckDay>,holidays:Map<LocalDate,Boolean>,previousDue:Long=0,finishedDate:LocalDate?=null):MorningLastCallTicket? {
  if(!enabled||start==null||(end!=null&&end<start))return null
  var day=maxOf(start,now.toLocalDate())
  val millis=now.toInstant().toEpochMilli()
  while(end==null||day<=end){
   if(day!=finishedDate && required(day,start,end,records,holidays)){
    val candidate=ticket(day,time,now.zone)
    // Fresh opt-in after the warning time starts on the next eligible day. A restored
    // existing alarm can still fire within its final minute, never after the deadline.
    if(candidate.due>=millis || (candidate.due==previousDue && millis<candidate.deadline))return candidate
   }
   if(day==LocalDate.MAX)return null
   day=day.plusDays(1)
  }
  return null
 }

 fun canDeliver(planned:MorningLastCallTicket,now:Long,enabled:Boolean,start:LocalDate?,end:LocalDate?,time:LocalTime,
                zone:ZoneId,records:List<CheckDay>,holidays:Map<LocalDate,Boolean>,finishedDate:LocalDate?):Boolean =
  enabled && finishedDate!=planned.date && required(planned.date,start,end,records,holidays) &&
   planned==ticket(planned.date,time,zone) && now>=planned.due && now<planned.deadline
}

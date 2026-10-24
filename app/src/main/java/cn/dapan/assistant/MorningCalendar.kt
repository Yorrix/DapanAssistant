package cn.dapan.assistant

import java.time.*

enum class MorningStatus { EXCLUDED, FAILED, PASSED }

object MorningCalendar {
 val zone:ZoneId=ZoneId.of("Asia/Shanghai")
 val defaultTime:LocalTime=LocalTime.of(7,30)
 fun now():LocalDateTime=LocalDateTime.now(zone)
 fun elapsedDate(now:LocalDateTime,time:LocalTime)=if(now.toLocalTime()<time)now.toLocalDate().minusDays(1)else now.toLocalDate()
 fun editable(date:LocalDate,start:LocalDate?,end:LocalDate?,now:LocalDateTime,time:LocalTime)=
  start!=null&&date>=start&&(end==null||date<=end)&&date<=elapsedDate(now,time)
 fun status(date:LocalDate,start:LocalDate?,end:LocalDate?,now:LocalDateTime,time:LocalTime,record:CheckDay?,holidays:Map<LocalDate,Boolean>):MorningStatus {
  if(!editable(date,start,end,now,time))return MorningStatus.EXCLUDED
  if(!(record?.required?:holidays[date]?:(date.dayOfWeek.value<=5)))return MorningStatus.EXCLUDED
  return if(record?.passed==true)MorningStatus.PASSED else MorningStatus.FAILED
 }
 fun next(date:LocalDate,status:MorningStatus)=when(status){
  MorningStatus.EXCLUDED->CheckDay(date,true,false)
  MorningStatus.FAILED->CheckDay(date,true,true)
  MorningStatus.PASSED->CheckDay(date,false,false)
 }
 fun canMark(date:LocalDate,today:LocalDate)=!date.isAfter(today)
 // Historic/manual marks remain visible outside the statistics range. A missing
 // required override still follows national workdays; passed alone cannot make a holiday required.
 // Future dates remain locked and visually muted in the UI.
 fun displayStatus(record:CheckDay?,fallback:MorningStatus,holidays:Map<LocalDate,Boolean>):MorningStatus=when{
  record==null->fallback
  !(record.required?:holidays[record.date]?:(record.date.dayOfWeek.value<=5))->MorningStatus.EXCLUDED
  record.passed->MorningStatus.PASSED
  else->MorningStatus.FAILED
 }
 fun stats(start:LocalDate?,end:LocalDate?,now:LocalDateTime,time:LocalTime,records:List<CheckDay>,holidays:Map<LocalDate,Boolean>):MorningStats {
  if(start==null)return MorningStats(0,0)
  return Rules.morning(start,minOf(end?:LocalDate.MAX,elapsedDate(now,time)),records,holidays)
 }
}

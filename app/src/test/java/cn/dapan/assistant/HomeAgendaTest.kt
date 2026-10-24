package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class HomeAgendaTest {
 private val now=LocalDateTime.of(2026,10,16,12,0)
 private fun event(name:String,kind:EventKind=EventKind.TIMED,start:LocalDateTime=now.plusHours(1),urgent:Boolean=false)=CalendarEntry(title=name,kind=kind,start=start,urgent=urgent).normalized()
 @Test fun defaultsKeepNextAppointmentAndPrioritizedItems(){
  val next=event("下一节课");val later=event("晚课",start=now.plusHours(4));val overdue=event("逾期",EventKind.DEADLINE,now.minusDays(1));val due=event("今日截止",EventKind.DEADLINE);val urgent=event("紧急",EventKind.TODO,urgent=true);val normal=event("普通",EventKind.TODO)
  assertEquals(listOf(next,overdue,due,urgent),TodayAgenda.choose(listOf(normal,later,urgent,due,next,overdue),now))
 }
 @Test fun typeCountsAndUrgentFilterAreApplied(){
  val urgent=event("紧急",EventKind.TODO,urgent=true)
  assertEquals(listOf(urgent),TodayAgenda.choose(listOf(event("课"),event("截止",EventKind.DEADLINE),event("普通",EventKind.TODO),urgent),now,AgendaOptions(timedLimit=0,ddlLimit=0,urgentOnly=true)))
 }
 @Test fun ongoingAndTomorrowAreOptIn(){
  val running=event("进行中",start=now.minusMinutes(30));val next=event("明天",start=now.plusDays(1))
  assertTrue(TodayAgenda.choose(listOf(running,next),now).isEmpty())
  assertEquals(listOf(running,next),TodayAgenda.choose(listOf(running,next),now,AgendaOptions(timedLimit=3,timedDays=2,ongoing=true)))
 }
 @Test fun allDayEndIsExclusiveAndCompletedItemsStayHidden(){
  val yesterday=event("昨天",EventKind.ALL_DAY,now.minusDays(1));val today=event("今天",EventKind.ALL_DAY);val completed=event("已完成",EventKind.TODO).copy(completed=true)
  assertEquals(listOf(today),TodayAgenda.choose(listOf(yesterday,today,completed),now,AgendaOptions(allDay=true)))
 }
 @Test fun horizonAndTotalLimitDoNotLeakExtraItems(){
  val near=event("近",EventKind.DEADLINE,now.plusDays(2));val far=event("远",EventKind.DEADLINE,now.plusDays(8))
  assertEquals(listOf(near),TodayAgenda.choose(listOf(far,near),now,AgendaOptions(ddlDays=3)))
  assertEquals(1,TodayAgenda.choose(listOf(event("课"),near,far),now,AgendaOptions(maxItems=1,ddlDays=30)).size)
 }
}

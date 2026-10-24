package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SameDayEventTest {
 private val day=LocalDate.of(2026,10,15)
 @Test fun timedEventsCannotCrossMidnightEvenForOneMinute(){
  val e=CalendarEntry(title="夜间",start=day.atTime(23,59),end=day.plusDays(1).atStartOfDay())
  assertTrue(e.crossesDay);assertNotNull(e.error())
 }
 @Test fun sameDayAllowsLongEventsButNotReversedTime(){
  val e=CalendarEntry(title="活动",start=day.atTime(0,0),end=day.atTime(23,59))
  assertNull(e.error());assertNotNull(e.copy(end=e.start).error())
 }
 @Test fun allDayStillUsesExclusiveNextDayBoundary(){
  val e=CalendarEntry(title="全天",start=day.atTime(8,0),kind=EventKind.ALL_DAY).normalized()
  assertFalse(e.crossesDay);assertNull(e.error())
 }
 @Test fun defaultEndStaysInSameDate(){
  val e=CalendarEntry(title="晚间",start=day.atTime(23,30))
  assertEquals(day.atTime(23,59),e.end);assertNull(e.error())
 }
}

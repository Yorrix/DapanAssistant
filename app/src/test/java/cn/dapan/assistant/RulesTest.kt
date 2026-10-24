package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
import java.time.*
class RulesTest {
 private val start=LocalDateTime.of(2026,10,14,8,0)
 @Test fun travelDoesNotForceReminder(){val e=CalendarEntry(title="上课",start=start,travelMinutes=20);assertNull(e.reminderTime());assertNull(e.error())}
 @Test fun departureUsesTravel(){val e=CalendarEntry(title="上课",start=start,travelMinutes=20,reminderMode=ReminderMode.DEPARTURE);assertEquals(start.minusMinutes(20),e.reminderTime())}
 @Test fun rejectsReminderAtOrAfterStart(){listOf(start,start.plusMinutes(1)).forEach{assertNotNull(CalendarEntry(title="课",start=start,reminderMode=ReminderMode.ABSOLUTE,absoluteReminder=it).error())}}
 @Test fun deadlineNeedsNoDuration(){assertNull(CalendarEntry(title="交作业",kind=EventKind.DEADLINE,start=start,end=start).error());assertNotNull(CalendarEntry(title="上课",start=start,end=start).error())}
 @Test fun missingCheckMeansFailedAndHolidayExcluded(){val monday=LocalDate.of(2026,10,12);val stats=Rules.morning(monday,monday.plusDays(2),listOf(CheckDay(monday,passed=true)),mapOf(monday.plusDays(1) to false));assertEquals(2,stats.required);assertEquals(1,stats.missed);assertEquals(.5,stats.rate!!,0.0)}
 @Test fun manualOverrideWins(){val day=LocalDate.of(2026,10,10);val stats=Rules.morning(day,day,listOf(CheckDay(day,false,false)),mapOf(day to true));assertEquals(0,stats.required);assertNull(stats.rate)}
 @Test fun makeupWeekendIncluded(){val day=LocalDate.of(2026,10,10);assertEquals(1,Rules.morning(day,day,emptyList(),mapOf(day to true)).required)}
 @Test fun zeroWeightNeverWins(){assertEquals(1,Rules.weightedIndex(listOf(0.0,1.0,0.0),0.0));assertEquals(1,Rules.weightedIndex(listOf(0.0,1.0,0.0),.999999))}
 @Test fun weightedBoundary(){assertEquals(0,Rules.weightedIndex(listOf(1.0,3.0),.249));assertEquals(1,Rules.weightedIndex(listOf(1.0,3.0),.25))}
 @Test fun deadlineBuckets(){assertEquals("已逾期",Rules.deadlineBucket(start.minusSeconds(1),start));assertEquals("今天",Rules.deadlineBucket(start.plusHours(1),start));assertEquals("七天内",Rules.deadlineBucket(start.plusDays(6),start));assertEquals("更晚",Rules.deadlineBucket(start.plusDays(7),start))}
 @Test fun chainedOverlapHasConsistentColumns(){val a=CalendarEntry(title="a",start=start,end=start.plusHours(2));val b=CalendarEntry(title="b",start=start.plusHours(1),end=start.plusHours(3));val c=CalendarEntry(title="c",start=start.plusHours(2),end=start.plusHours(4));val lanes=Rules.lanes(listOf(a,b,c));assertEquals(2,lanes[a.id]!!.count);assertEquals(2,lanes[b.id]!!.count);assertEquals(2,lanes[c.id]!!.count);assertEquals(lanes[a.id]!!.index,lanes[c.id]!!.index);assertNotEquals(lanes[b.id]!!.index,lanes[c.id]!!.index)}
}

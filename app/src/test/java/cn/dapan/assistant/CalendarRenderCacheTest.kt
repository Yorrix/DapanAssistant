package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarRenderCacheTest {
 @Test fun continuousPanningReusesSharedDaysButInvalidatesEditedEvents(){
  val day=LocalDate.of(2026,10,30)
  val rows=(0..40).map{CalendarEntry(title="活动$it",start=day.plusDays(it.toLong()).atTime(8,0),end=day.plusDays(it.toLong()).atTime(9,0))}
  val cache=CalendarRenderCache()
  val first=cache.range(rows,day,12)
  val next=cache.range(rows,day.plusDays(1),12)
  assertSame(first[day.plusDays(5)],next[day.plusDays(5)])
  assertSame(next,cache.range(rows,day.plusDays(1),12))
  next.forEach{(date,items)->assertEquals(CalendarDayLayout.build(rows,date),items)}
  val changed=cache.range(rows.map{it.copy(location="A01")},day.plusDays(1),12)
  assertEquals("A01",changed[day.plusDays(5)]!!.single().entry.location)
 }
 @Test fun overlapGroupsReuseButUpdateAfterEditingRecords(){
  val day=LocalDate.of(2026,10,19)
  val rows=listOf(CalendarEntry(title="课",start=day.atTime(8,0),end=day.atTime(10,0)),CalendarEntry(title="活动",start=day.atTime(9,0),end=day.atTime(11,0)))
  val cache=CalendarRenderCache();val first=cache.groups(rows,day)
  assertSame(first,cache.groups(rows,day));assertEquals(2,first[day]!!.single().lanes)
  val changed=cache.groups(rows.dropLast(1),day)
  assertEquals(1,changed[day]!!.single().lanes)
 }
 @Test fun returnsReuseAndInvalidatesOnWeekOrRecordChange(){
  val day=LocalDate.of(2026,10,19)
  val rows=listOf(CalendarEntry(title="课",start=day.atTime(8,0),end=day.atTime(9,0)))
  val cache=CalendarRenderCache();val first=cache.week(rows,day)
  assertSame(first,cache.week(rows,day))
  val changed=cache.week(rows.map{it.copy(title="新名称")},day)
  assertEquals("新名称",changed[day]!!.single().entry.title)
  assertTrue(cache.week(rows,day.plusWeeks(1)).values.all{it.isEmpty()})
 }
 @Test fun cachedLayoutMatchesFullCalendarForSameDayEvents(){
  val day=LocalDate.of(2026,10,19)
  val rows=(0..30).map{CalendarEntry(title="课$it",start=day.plusDays(it.toLong()).atTime(8,0),end=day.plusDays(it.toLong()).atTime(10,0),travelMinutes=20)}
  CalendarRenderCache().week(rows,day).forEach{(date,items)->assertEquals(CalendarDayLayout.build(rows,date),items)}
 }
 @Test fun previousEveningTravelIsNotLostWhenGroupingByDate(){
  val day=LocalDate.of(2026,10,19)
  val rows=listOf(CalendarEntry(title="凌晨出行",start=day.plusDays(1).atTime(0,15),end=day.plusDays(1).atTime(1,0),travelMinutes=30))
  val result=CalendarRenderCache().week(rows,day)[day]!!
  assertEquals(CalendarDayLayout.build(rows,day),result)
  assertEquals(DaySpan(1425f,1440f),result.single().travel.single())
 }
}

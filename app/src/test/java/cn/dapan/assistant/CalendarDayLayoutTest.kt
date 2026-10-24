package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarDayLayoutTest {
 private val day=LocalDate.of(2026,10,15)
 @Test fun travelDoesNotSplitAdjacentClasses(){
  val first=CalendarEntry(title="第一节",start=day.atTime(8,0),end=day.atTime(9,40))
  val next=CalendarEntry(title="第二节",start=day.atTime(9,50),end=day.atTime(11,30),travelMinutes=20)
  val result=CalendarDayLayout.build(listOf(first,next),day)
  assertTrue(result.all{it.lane.count==1})
  assertEquals(listOf(DaySpan(580f,590f)),result.single{it.entry.id==next.id}.travel)
  assertEquals(20,next.travelMinutes)
 }
 @Test fun actualOverlapsStillHaveSeparateColumns(){
  val first=CalendarEntry(title="第一节",start=day.atTime(8,0),end=day.atTime(10,0))
  val second=CalendarEntry(title="第二节",start=day.atTime(9,30),end=day.atTime(11,0),travelMinutes=20)
  val result=CalendarDayLayout.build(listOf(first,second),day)
  assertTrue(result.all{it.lane.count==2})
  assertNotEquals(result[0].lane.index,result[1].lane.index)
  assertTrue(result.single{it.entry.id==second.id}.travel.isEmpty())
 }
 @Test fun travelOnPreviousDayDoesNotOccupyAnEventLane(){
  val next=CalendarEntry(title="跨午夜",start=day.plusDays(1).atTime(0,10),travelMinutes=30)
  val result=CalendarDayLayout.build(listOf(next),day).single()
  assertNull(result.body)
  assertEquals(listOf(DaySpan(1420f,1440f)),result.travel)
  assertEquals(Rules.Lane(0,1),result.lane)
 }
 @Test fun fullSemesterOnlyRendersSelectedDay(){
  val entries=(0..100).map{CalendarEntry(title="课",start=day.plusDays(it.toLong()).atTime(8,0))}
  assertEquals(listOf(entries[30].id),CalendarDayLayout.build(entries,day.plusDays(30)).map{it.entry.id})
 }
}

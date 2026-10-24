package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarTimeAxisTest {
 private val lead=44f
 private val daytime=CalendarTimeAxis.fromShowBeforeSix(false)
 private val wholeDay=CalendarTimeAxis.fromShowBeforeSix(true)

 @Test fun defaultStartsAtSixAndCheckboxRestoresMidnight(){
  assertEquals(6,daytime.startHour);assertEquals(0,wholeDay.startHour)
  for(height in listOf(28f,48f,140f)){
   assertEquals(lead,daytime.position(360f,height,lead),.001f)
   assertEquals(lead+18*height,daytime.height(height,lead),.001f)
   assertEquals(lead,wholeDay.position(0f,height,lead),.001f)
   assertEquals(lead+24*height,wholeDay.height(height,lead),.001f)
  }
 }

 @Test fun labelsAndTapTargetsUseTheSameOriginAtEveryZoom(){
  for(axis in listOf(daytime,wholeDay))for(height in listOf(28f,48f,73.3f,140f)){
   for(minute in axis.firstMinute.toInt()..1425 step 15){
    val y=axis.position(minute.toFloat(),height,lead)
    assertEquals(minute,axis.tapMinute(y,height,lead))
    assertEquals(minute.toFloat(),axis.minuteAt(y,height,lead),.002f)
   }
   assertEquals(axis.firstMinute.toInt(),axis.tapMinute(0f,height,lead))
   assertEquals(1425,axis.tapMinute(axis.height(height,lead),height,lead))
  }
 }

 @Test fun oldMidnightBasedNineOClockPositionStaysNineAndSwitchesDoNotJump(){
  for(height in listOf(28f,48f,140f))for(density in listOf(1f,3f)){
   val oldOffset=(lead+9*height)*density
   for(axis in listOf(daytime,wholeDay,daytime)){
    val kept=axis.clampAbsoluteOffset(oldOffset,height,lead,density,200*density)
    assertEquals(oldOffset,kept,.001f)
    val top=axis.minuteAt(axis.relativeOffset(kept,height,density)/density,height,lead)
    assertEquals(540f,top,.002f)
   }
   val tooEarly=(lead+3*height)*density
   val kept=daytime.clampAbsoluteOffset(tooEarly,height,lead,density,200*density)
   assertEquals(0f,daytime.relativeOffset(kept,height,density),.001f)
  }
 }

 @Test fun pinchKeepsTheAbsoluteMinuteUnderTheFingers(){
  for(axis in listOf(daytime,wholeDay))for(density in listOf(1f,3f)){
   val center=190f*density;val oldHeight=48f;val newHeight=101f
   val absolute=axis.absoluteOffset(axis.position(720f,oldHeight,lead)*density-center,oldHeight,density)
   val anchor=axis.minuteAt((axis.relativeOffset(absolute,oldHeight,density)+center)/density,oldHeight,lead)
   val next=axis.absoluteOffset(axis.position(anchor,newHeight,lead)*density-center,newHeight,density)
   val restored=axis.minuteAt((axis.relativeOffset(next,newHeight,density)+center)/density,newHeight,lead)
   assertEquals(720f,restored,.002f)
  }
 }

 @Test fun eventsAndTravelCrossingSixAreClippedWithoutChangingStoredDataOrLeavingEmptyLanes(){
  val day=LocalDate.of(2026,10,23)
  val hidden=CalendarEntry(title="早间事项",start=day.atTime(4,0),end=day.atTime(5,45))
  val crossing=CalendarEntry(title="跨过六点",start=day.atTime(5,30),end=day.atTime(7,0))
  val before=CalendarDayLayout.build(listOf(hidden,crossing),day)
  assertTrue(before.all{it.lane.count==2})
  val result=CalendarDayLayout.visible(before,daytime).single()
  assertSame(crossing,result.entry)
  assertEquals(DaySpan(360f,420f),result.body)
  assertEquals(Rules.Lane(0,1),result.lane)
  assertEquals(day.atTime(5,30),crossing.start)
  assertEquals(DaySpan(330f,420f),before.single{it.entry.id==crossing.id}.body)
  val travel=CalendarEntry(title="出发",start=day.atTime(6,10),end=day.atTime(7,0),travelMinutes=20)
  val clippedTravel=CalendarDayLayout.visible(CalendarDayLayout.build(listOf(travel),day),daytime).single()
  assertEquals(listOf(DaySpan(360f,370f)),clippedTravel.travel)
  assertEquals(20,travel.travelMinutes)
  assertSame(before,CalendarDayLayout.visible(before,wholeDay))
 }

 @Test fun renderWindowKeepsEveryVisibleMinuteWithTheShiftedAxis(){
  for(hourHeight in listOf(28f,48f,140f))for(y in listOf(0f,300f,600f)){
   val window=CalendarRenderWindow.calculate(0f,y,315f,400f,105f,hourHeight,lead,daytime.firstMinute)
   assertTrue(window.firstMinute>=360f)
   for(minute in 360..1440){
    val screenY=daytime.position(minute.toFloat(),hourHeight,lead)-y
    if(screenY in 0f..400f)assertTrue("$minute at $hourHeight",window.intersects(minute.toFloat(),minute.toFloat()))
   }
  }
 }

 @Test fun deadlineLineKeepsItsTrueTimeAfterHidingEarlyHours(){
  for(height in listOf(28f,48f,140f))for(minute in listOf(360f,720f,1439f)){
   val bounds=CalendarDeadlineGeometry.bounds(minute,height,lead,18f,daytime.firstMinute)
   assertEquals(daytime.position(minute,height,lead),bounds.point,.001f)
   assertTrue(bounds.top>=0f)
   assertTrue(bounds.top+bounds.height<=daytime.height(height,lead)+.001f)
   assertTrue(bounds.visible(CalendarRenderWindow(0..6,minute,minute),height,lead,daytime.firstMinute))
  }
 }
}

package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class CalendarRenderWindowTest {
 @Test fun allVisibleDaysAndHoursRemainPresentAcrossPanAndZoom(){
  for(dayWidth in listOf(45f,105f,315f))for(hourHeight in listOf(28f,48f,140f)){
   for(x in listOf(0f,dayWidth*.8f,dayWidth*4))for(y in listOf(0f,400f,1800f)){
    val window=CalendarRenderWindow.calculate(x,y,315f,550f,dayWidth,hourHeight,44f)
    for(day in 0..6)if(day*dayWidth<x+315&&(day+1)*dayWidth>x)assertTrue(day in window.days)
    for(minute in 0..1440){val screenY=44+minute/60f*hourHeight-y
     if(screenY in 0f..550f)assertTrue(window.intersects(minute.toFloat(),minute.toFloat()))
    }
   }
  }
 }
 @Test fun viewportFiltersInvisibleEventsButKeepsSpanningEventsAndTravel(){
  val window=CalendarRenderWindow.calculate(315f,44+8*48f,315f,240f,105f,48f,44f)
  assertFalse(window.intersects(60f,120f))
  assertFalse(window.intersects(1380f,1430f))
  assertTrue(window.intersects(0f,1440f))
  assertTrue(window.intersects(475f,490f))
  assertTrue(window.days.count()<7)
 }
 @Test fun smallScrollsReuseTheSameCompositionWindow(){
  val first=CalendarRenderWindow.calculate(330f,440f,315f,400f,105f,48f,44f)
  val next=CalendarRenderWindow.calculate(331f,441f,315f,400f,105f,48f,44f)
  assertEquals(first,next)
 }
 @Test fun zoomingOutToWholeWeekAndDayDoesNotLeaveEmptyRegions(){
  val window=CalendarRenderWindow.calculate(0f,0f,315f,24*28f+44,45f,28f,44f)
  assertEquals((0..6).toList(),window.days.toList())
  assertTrue(window.intersects(0f,1f));assertTrue(window.intersects(1439f,1440f))
 }
}

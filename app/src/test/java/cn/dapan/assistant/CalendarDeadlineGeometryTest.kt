package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class CalendarDeadlineGeometryTest {
 @Test fun lineUsesExactInstantInsteadOfADuration(){
  for(height in listOf(28f,48f,140f))for(minute in listOf(0f,480f,1439f)){
   val bounds=CalendarDeadlineGeometry.bounds(minute,height,44f,18f)
   assertEquals(44+minute/60*height,bounds.point,.001f)
   assertTrue(bounds.top>=0)
   assertTrue(bounds.top+bounds.height<=44+24*height)
   assertTrue(bounds.point in bounds.top..(bounds.top+bounds.height))
  }
 }
 @Test fun labelAndLineStayVisibleAtBothDayEdges(){
  for(height in listOf(28f,140f)){
   val start=CalendarDeadlineGeometry.bounds(0f,height,44f,30f)
   val end=CalendarDeadlineGeometry.bounds(1439f,height,44f,30f)
   assertTrue(start.visible(CalendarRenderWindow(0..6,0f,15f),height,44f))
   assertTrue(end.visible(CalendarRenderWindow(0..6,1439f,1440f),height,44f))
  }
  val large=CalendarDeadlineGeometry.bounds(0f,48f,44f,60f)
  assertTrue(large.labelTop>large.point)
  assertTrue(large.labelTop+60<=large.top+large.height)
 }
 @Test fun visibleLabelIsKeptEvenWhenItsPointFallsJustBelowWindow(){
  val bounds=CalendarDeadlineGeometry.bounds(610f,48f,44f,20f)
  assertTrue(bounds.visible(CalendarRenderWindow(0..6,540f,600f),48f,44f))
  assertFalse(bounds.visible(CalendarRenderWindow(0..6,0f,120f),48f,44f))
 }
 @Test fun lineResizesWithColumnButDoesNotForceAFullWidthBanner(){
  val small=CalendarDeadlineGeometry.width(48f,150f)
  val medium=CalendarDeadlineGeometry.width(130f,150f)
  val wide=CalendarDeadlineGeometry.width(330f,150f)
  assertTrue(small<medium&&medium<wide)
  assertTrue(wide<330-8)
  for(width in listOf(8f,45f,100f,330f))assertTrue(CalendarDeadlineGeometry.width(width,500f)<=maxOf(1f,width-8))
 }
}

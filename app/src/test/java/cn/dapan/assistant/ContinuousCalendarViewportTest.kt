package cn.dapan.assistant

import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class ContinuousCalendarViewportTest {
 private val width=352f
 private val focus=157f // 390dp screen, 38dp time gutter: the real screen midline.

 @Test fun todayAndDatePickerCenterEveryWeekdayAtEveryZoom(){
  for(day in 1..7)for(column in listOf(50f,117f,352f)){
   val date=LocalDate.of(2026,10,26).plusDays(day.toLong()-1)
   val center=ContinuousCalendarViewport.center(date)
   val range=ContinuousCalendarViewport.buffered(center,width,column,focus)
   val first=LocalDate.ofEpochDay(range.first)
   val offset=ContinuousCalendarViewport.offset(first,center,column,focus)
   val dayMiddle=(date.toEpochDay()-range.first+.5)*column-offset+38
   assertEquals(195.0,dayMiddle,0.001)
   assertEquals(date,ContinuousCalendarViewport.date(center))
  }
 }

 @Test fun pansAcrossSundayMonthYearAndLeapDayWithoutStopping(){
  for(date in listOf(LocalDate.of(2026,11,1),LocalDate.of(2026,10,31),LocalDate.of(2026,12,31),LocalDate.of(2028,2,28))){
   val initial=ContinuousCalendarViewport.center(date)
   assertEquals(date.plusDays(1),ContinuousCalendarViewport.date(ContinuousCalendarViewport.pan(initial,-117f,117f)))
   assertEquals(date.minusDays(1),ContinuousCalendarViewport.date(ContinuousCalendarViewport.pan(initial,117f,117f)))
   assertEquals(date.plusDays(70),ContinuousCalendarViewport.date(ContinuousCalendarViewport.pan(initial,-8190f,117f)))
  }
 }

 @Test fun scrollingReusesABoundedWindowEvenFarFromToday(){
  for(offset in listOf(-200000.0,-7.31,0.0,7.5,200000.0))for(column in listOf(50f,117f,352f)){
   val center=ContinuousCalendarViewport.center(LocalDate.of(2026,10,31))+offset
   val visible=ContinuousCalendarViewport.visible(center,width,column,focus)
   val buffer=ContinuousCalendarViewport.buffered(center,width,column,focus)
   assertTrue(visible.first>=buffer.first+2 && visible.last<=buffer.last-2)
   assertTrue(buffer.count()<=13)
   val x=ContinuousCalendarViewport.offset(LocalDate.ofEpochDay(buffer.first),center,column,focus)
   assertTrue(abs(x)<column*4)
   val rendered=CalendarRenderWindow.calculate(x,300f,width,600f,column,48f,44f,360f,buffer.count())
   visible.forEach{assertTrue((it-buffer.first).toInt() in rendered.days)}
  }
 }

 @Test fun horizontalPinchKeepsDateUnderFingersWithoutWeekBoundaryClamp(){
  for(old in listOf(50f,117f,352f))for(next in listOf(50f,117f,352f))for(finger in listOf(20f,157f,330f)){
   val initial=ContinuousCalendarViewport.center(LocalDate.of(2026,11,1))
   val updated=ContinuousCalendarViewport.anchoredCenter(initial,finger,focus,old,next)
   assertEquals(initial+(finger-focus)/old.toDouble(),updated+(finger-focus)/next.toDouble(),1e-8)
   assertEquals(initial,ContinuousCalendarViewport.anchoredCenter(updated,finger,focus,next,old),1e-8)
  }
 }

 @Test fun weekArrowsShiftSevenDaysWithoutSnappingHorizontalPosition(){
  val position=ContinuousCalendarViewport.center(LocalDate.of(2026,10,31))+.21
  for(shift in listOf(-7,7)){
   val old=ContinuousCalendarViewport.visible(position,width,117f,focus)
   val next=ContinuousCalendarViewport.visible(position+shift,width,117f,focus)
   assertEquals(old.first+shift,next.first);assertEquals(old.last+shift,next.last)
   assertEquals(ContinuousCalendarViewport.offset(LocalDate.ofEpochDay(old.first),position,117f,focus),
    ContinuousCalendarViewport.offset(LocalDate.ofEpochDay(next.first),position+shift,117f,focus),.001f)
  }
 }

 @Test fun headerTracksVisibleRangeInsteadOfExpandingToMondayAndSunday(){
  val saturday=LocalDate.of(2026,10,31)
  val range=ContinuousCalendarViewport.visible(ContinuousCalendarViewport.center(saturday),width,117f,focus)
  assertEquals("10.30~11.02",CalendarViewport.range(LocalDate.ofEpochDay(range.first),LocalDate.ofEpochDay(range.last)))
  assertEquals("12.31~01.02",CalendarViewport.range(LocalDate.of(2026,12,31),LocalDate.of(2027,1,2)))
  assertEquals(0L..2L,ContinuousCalendarViewport.visible(1.5,300f,100f,150f))
 }

 @Test fun repeatedPixelScrollingDoesNotAccumulateVisibleDrift(){
  val initial=ContinuousCalendarViewport.center(LocalDate.of(2026,10,31))
  var value=initial
  repeat(100000){value=ContinuousCalendarViewport.pan(value,-.75f,117f)}
  repeat(100000){value=ContinuousCalendarViewport.pan(value,.75f,117f)}
  assertEquals(initial,value,1e-7)
 }
}

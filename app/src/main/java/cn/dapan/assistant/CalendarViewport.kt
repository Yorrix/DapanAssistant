package cn.dapan.assistant

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor

object CalendarViewport {
 private val shortDate=DateTimeFormatter.ofPattern("MM.dd",java.util.Locale.ROOT)
 fun monday(day:LocalDate):LocalDate = day.minusDays((day.dayOfWeek.value-1).toLong())
 fun range(day:LocalDate):String {
  val start=monday(day);val end=start.plusDays(6)
  return range(start,end)
 }
 fun range(start:LocalDate,end:LocalDate):String {
  if(start==end)return start.format(shortDate)
  return "${start.format(shortDate)}~${end.format(shortDate)}"
 }
 fun zoom(height:Float,ratio:Float):Float = (height*ratio).coerceIn(28f,140f)
 // Retain the time under the fingers, rather than jumping back to the start of the day.
 fun anchoredScroll(scroll:Float,centroid:Float,oldPixels:Float,newPixels:Float):Float =
  ((scroll+centroid)/oldPixels*newPixels-centroid).coerceAtLeast(0f)
}

/** Date coordinates never depend on a Monday or a finite week-sized scroll container.
 * Keep sub-day positions in Double; only small, window-relative distances become pixels.
 */
internal object ContinuousCalendarViewport {
 fun center(date:LocalDate):Double = date.toEpochDay()+.5
 fun date(center:Double):LocalDate = LocalDate.ofEpochDay(floor(center).toLong())
 fun pan(center:Double,deltaPx:Float,dayPx:Float):Double = center-deltaPx/dayPx.coerceAtLeast(1f).toDouble()
 fun offset(first:LocalDate,center:Double,dayPx:Float,focusPx:Float):Float =
  ((center-first.toEpochDay())*dayPx-focusPx).toFloat()
 fun anchoredCenter(center:Double,fingerPx:Float,focusPx:Float,oldDayPx:Float,newDayPx:Float):Double =
  center+(fingerPx-focusPx)*(1.0/oldDayPx.coerceAtLeast(1f)-1.0/newDayPx.coerceAtLeast(1f))
 fun visible(center:Double,widthPx:Float,dayPx:Float,focusPx:Float):LongRange {
  val width=dayPx.coerceAtLeast(1f).toDouble()
  val left=center-focusPx/width
  val right=center+(widthPx-focusPx)/width
  // A day touching only the edge is not visible. Avoid rounding noise at exact boundaries.
  val first=floor(left+1e-7).toLong()
  return first..(ceil(right-1e-7).toLong()-1).coerceAtLeast(first)
 }
 fun buffered(center:Double,widthPx:Float,dayPx:Float,focusPx:Float):LongRange {
  val visible=visible(center,widthPx,dayPx,focusPx)
  return (visible.first-2)..(visible.last+2)
 }
}

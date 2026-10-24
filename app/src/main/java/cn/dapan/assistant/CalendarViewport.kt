package cn.dapan.assistant

import java.time.LocalDate

object CalendarViewport {
 fun monday(day:LocalDate):LocalDate = day.minusDays((day.dayOfWeek.value-1).toLong())
 fun range(day:LocalDate):String {
  val start=monday(day);val end=start.plusDays(6)
  return if(start.year!=end.year) "${start.year}.${start.monthValue}.${start.dayOfMonth}–${end.year}.${end.monthValue}.${end.dayOfMonth}"
  else "${start.monthValue}月${start.dayOfMonth}日–${end.monthValue}月${end.dayOfMonth}日"
 }
 fun zoom(height:Float,ratio:Float):Float = (height*ratio).coerceIn(28f,140f)
 // Retain the time under the fingers, rather than jumping back to the start of the day.
 fun anchoredScroll(scroll:Float,centroid:Float,oldPixels:Float,newPixels:Float):Float =
  ((scroll+centroid)/oldPixels*newPixels-centroid).coerceAtLeast(0f)
}

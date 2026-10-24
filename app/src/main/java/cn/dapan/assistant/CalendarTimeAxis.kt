package cn.dapan.assistant

/** One time origin for the grid, hit targets, render window, zoom and event placement. */
internal data class CalendarTimeAxis(val startHour:Int){
 init{require(startHour in 0..23)}
 val firstMinute:Float get()=startHour*60f
 fun position(minute:Float,hourHeight:Float,lead:Float)=lead+(minute-firstMinute)/60f*hourHeight
 fun minuteAt(position:Float,hourHeight:Float,lead:Float)=firstMinute+(position-lead)/hourHeight*60f
 fun tapMinute(position:Float,hourHeight:Float,lead:Float)=
  (kotlin.math.floor((minuteAt(position,hourHeight,lead)+.001f)/15)*15).toInt().coerceIn(firstMinute.toInt(),1425)
 fun height(hourHeight:Float,lead:Float)=lead+(24-startHour)*hourHeight
 fun clampOffset(offset:Float,hourHeight:Float,lead:Float,density:Float,viewport:Float)=
  offset.coerceIn(0f,(height(hourHeight,lead)*density-viewport).coerceAtLeast(0f))
 // Keep saved scroll values in the existing midnight-based coordinate system.
 fun relativeOffset(absolute:Float,hourHeight:Float,density:Float)=absolute-startHour*hourHeight*density
 fun absoluteOffset(relative:Float,hourHeight:Float,density:Float)=relative+startHour*hourHeight*density
 fun clampAbsoluteOffset(offset:Float,hourHeight:Float,lead:Float,density:Float,viewport:Float)=
  absoluteOffset(clampOffset(relativeOffset(offset,hourHeight,density),hourHeight,lead,density,viewport),hourHeight,density)
 fun clip(span:DaySpan?):DaySpan?{
  span?:return null
  val start=maxOf(firstMinute,span.startMinute);val end=minOf(1440f,span.endMinute)
  return if(end>start)DaySpan(start,end)else null
 }
 companion object{
  fun fromShowBeforeSix(show:Boolean)=CalendarTimeAxis(if(show)0 else 6)
 }
}

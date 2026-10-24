package cn.dapan.assistant

import kotlin.math.ceil
import kotlin.math.floor

/** Buffered, quantized composition window. Pixel scrolling stays in layout/draw. */
internal data class CalendarRenderWindow(val days:IntRange,val firstMinute:Float,val lastMinute:Float){
 fun intersects(start:Float,end:Float)=end>=firstMinute&&start<=lastMinute
 companion object{
  fun calculate(x:Float,y:Float,width:Float,height:Float,dayWidth:Float,hourHeight:Float,lead:Float,firstMinute:Float=0f):CalendarRenderWindow{
   val day=dayWidth.coerceAtLeast(1f);val hour=hourHeight.coerceAtLeast(1f)
   // Half a day and an hour beyond the viewport keep fling/zoom edges populated.
   val firstDay=floor(x/day-.5f).toInt().coerceIn(0,6)
   val lastDay=floor((x+width)/day+.5f).toInt().coerceIn(firstDay,6)
   val first=firstMinute+floor(((y-lead)/hour-1)/2)*120f
   val last=firstMinute+ceil(((y+height-lead)/hour+1)/2)*120f
   return CalendarRenderWindow(firstDay..lastDay,first.coerceIn(firstMinute,1440f),last.coerceIn(firstMinute,1440f))
  }
 }
}

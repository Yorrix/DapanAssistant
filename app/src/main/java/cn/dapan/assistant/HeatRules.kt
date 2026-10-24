package cn.dapan.assistant

import kotlin.math.*

data class HeatFix(val lat:Double,val lon:Double,val accuracy:Float,val elapsed:Long,val time:Long)
data class HeatCell(val x:Int,val y:Int,val samples:Int,val seconds:Int)
object HeatRules {
 const val LAT=40.6863;const val LON=122.1197;const val GRID=20.0
 fun accepted(f:HeatFix,nowElapsed:Long):Boolean = f.lat.isFinite()&&f.lon.isFinite()&&f.lat in 40.675..40.692&&f.lon in 122.110..122.128&&f.accuracy.isFinite()&&f.accuracy in 0f..60f&&f.elapsed<=nowElapsed&&nowElapsed-f.elapsed<=120000
 fun cell(f:HeatFix)=floor((f.lon-LON)*111320*cos(Math.toRadians(LAT))/GRID).toInt() to floor((f.lat-LAT)*111320/GRID).toInt()
 fun latitude(y:Int)=LAT+(y+.5)*GRID/111320
 fun longitude(x:Int)=LON+(x+.5)*GRID/(111320*cos(Math.toRadians(LAT)))
 fun seconds(previous:HeatFix?,current:HeatFix):Int {
  if(previous==null)return 0
  val delta=current.elapsed-previous.elapsed
  if(delta !in 1000..120000)return 0
  val distance=hypot((current.lat-previous.lat)*111320,(current.lon-previous.lon)*111320*cos(Math.toRadians(LAT)))
  if(distance/(delta/1000.0)>15)return 0
  return if(cell(previous)==cell(current))(delta/1000).coerceAtMost(60).toInt()else 0
 }
}

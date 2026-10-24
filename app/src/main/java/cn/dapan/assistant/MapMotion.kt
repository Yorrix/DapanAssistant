package cn.dapan.assistant

import kotlin.math.abs
import kotlin.math.exp

/** Shortest turn across north, independent of sensor/display refresh rate. */
internal fun headingDelta(from:Float,to:Float):Float=(((to.toDouble()-from+180.0)%360.0+360.0)%360.0-180.0).toFloat()
internal fun smoothHeading(from:Float,to:Float,seconds:Float):Float {
 val delta=headingDelta(from,to)
 if(abs(delta)<.08f)return from+delta
 return from+delta*(1f-exp(-seconds.coerceIn(0f,.1f)/.14f))
}

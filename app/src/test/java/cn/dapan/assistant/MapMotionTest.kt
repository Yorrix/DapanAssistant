package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class MapMotionTest {
 @Test fun northCrossingTakesShortTurn(){
  assertEquals(2f,headingDelta(359f,1f),.001f)
  assertEquals(-2f,headingDelta(1f,359f),.001f)
  val result=smoothHeading(359f,1f,1f/60)
  assertTrue(result>359f&&result<361f)
 }
 @Test fun steadyTurnDoesNotOvershoot(){
  var angle=0f
  repeat(120){angle=smoothHeading(angle,90f,1f/60);assertTrue(angle in 0f..90f)}
  assertEquals(90f,angle,.1f)
 }
 @Test fun motionIsIndependentOfFrameRate(){
  fun advance(fps:Int):Float{var v=0f;repeat(fps){v=smoothHeading(v,120f,1f/fps)};return v}
  assertEquals(advance(30),advance(120),.1f)
 }
}

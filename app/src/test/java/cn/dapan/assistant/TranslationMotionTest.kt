package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class TranslationMotionTest {
 @Test fun followsWithoutOvershootEvenWhenFrameIsLate(){
  for(distance in listOf(0f,.2f,1f,30f,1000f))for(dt in listOf(0f,8f,16f,33f,5000f)){
   val step=translationFollowStep(distance,dt)
   assertTrue(step>=0f&&step<=distance)
  }
  assertEquals(0f,translationFollowStep(-30f,16f),0f)
  assertEquals(0f,translationFollowStep(Float.NaN,16f),0f)
 }
 @Test fun settlingDoesNotDependOnScreenRefreshRate(){
  fun afterFrames(frames:Int,dt:Float):Float{
   var remaining=1000f
   repeat(frames){remaining-=translationFollowStep(remaining,dt)}
   return remaining
  }
  assertEquals(afterFrames(60,1000f/60),afterFrames(120,1000f/120),.8f)
  assertTrue(afterFrames(60,1000f/60)<.8f)
 }
 @Test fun changingTargetKeepsMovingForwardAndSettles(){
  var position=0f;var target=100f
  repeat(180){frame->
   if(frame==10)target+=180f
   if(frame==30)target+=60f
   val next=position+translationFollowStep(target-position,1000f/60)
   assertTrue(next>=position);assertTrue(next<=target)
   position=next
  }
  assertEquals(target,position,.8f)
 }
 @Test fun contentShrinkingDoesNotPullTheReaderBack(){
  assertEquals(0f,translationFollowStep(-100f,16f),0f)
  assertEquals(.3f,translationFollowStep(.3f,16f),0f)
 }
}

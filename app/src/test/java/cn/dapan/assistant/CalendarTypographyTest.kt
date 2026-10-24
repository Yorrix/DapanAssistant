package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test

class CalendarTypographyTest {
 @Test fun zoomEnlargesTextAndRevealsNotes(){
  val small=CalendarTypography.fit(75f,60f,hasLocation=true,hasNotes=true)
  val big=CalendarTypography.fit(250f,220f,hasLocation=true,hasNotes=true)
  assertTrue(big.title>small.title+5)
  assertTrue(big.detail>small.detail)
  assertEquals(0,small.noteLines)
  assertTrue(big.noteLines>=2)
  assertEquals(1,big.locationLines)
  assertTrue(big.fullTime)
 }
 @Test fun tallNarrowEventDoesNotBecomeHugeUnreadableLetters(){
  val thin=CalendarTypography.fit(45f,500f,hasNotes=true)
  assertTrue(thin.title<=12)
  assertTrue(thin.splitTime)
  assertEquals(0,thin.noteLines)
 }
 @Test fun accessibilityAndShortEventsKeepTimeFirst(){
  val short=CalendarTypography.fit(200f,20f,2f,true,true)
  assertFalse(short.fullTime)
  assertEquals(0,short.titleLines)
  assertEquals(0,short.noteLines)
  val normal=CalendarTypography.fit(200f,140f,1f,true,true)
  val large=CalendarTypography.fit(200f,140f,2f,true,true)
  assertTrue(large.noteLines<=normal.noteLines)
 }
 @Test fun textBudgetFitsCardIncludingGapsAndDivider(){
  for(w in listOf(45f,80f,120f,200f,300f))for(h in listOf(20f,40f,60f,100f,140f,250f))for(scale in listOf(1f,1.5f,2f)){
   val t=CalendarTypography.fit(w,h,scale,true,true)
   if(t.fullTime){
    val used=t.line*(if(t.splitTime)2 else 1)+(t.title+3)*t.titleLines+
     t.line*t.locationLines+t.line*t.noteLines+2*(1+t.locationLines+if(t.noteLines>0)2 else 0)
    assertTrue("$w/$h/$scale used=$used",used*scale<=h+1)
   }
  }
 }
}

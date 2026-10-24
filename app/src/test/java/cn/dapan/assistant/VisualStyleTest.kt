package cn.dapan.assistant
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class VisualStyleTest {
 private fun luminance(c:Color):Double {
  fun channel(f:Float)=if(f<=.04045f)f/12.92 else ((f+.055)/1.055).pow(2.4)
  return .2126*channel(c.red)+.7152*channel(c.green)+.0722*channel(c.blue)
 }
 private fun contrast(a:Color,b:Color):Double {val x=luminance(a);val y=luminance(b);return (maxOf(x,y)+.05)/(minOf(x,y)+.05)}
 @Test fun allStylesKeepReadableLightAndDarkText(){
  assertEquals(5,UiStyle.entries.size)
  for(style in UiStyle.entries)for(dark in listOf(false,true)){
   val p=visualPalette(style,dark)
   for(background in listOf(p.paper,p.card,p.soft)){
    assertTrue("$style/$dark body",contrast(p.ink,background)>=4.5)
    assertTrue("$style/$dark secondary",contrast(p.muted,background)>=4.5)
   }
   assertTrue("$style/$dark accent",contrast(p.accent,p.card)>=4.5)
  }
 }
 @Test fun removedStyleFallsBackAndJournalPreferenceSurvives(){
  assertEquals(UiStyle.GEEK,UiStyle.from("BLOCK"))
  assertEquals(UiStyle.JOURNAL,UiStyle.from("JOURNAL"))
 }
}

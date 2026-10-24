package cn.dapan.assistant

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

class VisualPaletteTest {
 private fun contrast(a:Color,b:Color):Float{val x=a.luminance();val y=b.luminance();return (maxOf(x,y)+.05f)/(minOf(x,y)+.05f)}
 @Test fun everyIndependentPaletteHasReadableDayAndNightText(){
  UiColorPalette.entries.forEach{palette->listOf(false,true).forEach{dark->
   val p=visualPalette(palette,dark)
   listOf("正文" to p.ink,"辅助文字" to p.muted,"操作文字" to p.accent).forEach{(label,ink)->
    listOf(p.card,p.paper).forEach{background->assertTrue("$palette dark=$dark $label contrast=${contrast(ink,background)}",contrast(ink,background)>=4.5f)}
   }
   assertTrue("$palette dark=$dark button",contrast(p.onAccent,p.accent)>=4.5f)
  }}
 }
}

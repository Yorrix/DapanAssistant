package cn.dapan.assistant

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.widget.TextView
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LineHeightSpan
import android.graphics.Paint
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.*
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.*
import io.noties.markwon.ext.tasklist.TaskListPlugin
import io.noties.markwon.movement.MovementMethodPlugin
import org.commonmark.node.Node

@Composable fun MarkdownContent(markdown:String,modifier:Modifier=Modifier,audioReferences:Boolean=true,onAudio:(Int,Long)->Unit){
 val context=LocalContext.current;val palette=UiPalette;val density=LocalDensity.current
 val seek by rememberUpdatedState(onAudio)
 val family=when(AppAppearance.shape){UiShape.JOURNAL->Typeface.SERIF;else->Typeface.SANS_SERIF}
 val renderer=remember(context,palette,density.density,audioReferences){
  fun dp(value:Int)=(value*density.density).toInt()
  Markwon.builder(context)
   .usePlugin(StrikethroughPlugin.create())
   .usePlugin(TaskListPlugin.create(context))
   .usePlugin(TablePlugin.create(TableTheme.emptyBuilder().tableBorderColor(palette.line.toArgb()).tableBorderWidth(dp(1)).tableCellPadding(dp(5)).tableHeaderRowBackgroundColor(palette.soft.toArgb()).tableOddRowBackgroundColor(palette.card.toArgb()).tableEvenRowBackgroundColor(palette.card.toArgb()).build()))
   .usePlugin(MovementMethodPlugin.create(TableAwareMovementMethod.create()))
   .usePlugin(object:AbstractMarkwonPlugin(){
    override fun beforeRender(node:Node){if(audioReferences)RecordingMarkdown.linkReferences(node)}
    override fun configureTheme(builder:MarkwonTheme.Builder){
     builder.linkColor(palette.accent.toArgb()).isLinkUnderlined(false)
      .headingTextSizeMultipliers(floatArrayOf(1.3f,1.2f,1.1f,1f,1f,1f)).headingBreakHeight(0)
      .blockMargin(dp(16)).blockQuoteWidth(dp(2)).blockQuoteColor(palette.line.toArgb())
      .bulletWidth(dp(4)).listItemColor(palette.accent.toArgb())
      .codeTextColor(palette.ink.toArgb()).codeBackgroundColor(palette.soft.toArgb())
      .codeBlockTextColor(palette.ink.toArgb()).codeBlockBackgroundColor(palette.soft.toArgb())
      .thematicBreakColor(palette.line.toArgb())
    }
    override fun configureConfiguration(builder:MarkwonConfiguration.Builder){
     builder.linkResolver{_,link->
      val ref=RecordingMarkdown.reference(link)
      if(ref!=null)seek(ref.first,ref.second)
      else if(Uri.parse(link).scheme in listOf("http","https"))runCatching{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link)))}
     }
    }
   }).build()
 }
 val rendered=remember(markdown,renderer){
  val parsed=renderer.toMarkdown(RecordingMarkdown.normalize(markdown))
  if(audioReferences)parsed else SpannableStringBuilder(parsed).apply{
   Regex("(?<=\n)\n").findAll(parsed).forEach{gap->
    setSpan(CompactParagraphGap((4*density.density).toInt()),gap.range.first,gap.range.last+1,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
   }
  }
 }
 AndroidView(modifier=modifier,factory={TextView(it).apply{includeFontPadding=false;setPadding(0,0,0,0);setBackgroundColor(android.graphics.Color.TRANSPARENT)}},update={view->
  view.setTextColor(palette.ink.toArgb());view.setLinkTextColor(palette.accent.toArgb());view.typeface=family
  view.textSize=14f;view.setLineSpacing(3*density.density,1f)
  view.highlightColor=palette.accentSurface.toArgb()
  if(view.tag!==rendered){renderer.setParsedMarkdown(view,rendered);view.tag=rendered}
 })
}

private class CompactParagraphGap(private val height:Int):LineHeightSpan{
 override fun chooseHeight(text:CharSequence,start:Int,end:Int,spanstartv:Int,v:Int,fm:Paint.FontMetricsInt){
  // Only shrink empty separator lines; preserve headings and all content lines.
  if((start until end).all{text[it]=='\n'}){fm.ascent=-height;fm.top=-height;fm.descent=0;fm.bottom=0}
 }
}


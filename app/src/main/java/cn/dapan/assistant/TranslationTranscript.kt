package cn.dapan.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive

/**
 * Draft -> pending -> translated uses one row/key. Only its height changes, anchored at the
 * top; no swapping differently styled cards or scrolling a partial sentence to the viewport top.
 */
@Composable
internal fun TranslationTranscript(
 turns:List<TranslationTurn>, draftId:String, listening:Boolean, partial:String,
 from:String, to:String, showOriginal:Boolean, textSize:Float,
 onRetry:(TranslationTurn)->Unit, onSpeak:(TranslationTurn)->Unit,
 modifier:Modifier=Modifier
){
 val scroll=rememberLazyListState()
 var followTail by rememberSaveable{mutableStateOf(true)}
 var positioned by remember{mutableStateOf(false)}
 val dragged by scroll.interactionSource.collectIsDraggedAsState()
 val motion=AppAppearance.motion!=PageMotion.NONE
 val rows=remember(turns,draftId,listening,partial,from,to){
  if(listening)turns+TranslationTurn(id=draftId,speaker="讲话",from=from,to=to,text=partial)else turns
 }
 // Initial history positioning happens once. Live updates never restart an index-based scroll.
 LaunchedEffect(Unit){
  if(turns.isNotEmpty())scroll.scrollToItem(turns.lastIndex)
  positioned=true
 }
 LaunchedEffect(dragged){
  if(dragged)followTail=false
  else{
   snapshotFlow{scroll.isScrollInProgress}.first{!it}
   if(!scroll.canScrollForward)followTail=true
  }
 }
 LaunchedEffect(followTail,positioned,motion){
  if(!followTail||!positioned)return@LaunchedEffect
  // Wake on overflow, then follow the actual measured bottom once per frame. Sentence
  // revisions and animated heights update the target without cancelling/restarting motion.
  snapshotFlow{scroll.tailOverflow()>0.5f}.collect{overflow->
   if(overflow){
    var previous=withFrameNanos{it}
    while(isActive){
     val frame=withFrameNanos{it}
     val remaining=scroll.tailOverflow()
     if(remaining<=0.5f)break
     val step=if(motion)translationFollowStep(remaining,(frame-previous)/1_000_000f)else remaining
     previous=frame
     // A sub-pixel frame may consume zero; LazyList accumulates fractional deltas.
     // Breaking there strands follow mode while the overflow signal is still true.
     scroll.scrollBy(step)
    }
   }
  }
 }
 Box(modifier){
  LazyColumn(
   Modifier.fillMaxSize(),state=scroll,
   contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=12.dp),
   verticalArrangement=Arrangement.spacedBy(6.dp)
  ){
   if(turns.isEmpty()&&!listening)item(key="translation-empty"){
    Column(Modifier.fillMaxWidth().padding(top=14.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
     Text("选好语言，开始收听",fontSize=17.sp,fontWeight=FontWeight.SemiBold,color=Ink)
     Text("逐句显示译文，也可以点上方打字。",fontSize=12.sp,color=Muted)
    }
   }
   items(rows,key={it.id},contentType={"sentence"}){row->
    TranslationSentence(row,listening&&row.id==draftId,showOriginal,textSize,motion,onRetry,onSpeak)
   }
   // A stable end marker lets follow mode reveal the bottom of even a very long translation.
   item(key="translation-tail"){Spacer(Modifier.height(1.dp))}
  }
  if(!followTail&&turns.isNotEmpty())FilledTonalButton(
   onClick={followTail=true},
   modifier=Modifier.align(Alignment.BottomCenter).padding(6.dp),
   contentPadding=PaddingValues(horizontal=12.dp,vertical=0.dp)
  ){Text("↓ 最新内容",fontSize=12.sp)}
 }
}

private fun LazyListState.tailOverflow():Float{
 if(!canScrollForward)return 0f
 val info=layoutInfo
 val last=info.visibleItemsInfo.lastOrNull()?:return 0f
 if(last.index<info.totalItemsCount-1)return (info.viewportEndOffset-info.viewportStartOffset).coerceAtLeast(1).toFloat()
 return (last.offset+last.size+info.afterContentPadding-info.viewportEndOffset).coerceAtLeast(0).toFloat()
}

@Composable
private fun TranslationSentence(
 row:TranslationTurn, draft:Boolean, showOriginal:Boolean, textSize:Float, motion:Boolean,
 onRetry:(TranslationTurn)->Unit,onSpeak:(TranslationTurn)->Unit
){
 val context=LocalContext.current
 var menu by remember{mutableStateOf(false)}
 // Reserve the translation line at the final font size, including while ASR is still running.
 // Existing source text remains above it rather than jumping when a translation arrives.
 val reveal=remember(row.id){Animatable(if(row.translated.isBlank())0f else 1f)}
 LaunchedEffect(row.translated){
  if(row.translated.isNotBlank()){
   if(motion)reveal.animateTo(1f,tween(220, easing=LinearOutSlowInEasing))else reveal.snapTo(1f)
  }
 }
 Column(
  Modifier.fillMaxWidth().animateContentSize(
   animationSpec=if(motion)tween(280,easing=FastOutSlowInEasing)else snap(),
   alignment=Alignment.TopStart
  )
 ){
  Row(Modifier.fillMaxWidth().height(24.dp),verticalAlignment=Alignment.CenterVertically){
   Text(if(draft)"正在听"else"${row.from} → ${row.to}",fontSize=10.sp,color=if(draft)DutBlue else Muted)
   Spacer(Modifier.weight(1f))
   if(!draft)Box{
    TextButton(onClick={menu=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.size(28.dp,24.dp)){Text("⋯",fontSize=18.sp,color=Muted)}
    DropdownMenu(menu,{menu=false}){
     fun copy(text:String){
      menu=false
      (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("翻译",text))
     }
     DropdownMenuItem(text={Text("复制译文")},enabled=row.translated.isNotBlank(),onClick={copy(row.translated)})
     DropdownMenuItem(text={Text("复制原文与译文")},onClick={copy(row.text+"\n"+row.translated)})
     if(row.translated.isNotBlank())DropdownMenuItem(text={Text("朗读译文")},onClick={menu=false;onSpeak(row)})
     if(!row.pending&&row.translated.isBlank())DropdownMenuItem(text={Text("重试翻译")},onClick={menu=false;onRetry(row)})
    }
   }
  }
  if(showOriginal)SelectionContainer{
   Text(row.text.ifBlank{"等待下一句话…"},Modifier.fillMaxWidth().padding(bottom=4.dp),fontSize=13.sp,lineHeight=19.sp,color=Muted)
  }
  Box(Modifier.fillMaxWidth().heightIn(min=(textSize+6).dp)){
   if(row.translated.isNotBlank())SelectionContainer{
    Text(
     row.translated,Modifier.fillMaxWidth().graphicsLayer{alpha=reveal.value},
     fontSize=textSize.sp,lineHeight=(textSize+6).sp,fontWeight=FontWeight.Medium,color=Ink
    )
   }else{
    Text(
     when{
      !showOriginal&&(draft||row.pending)->row.text.ifBlank{"等待下一句话…"}
      draft->" "
      row.pending->"正在翻译…"
      else->"翻译未完成"
     },
     fontSize=textSize.sp,lineHeight=(textSize+6).sp,color=Muted
    )
   }
  }
  if(row.error.isNotBlank()){
   Text(row.error,fontSize=11.sp,lineHeight=16.sp,color=Coral)
   TextButton(onClick={onRetry(row)},contentPadding=PaddingValues(0.dp),modifier=Modifier.height(30.dp)){
    LineIcon(AppGlyph.Refresh,Modifier.size(13.dp),DutBlue)
    Spacer(Modifier.width(4.dp));Text("重试",fontSize=12.sp)
   }
  }
  HorizontalDivider(Modifier.padding(top=6.dp),color=Hairline.copy(alpha=.65f),thickness=.5.dp)
 }
}

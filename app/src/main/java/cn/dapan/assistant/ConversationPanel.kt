package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

/** Non-modal conversation above the existing composer; the originating tool remains mounted. */
@Composable fun ConversationPanel(lines:List<ChatLine>,notice:String?,busy:Boolean,expanded:Boolean,
 canRetry:Boolean,onRetry:()->Unit,onExpand:()->Unit,onClose:()->Unit,onClear:()->Unit,liveReply:String="",busyStatus:String="正在处理…",onApprove:(String)->Unit={},onReject:(String)->Unit={},onUndo:(String)->Unit={}){
 val scroll=rememberScrollState()
 val context=LocalContext.current
 val density=LocalDensity.current
 val keyboard=with(density){WindowInsets.ime.getBottom(this).toDp()}
 val room=(LocalConfiguration.current.screenHeightDp.dp-keyboard-180.dp).coerceAtLeast(64.dp)
 val visible=if(expanded)lines else if((busy&&liveReply.isNotBlank())||(notice!=null&&lines.none{it.text==notice}))emptyList() else lines.takeLast(1)
 LaunchedEffect(lines.lastOrNull()?.id,notice,expanded){withFrameNanos{};scroll.scrollTo(if(expanded)scroll.maxValue else 0)}
 LaunchedEffect(liveReply){if(liveReply.isNotEmpty()&&scroll.maxValue-scroll.value<with(density){48.dp.roundToPx()}){withFrameNanos{};scroll.scrollTo(scroll.maxValue)}}
 Surface(Modifier.padding(horizontal=12.dp,vertical=4.dp),color=CardSurface,shape=AppShape(16.dp),shadowElevation=3.dp,border=BorderStroke(.5.dp,Hairline)){Column(Modifier.fillMaxWidth().padding(horizontal=12.dp)){
  Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically){
   Text("与大盘对话",fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,modifier=Modifier.weight(1f))
   if(expanded)TextButton(onClick=onClear,enabled=!busy,contentPadding=PaddingValues(horizontal=6.dp)){Text("清空",fontSize=11.sp)}
   TextButton(onClick=onExpand,contentPadding=PaddingValues(horizontal=6.dp)){Text(if(expanded)"收起"else"历史",fontSize=11.sp)}
   QuietIconButton("关闭对话",AppGlyph.Close,onClose)
  }
  HorizontalDivider(color=Hairline,thickness=.5.dp)
  Column(Modifier.fillMaxWidth().heightIn(max=minOf(room,if(expanded||visible.any{it.operation!=null})380.dp else 240.dp)).verticalScroll(scroll).padding(top=8.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   if(visible.isEmpty()&&notice==null&&!busy)Text("直接在下方输入，可以连续追问。",fontSize=12.sp,color=Muted)
   visible.forEach{line->key(line.id){
    if(line.role=="user")Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
     Surface(Modifier.widthIn(max=280.dp),shape=AppShape(11.dp),color=SoftSurface){SelectionContainer{
      Text(line.text,Modifier.padding(horizontal=10.dp,vertical=7.dp),fontSize=13.sp,lineHeight=18.sp,color=Ink,maxLines=if(expanded)Int.MAX_VALUE else 2,overflow=TextOverflow.Ellipsis)
     }}
    }else if(line.operation!=null)AssistantOperationCard(line.operation,!busy,{onApprove(line.id)},{onReject(line.id)},{onUndo(line.id)})
    else Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(3.dp)){
     Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
      Text("大盘",Modifier.weight(1f),fontSize=11.sp,lineHeight=16.sp,color=Muted)
      Text("复制",Modifier.clickable{(context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("大盘回复",line.text))}.padding(horizontal=6.dp,vertical=4.dp),fontSize=11.sp,lineHeight=16.sp,color=Muted)
     }
     MarkdownContent(line.text,Modifier.fillMaxWidth(),audioReferences=false){_,_->}
    }
   }}
   if(notice!=null&&lines.none{it.text==notice})MarkdownContent(notice,Modifier.fillMaxWidth(),audioReferences=false){_,_->}
   if(busy&&liveReply.isNotBlank())Column(verticalArrangement=Arrangement.spacedBy(3.dp)){Text("大盘 · 正在回复",fontSize=11.sp,color=Muted);MarkdownContent(liveReply,Modifier.fillMaxWidth(),audioReferences=false){_,_->}}
   if(busy)Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){CircularProgressIndicator(Modifier.size(12.dp),strokeWidth=1.5.dp);Text(busyStatus,fontSize=12.sp,color=Muted)}
   if(canRetry&&!busy)TextButton(onClick=onRetry,contentPadding=PaddingValues(0.dp)){Text("重试这条消息",fontSize=12.sp)}
  }
 }}
}

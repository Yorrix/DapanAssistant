package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/** Typing is an optional companion popover. Voice remains on the companion itself. */
@Composable fun CompanionComposer(
 value:TextFieldValue,onValue:(TextFieldValue)->Unit,onFocus:(Boolean)->Unit,
 files:List<AssistantFile>,loading:Boolean,busy:Boolean,voiceActive:Boolean,
 onAdd:()->Unit,onRemove:(AssistantFile)->Unit,onHistory:()->Unit,onSend:()->Unit
){
 Surface(Modifier.padding(horizontal=12.dp,vertical=6.dp).widthIn(max=360.dp).fillMaxWidth(),
  shape=AppShape(16.dp),color=CardSurface,shadowElevation=5.dp,border=BorderStroke(.5.dp,Hairline)){
  Column(Modifier.padding(horizontal=5.dp,vertical=3.dp)){
   if(files.isNotEmpty())Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
    files.forEach{file->Surface(color=SoftSurface,shape=AppShape(8.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){
      Text(file.name,Modifier.widthIn(max=180.dp).padding(start=8.dp),fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
      IconButton(onClick={onRemove(file)},modifier=Modifier.size(32.dp).semantics{contentDescription="移除附件 ${file.name}"}){LineIcon(AppGlyph.Close,Modifier.size(13.dp),Muted)}
     }
    }}
   }
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    IconButton(onClick=onAdd,enabled=!loading&&!busy&&!voiceActive,modifier=Modifier.size(34.dp).semantics{contentDescription="添加文件"}){LineIcon(AppGlyph.Add,Modifier.size(18.dp),Muted)}
    BasicTextField(value,onValue,Modifier.weight(1f).heightIn(min=36.dp,max=96.dp).padding(vertical=8.dp,horizontal=3.dp)
     .onFocusChanged{onFocus(it.isFocused)}.semantics{contentDescription="AI 输入框"},enabled=!voiceActive,
     textStyle=TextStyle(color=Ink,fontSize=14.sp,lineHeight=20.sp),cursorBrush=SolidColor(DutBlue),maxLines=4,
     decorationBox={inner->Box{if(value.text.isEmpty())Text("和大盘说点什么",fontSize=14.sp,color=Muted);inner()}})
    IconButton(onClick=onHistory,modifier=Modifier.size(34.dp).semantics{contentDescription="聊天记录"}){LineIcon(AppGlyph.Chat,Modifier.size(17.dp),Muted)}
    val canSend=!voiceActive&&(busy||(!loading&&(value.text.isNotBlank()||files.isNotEmpty())))
    IconButton(onClick=onSend,enabled=canSend,modifier=Modifier.size(34.dp).semantics{contentDescription=if(busy)"停止生成"else"发送"}){
     LineIcon(if(busy)AppGlyph.Stop else AppGlyph.Send,Modifier.size(18.dp),if(canSend)DutBlue else Muted.copy(alpha=.4f))
    }
   }
   if(loading)Text("读取文件…",Modifier.padding(horizontal=8.dp),fontSize=10.sp,color=Muted)
  }
 }
}

@Composable fun CompanionVoiceFeedback(voice:AssistantVoiceController){
 voice.feedback?.let{text->
  Surface(Modifier.padding(horizontal=16.dp,vertical=6.dp).widthIn(max=340.dp),shape=AppShape(14.dp),color=CardSurface,shadowElevation=3.dp,border=BorderStroke(.5.dp,Hairline)){
   Row(Modifier.padding(horizontal=12.dp,vertical=9.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    val tint=if(voice.outside)MaterialTheme.colorScheme.error else DutBlue
    LineIcon(if(voice.outside)AppGlyph.Close else AppGlyph.Mic,Modifier.size(16.dp),tint)
    Text(text,fontSize=13.sp,lineHeight=19.sp,color=tint,maxLines=3,overflow=TextOverflow.Ellipsis)
   }
  }
 }
}

@Composable fun CompanionAutoHideToggle(autoHide:Boolean,enabled:Boolean,onClick:()->Unit){
 IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(36.dp).semantics{
  contentDescription="小助手闲置自动收起"
  stateDescription=if(autoHide)"已开启，闲置后只露草叶"else"已关闭，小助手保持显示"
  toggleableState=if(autoHide)androidx.compose.ui.state.ToggleableState.On else androidx.compose.ui.state.ToggleableState.Off
 }){
  Row(verticalAlignment=Alignment.CenterVertically){
   LineIcon(AppGlyph.Next,Modifier.size(17.dp).rotate(if(autoHide)0f else 180f),if(enabled&&autoHide)DutBlue else Muted)
   VerticalDivider(Modifier.height(14.dp),thickness=1.dp,color=if(autoHide)DutBlue else Muted)
  }
 }
}

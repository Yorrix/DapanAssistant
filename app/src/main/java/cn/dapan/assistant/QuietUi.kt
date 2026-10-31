package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

val LocalFloatingAiHeight=compositionLocalOf{0.dp}

// Semantic palette shared by Compose screens and custom Canvas rendering.
val DutBlue get()=UiPalette.accent
val Ink get()=UiPalette.ink
val Coral get()=DutBlue
val Paper get()=UiPalette.paper
val CardSurface get()=UiPalette.card
val SoftSurface get()=UiPalette.soft
val AccentSurface get()=UiPalette.accentSurface
val OnAccent get()=UiPalette.onAccent
val Pine get()=DutBlue
val Muted get()=UiPalette.muted
val Hairline get()=UiPalette.line

enum class AppGlyph { Add,Mic,Send,Stop,Today,Calendar,Tools,Settings,Back,Close,File,Next,Refresh,Chat,Search,Pin,Clock,Check,Done,Wallet,Trash,Edit,Walk,Gazette,Wheel,Marquee,Issue,Collapse,Expand }
@Composable fun LineIcon(glyph:AppGlyph,modifier:Modifier=Modifier,color:Color=Ink){
 Canvas(modifier.size(23.dp)){
  scale(size.width/24f,size.height/24f,pivot=Offset.Zero){
   val stroke=Stroke(1.65f,cap=StrokeCap.Round,join=StrokeJoin.Round)
   fun line(x:Float,y:Float,a:Float,b:Float)=drawLine(color,Offset(x,y),Offset(a,b),1.65f,StrokeCap.Round)
   fun path(vararg points:Float){val p=Path();p.moveTo(points[0],points[1]);for(i in 2 until points.size step 2)p.lineTo(points[i],points[i+1]);drawPath(p,color,style=stroke)}
   when(glyph){
    AppGlyph.Issue->{drawCircle(color,9f,Offset(12f,12f),style=stroke);line(12f,7f,12f,12f);drawCircle(color,1f,Offset(12f,16f))}
    AppGlyph.Collapse->{path(6f,9f,12f,15f,18f,9f);line(6f,20f,18f,20f)}
    AppGlyph.Expand->{path(6f,15f,12f,9f,18f,15f);line(6f,4f,18f,4f)}
    AppGlyph.Wheel->{drawCircle(color,8.5f,Offset(12f,13f),style=stroke);line(12f,13f,5f,8f);line(12f,13f,4f,16f);line(12f,13f,12f,21.5f);line(12f,13f,20f,16f);line(12f,13f,19f,8f);drawPath(Path().apply{moveTo(9.5f,2f);lineTo(14.5f,2f);lineTo(12f,7f);close()},color);drawCircle(color,1.5f,Offset(12f,13f))}
    AppGlyph.Marquee->{drawRoundRect(color,Offset(2f,5f),Size(20f,14f),CornerRadius(2.5f),style=stroke);path(6f,14.5f,8.5f,9.5f,11f,14.5f);line(7f,12.5f,10f,12.5f);line(14f,10f,18f,10f);line(14f,14f,18f,14f)}
    AppGlyph.Gazette->{path(3f,3f,19f,3f,19f,20f,4f,20f,3f,19f,3f,3f);path(19f,7f,22f,7f,22f,18f,21f,20f,19f,20f);line(6f,7f,16f,7f);line(6f,11f,9f,11f);line(6f,14f,9f,14f);line(6f,17f,9f,17f);line(12f,11f,16f,11f);line(12f,14f,16f,14f);line(12f,17f,16f,17f)}
    AppGlyph.Walk->{drawCircle(color,2f,Offset(14f,3f));path(7f,12f,10f,8f,14f,8f,17f,12f,21f,12f);path(13f,8f,11f,14f,16f,17f,17f,22f);path(11f,14f,8f,20f,4f,22f)}
    AppGlyph.Edit->{path(11f,4f,4f,4f,4f,20f,20f,20f,20f,13f);path(11f,14f,12f,10f,19f,3f,22f,6f,15f,13f,11f,14f);line(17f,5f,20f,8f)}
    AppGlyph.Trash->{line(4f,6f,20f,6f);path(9f,6f,9f,3f,15f,3f,15f,6f);path(6f,6f,7f,21f,17f,21f,18f,6f);line(10f,10f,10f,17f);line(14f,10f,14f,17f)}
    AppGlyph.Chat->{path(4f,4f,20f,4f,20f,17f,10f,17f,5f,21f,5f,17f,4f,17f,4f,4f);line(8f,8f,16f,8f);line(8f,12f,14f,12f)}
    AppGlyph.Search->{drawCircle(color,6f,Offset(10f,10f),style=stroke);line(15f,15f,21f,21f)}
    AppGlyph.Pin->{val p=Path();p.moveTo(12f,22f);p.cubicTo(-4f,8f,5f,2f,12f,2f);p.cubicTo(19f,2f,28f,8f,12f,22f);drawPath(p,color,style=stroke);drawCircle(color,2f,Offset(12f,9f),style=stroke)}
    AppGlyph.Clock->{drawCircle(color,9f,Offset(12f,12f),style=stroke);path(12f,6f,12f,12f,16f,15f)}
    AppGlyph.Check->{drawRoundRect(color,Offset(3f,3f),Size(18f,18f),CornerRadius(4f),style=stroke);path(7f,12f,11f,16f,17f,8f)}
    AppGlyph.Done->path(5f,12f,10f,17f,19f,7f)
    AppGlyph.Wallet->{drawRoundRect(color,Offset(2f,5f),Size(20f,15f),CornerRadius(3f),style=stroke);path(22f,10f,15f,10f,15f,15f,22f,15f);drawCircle(color,1f,Offset(18f,12.5f))}
    AppGlyph.Add->{line(12f,5f,12f,19f);line(5f,12f,19f,12f)}
    AppGlyph.Mic->{drawRoundRect(color,Offset(9f,3f),Size(6f,12f),CornerRadius(3f),style=stroke);drawArc(color,0f,180f,false,Offset(6f,8f),Size(12f,11f),style=stroke);line(12f,19f,12f,22f);line(9f,22f,15f,22f)}
    AppGlyph.Send->{line(12f,19f,12f,5f);path(6f,11f,12f,5f,18f,11f)}
    AppGlyph.Stop->drawRoundRect(color,Offset(7f,7f),Size(10f,10f),CornerRadius(2f))
    AppGlyph.Today->{drawCircle(color,5f,Offset(12f,12f),style=stroke);for(i in 0..7){val a=Math.PI*i/4;line((12+8*kotlin.math.cos(a)).toFloat(),(12+8*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat())}}
    AppGlyph.Calendar->{drawRoundRect(color,Offset(3f,5f),Size(18f,16f),CornerRadius(3f),style=stroke);line(3f,10f,21f,10f);line(8f,3f,8f,7f);line(16f,3f,16f,7f);drawCircle(color,1f,Offset(8f,15f));drawCircle(color,1f,Offset(12f,15f))}
    AppGlyph.Tools->{for(x in listOf(3f,14f))for(y in listOf(3f,14f))drawRoundRect(color,Offset(x,y),Size(7f,7f),CornerRadius(2f),style=stroke)}
    AppGlyph.Settings->{drawCircle(color,7f,Offset(12f,12f),style=stroke);drawCircle(color,2.5f,Offset(12f,12f),style=stroke);for(i in 0..7){val a=Math.PI*i/4;line((12+7*kotlin.math.cos(a)).toFloat(),(12+7*kotlin.math.sin(a)).toFloat(),(12+10*kotlin.math.cos(a)).toFloat(),(12+10*kotlin.math.sin(a)).toFloat())}}
    AppGlyph.Back->path(15f,5f,8f,12f,15f,19f)
    AppGlyph.Next->path(9f,5f,16f,12f,9f,19f)
    AppGlyph.Close->{line(7f,7f,17f,17f);line(7f,17f,17f,7f)}
    AppGlyph.File->{path(14f,3f,5f,3f,5f,21f,19f,21f,19f,8f,14f,3f,14f,8f,19f,8f);line(8f,13f,16f,13f);line(8f,17f,14f,17f)}
    AppGlyph.Refresh->{drawArc(color,35f,290f,false,Offset(5f,5f),Size(14f,14f),style=stroke);path(19f,4f,19f,9f,14f,9f)}
   }
  }
 }
}
@Composable fun QuietIconButton(label:String,glyph:AppGlyph,onClick:()->Unit,enabled:Boolean=true,color:Color=Ink){
 IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(40.dp).semantics{contentDescription=label}){LineIcon(glyph,Modifier.size(20.dp),color=if(enabled)color else Muted.copy(alpha=.4f))}
}

/** Compact search surface: one row, with the full query remaining editable. */
@Composable fun CompactSearchField(value:String,onValue:(String)->Unit,hint:String,modifier:Modifier=Modifier,onSearch:()->Unit={}){
 Row(modifier.fillMaxWidth().heightIn(min=40.dp).clip(AppShape(10.dp)).background(SoftSurface).padding(start=10.dp),verticalAlignment=Alignment.CenterVertically){
  LineIcon(AppGlyph.Search,Modifier.size(16.dp),Muted)
  BasicTextField(value,onValue,Modifier.weight(1f).padding(horizontal=8.dp,vertical=9.dp).semantics{contentDescription=hint},singleLine=true,
   textStyle=MaterialTheme.typography.bodyMedium.copy(color=Ink),cursorBrush=SolidColor(DutBlue),
   keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),
   keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={onSearch()}),
   decorationBox={inner->Box{if(value.isEmpty())Text(hint,fontSize=13.sp,lineHeight=18.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis);inner()}})
  if(value.isNotEmpty())QuietIconButton("清空搜索",AppGlyph.Close,{onValue("")})
 }
}
@Composable fun AiBar(value:TextFieldValue,onValue:(TextFieldValue)->Unit,onFocus:(Boolean)->Unit,
 files:List<AssistantFile>,loading:Boolean,busy:Boolean,voice:AssistantVoiceController,onAdd:()->Unit,onRemove:(AssistantFile)->Unit,
 onHistory:()->Unit,onSend:()->Unit,collapsed:Boolean=false){
 val voiceFeedback=voice.feedback
 val voiceWidth by androidx.compose.animation.core.animateDpAsState(if(collapsed)36.dp else 164.dp,label="composerMicWidth")
 val voiceBottom by androidx.compose.animation.core.animateDpAsState(if(collapsed)4.dp else 14.dp,label="composerMicPosition")
 Box(Modifier.fillMaxWidth()){
 if(collapsed)Spacer(Modifier.height(44.dp))
 else Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),shape=AppShape(18.dp),color=CardSurface,shadowElevation=if(AppAppearance.shape==UiShape.AURORA)3.dp else 6.dp,border=if(AppAppearance.shape==UiShape.AURORA)StyleBorder else BorderStroke(.5.dp,Hairline)){
  Column(Modifier.padding(horizontal=10.dp,vertical=4.dp)){
   if(files.isNotEmpty())Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
    files.forEach{file->Surface(shape=AppShape(10.dp),color=Paper){Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.padding(start=10.dp)){
     LineIcon(AppGlyph.File,Modifier.size(17.dp),DutBlue);Column(Modifier.widthIn(max=170.dp).padding(start=7.dp)){Text(file.name,fontSize=12.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(file.hint,fontSize=10.sp,color=Muted,maxLines=1)}
     IconButton(onClick={onRemove(file)},Modifier.size(40.dp).semantics{contentDescription="移除附件 ${file.name}"}){LineIcon(AppGlyph.Close,Modifier.size(15.dp),Muted)}
    }}}
   }
   BasicTextField(value,{ if(voiceFeedback==null)onValue(it) },Modifier.fillMaxWidth().heightIn(min=36.dp,max=120.dp).padding(horizontal=4.dp,vertical=6.dp).onFocusChanged{onFocus(it.isFocused)}.semantics{contentDescription="AI 输入框"},textStyle=TextStyle(color=Ink,fontSize=16.sp,lineHeight=24.sp),cursorBrush=SolidColor(DutBlue),maxLines=5,decorationBox={inner->Box{Box(Modifier.alpha(if(voiceFeedback==null)1f else 0f)){if(value.text.isEmpty())Text("和大盘说点什么",fontSize=16.sp,color=Muted.copy(alpha=.8f));inner()};voiceFeedback?.let{Text(it,fontSize=14.sp,lineHeight=20.sp,color=DutBlue,maxLines=1,overflow=TextOverflow.Ellipsis)}}})
   Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically){
    Row(Modifier.width(72.dp),verticalAlignment=Alignment.CenterVertically){
     IconButton(onClick=onAdd,enabled=!loading&&!busy,modifier=Modifier.size(36.dp).semantics{contentDescription="添加文件"}){LineIcon(AppGlyph.Add,Modifier.size(21.dp),if(!loading&&!busy)Muted else Muted.copy(alpha=.4f))}
     IconButton(onClick=onHistory,modifier=Modifier.size(36.dp).semantics{contentDescription="聊天记录"}){LineIcon(AppGlyph.Chat,Modifier.size(20.dp),Muted)}
    }
    Spacer(Modifier.weight(1f))
    Box(Modifier.width(72.dp),contentAlignment=Alignment.CenterEnd){
     IconButton(onClick=onSend,enabled=voiceFeedback==null&&(busy||(!loading&&(value.text.isNotBlank()||files.isNotEmpty()))),modifier=Modifier.size(36.dp).semantics{contentDescription=if(busy)"停止生成"else"发送"}){
      if(AppAppearance.shape==UiShape.AURORA&&!busy&&!loading&&(value.text.isNotBlank()||files.isNotEmpty())){
       Box(Modifier.size(28.dp).clip(CircleShape).background(Brush.linearGradient(listOf(DutBlue,androidx.compose.ui.graphics.lerp(DutBlue,UiPalette.companion,.25f)))),contentAlignment=Alignment.Center){LineIcon(AppGlyph.Send,Modifier.size(17.dp),OnAccent)}
      }else LineIcon(if(busy)AppGlyph.Stop else AppGlyph.Send,Modifier.size(21.dp),color=if(busy||(!loading&&(value.text.isNotBlank()||files.isNotEmpty())))DutBlue else Muted.copy(alpha=.4f))
     }
    }
   }
   if(loading)Text("读取文件…",fontSize=11.sp,color=Muted)

  }
 }
 Box(Modifier.align(Alignment.BottomCenter).padding(bottom=voiceBottom).width(voiceWidth).shadow(if(collapsed)5.dp else 0.dp,CircleShape)){
  VoiceButton(voice,modifier=Modifier.fillMaxWidth(),compact=collapsed)
  if(collapsed&&busy)IconButton(onClick=onSend,modifier=Modifier.size(36.dp).background(SoftSurface,CircleShape).semantics{contentDescription="停止生成"}){LineIcon(AppGlyph.Stop,Modifier.size(16.dp),DutBlue)}
  if(collapsed)voiceFeedback?.let{message->
   val offset=with(androidx.compose.ui.platform.LocalDensity.current){48.dp.roundToPx()}
   androidx.compose.ui.window.Popup(alignment=Alignment.BottomCenter,offset=IntOffset(0,-offset),properties=androidx.compose.ui.window.PopupProperties(focusable=false)){
    Surface(Modifier.widthIn(max=280.dp),shape=AppShape(12.dp),color=CardSurface,shadowElevation=3.dp){Text(message,Modifier.padding(12.dp),fontSize=13.sp,color=DutBlue,maxLines=3,overflow=TextOverflow.Ellipsis)}
   }
  }
 }
 }
}
@Composable fun BottomBar(screen:String,showTabs:Boolean=true,trailing:@Composable RowScope.()->Unit={},onOpen:(String)->Unit){
 Column(Modifier.fillMaxWidth()){
  HorizontalDivider(thickness=.5.dp,color=Hairline)
  Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
   if(!showTabs)Spacer(Modifier.weight(1f))
   if(showTabs)listOf("今天" to AppGlyph.Today,"日历" to AppGlyph.Calendar,"工具" to AppGlyph.Tools).forEachIndexed{index,(name,glyph)->
    if(index>0)VerticalDivider(Modifier.height(16.dp),thickness=.5.dp,color=Hairline)
    val selected=screen==name
    if(AppAppearance.shape==UiShape.AURORA){
     val tint by androidx.compose.animation.animateColorAsState(if(selected)DutBlue else Muted,androidx.compose.animation.core.tween(140),label="auroraTabTint")
     val indicator by androidx.compose.animation.core.animateDpAsState(if(selected)20.dp else 0.dp,androidx.compose.animation.core.spring(dampingRatio=.8f,stiffness=600f),label="auroraTabIndicator")
     Box(Modifier.weight(1f).fillMaxHeight().clip(AppShape(8.dp)).clickable{onOpen(name)}.semantics{contentDescription=name;this.selected=selected;role=Role.Tab},contentAlignment=Alignment.Center){
      Row(verticalAlignment=Alignment.CenterVertically){LineIcon(glyph,Modifier.size(18.dp),tint);Spacer(Modifier.width(7.dp));Text(name,fontSize=12.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,color=tint)}
      if(indicator>0.dp)Box(Modifier.align(Alignment.BottomCenter).padding(bottom=2.dp).width(indicator).height(2.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(DutBlue,UiPalette.companion))))
     }
    }else Row(Modifier.weight(1f).fillMaxHeight().clip(AppShape(8.dp)).clickable{onOpen(name)}.semantics{contentDescription=name;this.selected=selected;role=Role.Tab},horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
     LineIcon(glyph,Modifier.size(18.dp),if(selected)DutBlue else Muted)
     Spacer(Modifier.width(7.dp))
     Text(name,fontSize=12.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,color=if(selected)DutBlue else Muted)
    }
   }
   trailing()
  }
 }
}

@Composable fun ComposerToggle(collapsed:Boolean,enabled:Boolean,onClick:()->Unit){
 IconButton(onClick=onClick,enabled=enabled,modifier=Modifier.size(36.dp).semantics{
  contentDescription=if(collapsed)"展开 AI 栏"else"收起 AI 栏"
  stateDescription=if(collapsed)"已收起"else"已展开"
 }){LineIcon(AppGlyph.Next,Modifier.size(18.dp).rotate(if(collapsed)-90f else 90f),if(enabled)Muted else Muted.copy(alpha=.4f))}
}


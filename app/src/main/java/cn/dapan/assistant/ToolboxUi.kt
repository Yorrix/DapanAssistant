package cn.dapan.assistant

import org.json.*

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

private fun toolGlyph(name:String)=when(name){
 "晨签","待办"->AppGlyph.Check
 "专注"->AppGlyph.Clock
 "地图","导航","热力图","校园热力图"->AppGlyph.Pin
 "天气"->AppGlyph.Today
 "录音","录音摘要","课堂生词"->AppGlyph.Mic
 "实时翻译"->AppGlyph.Chat
 "账单","二手信息"->AppGlyph.Wallet
 "官网通知"->AppGlyph.Gazette
 "抽签"->AppGlyph.Wheel
 "手持弹幕"->AppGlyph.Marquee
 "信息查询","资料库","扫描文件"->AppGlyph.File
 else->AppGlyph.Tools
}
val toolGroups=listOf(
  "常用工具" to listOf("晨签","扫描文件","专注","抽签","手持弹幕"),
  "地图" to listOf("导航","热力图"),
  "生活" to listOf("二手信息","账单","取件码","天气"),
  "信息查询" to listOf("官网通知","信息查询","资料库"),
  "录音" to listOf("录音摘要","实时翻译","课堂生词")
 )
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ToolsScreen(favorites:ToolFavorites,scroll:ScrollState=rememberScrollState(),open:(String)->Unit){
 var search by rememberSaveable{mutableStateOf("")}
 var searching by rememberSaveable{mutableStateOf(false)}
 val searchFocus=remember{FocusRequester()}
 // Focus is requested only by an explicit search tap, never by restored page state.
 var focusRequested by remember{mutableStateOf(false)}
 LaunchedEffect(focusRequested){if(focusRequested){searchFocus.requestFocus();focusRequested=false}}

 AssistantSurface("工具",JSONObject().put("query",search).put("favorites",JSONArray(favorites.names.toList())),listOf(surfaceTool("search","筛选工具名称","query",false){a->prepared("查找工具",a){search=a.optString("query");"已筛选工具"}}))
 Column(Modifier.fillMaxSize()){
 Row(Modifier.fillMaxWidth().height(48.dp).padding(start=16.dp,end=8.dp),verticalAlignment=Alignment.CenterVertically){
  if(searching)BasicTextField(search,{search=it},Modifier.weight(1f).focusRequester(searchFocus).semantics{contentDescription="查找工具"},singleLine=true,textStyle=TextStyle(color=Ink,fontSize=16.sp),cursorBrush=SolidColor(DutBlue),decorationBox={inner->Box{if(search.isEmpty())Text("查找工具",color=Muted,fontSize=16.sp);inner()}})
  else Text("工具",Modifier.weight(1f),fontSize=17.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold,color=Ink)
  if(searching)QuietIconButton("关闭搜索",AppGlyph.Close,{search="";searching=false})
  else QuietIconButton("搜索工具",AppGlyph.Search,{searching=true;focusRequested=true},color=if(search.isBlank())Muted else DutBlue)
 }
 Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal=12.dp).padding(bottom=LocalFloatingAiHeight.current),verticalArrangement=Arrangement.spacedBy(6.dp)){
  var matches=0
  toolGroups.forEach{(title,names)->
   val shown=names.filter{title.contains(search.trim(),ignoreCase=true)||it.contains(search.trim(),ignoreCase=true)};matches+=shown.size
   if(shown.isNotEmpty())Column(Modifier.fillMaxWidth()){
    Row(Modifier.fillMaxWidth().height(36.dp),verticalAlignment=Alignment.CenterVertically){
     Box(Modifier.width(3.dp).height(17.dp).clip(CircleShape).background(DutBlue))
     Text(title,Modifier.weight(1f).padding(start=10.dp),fontSize=15.sp,fontWeight=FontWeight.SemiBold,color=Ink)
    }
    FlowRow(Modifier.fillMaxWidth().padding(top=2.dp),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
     shown.forEach{name->ToolChip(name,name in favorites.names,{open(toolDestination(name))},{favorites.toggle(name)})}

    }
   }
  }
  if(matches==0)Text("没有找到这个工具",fontSize=13.sp,color=Muted,modifier=Modifier.padding(vertical=12.dp))
  Spacer(Modifier.height(8.dp))
 }
 }
}

fun toolDestination(name:String)=when(name){"取件码"->"快递";"会议摘要","录音摘要"->"会议纪要";else->name}
@OptIn(ExperimentalFoundationApi::class)
@Composable fun ToolChip(name:String,favorite:Boolean,onOpen:()->Unit,onToggle:()->Unit){
 val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState()
 val pressScale by animateFloatAsState(if(pressed).96f else 1f,label="toolPress")
 val haptic=androidx.compose.ui.platform.LocalHapticFeedback.current
 val context=androidx.compose.ui.platform.LocalContext.current
 val fill=if(AppAppearance.shape in listOf(UiShape.VIVID,UiShape.AURORA))Brush.linearGradient(listOf(if(favorite)AccentSurface else CardSurface,androidx.compose.ui.graphics.lerp(if(favorite)AccentSurface else CardSurface,UiPalette.companion,if(favorite).12f else .05f)))else Brush.linearGradient(listOf(if(favorite)AccentSurface else CardSurface,if(favorite)AccentSurface else CardSurface))
 Row(Modifier.graphicsLayer{scaleX=pressScale;scaleY=pressScale}.shadow(StyleElevation,ToolShape).clip(ToolShape).background(fill)
  .border(.5.dp,if(favorite)DutBlue.copy(alpha=.35f)else Hairline,ToolShape)
  .combinedClickable(interactionSource=interaction,indication=LocalIndication.current,onClick=onOpen,onLongClickLabel=if(favorite)"取消收藏"else"收藏工具",onLongClick={
   runCatching{onToggle();haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)}
    .onFailure{android.widget.Toast.makeText(context,"收藏保存失败，请重试",android.widget.Toast.LENGTH_SHORT).show()}
  }).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
  LineIcon(toolGlyph(name),Modifier.size(16.dp),if(favorite)DutBlue else Muted)
  Text(name,Modifier.padding(start=7.dp),fontSize=13.sp,lineHeight=18.sp,color=if(favorite)DutBlue else Ink)
 }
}


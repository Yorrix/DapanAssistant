package cn.dapan.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal data class CalendarDeadlineBounds(val point:Float,val top:Float,val height:Float,val labelTop:Float){
 fun visible(window:CalendarRenderWindow,hourHeight:Float,lead:Float,firstMinute:Float=0f)=
  window.intersects(firstMinute+(top-lead)/hourHeight*60,firstMinute+(top+height-lead)/hourHeight*60)
}

/** The line always stays on its real instant. Only its label/touch area has height. */
internal object CalendarDeadlineGeometry {
 fun bounds(minute:Float,hourHeight:Float,lead:Float,labelHeight:Float,firstMinute:Float=0f):CalendarDeadlineBounds {
  val contentHeight=lead+(1440f-firstMinute)/60*hourHeight
  val point=lead+(minute.coerceIn(firstMinute,1440f)-firstMinute)/60*hourHeight
  val labelTop=if(point>=labelHeight+5)point-labelHeight-3 else point+4
  val top=minOf(point-8,labelTop-2).coerceAtLeast(0f)
  val bottom=maxOf(point+8,labelTop+labelHeight+2).coerceAtMost(contentHeight)
  return CalendarDeadlineBounds(point,top,(bottom-top).coerceAtLeast(1f),labelTop)
 }
 fun width(columnWidth:Float,naturalWidth:Float):Float {
  val available=(columnWidth-8).coerceAtLeast(1f)
  return maxOf(naturalWidth+12,available*.45f).coerceAtMost(available)
 }
}

@Composable internal fun CalendarDeadlineMarkers(
 groups:Map<LocalDateTime,List<CalendarEntry>>,width:Dp,hourHeight:Float,lead:Float,
 window:CalendarRenderWindow,axis:CalendarTimeAxis,edit:(CalendarEntry)->Unit,
){
 var selected by remember{mutableStateOf<LocalDateTime?>(null)}
 val format=remember{DateTimeFormatter.ofPattern("HH:mm")}
 val ordered=remember(groups){groups.entries.sortedBy{it.key}}
 ordered.forEach{(at,entries)->key(at){
  CalendarDeadlineMarker(entries,at.format(format),width,hourHeight,lead,window,axis){
   if(entries.size==1)edit(entries.first())else selected=at
  }
 }}
 selected?.let{at->CalendarDeadlineDetails(groups[at].orEmpty(),{selected=null},edit)}
}

@Composable private fun CalendarDeadlineMarker(
 entries:List<CalendarEntry>,time:String,width:Dp,hourHeight:Float,lead:Float,
 window:CalendarRenderWindow,axis:CalendarTimeAxis,onClick:()->Unit,
){
 val first=entries.firstOrNull()?:return
 val density=LocalDensity.current
 val minute=first.start.toLocalTime().toSecondOfDay()/60f
 if(minute<axis.firstMinute)return
 // Cull before measuring text; the bound covers the optional second label line.
 val bufferMinutes=48*density.fontScale/hourHeight*60
 if(!window.intersects(minute-bufferMinutes,minute+bufferMinutes))return
 val tint=if(AppAppearance.dark)Color(0xFFF2C17B)else Color(0xFF93601D)
 val zoom=minOf(((width.value-65)/180).coerceIn(0f,1f),((hourHeight-40)/100).coerceIn(0f,1f))
 val timeSize=(10+2*zoom).sp
 val titleSize=(11+3*zoom).sp
 val detailSize=(10+zoom).sp
 val measurer=rememberTextMeasurer(cacheSize=8)
 fun measure(text:String,size:TextUnit,weight:FontWeight=FontWeight.Normal):Float=
  measurer.measure(text,TextStyle(fontSize=size,fontWeight=weight),maxLines=1,softWrap=false).size.width/density.density
 val count=if(entries.size>1)"+${entries.size-1}"else""
 val detail=if(entries.size==1&&width>=190.dp&&hourHeight>=95f)
  listOf(first.location,first.notes).filter{it.isNotBlank()}.joinToString(" · ").take(160)else""
 val sizes=remember(time,first.title,count,detail,timeSize,titleSize,detailSize,density){
  floatArrayOf(measure(time,timeSize,FontWeight.Medium),measure(first.title,titleSize,FontWeight.Medium),
   if(count.isNotEmpty())measure(count,10.sp,FontWeight.Medium)else 0f,
   if(detail.isNotEmpty())measure(detail,detailSize)else 0f)
 }
 val lineWidth=CalendarDeadlineGeometry.width(width.value,maxOf(sizes[0]+sizes[1]+sizes[2]+8,sizes[3]))
 val textWidth=(lineWidth-6).coerceAtLeast(1f)
 // Keep HH:mm intact before allocating any space to the title.
 val fittedTimeSize=(timeSize.value*minOf(1f,textWidth/sizes[0].coerceAtLeast(1f))).sp
 val timeWidth=minOf(textWidth,sizes[0])
 val countFits=count.isEmpty()||textWidth>=timeWidth+sizes[2]+5
 val hasTitle=textWidth-timeWidth-(if(count.isNotEmpty()&&countFits)sizes[2]+5 else 0f)>=30
 val extraLine=detail.isNotEmpty()||!countFits
 val firstLineHeight=(maxOf(fittedTimeSize.value,if(hasTitle)titleSize.value else 0f)+3)*density.fontScale
 val secondLineHeight=if(extraLine)(detailSize.value+3)*density.fontScale else 0f
 val labelHeight=firstLineHeight+secondLineHeight+2
 val bounds=CalendarDeadlineGeometry.bounds(minute,hourHeight,lead,labelHeight,axis.firstMinute)
 if(!bounds.visible(window,hourHeight,lead,axis.firstMinute))return
 val labelTop=bounds.labelTop-bounds.top
 val strokeY=bounds.point-bounds.top
 Box(Modifier.offset(x=(width.value-lineWidth-4).coerceAtLeast(0f).dp,y=bounds.top.dp)
  .width(lineWidth.dp).height(bounds.height.dp)
  .clip(RoundedCornerShape(3.dp)).clickable(onClick=onClick)
  .semantics(mergeDescendants=true){
   role=Role.Button
   contentDescription=if(entries.size==1)"$time 截止，${first.title}，点击编辑"else"$time 截止，共 ${entries.size} 项，点击查看全部"
  }){
  Canvas(Modifier.fillMaxSize()){
   val y=strokeY.dp.toPx()
   drawLine(tint.copy(alpha=.9f),Offset(1.5.dp.toPx(),y),Offset(size.width-1.dp.toPx(),y),1.5.dp.toPx(),cap=StrokeCap.Round)
   drawCircle(tint,2.dp.toPx(),Offset(2.dp.toPx(),y))
  }
  Column(Modifier.offset(y=labelTop.coerceAtLeast(0f).dp).width(lineWidth.dp)
   .background(Paper.copy(alpha=.94f),RoundedCornerShape(2.dp)).padding(horizontal=3.dp)){
   Row(Modifier.height(firstLineHeight.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
    Text(time,fontSize=fittedTimeSize,lineHeight=(fittedTimeSize.value+3).sp,fontWeight=FontWeight.Medium,color=tint,maxLines=1,softWrap=false)
    if(hasTitle)Text(first.title,Modifier.weight(1f),fontSize=titleSize,lineHeight=(titleSize.value+3).sp,fontWeight=FontWeight.Medium,color=tint,maxLines=1,overflow=TextOverflow.Ellipsis)
    if(count.isNotEmpty()&&countFits)Text(count,fontSize=10.sp,fontWeight=FontWeight.Medium,color=tint,maxLines=1,softWrap=false)
   }
   if(extraLine)Text(if(!countFits)"${entries.size}项"else detail,fontSize=detailSize,lineHeight=(detailSize.value+3).sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
  }
 }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CalendarDeadlineDetails(entries:List<CalendarEntry>,dismiss:()->Unit,edit:(CalendarEntry)->Unit){
 val format=remember{DateTimeFormatter.ofPattern("M月d日 HH:mm")}
 ModalBottomSheet(onDismissRequest=dismiss,containerColor=Paper,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
  Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)){
   Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically){
    Text("${entries.firstOrNull()?.start?.format(format).orEmpty()} 截止 · ${entries.size}项",Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.Medium)
    QuietIconButton("关闭",AppGlyph.Close,dismiss)
   }
   LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp),contentPadding=PaddingValues(bottom=16.dp)){
    items(entries,key={it.id}){entry->
     Column(Modifier.fillMaxWidth().clickable{dismiss();edit(entry)}.padding(vertical=10.dp)){
      Text(entry.title,fontSize=14.sp,color=Ink)
      if(entry.notes.isNotBlank())Text(entry.notes,fontSize=12.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
     }
     HorizontalDivider(thickness=.5.dp,color=Hairline)
    }
   }
  }
 }
}

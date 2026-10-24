package cn.dapan.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.remember
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.*
import kotlin.math.roundToInt

@Composable internal fun CalendarEventLabel(e:CalendarEntry,width:Dp,height:Float){
 val fontScale=LocalDensity.current.fontScale
 val tint=calendarEventTint(e)
 val time=remember(e.start,e.end){val f=java.time.format.DateTimeFormatter.ofPattern("HH:mm");e.start.format(f) to e.end.format(f)}
 val type=CalendarTypography.fit(width.value,height,fontScale,e.location.isNotBlank(),e.notes.isNotBlank())
 Column(Modifier.fillMaxSize().clipToBounds().padding(start=6.dp,end=3.dp,top=2.dp,bottom=2.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
  if(type.fullTime){
   Text(if(type.splitTime)"${time.first}\n${time.second}"else "${time.first}–${time.second}",fontSize=type.detail.sp,lineHeight=type.line.sp,color=tint,maxLines=if(type.splitTime)2 else 1,softWrap=false)
   Text(e.title,fontSize=type.title.sp,lineHeight=(type.title+3).sp,color=tint,fontWeight=FontWeight.SemiBold,maxLines=type.titleLines,overflow=TextOverflow.Ellipsis)
   if(type.locationLines>0)Text(e.location,fontSize=type.detail.sp,lineHeight=type.line.sp,color=tint.copy(alpha=.85f),maxLines=type.locationLines,overflow=TextOverflow.Ellipsis)
   if(type.noteLines>0){
    androidx.compose.material3.HorizontalDivider(color=tint.copy(alpha=.16f),thickness=.5.dp)
    Text(e.notes,fontSize=type.detail.sp,lineHeight=type.line.sp,color=Ink.copy(alpha=.8f),maxLines=type.noteLines,overflow=TextOverflow.Ellipsis)
   }
  }else{
   Row(verticalAlignment=Alignment.CenterVertically){
    Text(time.first,fontSize=type.detail.sp,lineHeight=type.line.sp,color=tint,maxLines=1,softWrap=false)
    if(width>85.dp*fontScale)Text(" ${e.title}",fontSize=type.title.sp,lineHeight=(type.title+3).sp,color=tint,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
   }
   if(type.titleLines>0)Text(e.title,fontSize=type.title.sp,lineHeight=(type.title+3).sp,color=tint,maxLines=1,overflow=TextOverflow.Ellipsis)
  }
 }
}
/** Measure the full week independently of the viewport, then translate all three strips equally. */
@Composable internal fun CalendarHorizontalStrip(
 dayWidth:Dp,count:Int,offsetPx:()->Float,modifier:Modifier=Modifier,
 preview:()->Pair<Float,Float> = {1f to offsetPx()},
 content:@Composable RowScope.()->Unit
) {
 Layout(content={Row(Modifier.graphicsLayer{
  val (ratio,targetOffset)=preview()
  transformOrigin=TransformOrigin(0f,0f);scaleX=ratio;translationX=offsetPx()-targetOffset
 },content=content)},modifier=modifier.clipToBounds()){measurables,constraints->
  val total=dayWidth.roundToPx()*count
  val child=measurables.single().measure(constraints.copy(minWidth=total,maxWidth=total))
  layout(constraints.maxWidth,child.height){child.place(-offsetPx().roundToInt(),0)}
 }
}

@Composable internal fun CalendarListButton(label:String,count:Int,glyph:AppGlyph,onClick:()->Unit) {
 Row(Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(SoftSurface)
  .clickable(onClick=onClick).semantics{contentDescription="查看 $label，共 $count 项";role=Role.Button}
  .padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
  LineIcon(glyph,Modifier.size(14.dp),DutBlue)
  Text(label,fontSize=12.sp,fontWeight=FontWeight.SemiBold,color=Ink)
  if(count>0)Text(if(count>99)"99+"else count.toString(),fontSize=10.sp,color=DutBlue,fontWeight=FontWeight.Medium)
 }
}

@Composable internal fun CalendarAllDayStrip(
 items:List<CalendarEntry>,width:Dp,onAdd:()->Unit,edit:(CalendarEntry)->Unit,onMore:()->Unit
) {
 val density=LocalDensity.current
 val measure=rememberTextMeasurer()
 val textSize=(10+3*((width.value-90)/180).coerceIn(0f,1f)).sp
 val naturalWidths=remember(items,textSize,density){items.map{(measure.measure(it.title,style=TextStyle(fontSize=textSize),maxLines=1,softWrap=false).size.width/density.density+8).coerceAtLeast(28f)}}
 val allFit=naturalWidths.sum()+((items.size-1).coerceAtLeast(0)*2)<=width.value-4
 val minimum=68f*density.fontScale
 val usable=(width.value-4).coerceAtLeast(1f)
 val capacity=(usable/minimum).toInt().coerceAtLeast(1)
 val overflow=!allFit&&items.size>capacity
 val shown=if(overflow)((usable-28)/minimum).toInt().coerceAtLeast(1)else items.size
 Row(Modifier.width(width).fillMaxHeight().clickable(onClick=onAdd).padding(horizontal=2.dp,vertical=2.dp),
  verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)){
  items.take(shown).forEachIndexed{index,entry->
   val tint=calendarEventTint(entry)
   Box(Modifier.weight(if(allFit)naturalWidths[index]else 1f).fillMaxHeight().clip(AppShape(4.dp)).background(tint.copy(alpha=.14f))
    .clickable{edit(entry)}.semantics{contentDescription="全天：${entry.title}"}.padding(horizontal=3.dp),contentAlignment=Alignment.CenterStart){
    Text(entry.title,fontSize=textSize,lineHeight=(textSize.value+2).sp,color=tint,maxLines=1,overflow=TextOverflow.Ellipsis)
   }
  }
  if(overflow)Box(Modifier.width(26.dp).fillMaxHeight().clip(AppShape(4.dp)).background(SoftSurface)
   .clickable(onClick=onMore).semantics{contentDescription="查看其余 ${items.size-shown} 项全天事件"},contentAlignment=Alignment.Center){
   Text("+${items.size-shown}",fontSize=9.sp,color=Muted,maxLines=1)
  }
 }
}


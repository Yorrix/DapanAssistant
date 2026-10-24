package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.format.DateTimeFormatter

@Composable internal fun CalendarOverlapChooser(mode:CalendarOverlapMode,showBeforeSix:Boolean,onShowBeforeSix:(Boolean)->Unit,onMode:(CalendarOverlapMode)->Unit){
 var open by remember{mutableStateOf(false)}
 Box{
  Box(Modifier.size(32.dp).clip(AppShape(8.dp)).clickable{open=true}.semantics{contentDescription="日历显示设置，重叠方式：${mode.label}"},contentAlignment=Alignment.Center){
   LineIcon(AppGlyph.Settings,Modifier.size(17.dp),Muted)
  }
  DropdownMenu(expanded=open,onDismissRequest={open=false}){
   CalendarOverlapMode.entries.forEach{choice->DropdownMenuItem(text={Column(Modifier.widthIn(max=240.dp)){
    Text((if(choice==mode)"✓ "else"")+choice.label,fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=if(choice==mode)DutBlue else Ink)
    Text(choice.explanation,fontSize=11.sp,lineHeight=16.sp,color=Muted)
   }},onClick={onMode(choice);open=false})}
   HorizontalDivider(color=Hairline,thickness=.5.dp)
   DropdownMenuItem(text={Text("显示六点前时段",fontSize=13.sp)},
    leadingIcon={Checkbox(checked=showBeforeSix,onCheckedChange=null,modifier=Modifier.size(20.dp))},
    onClick={onShowBeforeSix(!showBeforeSix);open=false})
  }
 }
}

@Composable internal fun CalendarTimedItems(items:List<CalendarDayItem>,groups:List<CalendarOverlapGroup>,mode:CalendarOverlapMode,width:Dp,hourHeight:Float,lead:Float,window:CalendarRenderWindow,axis:CalendarTimeAxis,edit:(CalendarEntry)->Unit,onGroup:(List<String>)->Unit){
 val fontScale=LocalDensity.current.fontScale
 // Travel remains secondary and never participates in collision grouping.
 items.forEach{item->item.travel.forEach{span->
  if(window.intersects(span.startMinute,span.endMinute)){
  val tint=calendarEventTint(item.entry)
  val travelSize=9f+4f*minOf(((width.value-70)/150).coerceIn(0f,1f),((hourHeight-48)/92).coerceIn(0f,1f))
  Box(Modifier.offset(y=axis.position(span.startMinute,hourHeight,lead).dp).width(width).height(((span.endMinute-span.startMinute)/60*hourHeight).dp)
   .clip(AppShape(4.dp)).background(tint.copy(alpha=.05f)).drawBehind{drawLine(tint.copy(alpha=.35f),Offset(1.dp.toPx(),0f),Offset(1.dp.toPx(),size.height),2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(),3.dp.toPx())))}.clickable{edit(item.entry)}.padding(start=5.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp),modifier=Modifier.semantics{contentDescription="步行 ${item.entry.travelMinutes} 分钟"}){
    LineIcon(AppGlyph.Walk,Modifier.size((travelSize+1).dp),Muted)
    Text("${item.entry.travelMinutes}min",fontSize=travelSize.sp,lineHeight=(travelSize+2).sp,color=Muted,maxLines=1,softWrap=false)
   }
  }
  }
 }}
 groups.filter{window.intersects(it.start,it.end)}.forEach{group->key(group.ids){
  if(CalendarOverlap.folded(group,mode,width.value,fontScale)){
   val height=((group.end-group.start)/60*hourHeight).coerceAtLeast(24f)
   Box(Modifier.offset(x=2.dp,y=axis.position(group.start,hourHeight,lead).dp).width((width-4.dp).coerceAtLeast(1.dp)).height(height.dp)
    .clip(AppShape(5.dp)).background(AccentSurface).border(.5.dp,DutBlue.copy(alpha=.3f),AppShape(5.dp))
    .clickable{onGroup(group.ids)}.semantics{contentDescription="${group.items.size} 项有时间交叠的日程，点击展开"}.padding(5.dp)){
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
     Text("交叠 ${group.items.size} 项",fontSize=11.sp,lineHeight=13.sp,color=DutBlue,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
     if(height>=44*fontScale)Text(group.items.take(2).joinToString(" / "){it.entry.title},fontSize=11.sp,lineHeight=14.sp,color=Ink,maxLines=if(height>=65*fontScale)2 else 1,overflow=TextOverflow.Ellipsis)
     if(height>=85*fontScale)Text("点开查看时间与详情",fontSize=10.sp,lineHeight=13.sp,color=Muted,maxLines=2)
    }
   }
  }else{
   group.items.filter{window.intersects(it.body!!.startMinute,it.body!!.endMinute)}.sortedBy{it.lane.index}.forEach{item->key(item.entry.id){
    val e=item.entry;val span=item.body!!;val rect=CalendarOverlap.bounds(item,group,mode,width.value)
    val cardWidth=(rect.width-4).coerceAtLeast(1f).dp;val tint=calendarEventTint(e)
    val height=((span.endMinute-span.startMinute)/60*hourHeight).coerceAtLeast(14f)
    val stacked=mode==CalendarOverlapMode.STACK&&group.lanes>1
    Box(Modifier.offset(x=(rect.left+2).dp,y=axis.position(span.startMinute,hourHeight,lead).dp).width(cardWidth).height(height.dp)
     .clip(AppShape(5.dp)).background(CardSurface).background(tint.copy(alpha=if(AppAppearance.dark).22f else .12f))
     .then(if(stacked)Modifier.border(.5.dp,tint.copy(alpha=.3f),AppShape(5.dp))else Modifier)
     .clickable{if(stacked)onGroup(group.ids)else edit(e)}){
     Box(Modifier.width(2.dp).fillMaxHeight().background(tint))
     CalendarEventLabel(e,cardWidth,height)
    }
   }}
   if(mode==CalendarOverlapMode.STACK&&group.lanes>1){
    Box(Modifier.offset(x=(width-36.dp).coerceAtLeast(0.dp),y=axis.position(group.start,hourHeight,lead).dp).width(34.dp).height(18.dp).clip(AppShape(4.dp)).background(DutBlue)
     .clickable{onGroup(group.ids)}.semantics{contentDescription="展开 ${group.items.size} 项交叠日程"},contentAlignment=Alignment.Center){Text("${group.items.size}项",fontSize=10.sp,color=OnAccent)}
   }
  }
 }}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun CalendarOverlapDetails(entries:List<CalendarEntry>,dismiss:()->Unit,edit:(CalendarEntry)->Unit){
 val time=remember{DateTimeFormatter.ofPattern("HH:mm")}
 ModalBottomSheet(onDismissRequest=dismiss,containerColor=Paper,sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
  Column(Modifier.fillMaxWidth().heightIn(max=420.dp).padding(horizontal=16.dp).verticalScroll(rememberScrollState())){
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    Text("交叠日程 · ${entries.size}",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
    QuietIconButton("关闭",AppGlyph.Close,dismiss)
   }
   entries.sortedBy{it.start}.forEach{e->Column(Modifier.fillMaxWidth().clickable{dismiss();edit(e)}.padding(vertical=9.dp)){
    Text("${e.start.toLocalDate()}  ${e.start.format(time)}–${e.end.format(time)}",fontSize=12.sp,color=calendarEventTint(e))
    Text(e.title,fontSize=14.sp,fontWeight=FontWeight.Medium)
    if(e.location.isNotBlank())Text(e.location,fontSize=12.sp,color=Muted)
    HorizontalDivider(Modifier.padding(top=8.dp),color=Hairline,thickness=.5.dp)
   }}
   if(entries.isEmpty())Text("这些事项已被修改或删除",fontSize=13.sp,color=Muted)
   Spacer(Modifier.height(16.dp))
  }
 }
}

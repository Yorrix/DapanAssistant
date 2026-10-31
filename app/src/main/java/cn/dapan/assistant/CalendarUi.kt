package cn.dapan.assistant

import org.json.*

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged

private fun EventKind.label()=when(this){EventKind.TIMED->"日程";EventKind.DEADLINE->"DDL";EventKind.TODO->"TDL";EventKind.ALL_DAY->"全天"}

@Composable internal fun CalendarAction(label:String,onClick:()->Unit,selected:Boolean=false,modifier:Modifier=Modifier) {
 Box(modifier.height(36.dp).clip(AppShape(7.dp)).background(if(selected)CardSurface else Color.Transparent)
  .clickable(onClick=onClick).semantics{contentDescription=label;this.selected=selected;role=Role.Button}.padding(horizontal=12.dp),contentAlignment=Alignment.Center){
  Text(label,fontSize=13.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,color=if(selected)DutBlue else Ink)
 }
}

@Composable fun CalendarScreen(s:AppStore,edit:(CalendarEntry)->Unit,active:Boolean=true) {
 val context=androidx.compose.ui.platform.LocalContext.current
 val calendarNow by rememberDashboardTime(active)
 val presentation=remember{context.getSharedPreferences("calendar-presentation",0)}
 var overlapMode by remember{mutableStateOf(runCatching{CalendarOverlapMode.valueOf(presentation.getString("overlap","SMART")!!)}.getOrDefault(CalendarOverlapMode.SMART))}
 var showBeforeSix by remember{mutableStateOf(presentation.getBoolean("show-before-six",false))}
 val timeAxis=remember(showBeforeSix){CalendarTimeAxis.fromShowBeforeSix(showBeforeSix)}
 var overlapIds by remember{mutableStateOf<List<String>?>(null)}
 fun setOverlap(mode:CalendarOverlapMode){check(presentation.edit().putString("overlap",mode.name).commit()){"显示方式保存失败"};overlapMode=mode}
 var centerDay by rememberSaveable{mutableDoubleStateOf(ContinuousCalendarViewport.center(LocalDate.now()))}
 val visible by remember{derivedStateOf{ContinuousCalendarViewport.date(centerDay)}}
 var dateJump by remember{mutableStateOf<Pair<Int,Double>?>(null)}
 var list by rememberSaveable{mutableStateOf("")}
 var allDayList by remember{mutableStateOf<LocalDate?>(null)}
 var todayJump by remember{mutableIntStateOf(0)}
 var weekScale by rememberSaveable{mutableFloatStateOf(7f/3f)}
 var hourHeight by rememberSaveable{mutableFloatStateOf(48f)}
 // Gesture samples only update render layers; text is laid out once when fingers lift.
 var zooming by remember{mutableStateOf(false)}
 var previewHeight by remember{mutableFloatStateOf(48f)}
 var previewScale by remember{mutableFloatStateOf(7f/3f)}
 var previewCenter by remember{mutableDoubleStateOf(centerDay)}
 var previewY by remember{mutableFloatStateOf(0f)}
 val invalidEvents=remember(s.entries){s.entries.filter{it.crossesDay}}
 var showInvalid by remember{mutableStateOf(false)}
 val renderCache=rememberPageSnapshot("calendar-render-cache"){CalendarRenderCache()}.value
 val allDays=remember(s.entries){s.entries.filter{it.kind==EventKind.ALL_DAY}.groupBy{it.start.toLocalDate()}}
 val deadlines=remember(s.entries){s.entries.filter{it.kind==EventKind.DEADLINE&&!it.completed}.groupBy{it.start.toLocalDate()}.mapValues{it.value.groupBy{e->e.start}}}
 fun jumpTo(center:Double){dateJump=(dateJump?.first?:0)+1 to center}
 fun selectDate(date:LocalDate){jumpTo(ContinuousCalendarViewport.center(date))}
 // Keep registration outside the subcomposed layout: hidden retained pages are
 // not measured, but their AI binding must still be removed immediately.
 AssistantSurface("日历",JSONObject().put("selectedDate",visible.toString()).put("mode","周").put("overlap",overlapMode.label).put("list",list).put("showBeforeSix",showBeforeSix),listOf(surfaceTool("view","日历查看：date=YYYY-MM-DD，选中日期居中显示；list=DDL/TDL/空；overlap=并排/叠放/智能折叠","date,list,overlap",false){a->val date=a.optString("date",visible.toString());LocalDate.parse(date);val kind=a.optString("list","");require(kind in listOf("","DDL","TDL"));val display=a.optString("overlap",overlapMode.label);val chosen=CalendarOverlapMode.entries.singleOrNull{it.label==display}?:error("请选择并排、叠放或智能折叠");prepared("查看日历",a){if(chosen!=overlapMode)setOverlap(chosen);selectDate(LocalDate.parse(date));list=kind;"已居中显示 $date $kind"}}),enabled=active)
 // Calendar owns its grid; decorative theme paper must never show through it.
 BoxWithConstraints(Modifier.fillMaxSize().background(Paper)) {
 val density=LocalDensity.current.density
 val availableWidth=(maxWidth-38.dp).coerceAtLeast(1.dp)
 val initialViewportHeight=(maxHeight.value-135f).coerceAtLeast(1f)
 val widthPx=availableWidth.value*density
 // The focus is the screen midline, accounting for the time gutter on the left.
 val focusPx=(maxWidth.value/2-38).coerceAtLeast(0f)*density
 fun dayPixels(scale:Float)=(widthPx*scale/7).roundToInt().coerceAtLeast(1).toFloat()
 val dayWidthPx=dayPixels(weekScale)
 val dayWidth=(dayWidthPx/density).dp
 val bufferedDays by remember(widthPx,focusPx,weekScale){derivedStateOf{
  ContinuousCalendarViewport.buffered(if(zooming)previewCenter else centerDay,widthPx,dayPixels(if(zooming)previewScale else weekScale),focusPx)
 }}
 val visibleDays by remember(widthPx,focusPx,weekScale){derivedStateOf{
  ContinuousCalendarViewport.visible(if(zooming)previewCenter else centerDay,widthPx,dayPixels(if(zooming)previewScale else weekScale),focusPx)
 }}
 val dates=remember(bufferedDays){bufferedDays.map(LocalDate::ofEpochDay)}
 val start=dates.first()
 val fullDayItems=remember(s.entries,bufferedDays){renderCache.range(s.entries,start,dates.size)}
 val dayItems=remember(fullDayItems,timeAxis){if(timeAxis.startHour==0)fullDayItems else fullDayItems.mapValues{CalendarDayLayout.visible(it.value,timeAxis)}}
 val overlapGroups=remember(dayItems){if(timeAxis.startHour==0)renderCache.groups(s.entries,start,dates.size)else dayItems.mapValues{CalendarOverlap.groups(it.value)}}
 fun horizontalOffset()=ContinuousCalendarViewport.offset(start,centerDay,dayWidthPx,focusPx)
 val horizontalPreview:()->Pair<Float,Float> = {
  if(zooming)dayPixels(previewScale)/dayWidthPx to ContinuousCalendarViewport.offset(start,previewCenter,dayPixels(previewScale),focusPx)
  else 1f to horizontalOffset()
 }
 val lead=44f
 // Preserve the old saved format (pixels from 00:00). Folding hours changes only display coordinates.
 var offset by rememberSaveable{mutableFloatStateOf((7*hourHeight+lead)*density)}
 var viewportHeight by remember{mutableIntStateOf(0)}
 fun limitOffset(value:Float,height:Float=hourHeight)=timeAxis.clampAbsoluteOffset(value,height,lead,density,viewportHeight.toFloat())
 fun setShowBeforeSix(value:Boolean){
  if(value==showBeforeSix)return
  check(presentation.edit().putBoolean("show-before-six",value).commit()){"显示时段保存失败"}
  val nextAxis=CalendarTimeAxis.fromShowBeforeSix(value)
  androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot{
   offset=nextAxis.clampAbsoluteOffset(offset,hourHeight,lead,density,viewportHeight.toFloat())
   showBeforeSix=value
  }
 }
 val horizontalScroll=rememberScrollableState{delta->centerDay=ContinuousCalendarViewport.pan(centerDay,delta,dayWidthPx);delta}
 // Cancel a running fling before explicit navigation; seven-day jumps retain the fractional position.
 LaunchedEffect(dateJump){dateJump?.let{target->horizontalScroll.scroll(MutatePriority.PreventUserInput){centerDay=target.second}}}
 Column(Modifier.fillMaxSize()) {

  Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
   Text(CalendarViewport.range(LocalDate.ofEpochDay(visibleDays.first),LocalDate.ofEpochDay(visibleDays.last)),
    modifier=Modifier.weight(1f).clickable{android.app.DatePickerDialog(context,platformDialogTheme,{_,y,m,d->selectDate(LocalDate.of(y,m+1,d))},visible.year,visible.monthValue-1,visible.dayOfMonth).show()}.semantics{contentDescription="选择日期，居中显示"}.padding(end=4.dp,top=4.dp,bottom=4.dp),
    autoSize=TextAutoSize.StepBased(minFontSize=8.sp,maxFontSize=18.sp,stepSize=.5.sp),
    fontWeight=FontWeight.SemiBold,lineHeight=1.2.em,maxLines=1,softWrap=false)
   CalendarAction("今天",{selectDate(calendarNow.toLocalDate());todayJump++})
   Box(Modifier.size(30.dp).clickable{jumpTo(centerDay-7)}.semantics{contentDescription="上一周"},contentAlignment=Alignment.Center){LineIcon(AppGlyph.Back,Modifier.size(16.dp),Muted)}
   Box(Modifier.size(30.dp).clickable{jumpTo(centerDay+7)}.semantics{contentDescription="下一周"},contentAlignment=Alignment.Center){LineIcon(AppGlyph.Next,Modifier.size(16.dp),Muted)}
   CalendarOverlapChooser(overlapMode,showBeforeSix,{value->runCatching{setShowBeforeSix(value)}.onFailure{android.widget.Toast.makeText(context,it.message,android.widget.Toast.LENGTH_SHORT).show()}}){mode->runCatching{setOverlap(mode)}.onFailure{android.widget.Toast.makeText(context,it.message,android.widget.Toast.LENGTH_SHORT).show()}}
   Box(Modifier.size(32.dp).clip(CircleShape).background(AccentSurface).clickable{edit(newEntry(visible))}.semantics{contentDescription="新增事项";role=Role.Button},contentAlignment=Alignment.Center){LineIcon(AppGlyph.Add,Modifier.size(18.dp),DutBlue)}
  }
  Row(Modifier.fillMaxWidth().padding(start=12.dp,end=12.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
   Text("${visible.monthValue}月${visible.dayOfMonth}日 · 周${"一二三四五六日"[visible.dayOfWeek.value-1]} · ${relativeDay(visible,calendarNow.toLocalDate())}",Modifier.weight(1f),fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
   if(invalidEvents.isNotEmpty())CalendarAction("${invalidEvents.size}项待修正",{showInvalid=true})
   CalendarListButton("DDL",s.entries.count{it.kind==EventKind.DEADLINE&&!it.completed},AppGlyph.Clock){list="DDL"}
   Spacer(Modifier.width(6.dp))
   CalendarListButton("TDL",s.calendarEntries.count{it.kind==EventKind.TODO&&!it.completed},AppGlyph.Check){list="TDL"}
  }
  // A shared horizontal offset keeps dates, all-day strips and timed columns aligned.
  Row(Modifier.fillMaxWidth().height(31.dp).padding(start=38.dp,bottom=3.dp),verticalAlignment=Alignment.CenterVertically) {
   CalendarHorizontalStrip(dayWidth,dates.size,::horizontalOffset,Modifier.fillMaxSize().scrollable(horizontalScroll,Orientation.Horizontal),horizontalPreview){
    dates.forEach{date->key(date){Row(Modifier.width(dayWidth).fillMaxHeight().clickable{selectDate(date)}.semantics{contentDescription="${date}，居中显示"},horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
     Text("周${"一二三四五六日"[date.dayOfWeek.value-1]}",fontSize=10.sp,color=Muted)
     Box(Modifier.padding(start=3.dp).size(21.dp).background(if(date==calendarNow.toLocalDate())DutBlue else Color.Transparent,CircleShape),contentAlignment=Alignment.Center){Text("${date.dayOfMonth}",fontSize=12.sp,fontWeight=FontWeight.Medium,color=if(date==calendarNow.toLocalDate())OnAccent else Ink)}
    }}}
   }
  }
  Row(Modifier.fillMaxWidth().height(24.dp),verticalAlignment=Alignment.CenterVertically) {
   Text("全天",fontSize=9.sp,color=Muted,modifier=Modifier.width(38.dp).padding(start=3.dp))
   CalendarHorizontalStrip(dayWidth,dates.size,::horizontalOffset,Modifier.weight(1f).fillMaxHeight().scrollable(horizontalScroll,Orientation.Horizontal),horizontalPreview){
    dates.forEach{date->key(date){
     val items=allDays[date].orEmpty()
     CalendarAllDayStrip(items,dayWidth,{edit(CalendarEntry(title="",kind=EventKind.ALL_DAY,start=date.atStartOfDay()).normalized())},edit,{allDayList=date})
    }}
   }
  }
  var pinching by remember{mutableStateOf(false)}
  val renderWindow by remember(density,availableWidth,initialViewportHeight,weekScale,hourHeight,timeAxis,bufferedDays,focusPx){derivedStateOf{
   val scale=if(zooming)previewScale else weekScale
   CalendarRenderWindow.calculate(
    ContinuousCalendarViewport.offset(start,if(zooming)previewCenter else centerDay,dayPixels(scale),focusPx)/density,
    timeAxis.relativeOffset(if(zooming)previewY else offset,if(zooming)previewHeight else hourHeight,density)/density,
    availableWidth.value,if(viewportHeight>0)viewportHeight/density else initialViewportHeight,
    dayPixels(scale)/density,if(zooming)previewHeight else hourHeight,lead,timeAxis.firstMinute,dates.size)
  }}
  LaunchedEffect(todayJump){if(todayJump>0)offset=limitOffset(timeAxis.absoluteOffset(timeAxis.position((calendarNow.toLocalTime().toSecondOfDay()/60f-60).coerceAtLeast(timeAxis.firstMinute),hourHeight,lead)*density,hourHeight,density))}
  val scrolling=rememberScrollableState{delta->val old=offset;offset=limitOffset(offset-delta);old-offset}
  Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().onSizeChanged{viewportHeight=it.height;offset=limitOffset(offset)}.scrollable(scrolling,Orientation.Vertical,enabled=!pinching).scrollable(horizontalScroll,Orientation.Horizontal,enabled=!pinching).pointerInput(density,availableWidth,timeAxis){
   awaitEachGesture {
    awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
    var pairIds:List<PointerId> = emptyList();var initialSpan=0f;var initialX=0f;var initialY=0f;var initialHeight=hourHeight;var initialScale=weekScale
    var anchorMinute=0f;var anchorDay=0.0;var scaleX=false;var scaleY=true;var claimed=false
    try{
     do{
      val event=awaitPointerEvent(PointerEventPass.Initial)
      val fingers=event.changes.filter{it.pressed}.take(2)
      if(fingers.size==2){
       if(!zooming){previewHeight=hourHeight;previewScale=weekScale;previewCenter=centerDay;previewY=offset;zooming=true}
       pinching=true;claimed=true
       val ids=fingers.map{it.id};val delta=fingers[0].position-fingers[1].position;val span=delta.getDistance()
       val center=(fingers[0].position+fingers[1].position)/2f
       if(ids!=pairIds){
        pairIds=ids;initialSpan=span;initialX=abs(delta.x);initialY=abs(delta.y);initialHeight=previewHeight;initialScale=previewScale
        anchorMinute=timeAxis.minuteAt((timeAxis.relativeOffset(previewY,previewHeight,density)+center.y)/density,previewHeight,lead)
        anchorDay=previewCenter+(center.x-38*density-focusPx)/dayPixels(previewScale)
        scaleX=initialX>viewConfiguration.touchSlop*2&&initialX>=initialY*.7f
        scaleY=(initialY>viewConfiguration.touchSlop*2&&initialY>=initialX*.7f)
       }else if(initialSpan>viewConfiguration.touchSlop*2){
        val height=if(scaleY)CalendarViewport.zoom(initialHeight,(abs(delta.y)/initialY).pow(.85f))else previewHeight
        val scale=if(scaleX)(initialScale*(abs(delta.x)/initialX).pow(.85f)).coerceIn(1f,7f)else previewScale
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot{
         previewHeight=height;previewY=limitOffset(timeAxis.absoluteOffset(timeAxis.position(anchorMinute,height,lead)*density-center.y,height,density),height)
         previewScale=scale;previewCenter=anchorDay-(center.x-38*density-focusPx)/dayPixels(scale)
        }
       }
      }else pairIds=emptyList()
      if(claimed)event.changes.forEach{it.consume()}
     }while(event.changes.any{it.pressed})
    }finally{
     androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot{
      if(zooming){hourHeight=previewHeight;weekScale=previewScale;offset=previewY;centerDay=previewCenter}
      zooming=false;pinching=false
     }
    }
   }
  }) {
   Layout(modifier=Modifier.fillMaxSize(),content={Row(Modifier.fillMaxWidth().graphicsLayer{
    transformOrigin=TransformOrigin(0f,0f)
    val ratio=if(zooming)previewHeight/hourHeight else 1f
    scaleY=ratio
    translationY=if(zooming)timeAxis.relativeOffset(offset,hourHeight,density)-timeAxis.relativeOffset(previewY,previewHeight,density)+lead*density*(1f-ratio)else 0f
   }) {
    val timeColor=Muted.copy(alpha=.8f)
    val fontScale=LocalDensity.current.fontScale
    val timePaint=remember(timeColor,density,fontScale){android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{textSize=9f*density*fontScale;color=timeColor.toArgb()}}
    val hourLabels=remember{(0..23).map{"%02d:00".format(it)}}
    Canvas(Modifier.width(38.dp).height(timeAxis.height(hourHeight,lead).dp)) {
     val paint=timePaint
     (timeAxis.startHour..23).forEach{hour->val y=timeAxis.position(hour*60f,hourHeight,lead).dp.toPx();drawContext.canvas.nativeCanvas.drawText(hourLabels[hour],3.dp.toPx(),y-(paint.ascent()+paint.descent())/2,paint)}
    }
    CalendarHorizontalStrip(dayWidth,dates.size,::horizontalOffset,Modifier.weight(1f).height(timeAxis.height(hourHeight,lead).dp),horizontalPreview){
    dates.forEachIndexed{dayIndex,date->key(date){Box(Modifier.width(dayWidth).height(timeAxis.height(hourHeight,lead).dp).background(if(date==calendarNow.toLocalDate())DutBlue.copy(alpha=.018f)else Color.Transparent)) {
     val columnWidth=dayWidth;val lineColor=Hairline
     Canvas(Modifier.fillMaxSize().pointerInput(date,hourHeight,timeAxis){detectTapGestures{position->
      val minutes=timeAxis.tapMinute(position.y/density,hourHeight,lead)
      edit(CalendarEntry(title="",kind=EventKind.TIMED,start=date.atStartOfDay().plusMinutes(minutes.toLong())))
     }}){
      drawLine(lineColor.copy(alpha=.5f),Offset.Zero,Offset(0f,size.height),strokeWidth=.5.dp.toPx())
      (timeAxis.startHour..23).forEach{hour->val y=timeAxis.position(hour*60f,hourHeight,lead).dp.toPx();drawLine(lineColor,Offset(0f,y),Offset(size.width,y),strokeWidth=.5.dp.toPx())
       if(hourHeight>=48f)drawLine(lineColor.copy(alpha=.3f),Offset(0f,y+hourHeight.dp.toPx()/2),Offset(size.width,y+hourHeight.dp.toPx()/2),strokeWidth=.5.dp.toPx())
      }
     }
     if(dayIndex in renderWindow.days){
     CalendarTimedItems(dayItems[date].orEmpty(),overlapGroups[date].orEmpty(),overlapMode,columnWidth,hourHeight,lead,renderWindow,timeAxis,edit){overlapIds=it}
     CalendarDeadlineMarkers(deadlines[date].orEmpty(),columnWidth,hourHeight,lead,renderWindow,timeAxis,edit)
     }
     if(date==calendarNow.toLocalDate()&&calendarNow.toLocalTime().toSecondOfDay()/60f>=timeAxis.firstMinute){
      val red=if(AppAppearance.dark)Color(0xFFFF8C88)else Color(0xFFD94742)
      val nowY=timeAxis.position(calendarNow.toLocalTime().toSecondOfDay()/60f,hourHeight,lead)
      Box(Modifier.offset(y=nowY.dp).fillMaxWidth().height(1.dp).background(red))
      Box(Modifier.offset(x=(-2).dp,y=(nowY-2).dp).size(5.dp).background(red,CircleShape))
     }

    }}}
    }
   }},measurePolicy={measurables,constraints->
    val content=measurables.single().measure(constraints.copy(minHeight=0,maxHeight=Constraints.Infinity))
    layout(constraints.maxWidth,constraints.maxHeight){content.placeRelative(0,-timeAxis.relativeOffset(offset,hourHeight,density).roundToInt())}
   })
  }
 }

 }
 if(active){
 overlapIds?.let{ids->CalendarOverlapDetails(s.entries.filter{it.id in ids},{overlapIds=null},edit)}
 if(showInvalid)AlertDialog(onDismissRequest={showInvalid=false},title={Text("这些旧日程需要修改",fontSize=17.sp)},text={Column(Modifier.verticalScroll(rememberScrollState())){
  Text("日程现在不能跨天。以下记录已保留，暂不画入时间轴；点选后修改到同一天，或在编辑页删除。",fontSize=13.sp,color=Muted)
  invalidEvents.forEach{e->Column(Modifier.fillMaxWidth().clickable{showInvalid=false;edit(e)}.padding(vertical=8.dp)){
   Text(e.title,fontSize=14.sp)
   Text("${e.start.toString().replace('T',' ')} → ${e.end.toString().replace('T',' ')}",fontSize=11.sp,color=Muted)
  }}
 }},confirmButton={TextButton(onClick={showInvalid=false}){Text("关闭")}})
 allDayList?.let{date->AlertDialog(onDismissRequest={allDayList=null},containerColor=CardSurface,title={Text("${date.monthValue}月${date.dayOfMonth}日 · 全天",fontSize=16.sp)},text={Column(Modifier.verticalScroll(rememberScrollState())){
  s.entries.filter{it.kind==EventKind.ALL_DAY&&it.start.toLocalDate()==date}.forEach{entry->Text(entry.title,fontSize=14.sp,modifier=Modifier.fillMaxWidth().clickable{allDayList=null;edit(entry)}.padding(vertical=9.dp))}
 }},confirmButton={TextButton(onClick={allDayList=null;edit(CalendarEntry(title="",kind=EventKind.ALL_DAY,start=date.atStartOfDay()).normalized())}){Text("新增全天")}},dismissButton={TextButton(onClick={allDayList=null}){Text("关闭")}})}
 if(list.isNotEmpty())CalendarListSheet(s,list,{list=""},edit)
 }
}

internal fun calendarEventTint(e:CalendarEntry):Color {
 val dark=AppAppearance.dark
 return when(e.category){
  "课程"->DutBlue
  "作业"->if(dark)Color(0xFFBCA9F3)else Color(0xFF7055A2)
  ""->if(dark)Color(0xFF85C9BE)else Color(0xFF28796C)
  else->if(e.category.hashCode()%2==0){if(dark)Color(0xFFBCA9F3)else Color(0xFF7055A2)}else{if(dark)Color(0xFF85C9BE)else Color(0xFF28796C)}
 }
}



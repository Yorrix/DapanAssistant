package cn.dapan.assistant

import org.json.*

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun MorningScreen(s:AppStore) {
 val context=LocalContext.current
 var permissionRevision by remember{mutableIntStateOf(0)}
 val notifyPermission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){granted->if(!granted)android.widget.Toast.makeText(context,"通知未获允许，可在设置中开启",android.widget.Toast.LENGTH_SHORT).show();Reminders.reschedule(context);permissionRevision++}
 val lifecycle=(context as androidx.activity.ComponentActivity).lifecycle
 DisposableEffect(lifecycle){val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME){Reminders.reschedule(context);permissionRevision++}};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
 val tick by rememberDashboardTime()
 val now=tick.atZone(ZoneId.systemDefault()).withZoneSameInstant(MorningCalendar.zone).toLocalDateTime()
 val today=now.toLocalDate()
 var monthText by rememberSaveable{mutableStateOf(YearMonth.from(today).toString())}
 val month=YearMonth.parse(monthText)
 var setup by rememberSaveable{mutableStateOf(s.morningStart==null)}
 var draftStart by remember(s.morningStart){mutableStateOf(s.morningStart?:today)}
 var draftEnd by remember(s.morningEnd){mutableStateOf(s.morningEnd)}
 var error by remember{mutableStateOf("")}
 val records=s.checks.associateBy{it.date}
 val stats=MorningCalendar.stats(s.morningStart,s.morningEnd,now,s.morningTime,s.checks,Holidays.days)
 val todayStatus=MorningCalendar.status(today,s.morningStart,s.morningEnd,now,s.morningTime,records[today],Holidays.days)
 val showToday=todayStatus==MorningStatus.FAILED&&!records.containsKey(today)
 val red=if(AppAppearance.dark)Color(0xFFFF9891)else Color(0xFFB8433B)
 val green=if(AppAppearance.dark)Color(0xFF83D8AF)else Color(0xFF267552)
 fun tint(status:MorningStatus)=when(status){MorningStatus.EXCLUDED->Muted;MorningStatus.FAILED->red;MorningStatus.PASSED->green}
 fun label(status:MorningStatus)=when(status){MorningStatus.EXCLUDED->"不计入";MorningStatus.FAILED->"不合格";MorningStatus.PASSED->"合格"}
 fun record(day:CheckDay){runCatching{s.check(day);error=""}.onFailure{error=it.message?:"未能保存，请重试"}}
 fun chooseDate(value:LocalDate,onChoose:(LocalDate)->Unit){DatePickerDialog(context,platformDialogTheme,{_,y,m,d->onChoose(LocalDate.of(y,m+1,d))},value.year,value.monthValue-1,value.dayOfMonth).show()}
 AssistantSurface("晨签",JSONObject().put("month",monthText).put("start",s.morningStart).put("end",s.morningEnd).put("time",s.morningTime.toString()).put("notify",s.morningNotify).put("lastCall",s.morningLastCall),listOf(surfaceTool("month","切换晨签月历 month=YYYY-MM","month",false){a->val m=YearMonth.parse(a.text("month")).toString();prepared("查看晨签月历",a){monthText=m;"已显示 $m 晨签记录"}}))
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=12.dp).padding(bottom=LocalFloatingAiHeight.current+10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
  Panel{
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
    listOf("应签天数" to stats.required.toString(),"合格天数" to stats.passed.toString(),"缺签次数" to stats.missed.toString(),"合格率" to (stats.rate?.let{"%.1f%%".format(it*100)}?:"—")).forEachIndexed{i,(title,count)->
     BoxWithConstraints(Modifier.weight(if(i==3)1.12f else 1f)){
      val measurer=rememberTextMeasurer(cacheSize=12)
      val density=LocalDensity.current
      val valueStyle=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold)
      val width=constraints.maxWidth
      val valueSize=remember(count,width,i,density.fontScale,valueStyle,measurer){
       val sizes=if(i==3)listOf(17,16,15,14,13,12)else listOf(23,21,19,17,15,13)
       (sizes.firstOrNull{size->measurer.measure(count,style=valueStyle.copy(fontSize=size.sp),maxLines=1,softWrap=false).size.width<=width}?:sizes.last()).sp
      }
      Column(verticalArrangement=Arrangement.spacedBy(1.dp)){
       Text(count,style=valueStyle,fontSize=valueSize,lineHeight=(valueSize.value+4).sp,color=when(i){1,3->green;2->red;else->Ink},maxLines=1)
       Text(title,fontSize=10.sp,color=Muted)
      }
     }
    }
    if(showToday)Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
     MorningAnswerButton("今天合格",green){record(CheckDay(today,true,true))}
     MorningAnswerButton("不合格",red){record(CheckDay(today,true,false))}
    }
   }
  }
  Panel{
   Row(Modifier.fillMaxWidth().height(32.dp),verticalAlignment=Alignment.CenterVertically){
    QuietIconButton("上个月",AppGlyph.Back,{monthText=month.minusMonths(1).toString()})
    Text("${month.year}年${month.monthValue}月",Modifier.weight(1f).clickable{chooseDate(month.atDay(1)){monthText=YearMonth.from(it).toString()}},fontSize=15.sp,fontWeight=FontWeight.SemiBold)
    TextButton(onClick={monthText=YearMonth.from(today).toString()},contentPadding=PaddingValues(horizontal=6.dp)){Text("本月",fontSize=11.sp)}
    QuietIconButton("下个月",AppGlyph.Next,{monthText=month.plusMonths(1).toString()})
   }
   Row(Modifier.fillMaxWidth()){"一二三四五六日".forEach{Text(it.toString(),Modifier.weight(1f).wrapContentWidth(Alignment.CenterHorizontally),fontSize=10.sp,color=Muted)}}
   val leading=month.atDay(1).dayOfWeek.value-1
   val rows=(leading+month.lengthOfMonth()+6)/7
   repeat(rows){row->
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)){
     repeat(7){col->
      val number=row*7+col-leading+1
      if(number !in 1..month.lengthOfMonth())Spacer(Modifier.weight(1f).height(36.dp))
      else{
       val date=month.atDay(number)
       val future=!MorningCalendar.canMark(date,today)
       val status=MorningCalendar.displayStatus(records[date],MorningCalendar.status(date,s.morningStart,s.morningEnd,now,s.morningTime,records[date],Holidays.days),Holidays.days)
       val color=if(future)Muted.copy(alpha=.45f)else tint(status)
       Box(Modifier.weight(1f).height(36.dp).clip(AppShape(7.dp)).background(if(future)Color.Transparent else if(status==MorningStatus.EXCLUDED)SoftSurface else color.copy(alpha=.14f))
        .then(if(date==today)Modifier.border(1.dp,DutBlue,AppShape(7.dp))else Modifier)
        .then(if(future)Modifier else Modifier.clickable{record(MorningCalendar.next(date,status))})
        .semantics{contentDescription="${date.monthValue}月${date.dayOfMonth}日";stateDescription=if(future)"未到日期，不可修改"else label(status);if(future)disabled()},contentAlignment=Alignment.Center){
        Text(number.toString(),fontSize=14.sp,fontWeight=if(date==today)FontWeight.Bold else FontWeight.Medium,color=color)
       }
      }
     }
    }
   }
   Row(Modifier.fillMaxWidth().padding(top=2.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
    listOf(MorningStatus.FAILED,MorningStatus.PASSED,MorningStatus.EXCLUDED).forEach{status->
     Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)){
      Box(Modifier.size(6.dp).background(tint(status),AppShape(2.dp)))
      Text(label(status),fontSize=10.sp,color=Muted)
     }
    }
    Text("浅字 · 未到日期",fontSize=10.sp,color=Muted.copy(alpha=.65f))
   }
  }
  Panel{
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    Text("每日统计时间",Modifier.weight(1f),fontSize=12.sp)
    TextButton(onClick={TimePickerDialog(context,platformDialogTheme,{_,h,m->runCatching{s.configureMorning(s.morningStart,s.morningEnd,LocalTime.of(h,m))}.onFailure{error=it.message?:"保存失败"}},s.morningTime.hour,s.morningTime.minute,true).show()},contentPadding=PaddingValues(horizontal=6.dp)){
     Text(s.morningTime.format(DateTimeFormatter.ofPattern("HH:mm")),fontSize=13.sp)
    }
   }
   Row(Modifier.fillMaxWidth().height(36.dp),verticalAlignment=Alignment.CenterVertically){
    Text("通知询问",Modifier.weight(1f),fontSize=12.sp)
    Switch(s.morningNotify,{enabled->if(enabled&&android.os.Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS);runCatching{s.configureMorning(s.morningStart,s.morningEnd,notify=enabled)}.onFailure{error=it.message?:"保存失败"}})
   }
   Row(Modifier.fillMaxWidth().heightIn(min=42.dp),verticalAlignment=Alignment.CenterVertically){
    Column(Modifier.weight(1f)){
     Text("结束前 1 分钟提醒",fontSize=12.sp)
     Text("${if(s.morningTime<LocalTime.of(0,1))"前一天 "else""}${s.morningTime.minusMinutes(1).format(DateTimeFormatter.ofPattern("HH:mm"))} · 跟随统计时间，精确提醒",fontSize=10.sp,color=Muted)
    }
    Switch(s.morningLastCall,{enabled->
     runCatching{s.configureMorning(s.morningStart,s.morningEnd,lastCall=enabled);permissionRevision++}.onFailure{error=it.message?:"保存失败"}
     if(enabled&&android.os.Build.VERSION.SDK_INT>=33&&context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)notifyPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    })
   }
   if(s.morningLastCall){
    Text("仅在需要晨签且尚未记录时提醒；系统可能显示下次闹钟图标。",fontSize=10.sp,lineHeight=14.sp,color=Muted)
    val issue=remember(permissionRevision,s.morningLastCall,s.morningTime,s.morningStart,s.morningEnd){MorningLastCall.issue(context)}
    issue?.let{Text(it,fontSize=11.sp,color=MaterialTheme.colorScheme.error)}
    if(!Reminders.precise(context))TextButton(onClick={runCatching{context.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:${context.packageName}")))}.onFailure{error="请在系统设置中允许大盘助手使用闹钟和提醒"}},contentPadding=PaddingValues(0.dp)){Text("允许精确计时",fontSize=12.sp)}
   }
  }
  if((s.morningNotify||s.morningLastCall)&&s.morningStart==null)Text("设置统计起点后才会安排晨签通知。",fontSize=12.sp,color=Muted)
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){
    Text("统计范围",fontSize=11.sp,color=Muted)
    Text("${s.morningStart?:"未设置起点"} — ${s.morningEnd?:"持续统计"}",fontSize=12.sp)
   }
   TextButton(onClick={setup=!setup;draftStart=s.morningStart?:today;draftEnd=s.morningEnd}){Text(if(setup)"收起"else"修改",fontSize=12.sp)}
  }
  if(setup)Panel{
   Row(verticalAlignment=Alignment.CenterVertically){
    Text("起点",fontSize=12.sp,color=Muted)
    TextButton(onClick={chooseDate(draftStart){draftStart=it}},modifier=Modifier.weight(1f)){Text(draftStart.toString(),fontSize=12.sp)}
    Text("终点",fontSize=12.sp,color=Muted)
    TextButton(onClick={chooseDate(draftEnd?:maxOf(draftStart,today)){draftEnd=it}},modifier=Modifier.weight(1f)){Text(draftEnd?.toString()?:"设置日期",fontSize=12.sp)}
   }
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
    if(draftEnd!=null)TextButton(onClick={draftEnd=null}){Text("持续统计",fontSize=12.sp)}
    TextButton(onClick={runCatching{s.configureMorning(draftStart,draftEnd);error="";setup=false}.onFailure{error=it.message?:"保存失败"}}){Text("保存范围",fontSize=12.sp)}
   }
  }
  if(error.isNotBlank())Text(error,fontSize=11.sp,color=MaterialTheme.colorScheme.error)
  Row(verticalAlignment=Alignment.CenterVertically){
   Text("按国家工作日默认标记，可逐日调整",Modifier.weight(1f),fontSize=10.sp,color=Muted)
   ToolHelp("这里只记录晨签，不替你向学校签到。今天及之前的日期可以点击修改；未来日期显示为浅色、无色块，不可点击。只有进入统计范围并到达每日统计时间才计入。灰色不计入，红色不合格，绿色合格。合格率=合格天数/应签天数，应签为0时显示—。\n\n通知与顶部按钮回答后不再重复询问；仍可点击月历修改。\n\n已内置2026年国家放假和调休安排，其他年份暂按周一至周五；学校寒暑假、免签日请手动改灰。")
  }
 }
}

@Composable private fun MorningAnswerButton(text:String,color:Color,click:()->Unit){
 Box(Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha=.12f)).clickable(onClick=click).padding(horizontal=9.dp),contentAlignment=Alignment.Center){
  Text(text,fontSize=11.sp,fontWeight=FontWeight.Medium,color=color)
 }
}

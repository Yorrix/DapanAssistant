package cn.dapan.assistant

import org.json.*

import android.Manifest
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*

@Composable fun WeatherScreen(){
 val context=LocalContext.current;val scope=rememberCoroutineScope();val tick by rememberDashboardTime();val now=tick.atZone(ZoneId.systemDefault()).withZoneSameInstant(WeatherData.zone).toLocalDateTime()
 var result by remember{mutableStateOf(WeatherData.snapshot())};var error by remember{mutableStateOf("")};var loading by remember{mutableStateOf(false)};var detail by remember{mutableStateOf<WeatherDetail?>(null)}
 val noticeEnabled=remember{mutableStateMapOf(*WeatherNotice.entries.map{it to WeatherMorning.enabled(context,it)}.toTypedArray())};val noticeTimes=remember{mutableStateMapOf(*WeatherNotice.entries.map{it to WeatherMorning.time(context,it)}.toTypedArray())}
 var permissionOk by remember{mutableStateOf(Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)}
 val lifecycle=(context as androidx.activity.ComponentActivity).lifecycle
 DisposableEffect(lifecycle){val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)permissionOk=Build.VERSION.SDK_INT<33||context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
 val requestPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){permissionOk=it}
 fun configure(notice:WeatherNotice,value:Boolean,hour:LocalTime=noticeTimes.getValue(notice)){runCatching{WeatherMorning.configure(context,value,hour,notice);noticeEnabled[notice]=value;noticeTimes[notice]=hour}.onFailure{error="通知设置未完成：${it.message}"}}
 fun refresh(){if(loading)return;loading=true;scope.launch{try{result=withContext(Dispatchers.IO){WeatherData.fetch(context)};error=""}catch(e:CancellationException){throw e}catch(e:Exception){error="更新失败，请检查网络；已有预报会保留。"}finally{loading=false}}}
 LaunchedEffect(Unit){if(result==null)result=withContext(Dispatchers.IO){WeatherData.cached(context)};refresh()}
 AssistantSurface("天气",JSONObject().put("notifications",JSONArray(WeatherNotice.entries.map{JSONObject().put("period",it.label).put("enabled",noticeEnabled[it]).put("time",noticeTimes[it].toString()).put("forecast",it.dayLabel)})).put("current",result?.optJSONObject("current")),listOf(
  surfaceTool("refresh","刷新真实天气数据","",false){a->prepared("刷新天气",a){result=withContext(Dispatchers.IO){WeatherData.fetch(context)};"天气已更新"}},
  surfaceTool("notification","天气通知 period=晨间/晚间（默认晨间），enabled布尔,time=HH:mm；晨间预报今天，晚间预报明天","period,enabled,time"){a->val n=when(a.optString("period","晨间")){"晨间"->WeatherNotice.MORNING;"晚间"->WeatherNotice.EVENING;else->error("请选择晨间或晚间")};val e=a.optBoolean("enabled",noticeEnabled.getValue(n));val t=LocalTime.parse(a.optString("time",noticeTimes.getValue(n).toString()));prepared("设置${n.label}天气通知 · $t · ${if(e)"开启"else"关闭"}",a){WeatherMorning.configure(context,e,t,n);noticeEnabled[n]=e;noticeTimes[n]=t;"${n.label}天气通知已设置为 $t ${if(e)"开启"else"关闭"}"}}
 ))
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=12.dp).padding(bottom=LocalFloatingAiHeight.current+10.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
  Row(Modifier.fillMaxWidth().height(30.dp),verticalAlignment=Alignment.CenterVertically){Text("盘锦校区",Modifier.weight(1f),fontSize=13.sp,color=Muted);TextButton(onClick={refresh()},enabled=!loading,contentPadding=PaddingValues(0.dp)){Text(if(loading)"更新中…"else"刷新",fontSize=12.sp)}}
  if(error.isNotBlank())Text(error,fontSize=11.sp,color=MaterialTheme.colorScheme.error)
  if(loading&&result==null)LinearProgressIndicator(Modifier.fillMaxWidth())
  result?.let{o->
   val current=o.getJSONObject("current")
   Surface(color=CardSurface,shape=AppShape(10.dp),modifier=Modifier.fillMaxWidth().clickable{detail=weatherDetail(current,title="当前天气 · ${current.optString("time").replace('T',' ')}")}){
    Row(Modifier.padding(horizontal=10.dp,vertical=7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
     Column(Modifier.weight(1f)){Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){Text(WeatherData.number(current,"temperature_2m")+"°",fontSize=25.sp);WeatherIcon(WeatherData.condition(current.optInt("weather_code",-1)),Modifier.size(22.dp));Text(WeatherData.condition(current.optInt("weather_code",-1)),fontSize=13.sp)};WeatherSignals.flags(current).firstOrNull()?.let{WeatherFlagMark(it)}}
     Column(verticalArrangement=Arrangement.spacedBy(1.dp)){Text("体感 ${WeatherData.number(current,"apparent_temperature")}°",fontSize=15.sp,fontWeight=FontWeight.Medium,color=Ink);Text("湿度 ${WeatherData.number(current,"relative_humidity_2m")}%",fontSize=11.sp,color=Muted);Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)){WeatherIcon("风",Modifier.size(13.dp));Text("${WeatherData.number(current,"wind_speed_10m")} km/h",fontSize=10.sp,color=Muted)}}
     LineIcon(AppGlyph.Next,Modifier.size(12.dp),Muted)
    }
   }
   WeatherAdvice.text(current).takeIf{it.isNotBlank()}?.let{Text(it,fontSize=12.sp,lineHeight=16.sp,color=Muted,modifier=Modifier.padding(horizontal=2.dp))}
   val hourly=o.optJSONObject("hourly")
   Panel{
    Row(Modifier.fillMaxWidth()){Text("未来 12 小时",Modifier.weight(1f),fontSize=12.sp,fontWeight=FontWeight.SemiBold)}
    val indexes=WeatherData.nextHours(o,now)
    if(hourly==null||indexes.isEmpty())Text("预报已过期，请刷新",fontSize=11.sp,color=Muted)
    else LazyRow(horizontalArrangement=Arrangement.spacedBy(4.dp)){
     items(indexes.size){n->val i=indexes[n];val at=LocalDateTime.parse(hourly.getJSONArray("time").getString(i));val flag=WeatherSignals.flags(hourly,i).firstOrNull()
      Column(Modifier.width(64.dp).background(if(flag!=null)weatherFlagColor(flag.level).copy(alpha=.07f)else SoftSurface,AppShape(7.dp)).clickable{detail=weatherDetail(hourly,i,title="${at.monthValue}/${at.dayOfMonth} ${at.toLocalTime()}")}.padding(vertical=5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(1.dp)){
       Text((if(at.toLocalDate()!=now.toLocalDate())"明 "else"")+"%02d时".format(at.hour),fontSize=10.sp,color=Muted)
       Text(WeatherData.number(hourly,"temperature_2m",i)+"°",fontSize=16.sp,fontWeight=FontWeight.Medium)
       Text("体感 "+WeatherData.number(hourly,"apparent_temperature",i)+"°",fontSize=11.sp,color=Ink)
       Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)){WeatherIcon(WeatherData.hourCondition(hourly,i),Modifier.size(17.dp));Text(WeatherData.hourCondition(hourly,i),fontSize=10.sp,color=if(flag!=null)weatherFlagColor(flag.level)else Ink,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)}
       Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(2.dp)){if(flag!=null)WeatherFlagMark(flag,true);if((WeatherData.value(hourly,"precipitation_probability",i)?:0.0)>0)Text(WeatherData.number(hourly,"precipitation_probability",i)+"%",fontSize=10.sp,color=Muted);if((WeatherData.value(hourly,"wind_gusts_10m",i)?:0.0)>=50)WeatherIcon("大风",Modifier.size(12.dp))}
      }
     }
    }
   }
   val daily=o.getJSONObject("daily")
   Panel{
    Row(Modifier.fillMaxWidth()){Text("未来七天",Modifier.weight(1f),fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text("从明天起",fontSize=10.sp,color=Muted)}
    val days=WeatherData.nextDays(daily,now.toLocalDate())
    days.forEach{i->
     val day=LocalDate.parse(daily.getJSONArray("time").getString(i));val flag=WeatherSignals.flags(daily,i,true).firstOrNull()
     val condition=WeatherData.dailyCondition(daily,i)
     HorizontalDivider(color=Hairline,thickness=.5.dp)
     Row(Modifier.fillMaxWidth().heightIn(min=38.dp).background(if(flag!=null)weatherFlagColor(flag.level).copy(alpha=.045f)else androidx.compose.ui.graphics.Color.Transparent).clickable{detail=weatherDetail(daily,i,true,"${day.monthValue}月${day.dayOfMonth}日")}.padding(vertical=2.dp),verticalAlignment=Alignment.CenterVertically){
      Column(Modifier.width(38.dp)){
       Text(if(day==now.toLocalDate().plusDays(1))"明天"else"周"+"一二三四五六日"[day.dayOfWeek.value-1],fontSize=11.sp,lineHeight=13.sp)
       Text("${day.monthValue}/${day.dayOfMonth}",fontSize=9.sp,lineHeight=11.sp,color=Muted)
      }
      Row(Modifier.weight(1f),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
       WeatherIcon(condition,Modifier.size(25.dp))
       Column{
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(3.dp)){
         Text(condition,fontSize=12.sp,lineHeight=15.sp,maxLines=1,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,color=if(flag!=null)weatherFlagColor(flag.level)else Ink,modifier=Modifier.weight(1f,false))
         if(flag!=null)WeatherFlagMark(flag,true)
         if((WeatherData.value(daily,"wind_gusts_10m_max",i)?:0.0)>=50)WeatherIcon("大风",Modifier.size(14.dp))
        }
        if((WeatherData.value(daily,"precipitation_probability_max",i)?:0.0)>0)Text(WeatherData.number(daily,"precipitation_probability_max",i)+"%",fontSize=10.sp,lineHeight=12.sp,color=Muted)
       }
      }
      Column(Modifier.padding(horizontal=5.dp),horizontalAlignment=Alignment.End){
       Text(WeatherData.number(daily,"temperature_2m_min",i)+"° / "+WeatherData.number(daily,"temperature_2m_max",i)+"°",fontSize=14.sp,fontWeight=FontWeight.Medium)
       if(WeatherData.value(daily,"apparent_temperature_min",i)!=null)Text("体感 "+WeatherData.number(daily,"apparent_temperature_min",i)+"° / "+WeatherData.number(daily,"apparent_temperature_max",i)+"°",fontSize=11.sp,color=Muted)
      }
      LineIcon(AppGlyph.Next,Modifier.size(10.dp),Muted)
     }
    }
    if(days.size<7)Text(if(days.isEmpty())"暂无未来预报，请刷新"else"其余日期暂无预报，请刷新",fontSize=10.sp,color=Muted)
   }

   Text("预报 ${o.getJSONObject("current").optString("time").replace('T',' ')}",fontSize=10.sp,color=Muted)
  }
  Panel{
   WeatherNotice.entries.forEach{notice->
    val enabled=noticeEnabled.getValue(notice);val time=noticeTimes.getValue(notice)
    Row(Modifier.fillMaxWidth().height(42.dp),verticalAlignment=Alignment.CenterVertically){
     Column(Modifier.weight(1f)){Text("${notice.label}通知",fontSize=12.sp);Text("预报${if(notice==WeatherNotice.MORNING)"今天"else"明天"}天气",fontSize=10.sp,color=Muted)}
     TextButton(onClick={TimePickerDialog(context,platformDialogTheme,{_,h,m->configure(notice,enabled,LocalTime.of(h,m))},time.hour,time.minute,true).show()},contentPadding=PaddingValues(horizontal=8.dp)){Text(time.toString(),fontSize=12.sp)}
     Switch(enabled,{value->configure(notice,value);if(value&&!permissionOk&&Build.VERSION.SDK_INT>=33)requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)})
    }
   }
   if(noticeEnabled.values.any{it}&&!permissionOk)TextButton(onClick={if(Build.VERSION.SDK_INT>=33)requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)}){Text("需要允许通知权限",fontSize=12.sp)}
   if(noticeEnabled.values.any{it}&&!Reminders.precise(context))Text("系统未允许准时提醒，通知可能延后；可在设置中开启。",fontSize=11.sp,color=Muted)
  }
  Text("Open-Meteo · CC BY 4.0 · 红/黄为出行提示，非官方预警",fontSize=9.sp,color=Muted)
  Spacer(Modifier.height(6.dp))
 }
 detail?.let{WeatherDetailDialog(it){detail=null}}
}


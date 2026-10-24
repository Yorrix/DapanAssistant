package cn.dapan.assistant

import android.content.*
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File
import java.time.*

@Composable fun HomeDateHeader(){
 val now by rememberDashboardTime()
 Text("${now.monthValue}月${now.dayOfMonth}日 · ${"一二三四五六日"[now.dayOfWeek.value-1]}",fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,color=Ink)
}
@Composable fun HomeWidgetCard(title:String,open:()->Unit,content:@Composable ColumnScope.()->Unit){
 val interaction=remember{MutableInteractionSource()};val pressed by interaction.collectIsPressedAsState()
 val pressScale by animateFloatAsState(if(pressed).985f else 1f,label="widgetPress")
 Surface(modifier=Modifier.graphicsLayer{scaleX=pressScale;scaleY=pressScale},color=CardSurface,shape=AppShape(14.dp),shadowElevation=StyleElevation,border=StyleBorder){
  Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=6.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
   Row(Modifier.fillMaxWidth().height(32.dp).clickable(interactionSource=interaction,indication=LocalIndication.current,onClick=open),verticalAlignment=Alignment.CenterVertically){
    Text(title,Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
    LineIcon(AppGlyph.Next,Modifier.size(14.dp),Muted)
   }
   content()
  }
 }
}
@Composable private fun HomeMetrics(items:List<Pair<String,String>>){
 Row(Modifier.fillMaxWidth().padding(vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
  items.forEach{(label,value)->Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
   Text(value,fontSize=18.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold,color=Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
   Text(label,fontSize=11.sp,lineHeight=16.sp,color=Muted)
  }}
 }
}
@Composable fun HomeMorning(s:AppStore,open:()->Unit){
 val tick by rememberDashboardTime();val now=tick.atZone(ZoneId.systemDefault()).withZoneSameInstant(MorningCalendar.zone).toLocalDateTime()
 val today=now.toLocalDate();val record=s.checks.find{it.date==today};var error by remember{mutableStateOf<String?>(null)}
 val elapsed=MorningCalendar.elapsedDate(now,s.morningTime)
 val stats=remember(s.morningStart,s.morningEnd,elapsed,s.morningTime,s.checks,Holidays.days){MorningCalendar.stats(s.morningStart,s.morningEnd,now,s.morningTime,s.checks,Holidays.days)}
 val status=MorningCalendar.status(today,s.morningStart,s.morningEnd,now,s.morningTime,record,Holidays.days)
 HomeWidgetCard("晨签",open){
  if(s.morningStart==null)TextButton(onClick=open,contentPadding=PaddingValues(0.dp)){Text("设置统计起止日期",fontSize=13.sp)}
  else{
   HomeMetrics(listOf("合格率" to (stats.rate?.let{"%.1f%%".format(it*100)}?:"—"),"合格 / 应签" to "${stats.passed} / ${stats.required}","缺签" to "${stats.missed}"))
   Row(Modifier.fillMaxWidth().heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically){
    val label=when{now.toLocalTime()<s.morningTime->"今日 ${s.morningTime} 开始统计";status==MorningStatus.EXCLUDED->"今天不计入";status==MorningStatus.PASSED->"今天已合格";record!=null->"今天未合格";else->"今天合格了吗？"}
    Text(label,Modifier.weight(1f),fontSize=12.sp,color=Muted)
    if(status==MorningStatus.FAILED&&record==null){
     listOf(true to "合格",false to "不合格").forEach{(passed,label)->TextButton(onClick={runCatching{s.check(CheckDay(today,true,passed))}.onFailure{error="未能保存，请重试"}},contentPadding=PaddingValues(horizontal=7.dp)){Text(label,fontSize=12.sp)}}
    }
   }
  }
  error?.let{Text(it,fontSize=11.sp,color=MaterialTheme.colorScheme.error)}
 }
}
@Composable fun HomeBilling(s:AppStore,open:()->Unit){
 var editing by remember{mutableStateOf<NoteItem?>(null)}
 val now by rememberDashboardTime();val today=now.atZone(ZoneId.systemDefault()).withZoneSameInstant(Billing.zone).toLocalDate()
 val bills=remember(s.notes){s.notes.filter{it.module=="账单"}};val daily=remember(bills,today){bills.filter{Billing.day(it)==today}};val monthly=remember(bills,YearMonth.from(today)){bills.filter{YearMonth.from(Billing.day(it))==YearMonth.from(today)}}
 HomeWidgetCard("账单",open){
  HomeMetrics(listOf("今日支出" to "¥${Billing.money(Billing.sum(daily,false))}","本月支出" to "¥${Billing.money(Billing.sum(monthly,false))}"))
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
   Text("今天吃饭 ¥${Billing.money(Billing.sum(daily.filter{Billing.category(it)=="吃饭"},false))}",Modifier.weight(1f),fontSize=12.sp,color=Muted)
   TextButton(onClick={editing=NoteItem(module="账单",title="",group="吃饭")},contentPadding=PaddingValues(horizontal=4.dp)){Text("记一笔",fontSize=12.sp)}
  }
 }
 editing?.let{BillEditor(it,{editing=null},{n->s.put(n);editing=null})}
}
@Composable fun HomeParcels(s:AppStore,open:()->Unit){
 val c=LocalContext.current;val records=remember(s.notes){s.notes.filter{it.module=="快递"&&!it.done}.sortedByDescending{it.created}}
 HomeWidgetCard("取件码 · ${records.size}",open){
  if(records.isEmpty())Text("暂无待取快递",Modifier.padding(vertical=5.dp),fontSize=12.sp,color=Muted)
  records.take(3).forEach{n->Row(Modifier.fillMaxWidth().heightIn(min=32.dp),verticalAlignment=Alignment.CenterVertically){
   Text(ParcelCodes.display(n),Modifier.weight(1f),fontSize=17.sp,lineHeight=23.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
   TextButton(onClick={(c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("取件码",ParcelCodes.display(n)))},contentPadding=PaddingValues(horizontal=4.dp)){Text("复制",fontSize=11.sp)}
  }}
  if(records.size>3)Text("另有 ${records.size-3} 个 · 查看全部",Modifier.clickable(onClick=open).padding(vertical=4.dp),fontSize=11.sp,color=DutBlue)
 }
}
@Composable fun HomeFocus(s:AppStore,open:()->Unit){
 val c=LocalContext.current;var minutes by remember{mutableIntStateOf(FocusControl.minutes(c))};var error by remember{mutableStateOf<String?>(null)}
 val tick by rememberDashboardTime();val remaining=s.focusEnd-tick.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
 HomeWidgetCard("专注",open){
  if(remaining>0)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){Text("剩余 ${(remaining+59999)/60000} 分钟",fontSize=20.sp,lineHeight=25.sp,fontWeight=FontWeight.Medium);Text("正在专注",fontSize=11.sp,color=Muted)}
   TextButton(onClick=open,contentPadding=PaddingValues(horizontal=8.dp)){Text("继续",fontSize=12.sp)}
  }else{
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){
   listOf(25,45,60).forEach{value->Surface(onClick={minutes=value},shape=AppShape(8.dp),color=if(minutes==value)AccentSurface else SoftSurface){Text("$value 分",Modifier.padding(horizontal=9.dp,vertical=7.dp),fontSize=12.sp,color=if(minutes==value)DutBlue else Ink)}}
   Spacer(Modifier.weight(1f))
   Button(onClick={if(s.focusEnd>System.currentTimeMillis())open()else if(FocusControl.needsDnd(c)&&!FocusControl.access(c)){error="请先在专注页允许免打扰，或开启允许通知"}else runCatching{FocusControl.start(c,s,minutes);open()}.onFailure{error=it.message}},contentPadding=PaddingValues(horizontal=12.dp),modifier=Modifier.height(34.dp),shape=AppShape(10.dp)){Text("开始",fontSize=12.sp)}
  }
  if(minutes !in listOf(25,45,60))Text("当前 $minutes 分钟",fontSize=11.sp,color=Muted)
  }
  Text("${if(FocusControl.allowNotifications(c))"允许通知"else"屏蔽通知"} · ${if(FocusControl.allowCalls(c))"允许来电"else"静音来电"} · 点标题调整",fontSize=10.sp,lineHeight=15.sp,color=Muted,modifier=Modifier.padding(top=3.dp,bottom=2.dp))
  error?.let{Text(it,fontSize=11.sp,color=MaterialTheme.colorScheme.error)}
 }
}
@Composable fun HomeWeather(open:()->Unit){
 val c=LocalContext.current;val tick by rememberDashboardTime();var data by remember{mutableStateOf(WeatherData.snapshot())};var failed by remember{mutableStateOf(false)}
 LaunchedEffect(tick.toLocalDate(),tick.hour){
  if(data==null)data=withContext(Dispatchers.IO){WeatherData.cached(c)}
  val age=System.currentTimeMillis()-c.getSharedPreferences("weather-cache",0).getLong("fetched",0)
  if(data==null||age !in 0..15*60000L)try{data=withContext(Dispatchers.IO){WeatherData.fetch(c)};failed=false}catch(e:CancellationException){throw e}catch(_:Exception){failed=true}
 }
 HomeWidgetCard("天气 · 盘锦",open){
  val current=data?.optJSONObject("current");val daily=data?.optJSONObject("daily")
  if(current==null)Text(if(failed)"天气暂不可用，点此重试"else"正在获取天气…",Modifier.clickable(onClick=open).padding(vertical=6.dp),fontSize=12.sp,color=Muted)
  else{
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)){
    Text("${WeatherData.number(current,"temperature_2m")}°",fontSize=28.sp,lineHeight=34.sp,fontWeight=FontWeight.Medium)
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
     Text(WeatherData.condition(current.optInt("weather_code",-1)),fontSize=14.sp)
     Text("体感 ${WeatherData.number(current,"apparent_temperature")}°",fontSize=16.sp,fontWeight=FontWeight.Medium)
    }
    WeatherSignals.flags(current).firstOrNull()?.let{WeatherFlagMark(it)}
   }
   val index=daily?.optJSONArray("time")?.let{a->(0 until a.length()).firstOrNull{a.optString(it)==WeatherData.now().toLocalDate().toString()}}
   if(daily!=null&&index!=null)Text("今天 ${WeatherData.number(daily,"temperature_2m_min",index)}～${WeatherData.number(daily,"temperature_2m_max",index)}° · ${WeatherData.dailyCondition(daily,index)}",fontSize=12.sp,color=Muted,modifier=Modifier.padding(vertical=4.dp))
   WeatherAdvice.text(current).takeIf{it.isNotBlank()}?.let{Text(it,fontSize=12.sp,lineHeight=16.sp,color=Muted)}
   val age=current.optString("time").let{runCatching{kotlin.math.abs(Duration.between(LocalDateTime.parse(it),WeatherData.now()).toMinutes())}.getOrDefault(Long.MAX_VALUE)}
   if(failed||age>120)Text("缓存天气 · ${current.optString("time").replace('T',' ')}",fontSize=10.sp,color=Muted)
  }
 }
}
@Composable fun HomeNotices(scope:HomeNoticeScope,open:()->Unit){
 val c=LocalContext.current;var data by rememberPageSnapshot<JSONObject?>("home-notices"){null};var syncing by remember{mutableStateOf(true)};var failed by remember{mutableStateOf(false)}
 var selected by remember{mutableStateOf<JSONObject?>(null)}
 var followed by remember(c){mutableStateOf(NoticeSubscriptions.followed(c))}
 DisposableEffect(c){
  val prefs=c.applicationContext.getSharedPreferences("notice-subscriptions",0)
  val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{_,key->
   if(key==null||key=="sources")followed=NoticeSubscriptions.followed(c)
  }
  prefs.registerOnSharedPreferenceChangeListener(listener)
  followed=NoticeSubscriptions.followed(c)
  onDispose{prefs.unregisterOnSharedPreferenceChangeListener(listener)}
 }
 LaunchedEffect(Unit){
  val widgetFile=File(c.filesDir,"home-notices-cache.json")
  val file=listOf(File(c.filesDir,"notice-cache.json"),widgetFile).filter{it.exists()}.maxByOrNull{it.lastModified()}
  data=withContext(Dispatchers.IO){runCatching{JSONObject(file?.readText()?:c.assets.open("notices.json").bufferedReader().use{it.readText()})}.getOrNull()}
  if(data!=null&&file!=null&&System.currentTimeMillis()-file.lastModified() in 0..15*60000L){syncing=false;return@LaunchedEffect}
  try{
   val fresh=withContext(Dispatchers.IO){CommunityClient(c).request("/notices")};data=fresh
   withContext(Dispatchers.IO){val atomic=android.util.AtomicFile(widgetFile);val out=atomic.startWrite();try{out.write(fresh.toString().toByteArray());atomic.finishWrite(out)}catch(e:Exception){atomic.failWrite(out);throw e}}
  }catch(e:CancellationException){throw e}catch(_:Exception){failed=true}finally{syncing=false}
 }
 HomeWidgetCard("官网通知",open){
  val articles=remember(data,scope,followed){HomeNoticeItems.select(data?.optJSONArray("items").objects(),scope,followed)}
  val sourceNames=remember(data){data?.optJSONArray("sources").objects().associate{NoticeDelta.host(it) to it.optString("name")}}
  if(articles.isEmpty())Text(when{
   scope==HomeNoticeScope.FOLLOWED&&followed.isEmpty()->"尚未关注来源 · 进入官网通知后长按来源关注"
   syncing->"正在同步通知…"
   scope==HomeNoticeScope.FOLLOWED->"关注来源暂无通知"
   else->"暂无通知，点标题查看"
  },fontSize=12.sp,lineHeight=18.sp,color=Muted,modifier=Modifier.padding(vertical=5.dp))
  articles.forEachIndexed{i,n->
   if(i>0)HorizontalDivider(color=Hairline,thickness=.5.dp)
   Column(Modifier.fillMaxWidth().clickable{selected=n}.padding(vertical=5.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
    Text(n.optString("title"),fontSize=13.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
    Text(listOfNotNull(sourceNames[NoticeDelta.host(n)]?.takeIf{it.isNotBlank()},n.optString("date").takeIf{it.isNotBlank()}).joinToString(" · "),fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
   }
  }
  if(failed)Text("同步未完成 · 显示缓存",fontSize=10.sp,color=Muted)
 }
 selected?.let{n->AlertDialog(onDismissRequest={selected=null},title={Text(n.optString("title"),fontSize=16.sp)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
  Text(n.optString("date"),fontSize=11.sp,color=Muted)
  Text(n.optString("summary").ifBlank{"暂无摘要，可查看官网原文。"},fontSize=14.sp,lineHeight=21.sp)
 }},confirmButton={TextButton(onClick={val uri=Uri.parse(n.optString("url"));if(uri.scheme in listOf("http","https"))runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,uri))}}){Text("官网原文")}},dismissButton={TextButton(onClick={selected=null}){Text("关闭")}})}
}

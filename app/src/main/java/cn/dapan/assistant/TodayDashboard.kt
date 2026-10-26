package cn.dapan.assistant

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

fun relativeDay(day:LocalDate,today:LocalDate=LocalDate.now()):String = when(val n=ChronoUnit.DAYS.between(today,day)){
 0L->"今天";1L->"明天";2L->"后天";-1L->"昨天";-2L->"前天";else->if(n>0)"${n}天后"else"${-n}天前"
}
@Composable fun rememberDashboardTime(active:Boolean=true):State<LocalDateTime> = produceState(LocalDateTime.now(),active){
 if(active)while(true){value=LocalDateTime.now();delay(15000)}
}
class ToolFavorites(context:Context){
 private val prefs=context.applicationContext.getSharedPreferences("tool-favorites",Context.MODE_PRIVATE)
 var names by mutableStateOf(prefs.getStringSet("names",emptySet())!!.filterNot{it in setOf("双向对话","双向对话翻译")||RetiredFeatures.removed(it)}.map{when(it){"会议摘要","会议纪要"->"录音摘要";"地图"->"导航";"校园热力图"->"热力图";"快递"->"取件码";"文件搜索"->"资料库";else->it}}.toSet());private set
 fun toggle(name:String){val next=if(name in names)names-name else names+name;check(prefs.edit().putStringSet("names",next).commit()){"收藏保存失败"};names=next}
}
object TodayAgenda {
 // One upcoming appointment, then deadlines ranked by proximity interleaved with urgent tasks.
 fun choose(entries:List<CalendarEntry>,now:LocalDateTime,options:AgendaOptions=AgendaOptions()):List<CalendarEntry>{
  val active=entries.filterNot{it.completed||(it.kind==EventKind.TODO&&it.deferred)}
  val today=now.toLocalDate()
  val timed=active.filter{it.kind==EventKind.TIMED&&it.start.toLocalDate()>=today&&it.start.toLocalDate()<today.plusDays(options.timedDays.toLong())&&
   (!it.start.isBefore(now)||(options.ongoing&&it.end>now))}.sortedBy{it.start}.take(options.timedLimit)
  fun rank(e:CalendarEntry):Int=when{
   e.kind==EventKind.TODO->if(e.urgent)2 else 5
   e.start.isBefore(now)->0
   e.start<=now.plusDays(1)->1
   e.start<=now.plusDays(3)->3
   else->4
  }
  val relevant=active.filter{(it.kind==EventKind.TODO&&(!options.urgentOnly||it.urgent))||
   (it.kind==EventKind.DEADLINE&&it.start<=now.plusDays(options.ddlDays.toLong()))}
   .sortedWith(compareBy<CalendarEntry>{rank(it)}.thenBy{it.start}.thenBy{it.id})
  val selected=timed.take(options.maxItems).toMutableList()
  if(options.allDay)active.filter{it.kind==EventKind.ALL_DAY&&it.start.toLocalDate()<=today&&it.end>today.atStartOfDay()}.sortedBy{it.start}.forEach{if(selected.size<options.maxItems)selected+=it}
  relevant.forEach{e->val limit=if(e.kind==EventKind.TODO)options.todoLimit else options.ddlLimit
   if(selected.size<options.maxItems&&selected.count{it.kind==e.kind}<limit)selected+=e}
  return selected
 }
 fun subtitle(e:CalendarEntry,now:LocalDateTime):String {
  val time=e.start.format(DateTimeFormatter.ofPattern("HH:mm"))
  return when(e.kind){
   EventKind.TIMED->"$time–${e.end.format(DateTimeFormatter.ofPattern("HH:mm"))}"+(if(e.travelMinutes>0)" · 路程 ${e.travelMinutes} 分钟"else"")+(if(e.location.isNotBlank())" · ${e.location}"else"")
   EventKind.DEADLINE->if(e.start<now)"已逾期 · ${e.start.monthValue}/${e.start.dayOfMonth} $time"else"${relativeDay(e.start.toLocalDate(),now.toLocalDate())} $time 截止"
   EventKind.TODO->(e.todoPriority+"待办")+e.location.takeIf{it.isNotBlank()}.orEmpty().let{if(it.isEmpty())""else" · $it"}
   EventKind.ALL_DAY->"全天"
  }
 }
}
@Composable fun TodayDashboard(store:AppStore,favorites:ToolFavorites,layout:HomeLayout,open:(String)->Unit,edit:(CalendarEntry)->Unit,scroll:androidx.compose.foundation.lazy.LazyListState=androidx.compose.foundation.lazy.rememberLazyListState()){
 val now by rememberDashboardTime();val agenda=TodayAgenda.choose(store.calendarEntries,now,layout.agenda)
 AssistantSurface("今天",org.json.JSONObject().put("agenda",org.json.JSONArray(agenda.map(::entryToJson))).put("favorites",org.json.JSONArray(favorites.names.toList())).put("widgets",org.json.JSONArray(layout.order.filter{it in layout.enabled}.map{it.title})).put("widgetOrder",org.json.JSONArray(layout.order.map{it.title})).put("noticeScope",layout.notices.name),listOf(
  surfaceTool("widgets","调整首页小组件：enabled为要显示的完整名称数组，order按希望顺序列出组件，未列出的按原顺序保留；noticeScope=ALL显示全部通知/FOLLOWED只显示关注来源；各项可单独修改，可用名称见widgetOrder","enabled,order,noticeScope"){a->
   require(a.has("enabled")||a.has("order")||a.has("noticeScope")){"请指定显示组件、排序或通知范围"}
   fun names(key:String)=a.getJSONArray(key).objectsAsText().map{name->HomeWidget.entries.singleOrNull{it.title==name}?:error("没有小组件 $name")}
   val enabled=if(a.has("enabled"))names("enabled").toSet()else layout.enabled
   val order=if(a.has("order"))HomeWidgetOrdering.requested(names("order"),layout.order)else layout.order
   val noticeScope=if(a.has("noticeScope"))HomeNoticeScope.valueOf(a.onlyChoice("noticeScope",HomeNoticeScope.entries.map{it.name}))else layout.notices
   require(order.size==HomeWidget.entries.size&&order.toSet()==HomeWidget.entries.toSet()){"排序需包含全部小组件且不重复"}
   prepared("调整首页小组件",a){layout.configureWidgets(enabled,order,noticeScope);"首页小组件已更新"}
  }
 ))
 LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),state=scroll,verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=10.dp+LocalFloatingAiHeight.current)){
  if(layout.enabled.isEmpty())item{Text("首页已留空 · 点右上角编辑添加小组件",Modifier.padding(vertical=20.dp),fontSize=12.sp,color=Muted)}
  layout.order.filter{it in layout.enabled}.forEach{widget->item(key=widget.name){when(widget){
   HomeWidget.QUOTE->HitokotoToday(compact=true)
   HomeWidget.AGENDA->HomeAgenda(agenda,now,layout.agenda,open,edit)
   HomeWidget.FAVORITES->HomeFavorites(favorites,open)
   HomeWidget.MORNING->HomeMorning(store){open("晨签")}
   HomeWidget.FOCUS->HomeFocus(store){open("专注")}
   HomeWidget.BILLING->HomeBilling(store){open("账单")}
   HomeWidget.PARCEL->HomeParcels(store){open("取件码")}
   HomeWidget.WEATHER->HomeWeather{open("天气")}
   HomeWidget.NOTICES->HomeNotices(layout.notices){open("官网通知")}
   HomeWidget.LIBRARY->HomeLibrary{open("资料库")}
   HomeWidget.RECORDINGS->HomeRecordings{open(toolDestination("录音摘要"))}
   HomeWidget.VOCABULARY->HomeVocabulary{open("课堂生词")}
  }}}
 }
}
@Composable private fun HomeAgenda(agenda:List<CalendarEntry>,now:LocalDateTime,options:AgendaOptions,open:(String)->Unit,edit:(CalendarEntry)->Unit){
 Surface(color=CardSurface,shape=AppShape(14.dp),shadowElevation=StyleElevation,border=StyleBorder){
   Column(Modifier.padding(horizontal=12.dp,vertical=6.dp)){
    Row(Modifier.fillMaxWidth().height(34.dp),verticalAlignment=Alignment.CenterVertically){
     Text("日程",Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.SemiBold)
     TextButton(onClick={open("日历")},contentPadding=PaddingValues(horizontal=0.dp)){Text("查看全部",fontSize=12.sp)}
    }
    if(agenda.isEmpty())Text("暂无待处理事项",fontSize=12.sp,color=Muted,modifier=Modifier.padding(vertical=8.dp))
    agenda.forEachIndexed{index,e->
     if(index>0)HorizontalDivider(thickness=.5.dp,color=Hairline)
     Row(Modifier.fillMaxWidth().clickable{edit(e)}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
      val label=when(e.kind){EventKind.TIMED->if(e.start<=now&&e.end>now)"进行中"else if(e.start.toLocalDate()==now.toLocalDate())"日程"else relativeDay(e.start.toLocalDate(),now.toLocalDate());EventKind.DEADLINE->"DDL";EventKind.TODO->"TDL";else->"全天"}
      Text(label,Modifier.width(46.dp),fontSize=11.sp,fontWeight=FontWeight.Medium,color=if(e.kind==EventKind.DEADLINE&&e.start<now)MaterialTheme.colorScheme.error else DutBlue)
      Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
       Text(e.title,fontSize=14.sp,lineHeight=19.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=Ink)
       if(options.details)Text(TodayAgenda.subtitle(e,now),fontSize=11.sp,lineHeight=16.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
      }
      LineIcon(AppGlyph.Next,Modifier.size(13.dp),Muted)
     }
    }
   }
  }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun HomeFavorites(favorites:ToolFavorites,open:(String)->Unit){
 Surface(color=CardSurface,shape=AppShape(14.dp),shadowElevation=StyleElevation,border=StyleBorder){
   Column(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("工具收藏",Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.SemiBold)}
    val names=toolGroups.flatMap{it.second}.filter{it in favorites.names}
    if(names.isEmpty())TextButton(onClick={open("工具")},contentPadding=PaddingValues(0.dp)){Text("去工具页收藏常用工具",fontSize=12.sp)}
    else FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
     names.forEach{name->ToolChip(name,true,{open(toolDestination(name))},{favorites.toggle(name)})}
    }
   }
  }
}

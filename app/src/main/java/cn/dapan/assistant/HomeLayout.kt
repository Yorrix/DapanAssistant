package cn.dapan.assistant

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.*

enum class HomeWidget(val title:String,val detail:String){
 QUOTE("一言","一句话与出处"),AGENDA("日程","近期日程、DDL 和 TDL"),FAVORITES("工具收藏","保留长按收藏的工具"),
 MORNING("晨签","合格率与今日记录"),FOCUS("专注","选择时长，开始专注"),BILLING("账单","今日与本月支出"),
 PARCEL("取件码","查看、复制待取件码"),WEATHER("天气","当前天气与今日预报"),NOTICES("官网通知","最近三条通知"),
 LIBRARY("资料库","最近文件与快捷收纳"),
 RECORDINGS("录音摘要","最近记录与已有摘要"),VOCABULARY("课堂生词","最近生词与释义")
}
data class AgendaOptions(
 val maxItems:Int=4,val timedLimit:Int=1,val ddlLimit:Int=2,val todoLimit:Int=2,
 val timedDays:Int=1,val ddlDays:Int=7,val urgentOnly:Boolean=false,
 val ongoing:Boolean=false,val allDay:Boolean=false,val details:Boolean=true
)
class HomeLayout(c:Context){
 companion object {
  val defaultOrder=listOf(HomeWidget.QUOTE,HomeWidget.WEATHER,HomeWidget.AGENDA,HomeWidget.FAVORITES,HomeWidget.NOTICES,HomeWidget.MORNING,HomeWidget.FOCUS,HomeWidget.BILLING,HomeWidget.PARCEL,HomeWidget.LIBRARY,HomeWidget.RECORDINGS,HomeWidget.VOCABULARY)
  val defaultEnabled=defaultOrder.take(5).toSet()
 }
 private val prefs=c.applicationContext.getSharedPreferences("home-layout",0)
 var order by mutableStateOf(HomeWidgetOrdering.restore(prefs.getString("order",null)));private set
 var enabled by mutableStateOf(prefs.getStringSet("enabled",null)?.mapNotNull{n->HomeWidget.entries.find{it.name==n}}?.toSet()?:defaultEnabled);private set
 var notices by mutableStateOf(HomeNoticeScope.fromStored(prefs.getString("noticeScope",null)));private set
 var agenda by mutableStateOf(AgendaOptions(
  prefs.getInt("max",4).coerceIn(1,8),prefs.getInt("timed",1).coerceIn(0,4),prefs.getInt("ddl",2).coerceIn(0,4),prefs.getInt("todo",2).coerceIn(0,4),
  prefs.getInt("timedDays",1).coerceIn(1,7),prefs.getInt("ddlDays",7).coerceIn(1,30),prefs.getBoolean("urgent",false),prefs.getBoolean("ongoing",false),prefs.getBoolean("allDay",false),prefs.getBoolean("details",true)
 ));private set
 fun toggle(w:HomeWidget){val next=if(w in enabled)enabled-w else enabled+w;check(prefs.edit().putStringSet("enabled",next.map{it.name}.toSet()).commit());enabled=next}
 fun move(w:HomeWidget,delta:Int){val index=order.indexOf(w);val target=index+delta;if(index<0||target !in order.indices)return;val next=order.toMutableList();next.removeAt(index);next.add(target,w);check(prefs.edit().putString("order",next.joinToString(","){it.name}).commit());order=next}
 fun reorder(next:List<HomeWidget>){require(next.size==order.size&&next.toSet()==order.toSet());check(prefs.edit().putString("order",next.joinToString(","){it.name}).commit());order=next.toList()}
 fun configureWidgets(nextEnabled:Set<HomeWidget>,nextOrder:List<HomeWidget>,noticeScope:HomeNoticeScope=notices){
  require(nextOrder.size==HomeWidget.entries.size&&nextOrder.toSet()==HomeWidget.entries.toSet()){"排序必须包含所有小组件且不重复"}
  check(prefs.edit().putString("order",nextOrder.joinToString(","){it.name}).putStringSet("enabled",nextEnabled.map{it.name}.toSet()).putString("noticeScope",noticeScope.name).commit()){"首页设置保存失败"}
  order=nextOrder.toList();enabled=nextEnabled.toSet();notices=noticeScope
 }
 fun update(a:AgendaOptions){check(prefs.edit().putInt("max",a.maxItems).putInt("timed",a.timedLimit).putInt("ddl",a.ddlLimit).putInt("todo",a.todoLimit).putInt("timedDays",a.timedDays).putInt("ddlDays",a.ddlDays).putBoolean("urgent",a.urgentOnly).putBoolean("ongoing",a.ongoing).putBoolean("allDay",a.allDay).putBoolean("details",a.details).commit());agenda=a}
 fun updateNotices(scope:HomeNoticeScope){check(prefs.edit().putString("noticeScope",scope.name).commit()){"通知小组件设置保存失败"};notices=scope}
}

@Composable fun HomeLayoutEditor(layout:HomeLayout,onClose:()->Unit){
 var section by remember{mutableStateOf<HomeWidget?>(null)};var failure by remember{mutableStateOf<String?>(null)}
 fun save(action:()->Unit){runCatching(action).onFailure{failure="设置未能保存，请重试"}}
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp).heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.86f),shape=AppShape(18.dp),color=CardSurface){
   Column(Modifier.padding(horizontal=12.dp)){
    Row(Modifier.fillMaxWidth().height(46.dp),verticalAlignment=Alignment.CenterVertically){
     if(section!=null)QuietIconButton("返回小组件",AppGlyph.Back,{section=null})
     Text(when(section){HomeWidget.AGENDA->"日程小组件";HomeWidget.NOTICES->"官网通知小组件";else->"编辑首页"},Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
     TextButton(onClick=onClose){Text("完成",fontSize=13.sp)}
    }
    Text(when(section){HomeWidget.AGENDA->"数量是上限；只显示未完成事项。";HomeWidget.NOTICES->"显示所选范围内的最近三条通知。";else->"勾选显示，拖动右侧手柄排序。"},fontSize=11.sp,color=Muted,modifier=Modifier.padding(bottom=6.dp))
    HorizontalDivider(color=Hairline,thickness=.5.dp)
    if(section==null)HomeWidgetSorter(layout,Modifier.weight(1f,fill=false),onConfigure={section=it},onFailure={failure="设置未能保存，请重试"})else if(section==HomeWidget.NOTICES)Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).selectableGroup()){
     HomeNoticeScope.entries.forEach{choice->
      Row(Modifier.fillMaxWidth().heightIn(min=48.dp).selectable(selected=layout.notices==choice,role=Role.RadioButton,onClick={save{layout.updateNotices(choice)}}),verticalAlignment=Alignment.CenterVertically){
       RadioButton(selected=layout.notices==choice,onClick=null,modifier=Modifier.padding(horizontal=8.dp))
       Text(choice.label,fontSize=14.sp)
      }
     }
     Text("在官网通知页长按来源即可关注。",fontSize=11.sp,color=Muted,modifier=Modifier.padding(start=8.dp,bottom=12.dp))
    }else Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState())){
     val a=layout.agenda
     HomeCountSetting("最多显示",a.maxItems,1..8,"条"){save{layout.update(a.copy(maxItems=it))}}
     HomeCountSetting("日程数量",a.timedLimit,0..4,"条"){save{layout.update(a.copy(timedLimit=it))}}
     HomeCountSetting("日程范围",a.timedDays,1..7,"天"){save{layout.update(a.copy(timedDays=it))}}
     HomeCountSetting("DDL 数量",a.ddlLimit,0..4,"条"){save{layout.update(a.copy(ddlLimit=it))}}
     HomeCountSetting("DDL 范围",a.ddlDays,1..30,"天"){save{layout.update(a.copy(ddlDays=it))}}
     HomeCountSetting("TDL 数量",a.todoLimit,0..4,"条"){save{layout.update(a.copy(todoLimit=it))}}
     HomeToggle("包含正在进行的日程",a.ongoing){save{layout.update(a.copy(ongoing=it))}}
     HomeToggle("包含今天的全天事项",a.allDay){save{layout.update(a.copy(allDay=it))}}
     HomeToggle("TDL 只看紧急",a.urgentOnly){save{layout.update(a.copy(urgentOnly=it))}}
     HomeToggle("显示时间、地点等详情",a.details){save{layout.update(a.copy(details=it))}}
    }
    failure?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
   }
  }
 }
}
@Composable private fun HomeToggle(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth().heightIn(min=42.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),fontSize=13.sp);Switch(value,onChange)}}
@Composable private fun HomeCountSetting(label:String,value:Int,range:IntRange,unit:String,onChange:(Int)->Unit){
 Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically){
  Text(label,Modifier.weight(1f),fontSize=13.sp)
  TextButton(onClick={onChange(value-1)},enabled=value>range.first,modifier=Modifier.width(36.dp),contentPadding=PaddingValues(0.dp)){Text("−")}
  Text("$value $unit",fontSize=13.sp,modifier=Modifier.width(48.dp),textAlign=androidx.compose.ui.text.style.TextAlign.Center)
  TextButton(onClick={onChange(value+1)},enabled=value<range.last,modifier=Modifier.width(36.dp),contentPadding=PaddingValues(0.dp)){Text("＋")}
 }
}

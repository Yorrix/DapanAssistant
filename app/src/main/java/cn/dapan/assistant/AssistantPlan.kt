package cn.dapan.assistant

import android.content.Context
import androidx.compose.runtime.*
import org.json.JSONArray
import org.json.JSONObject

enum class AssistantAccess(val label:String,val detail:String){
 FULL("完全控制","直接执行助手支持的操作，包括批量修改和删除。"),
 CONFIRM("需要批准","操作先列出清单，由你确认后执行。查询和回答无需确认。"),
 READ_ONLY("只读","可以查询、回答和打开工具，不能通过助手修改数据或启动计时。")
}
class AssistantPolicy(context:Context){
 private val prefs=context.applicationContext.getSharedPreferences("assistant-access",Context.MODE_PRIVATE)
 var access by mutableStateOf(runCatching{AssistantAccess.valueOf(prefs.getString("mode","CONFIRM")!!)}.getOrDefault(AssistantAccess.CONFIRM));private set
 fun set(value:AssistantAccess){check(prefs.edit().putString("mode",value.name).commit()){"权限设置保存失败"};access=value}
}
data class AssistantSnapshot(val entries:List<CalendarEntry>,val notes:List<NoteItem>,val checks:List<CheckDay>,val categories:List<String>,val groups:List<String>,val focusEnd:Long,val morningStart:java.time.LocalDate?=null,val morningEnd:java.time.LocalDate?=null){
 val calendar:List<CalendarEntry> get()=entries+notes.filter{it.module=="待办"&&entries.none{e->e.id==it.id}}.map{it.asTodo()}
}
data class OperationPreview(val verb:String,val title:String,val before:Map<String,String> = emptyMap(),val after:Map<String,String> = emptyMap(),val context:String="",val count:Int=1){
 fun text()= "$verb · $title\n"+(if(context.isNotBlank())"$context\n"else"")+(before.keys+after.keys).distinct().joinToString("\n"){key->
  when{before.isEmpty()->"$key：${after[key].orEmpty()}";after.isEmpty()->"$key：${before[key].orEmpty()}";else->"$key：${before[key].orEmpty()} → ${after[key].orEmpty()}"}
 }
}
data class AssistantPlan(val before:AssistantSnapshot,val after:AssistantSnapshot,val items:List<OperationPreview>,val destination:String?=null,val route:AssistantAction.Route?=null,val writes:Boolean=true,val focusMinutes:Int?=null,val unchanged:Int=0,val unmatched:List<String> = emptyList(),val undoing:Boolean=false){
 val operationCount:Int get()=items.sumOf{it.count}
 val noOp:Boolean get()=items.isEmpty()&&destination==null&&route==null
 val summary:String get()=(items.groupBy{it.verb}.map{(verb,rows)->"$verb ${rows.sumOf{it.count}} 项"}+listOfNotNull(if(unchanged>0)"$unchanged 项原本已符合，保持不变"else null,if(unmatched.isNotEmpty())"${unmatched.size} 个名称未匹配"else null)).joinToString(" · ").ifBlank{"没有需要执行的修改"}
 fun receipt():String=(if(noOp)"未改动数据："else"已执行：")+summary+(if(unmatched.isNotEmpty())"\n未找到原名称：${unmatched.joinToString("、")}。这些名称没有修改，请核对。"else "")+items.take(20).joinToString("\n\n",prefix=if(items.isEmpty())""else"\n\n"){it.text()}+(if(items.size>20)"\n其余 ${items.size-20} 项也已执行，可在对应工具查看。"else "")
}
object AssistantPlanner {
 private fun eventFields(e:CalendarEntry):Map<String,String> = linkedMapOf<String,String>().apply{
  put("名称",e.title);put("类型",when(e.kind){EventKind.TIMED->"日程";EventKind.DEADLINE->"DDL";EventKind.TODO->"TDL";EventKind.ALL_DAY->"全天"})
  if(e.kind!=EventKind.TODO)put("时间",when(e.kind){EventKind.ALL_DAY->e.start.toLocalDate().toString();EventKind.DEADLINE->e.start.toString().replace('T',' ');else->"${e.start.toString().replace('T',' ')} ～ ${e.end.toString().replace('T',' ')}"})
  put("分类",e.category.ifBlank{"无分类"})
  if(e.kind==EventKind.TIMED)put("路程","${e.travelMinutes} 分钟")
  if(e.kind!=EventKind.TODO)put("提醒",when(e.reminderMode){ReminderMode.OFF->"不提醒";ReminderMode.DEPARTURE->"出发时 · ${e.reminderTime()}";ReminderMode.BEFORE->"提前 ${e.beforeMinutes} 分钟 · ${e.reminderTime()}";ReminderMode.ABSOLUTE->"指定 · ${e.absoluteReminder}"}.replace('T',' '))
  if(e.kind==EventKind.TODO)put("优先级",if(e.urgent)"紧急"else"一般")
  put("地点",e.location.ifBlank{"无"});put("备注",e.notes.ifBlank{"无"})
  if(e.kind in listOf(EventKind.TODO,EventKind.DEADLINE))put("状态",if(e.completed)"已完成"else"未完成")
 }
 private fun noteFields(n:NoteItem):Map<String,String>{
  if(n.module=="账单"){val amount=Billing.amount(n.detail);return linkedMapOf("名称" to n.title,"类型" to if(amount==null)"金额待核对"else if(amount.signum()<0)"收入"else"支出","金额" to (amount?.abs()?.let{"¥${Billing.money(it)}"}?:n.detail.substringBefore('\n')),"日期" to Billing.day(n).toString(),"分类" to Billing.category(n),"备注" to Billing.memo(n).ifBlank{"无"})}
  return linkedMapOf("名称" to n.title,"模块" to n.module,"内容" to n.detail.ifBlank{"无"},"分组" to n.group,"状态" to if(n.done)"已完成"else"未完成")
 }
 private fun preview(verb:String,title:String,before:Map<String,String>,after:Map<String,String>):OperationPreview{
  if(before.isEmpty()||after.isEmpty())return OperationPreview(verb,title,before,after)
  val changed=(before.keys+after.keys).filter{before[it]!=after[it]}.toSet()
  return OperationPreview(verb,title,before.filterKeys{it in changed},after.filterKeys{it in changed})
 }
 fun build(store:AppStore,action:AssistantAction):AssistantPlan=build(store.assistantSnapshot(),action)
 fun build(original:AssistantSnapshot,action:AssistantAction,now:java.time.LocalDateTime=MorningCalendar.now()):AssistantPlan{
  var state=original;val rows=mutableListOf<OperationPreview>();val touched=mutableSetOf<String>();var unchanged=0;val unmatched=mutableListOf<String>()
  fun reserve(id:String){require(touched.add(id)){"同一批清单重复修改了一条记录，请合并修改后重试"}}
  fun saveEvent(value:CalendarEntry){
   val e=value.copy(category=value.category.trim().takeUnless{it=="无分类"}.orEmpty()).normalized();require(e.error()==null){e.error()!!}
   validateReminderChange(original.calendar.find{it.id==e.id},e,now)
   state=state.copy(entries=state.entries.filterNot{it.id==e.id}+e,
    notes=state.notes.map{if(it.module=="待办"&&it.id==e.id)it.copy(title=e.title,detail=e.notes,group=if(e.urgent)"紧急"else"一般",done=e.completed)else it},
    categories=(state.categories+listOf(e.category)).filter{it.isNotBlank()}.distinct())
  }
  fun matchEvent(a:AssistantAction.Change):CalendarEntry{
   val all=state.calendar.filter{a.module!="待办"||it.kind==EventKind.TODO}
   val found=if(a.targetId.isNotBlank())all.filter{it.id==a.targetId}else all.filter{it.title.equals(a.target,true)}.ifEmpty{all.filter{it.title.contains(a.target,true)}}
   require(found.isNotEmpty()){"找不到“${a.target}”，请提供已有事项名称"}
   require(found.size==1){"“${a.target}”对应多条事项，请说明日期和时间：\n"+found.take(12).joinToString("\n"){"${it.title} · ${if(it.kind==EventKind.TODO)"TDL"else it.start.toString()}"}}
   return found.single()
  }
  fun matchNote(a:AssistantAction.Change):NoteItem{
   val all=state.notes.filter{it.module==a.module};val found=if(a.targetId.isNotBlank())all.filter{it.id==a.targetId}else all.filter{it.title.equals(a.target,true)}.ifEmpty{all.filter{it.title.contains(a.target,true)}}
   require(found.size==1){"“${a.target}”${if(found.isEmpty())"没有匹配记录"else"对应多条记录"}，请补充名称或内容"};return found.single()
  }
  val actions=if(action is AssistantAction.Batch)action.actions else listOf(action)
  require(actions.size in 1..64){"一批最多 64 项操作"}
  actions.forEach{a->when(a){
   is AssistantAction.Event->{reserve(a.value.id);saveEvent(a.value);rows+=preview("新增",a.value.title,emptyMap(),eventFields(state.entries.last()))}
   is AssistantAction.Note->{reserve(a.value.id);if(a.value.module=="待办"){saveEvent(a.value.asTodo());rows+=preview("新增",a.value.title,emptyMap(),eventFields(state.entries.last()))}else{state=state.copy(notes=state.notes+a.value);rows+=preview("新增",a.value.title,emptyMap(),noteFields(a.value))}}
   is AssistantAction.Change->{
    if(a.module in listOf("日历","待办")){
     val old=matchEvent(a);reserve(old.id)
     if(a.operation=="delete"){state=state.copy(entries=state.entries.filterNot{it.id==old.id},notes=state.notes.filterNot{it.module=="待办"&&it.id==old.id});rows+=preview("删除",old.title,eventFields(old),emptyMap())}
     else{
      val next=if(a.operation=="complete"){require(old.kind in listOf(EventKind.DEADLINE,EventKind.TODO)){"只有 DDL / TDL 可以标记完成"};old.copy(completed=true)}else AssistantActions.patchEvent(old,if(a.module=="待办")AssistantActions.todoPatch(a.patch)else a.patch)
      if(next==old)unchanged++ else{saveEvent(next);rows+=preview(if(a.operation=="complete")"完成"else"修改",old.title,eventFields(old),eventFields(state.entries.last())).copy(context=if(old.kind==EventKind.TODO)"TDL"else "${eventFields(old)["类型"]} · ${eventFields(old)["时间"]}")}
     }
    }else{
     val old=matchNote(a);reserve(old.id)
     if(a.operation=="delete"){state=state.copy(notes=state.notes.filterNot{it.id==old.id});rows+=preview("删除",old.title,noteFields(old),emptyMap())}
     else{val next=if(a.operation=="complete"){require(old.module=="快递"){"这种记录不支持标记完成"};old.copy(done=true)}else AssistantActions.patchNote(old,a.patch);if(next==old)unchanged++ else{state=state.copy(notes=state.notes.filterNot{it.id==old.id}+next);rows+=preview(if(a.operation=="complete")"完成"else"修改",old.title,noteFields(old),noteFields(next))}}
    }
   }
   is AssistantAction.Morning->{require(MorningCalendar.canMark(a.day.date,now.toLocalDate())){"未来日期不能记录晨签"};reserve("morning:${a.day.date}");val old=state.checks.find{it.date==a.day.date};val next=a.day.copy(required=a.day.required?:true,passed=a.day.required!=false&&a.day.passed)
    fun fields(d:CheckDay)=mapOf("晨签" to if(d.required==false)"不计入"else if(d.passed)"合格（含请假）"else"未合格","是否要求" to (d.required?.let{if(it)"需要"else"不需要"}?:"按法定工作日"))
    if(next==old)unchanged++ else{state=state.copy(checks=state.checks.filterNot{it.date==next.date}+next);rows+=preview("记录",next.date.toString(),old?.let(::fields)?:emptyMap(),fields(next))}}
   is AssistantAction.MorningRange->{
    val result=MorningBatch.plan(state,a,now.toLocalDate())
    generateSequence(a.from){it.plusDays(1)}.takeWhile{it<=a.through}.forEach{reserve("morning:$it")}
    if(result.after.morningStart!=state.morningStart)reserve("morning:start")
    state=result.after;rows+=result.items;unchanged+=result.unchanged
   }
   is AssistantAction.RenameEvents->{
    val selected=original.calendar.filter{it.kind==EventKind.TIMED&&(a.from==null||!it.start.toLocalDate().isBefore(a.from))&&(a.through==null||!it.start.toLocalDate().isAfter(a.through))}
    // Match the original snapshot, never cascade A→B and B→C within this request.
    a.names.forEach{(from,to)->
     val matches=selected.filter{it.title==from}
     if(matches.isEmpty())unmatched+=from
     else if(from==to)unchanged+=matches.size
     else{
      matches.forEach{old->reserve(old.id);saveEvent(old.copy(title=to))}
      val dates=matches.map{it.start.toLocalDate()}.sorted()
      rows+=OperationPreview("修改",from,mapOf("名称" to from),mapOf("名称" to to),"${matches.size} 条日程 · ${dates.first()} ～ ${dates.last()} · 时间、地点和提醒保持不变",matches.size)
     }
    }
   }
   is AssistantAction.Focus->{reserve("focus");state=state.copy(focusEnd=System.currentTimeMillis()+a.minutes*60000L);rows+=OperationPreview("开始","专注",after=mapOf("时长" to "${a.minutes} 分钟","当前计时" to if(original.focusEnd>System.currentTimeMillis())"替换正在进行的计时"else"无"))}
   is AssistantAction.Navigate->{require(actions.size==1);return AssistantPlan(original,original,listOf(OperationPreview("打开",a.module)),destination=a.module,writes=a.module=="扫描文件")}
   is AssistantAction.Route->{require(actions.size==1);val route=a.copy(from=AssistantRoutes.point(a.from),to=AssistantRoutes.point(a.to));AssistantRoutes.requireComplete(route);return AssistantPlan(original,original,listOf(OperationPreview("设置","校园参考路线",after=mapOf("起点" to route.from,"终点" to route.to),context=if(route.from==AssistantRoutes.CURRENT_LOCATION)"使用实时定位；定位可用后自动计算参考路线。"else"已知楼宇间的参考路线，计算结果见地图。")),destination="地图",route=route,writes=false)}
   else->error("这项操作不能写入执行清单")
  }}
  return AssistantPlan(original,state,rows,writes=state!=original,focusMinutes=actions.filterIsInstance<AssistantAction.Focus>().singleOrNull()?.minutes,unchanged=unchanged,unmatched=unmatched)
 }
 fun validateReminderChange(old:CalendarEntry?,next:CalendarEntry,now:java.time.LocalDateTime=java.time.LocalDateTime.now()){
  // Renaming a course must not invalidate an existing departure reminder.
  if(old!=null&&old.start==next.start&&old.reminderTime()==next.reminderTime()&&old.completed==next.completed)return
  require(next.completed||next.start<=now||next.reminderTime()?.isBefore(now)!=true){"“${next.title}”的提醒时间已过去，请指定未来时间或不提醒"}
 }
 fun context(store:AppStore):String{
  val calendar=AssistantRecordSelection.calendar(store.calendarEntries,java.time.LocalDateTime.now())
  val notes=store.notes.filter{it.module!="待办"}.takeLast(150)
  return JSONObject().put("morning",JSONObject().put("start",store.morningStart?.toString().orEmpty()).put("end",store.morningEnd?.toString().orEmpty()).put("time",store.morningTime.toString()).put("holidayYears",JSONArray(Holidays.days.keys.map{it.year}.distinct())))
   .put("categories",JSONArray(store.categories)).put("calendar",JSONArray(calendar.map{entryToJson(it).put("notes",it.notes.take(200))}))
   .put("calendarTitles",JSONArray(store.calendarEntries.filter{it.kind==EventKind.TIMED}.groupBy{it.title}.map{(title,entries)->JSONObject().put("title",title).put("count",entries.size).put("from",entries.minOf{it.start}.toLocalDate().toString()).put("through",entries.maxOf{it.start}.toLocalDate().toString())}))
   .put("notes",JSONArray(notes.map{JSONObject().put("id",it.id).put("module",it.module).put("title",it.title).put("detail",it.detail.take(500)).put("group",it.group).put("date",if(it.module=="账单")Billing.day(it).toString()else "").put("done",it.done)}))
   .put("truncated",store.calendarEntries.size>calendar.size||store.notes.count{it.module!="待办"}>notes.size).toString()
 }
}


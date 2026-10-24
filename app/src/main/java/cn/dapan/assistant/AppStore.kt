package cn.dapan.assistant

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.*
import org.json.*
import java.io.File
import java.time.*

class AppStore private constructor(val context: Context) {
 companion object {
  // Construction below always uses applicationContext; no Activity is retained.
  @android.annotation.SuppressLint("StaticFieldLeak")
  @Volatile private var instance: AppStore? = null
  fun get(context: Context): AppStore = instance ?: synchronized(this) {
   instance ?: AppStore(context.applicationContext).also { instance = it }
  }
 }
 private val file = AtomicFile(File(context.filesDir, "dapan-v1.json"))
 var entries by mutableStateOf(listOf<CalendarEntry>()); private set
 var notes by mutableStateOf(listOf<NoteItem>()); private set
 var checks by mutableStateOf(listOf<CheckDay>()); private set
 var categories by mutableStateOf(listOf("课程", "作业")); private set
 var groups by mutableStateOf(listOf("紧急", "一般", "不紧急")); private set
 var morningStart by mutableStateOf<LocalDate?>(null)
 var morningEnd by mutableStateOf<LocalDate?>(null)
 var morningTime by mutableStateOf(MorningCalendar.defaultTime)
 var morningNotify by mutableStateOf(false)
 var morningLastCall by mutableStateOf(false)
 var loadError by mutableStateOf<String?>(null)
 var focusEnd by mutableLongStateOf(0L)
 private var installedCampusServices=emptySet<String>()
 init {
  reload()
  if(loadError==null)runCatching{installCampusServices()}.onFailure{loadError="内置校园服务保存失败，原有数据已保留，请重启重试。"}
 }
 fun reload() {
  if (!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists()) return
  try {
   val o = JSONObject(file.openRead().bufferedReader().use { it.readText() })
   installedCampusServices=o.optJSONArray("installedCampusServices")?.let{a->(0 until a.length()).map{a.getString(it)}.toSet()}.orEmpty()
   entries = o.optJSONArray("entries").objects().map(::entryFromJson)
   notes = o.optJSONArray("notes").objects().map { item -> val a=item.optJSONArray("attachments"); NoteItem(item.getString("id"),item.getString("module"),item.getString("title"),item.optString("detail"),item.optString("group","一般"),item.optBoolean("done"),item.optLong("created"),if(a==null)emptyList() else (0 until a.length()).map(a::getString)) }
   checks = o.optJSONArray("checks").objects().map { CheckDay(LocalDate.parse(it.getString("date")),if(it.has("required")) it.getBoolean("required") else null,it.optBoolean("passed")) }
   morningStart = o.optString("morningStart").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
   morningEnd = o.optString("morningEnd").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
   morningTime = o.optString("morningTime").takeIf { it.isNotBlank() }?.let(LocalTime::parse) ?: MorningCalendar.defaultTime
   morningNotify = o.optBoolean("morningNotify"); morningLastCall = o.optBoolean("morningLastCall",false); focusEnd = o.optLong("focusEnd")
   o.optJSONArray("categories")?.let { categories = (0 until it.length()).map(it::getString).filter{it.isNotBlank()&&it!="无分类"}.distinct() }
   categories=(listOf("课程")+categories).distinct()
   o.optJSONArray("groups")?.let { groups = (0 until it.length()).map(it::getString) }
   loadError = null
  } catch (e: Exception) { loadError = "本地数据读取失败，已保留原文件。请先导出备份。" }
 }
 fun save() {
  check(loadError == null) { "原数据读取失败，禁止覆盖" }
  val o = JSONObject().put("version",1).put("entries",JSONArray(entries.map(::entryToJson)))
   .put("installedCampusServices",JSONArray(installedCampusServices.toList()))
   .put("notes",JSONArray(notes.map { JSONObject().put("id",it.id).put("module",it.module).put("title",it.title).put("detail",it.detail).put("group",it.group).put("done",it.done).put("created",it.created).put("attachments",JSONArray(it.attachments)) }))
   .put("checks",JSONArray(checks.map { JSONObject().put("date",it.date.toString()).put("required",it.required).put("passed",it.passed) }))
   .put("morningStart",morningStart?.toString() ?: "").put("morningEnd",morningEnd?.toString() ?: "").put("morningTime",morningTime.toString()).put("morningNotify",morningNotify).put("morningLastCall",morningLastCall).put("focusEnd",focusEnd)
   .put("categories",JSONArray(categories)).put("groups",JSONArray(groups))
  val out = file.startWrite(); try { out.write(o.toString().toByteArray()); file.finishWrite(out) } catch(e: Exception) { file.failWrite(out); throw e }
 }
 private fun update(change:()->Unit) {
  check(loadError==null){"原数据读取失败，禁止覆盖"}
  val oldEntries=entries;val oldNotes=notes;val oldChecks=checks;val oldCategories=categories;val oldGroups=groups;val oldFocus=focusEnd;val oldMorningStart=morningStart;val oldMorningEnd=morningEnd;val oldMorningTime=morningTime;val oldMorningNotify=morningNotify;val oldMorningLastCall=morningLastCall
  val oldInstalled=installedCampusServices
  try {change();save()}catch(e:Exception){installedCampusServices=oldInstalled;entries=oldEntries;notes=oldNotes;checks=oldChecks;categories=oldCategories;groups=oldGroups;focusEnd=oldFocus;morningStart=oldMorningStart;morningEnd=oldMorningEnd;morningTime=oldMorningTime;morningNotify=oldMorningNotify;morningLastCall=oldMorningLastCall;throw e}
 }
 private fun installCampusServices(){
  val merged=CampusServices.merge(notes,installedCampusServices)
  if(merged.notes!=notes||merged.installed!=installedCampusServices)update{notes=merged.notes;installedCampusServices=merged.installed}
 }
 // Existing todo notes remain in place, including their attachments and creation dates.
 val calendarEntries: List<CalendarEntry> by derivedStateOf {
  val ids=entries.mapTo(HashSet()){it.id}
  entries+notes.asSequence().filter{it.module=="待办"&&it.id !in ids}.map{it.asTodo()}.toList()
 }
 fun put(e: CalendarEntry) {
  require(e.error() == null)
  require(calendarEntries.find{it.id==e.id}?.kind?.let{it==e.kind}!=false){"已有事项不能更改类型，请新建事项"}
  update {
   val legacy=notes.find{it.module=="待办" && it.id==e.id}
   if(legacy!=null && e.kind==EventKind.TODO) {
    notes=notes.map{if(it.id==e.id)it.copy(title=e.title,detail=e.notes,group=if(e.urgent)"紧急"else"一般",done=e.completed)else it}
    entries=(entries.filterNot{it.id==e.id}+e).sortedBy{it.start}
   } else {
    if(legacy!=null)notes=notes.filterNot{it.id==e.id}
    entries=(entries.filterNot{it.id==e.id}+e).sortedBy{it.start}
   }
  }
  Reminders.schedule(context,e)
 }
 fun remove(e: CalendarEntry) { update{entries=entries.filterNot{it.id==e.id};notes=notes.filterNot{it.module=="待办"&&it.id==e.id}};Reminders.cancel(context,e.id) }
 fun put(n: NoteItem) { update{notes = notes.filterNot { it.id == n.id } + n} }
 fun remove(n: NoteItem) { update{notes = notes.filterNot { it.id == n.id }} }
 fun removeParcelCodes(ids:Set<String>) { update{notes=notes.filterNot{it.module=="快递"&&it.id in ids}} }
 fun updateFocusEnd(end:Long){update{focusEnd=end}}
 fun check(day: CheckDay) { require(MorningCalendar.canMark(day.date,MorningCalendar.now().toLocalDate())){"未来日期不能记录晨签"};update{checks = checks.filterNot { it.date == day.date } + day};if(day.date==MorningCalendar.now().toLocalDate()){context.getSystemService(android.app.NotificationManager::class.java).cancel(730);MorningLastCall.cancelNotification(context)};Reminders.reschedule(context) }
 fun configureMorning(start:LocalDate?,end:LocalDate?,time:LocalTime=morningTime,notify:Boolean=morningNotify,lastCall:Boolean=morningLastCall) {
  require(start==null||end==null||end>=start){"统计终点不能早于起点"}
  update{morningStart=start;morningEnd=end;morningTime=time;morningNotify=notify;morningLastCall=lastCall}
  context.getSystemService(android.app.NotificationManager::class.java).cancel(730)
  MorningLastCall.cancelNotification(context)
  Reminders.morning(context,notify)
 }
 fun addCategory(s: String) { if (s.isNotBlank() && s.trim()!="无分类" && s.trim() !in categories) update{categories = categories + s.trim()} }
 fun removeCategory(s:String) { if(s.isBlank()||s in listOf("无分类","课程"))return;update{categories=categories.filterNot{it==s};entries=entries.map{if(it.category==s)it.copy(category="")else it}} }
 fun addGroup(s: String) { if (s.isNotBlank() && s.trim() !in groups) update{groups = groups + s.trim()} }
 fun addDemoEntries(values:List<CalendarEntry>){
  require(values.all{it.id.startsWith(DemoEvents.PREFIX)&&it.error()==null})
  update{entries=(entries+values.filter{v->entries.none{it.id==v.id}}).sortedBy{it.start}}
 }
 fun clearDemoEntries(){
  val ids=entries.filter{it.id.startsWith(DemoEvents.PREFIX)}.map{it.id}
  update{entries=entries.filterNot{it.id in ids}}
  Reminders.cancelMany(context,ids)
 }
 fun clearRecords(selected:Set<ClearDataKind>){
  val recordKinds=selected-ClearDataKind.CHAT-ClearDataKind.KEYS
  if(recordKinds.isEmpty())return
  val removed=entries.filter{ClearDataRules.entry(it,selected)}
  update{
   entries=entries.filterNot{ClearDataRules.entry(it,selected)}
   notes=notes.filterNot{ClearDataRules.note(it,selected)}
   if(ClearDataKind.MORNING in selected){checks=emptyList();morningStart=null;morningEnd=null;morningTime=MorningCalendar.defaultTime;morningNotify=false;morningLastCall=false}
  }
  // Recovery copies may contain records the user just chose to erase.
  listOf("before-course-import.json","before-last-import.json").forEach{name->
   val recovery=AtomicFile(File(context.filesDir,name));recovery.delete()
   check(!recovery.baseFile.exists()){"自动恢复副本未能清除"}
  }
  val notifications=context.getSystemService(android.app.NotificationManager::class.java)
  Reminders.cancelMany(context,removed.map{it.id})
  removed.forEach{notifications.cancel(it.id.hashCode())}
  if(ClearDataKind.MORNING in selected){MorningLastCall.clear(context);Reminders.morning(context,false);notifications.cancel(730)}
 }
 fun importTeaching(values:List<CalendarEntry>):String {
  require(values.size<=5000&&values.all{it.id.startsWith(TimetableImport.PREFIX)&&it.error()==null})
  val merge=TimetableImport.merge(values,entries)
  if(merge.fresh.isEmpty())return "没有新增课程；已有日程保持原样。"
  val recovery=AtomicFile(File(context.filesDir,"before-course-import.json"))
  val out=recovery.startWrite()
  try{out.write(backup().toByteArray());recovery.finishWrite(out)}catch(e:Exception){recovery.failWrite(out);throw e}
  update{entries=(entries+merge.fresh).sortedBy{it.start};if("课程" !in categories)categories=categories+"课程"}
  val failed=runCatching{Reminders.reschedule(context)}.isFailure
  return "已导入 ${merge.fresh.size} 次课程，跳过 ${merge.unchanged} 次已有记录，保留 ${merge.changed.size} 次差异记录。"+
   if(failed)"提醒未能安排，请检查提醒权限。"else ""
 }
 fun assistantSnapshot()=AssistantSnapshot(entries.toList(),notes.toList(),checks.toList(),categories.toList(),groups.toList(),focusEnd,morningStart,morningEnd)
 // A batch writes one AtomicFile. No reminder side effects occur until the entire batch is durable.
 fun applyAssistant(plan:AssistantPlan,policy:AssistantPolicy,confirmed:Boolean):String? {
  check(policy.access!=AssistantAccess.READ_ONLY){"只读模式不能修改数据"}
  check(policy.access!=AssistantAccess.CONFIRM||confirmed){"操作尚未确认"}
  require(plan.writes)
  check(assistantSnapshot()==plan.before){"清单生成后数据发生了变化，请重新发出指令；本次未执行"}
  plan.after.entries.forEach{e->if(plan.before.entries.none{it==e})require(e.error()==null){e.error()!!};require(calendarEntries.find{it.id==e.id}?.kind?.let{it==e.kind}!=false){"已有事项不能更改类型"}}
  plan.after.entries.filter{e->plan.before.calendar.none{it==e}}.forEach{e->
   if(!plan.undoing)AssistantPlanner.validateReminderChange(plan.before.calendar.find{it.id==e.id},e)
  }
  plan.after.checks.filter{it !in plan.before.checks}.forEach{require(MorningCalendar.canMark(it.date,MorningCalendar.now().toLocalDate())){"未来日期不能记录晨签"}}
  require(plan.after.morningStart==null||plan.after.morningEnd==null||plan.after.morningEnd>=plan.after.morningStart){"统计终点不能早于起点"}
  update{
   entries=plan.after.entries.sortedBy{it.start};notes=plan.after.notes;checks=plan.after.checks;categories=plan.after.categories;groups=plan.after.groups
   morningStart=plan.after.morningStart;morningEnd=plan.after.morningEnd
   focusEnd=plan.focusMinutes?.let{System.currentTimeMillis()+it*60000L}?:plan.after.focusEnd
  }
  if(plan.after.checks.any{it.date==MorningCalendar.now().toLocalDate()&&it !in plan.before.checks}){context.getSystemService(android.app.NotificationManager::class.java).cancel(730);MorningLastCall.cancelNotification(context)}
  var reminderFailed=false
  val old=plan.before.calendar.associateBy{it.id};val current=calendarEntries.associateBy{it.id}
  runCatching{Reminders.cancelMany(context,(old.keys-current.keys).toList())}.onFailure{reminderFailed=true}
  if(plan.before.checks!=plan.after.checks||plan.before.morningStart!=morningStart||plan.before.morningEnd!=morningEnd)
   runCatching{Reminders.reschedule(context)}.onFailure{reminderFailed=true}
  return if(reminderFailed)"记录已保存，但部分系统提醒未能安排。请检查通知及闹钟权限，再重新打开应用。"else null
 }
 fun backup(): String {
  if (!file.baseFile.exists()) save()
  return file.openRead().bufferedReader().use { it.readText() }
 }
 fun importRecords(preview: BackupPreview) {
  check(loadError == null) { "请先保存损坏文件，恢复功能不会覆盖无法读取的数据" }
  val previous = backup()
  val combined = preview.mergeInto(JSONObject(previous))
  // Write the recovery copy before changing live records. API keys are never included.
  val recovery = AtomicFile(File(context.filesDir,"before-last-import.json"))
  val recoveryStream = recovery.startWrite()
  try { recoveryStream.write(previous.toByteArray()); recovery.finishWrite(recoveryStream) }
  catch (e:Exception) { recovery.failWrite(recoveryStream); throw e }
  val out = file.startWrite()
  try { out.write(combined.toString().toByteArray()); file.finishWrite(out) }
  catch(e:Exception) { file.failWrite(out); throw e }
  reload()
  Reminders.reschedule(context)
 }
}
fun JSONArray?.objects(): List<JSONObject> = if(this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
fun entryToJson(e: CalendarEntry) = JSONObject().put("id",e.id).put("title",e.title).put("kind",e.kind.name).put("start",e.start.toString()).put("end",e.end.toString()).put("travel",e.travelMinutes).put("reminder",e.reminderMode.name).put("before",e.beforeMinutes).put("absolute",e.absoluteReminder?.toString() ?: "").put("category",e.category).put("location",e.location).put("notes",e.notes).put("completed",e.completed).put("urgent",e.urgent)
fun entryFromJson(o: JSONObject) = CalendarEntry(id=o.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },title=o.getString("title"),kind=EventKind.valueOf(o.optString("kind","TIMED")),start=LocalDateTime.parse(o.optString("start").ifBlank{require(o.optString("kind")=="TODO");LocalDateTime.now().toString()}),end=LocalDateTime.parse(o.optString("end").ifBlank{o.optString("start").ifBlank{LocalDateTime.now().toString()}}),travelMinutes=o.optInt("travel"),reminderMode=ReminderMode.valueOf(o.optString("reminder","OFF")),beforeMinutes=o.optInt("before",10),absoluteReminder=o.optString("absolute").takeIf { it.isNotBlank() }?.let(LocalDateTime::parse),category=o.optString("category"),location=o.optString("location"),notes=o.optString("notes"),completed=o.optBoolean("completed"),urgent=o.optBoolean("urgent"))

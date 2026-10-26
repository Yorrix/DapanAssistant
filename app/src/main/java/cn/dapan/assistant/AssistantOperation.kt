package cn.dapan.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

enum class OperationStatus(val label:String){PENDING("等待批准"),RUNNING("正在执行"),DONE("已执行"),PARTIAL("部分完成"),CANCELLED("已放弃"),FAILED("未完成"),UNDONE("已撤销"),EXPIRED("已失效")}

internal object AssistantToolOutcome {
 fun status(evidence:String):OperationStatus=when(runCatching{JSONObject(evidence).optString("status")}.getOrDefault("")){
  "partial"->OperationStatus.PARTIAL
  "failed"->OperationStatus.FAILED
  else->OperationStatus.DONE
 }
}

/** Only changed records are retained for undo. Unrelated later edits remain intact. */
data class AssistantUndo(val before:AssistantSnapshot,val after:AssistantSnapshot,val focusChanged:Boolean){
 fun plan(current:AssistantSnapshot):AssistantPlan{
  fun <T,K> restore(now:List<T>,old:List<T>,applied:List<T>,key:(T)->K):List<T>{
   val ids=(old+applied).map(key).toSet()
   check(now.filter{key(it) in ids}.associateBy(key)==applied.associateBy(key)){"相关记录已被再次修改，不能直接撤销；本次没有改动数据。"}
   return now.filterNot{key(it) in ids}+old
  }
  if(focusChanged)check(current.focusEnd==after.focusEnd){"专注计时已改变，不能撤销旧操作。"}
  val startChanged=before.morningStart!=after.morningStart
  val endChanged=before.morningEnd!=after.morningEnd
  if(startChanged)check(current.morningStart==after.morningStart){"晨签统计起点已再次修改，不能直接撤销。"}
  if(endChanged)check(current.morningEnd==after.morningEnd){"晨签统计终点已再次修改，不能直接撤销。"}
  val restored=current.copy(
   entries=restore(current.entries,before.entries,after.entries){it.id},
   notes=restore(current.notes,before.notes,after.notes){it.id},
   checks=restore(current.checks,before.checks,after.checks){it.date},
   categories=(current.categories+before.entries.map{it.category}).filter{it.isNotBlank()}.distinct(),
   morningStart=if(startChanged)before.morningStart else current.morningStart,
   morningEnd=if(endChanged)before.morningEnd else current.morningEnd,
   focusEnd=if(focusChanged)before.focusEnd.takeIf{it>System.currentTimeMillis()}?:0 else current.focusEnd)
  return AssistantPlan(current,restored,listOf(OperationPreview("撤销","恢复这次操作前的记录")),undoing=true)
 }
 fun json()=JSONObject().put("before",snapshotJson(before)).put("after",snapshotJson(after)).put("focusChanged",focusChanged)
 companion object{
  fun capture(before:AssistantSnapshot,after:AssistantSnapshot):AssistantUndo?{
   fun <T,K> changed(a:List<T>,b:List<T>,key:(T)->K):Set<K>{val am=a.associateBy(key);val bm=b.associateBy(key);return (am.keys+bm.keys).filter{am[it]!=bm[it]}.toSet()}
   val events=changed(before.entries,after.entries){it.id};val notes=changed(before.notes,after.notes){it.id};val checks=changed(before.checks,after.checks){it.date};val focus=before.focusEnd!=after.focusEnd
   if(events.isEmpty()&&notes.isEmpty()&&checks.isEmpty()&&!focus&&before.morningStart==after.morningStart&&before.morningEnd==after.morningEnd)return null
   fun partial(s:AssistantSnapshot)=s.copy(entries=s.entries.filter{it.id in events},notes=s.notes.filter{it.id in notes},checks=s.checks.filter{it.date in checks},categories=emptyList(),groups=emptyList())
   return AssistantUndo(partial(before),partial(after),focus)
  }
  fun fromJson(o:JSONObject)=AssistantUndo(snapshotFromJson(o.getJSONObject("before")),snapshotFromJson(o.getJSONObject("after")),o.getBoolean("focusChanged"))
  private fun snapshotJson(s:AssistantSnapshot)=JSONObject().put("entries",JSONArray(s.entries.map(::entryToJson))).put("notes",JSONArray(s.notes.map{n->JSONObject().put("id",n.id).put("module",n.module).put("title",n.title).put("detail",n.detail).put("group",n.group).put("done",n.done).put("created",n.created).put("attachments",JSONArray(n.attachments))})).put("checks",JSONArray(s.checks.map{JSONObject().put("date",it.date.toString()).put("required",it.required).put("passed",it.passed)})).put("focus",s.focusEnd).put("morningStart",s.morningStart?.toString().orEmpty()).put("morningEnd",s.morningEnd?.toString().orEmpty())
  private fun snapshotFromJson(o:JSONObject)=AssistantSnapshot(o.getJSONArray("entries").objects().map(::entryFromJson),o.getJSONArray("notes").objects().map{n->NoteItem(n.getString("id"),n.getString("module"),n.getString("title"),n.getString("detail"),n.getString("group"),n.getBoolean("done"),n.getLong("created"),n.getJSONArray("attachments").let{a->(0 until a.length()).map(a::getString)})},o.getJSONArray("checks").objects().map{CheckDay(LocalDate.parse(it.getString("date")),if(it.has("required"))it.getBoolean("required")else null,it.getBoolean("passed"))},emptyList(),emptyList(),o.getLong("focus"),o.optString("morningStart").takeIf{it.isNotBlank()}?.let(LocalDate::parse),o.optString("morningEnd").takeIf{it.isNotBlank()}?.let(LocalDate::parse))
 }
}

data class AssistantOperation(val status:OperationStatus,val summary:String,val items:List<OperationPreview>,val detail:String="",val undo:AssistantUndo?=null,val memory:String="",val toolRequest:String=""){
 fun json()=JSONObject().put("status",status.name).put("summary",summary).put("detail",detail).put("undo",undo?.json()).put("memory",memory).put("toolRequest",toolRequest).put("items",JSONArray(items.map{JSONObject().put("verb",it.verb).put("title",it.title).put("before",JSONObject(it.before)).put("after",JSONObject(it.after)).put("context",it.context).put("count",it.count)}))
 companion object{
  fun fromPlan(plan:AssistantPlan,status:OperationStatus,undo:AssistantUndo?=null)=AssistantOperation(status,plan.summary,plan.items,if(plan.unmatched.isEmpty())""else"未匹配，不会修改：${plan.unmatched.joinToString("、")}",undo,AssistantOperationMemory.capture(plan.before,plan.after))
  fun fromJson(o:JSONObject):AssistantOperation{
   fun fields(o:JSONObject)=o.keys().asSequence().associateWith{o.getString(it)}
   val status=OperationStatus.valueOf(o.getString("status"))
   return AssistantOperation(if(status in setOf(OperationStatus.PENDING,OperationStatus.RUNNING))OperationStatus.EXPIRED else status,o.getString("summary"),o.getJSONArray("items").objects().map{OperationPreview(it.getString("verb"),it.getString("title"),fields(it.getJSONObject("before")),fields(it.getJSONObject("after")),it.getString("context"),it.optInt("count",1))},if(status==OperationStatus.RUNNING)"执行过程曾中断，请先核对实际记录。"else if(status==OperationStatus.PENDING)"应用已重新打开，请重新发出指令生成清单。"else o.optString("detail"),o.optJSONObject("undo")?.let(AssistantUndo::fromJson),o.optString("memory"),o.optString("toolRequest"))
  }
 }
}

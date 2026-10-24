package cn.dapan.assistant

import org.json.JSONObject
import java.util.Locale

/** One route contract for model actions, page tools, previews and the map. */
internal object AssistantRoutes {
 const val CURRENT_LOCATION="我的位置"
 private val currentNames=setOf("我的位置","我现在的位置","我当前的位置","我所在的位置","现在的位置","当前位置","当前定位","我的定位","这里","这儿","我这里","我这儿","current location","my location")
 fun point(value:String):String {
  val name=value.trim()
  if(name.lowercase(Locale.ROOT) in currentNames)return CURRENT_LOCATION
  return if(Regex("[a-zA-Z]\\s*\\d{1,2}(?:\\s*[nNsSwWeE])?").matches(name))
   name.replace(Regex("\\s+"),"").uppercase(Locale.ROOT) else name
 }
 fun normalize(action:AssistantAction,context:JSONObject):AssistantAction {
  if(action is AssistantAction.Route)return action.copy(from=point(action.from),to=point(action.to))
  if(action !is AssistantAction.Tool||AssistantSurfaces.canonical(action.module)!="地图"||action.operation!="route")return action
  require(action.args.keys().asSequence().all{it in setOf("from","to")}){"route 仅支持 from、to 参数"}
  val args=JSONObject(action.args.toString())
  listOf("from","to").forEach{if(args.has(it))args.put(it,point(args.getString(it)))}
  // Freeze inherited endpoints from the request, never from a later page state.
  val current=context.optJSONObject("current")
  val state=if(AssistantSurfaces.canonical(current?.optString("screen").orEmpty())=="地图")current?.optJSONObject("state") else null
  val from=if(args.has("from"))args.getString("from")else point(state?.optString("from").orEmpty())
  val to=if(args.has("to"))args.getString("to")else point(state?.optString("to").orEmpty())
  val clearing=listOf("from","to").any{args.has(it)&&args.getString(it).isBlank()}
  // Explicit empty endpoints are a clear command, not a new navigation request.
  if(clearing)return action.copy(args=args)
  if(to.isNotBlank())return AssistantAction.Route(from,to)
  return action.copy(args=args)
 }
 fun requireComplete(route:AssistantAction.Route){
  require(route.from.isNotBlank()){"导航缺少起点。若用户说从我这里、现在的位置出发，from 必须为“我的位置”；否则沿用本轮明确的起点或询问，不能把空起点当成成功。"}
  require(route.to.isNotBlank()){"导航缺少终点，请询问要去哪里"}
 }
 fun validate(action:AssistantAction,places:Set<String>){
  when(action){
   is AssistantAction.Route->{requireComplete(action);requirePoint(action.from,places,true);requirePoint(action.to,places,false)}
   is AssistantAction.Tool->if(AssistantSurfaces.canonical(action.module)=="地图"&&action.operation=="route"){
    listOf("from","to").forEach{key->if(action.args.has(key))action.args.getString(key).takeIf{it.isNotBlank()}?.let{requirePoint(it,places,key=="from")}}
   }
   else->Unit
  }
 }
 private fun requirePoint(value:String,places:Set<String>,start:Boolean){
  require(value in places||(start&&value==CURRENT_LOCATION)){"${if(start)"起点"else"终点"}“$value”不在校园点位中，请 lookup 地图核对名称；不要编造地点"}
 }
 fun isRoute(action:AssistantAction)=action is AssistantAction.Route||(action is AssistantAction.Tool&&AssistantSurfaces.canonical(action.module)=="地图"&&action.operation=="route")
}

/** A complete route targets named endpoints, not the transient selection on the sending page. */
internal object AssistantRequestGuard {
 private fun independentTool(a:AssistantAction.Tool):Boolean {
  val module=AssistantSurfaces.canonical(a.module);val op=a.operation;val args=a.args
  fun hasText(key:String)=args.opt(key) is String&&args.optString(key).isNotBlank()
  // These operations address named preferences/global data, not the page's selected object.
  // This is deliberately an allow-list: new/unknown callbacks keep the selection guard.
  return when(module){
   "设置"->op in setOf("theme","pet")
   "今天"->op=="widgets"
   "工具"->op=="search"||(op=="favorite"&&hasText("name")&&args.has("enabled"))
   "晨签"->op=="configure"||(op=="month"&&hasText("month"))
   "天气"->op in setOf("refresh","notification")
   "日历"->op=="import_courses"||op=="view"||(op in setOf("category_add","category_delete")&&hasText("name"))
   "账单"->op=="month"&&hasText("month")
   "信息查询"->op=="view"
   "资料库"->when(op){
    "save_attachments"->(args.optJSONArray("ids")?.length()?:0)>0
    "edit","delete","open","reindex","select"->hasText("id")
    "search","import"->true
    else->false
   }
   "官网通知"->when(op){
    "filter","refresh"->true
    "select","open"->hasText("id")
    "follow"->hasText("source")&&args.has("enabled")
    else->false
   }
   "手持弹幕"->op=="configure"
   "专注"->op=="configure"
   "抽签"->when(op){
    "configure"->true
    "select","delete"->hasText("id")
    "save"->hasText("id")||(hasText("name")&&(args.optJSONArray("options")?.length()?:0)>0)
    else->false // spin uses the selected wheel, even if the surrounding tab is unchanged.
   }
   "实时翻译"->op=="configure"
   "课堂生词"->op=="configure"||op=="search"||(op=="known"&&hasText("word"))
   "地图"->op in setOf("view","heat_view","heat_record")||(op=="route"&&args.has("from")&&args.has("to"))
   "会议纪要"->when(op){
    "configure"->true
    "create"->hasText("title")
    "record_start"->hasText("folderId")||hasText("title")
    "summarize","delete_folder"->hasText("folderId")
    "play","transcribe","restore_text","delete_clip"->hasText("clipId")
    "edit_line"->hasText("clipId")&&args.has("startMs")&&args.has("text")
    "rename"->(hasText("folderId") xor hasText("clipId"))&&hasText("name")
    "reorder"->hasText("folderId")&&args.has("clipIds")
    "export"->(hasText("folderId") xor hasText("clipId"))&&hasText("format")
    // Folder-only view inherits its current clip/search scope. Bind both scope and folder.
    "view"->hasText("folderId")&&(hasText("clipId")||args.opt("all")==true)
    else->false
   }
   "二手信息"->when(op){
    "search","compose"->true
    "detail","status","delete"->hasText("id")
    "editor"->true // Explicitly opens a named listing or a fresh draft.
    else->false // draft / publish / image picker belong to the current draft.
   }
   else->false
  }
 }
 fun check(action:AssistantAction,origin:String,current:String,before:String,after:String){
  // Explicit data actions bind to a snapshot/ID and are checked again before committing.
  // A tab change is not a cancellation. Only implicit page callbacks depend on selection.
  fun independent(a:AssistantAction):Boolean=when(a){
   is AssistantAction.Reply,is AssistantAction.Search,is AssistantAction.Navigate,is AssistantAction.Route,
   is AssistantAction.Event,is AssistantAction.Note,is AssistantAction.Morning,is AssistantAction.MorningRange,is AssistantAction.Focus,
   is AssistantAction.RenameEvents->true
   is AssistantAction.Change->a.targetId.isNotBlank()
   is AssistantAction.Tool->independentTool(a)
   is AssistantAction.Batch->a.actions.all(::independent)
   else->false
  }
  if(independent(action))return
  check(AssistantSurfaces.canonical(origin)==AssistantSurfaces.canonical(current)&&before==after){
   "你已切换页面或所选内容，当前操作未执行，请重新说明"
  }
 }
}

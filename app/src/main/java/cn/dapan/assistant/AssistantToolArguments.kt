package cn.dapan.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Check context-independent arguments before dispatch, while the reasoning loop can repair.
 * Omitted optional fields retain the handlers' current-value/default semantics. Object existence,
 * current selection, permissions and runtime availability stay with the handlers. */
internal object AssistantToolArguments {
 private val required=mapOf(
  "资料库/save_attachments" to "ids", "资料库/edit" to "id", "资料库/delete" to "id", "资料库/open" to "id", "资料库/reindex" to "id", "资料库/select" to "id",
  "抽签/select" to "id", "抽签/delete" to "id",
  "工具/favorite" to "name,enabled", "日历/category_add" to "name", "日历/category_delete" to "name",
  "账单/month" to "month", "晨签/month" to "month",
  "官网通知/follow" to "source,enabled", "官网通知/select" to "id", "官网通知/open" to "id",
  "实时翻译/translate" to "text", "课堂生词/known" to "word", "地图/heat_record" to "enabled",
  "会议纪要/create" to "title,kind", "会议纪要/play" to "clipId", "会议纪要/rename" to "name",
  "会议纪要/reorder" to "folderId,clipIds", "会议纪要/transcribe" to "clipId", "会议纪要/edit_line" to "clipId,startMs,text",
  "会议纪要/restore_text" to "clipId", "会议纪要/export" to "format", "会议纪要/delete_folder" to "folderId", "会议纪要/delete_clip" to "clipId",
  "二手信息/detail" to "id", "二手信息/status" to "id,status", "二手信息/delete" to "id", "二手信息/compose" to "kind,title"
 )
 private val booleanKeys=setOf("enabled","notify","lastCall","pointer","spinAfter","expanded","summary","original","known","all","mine","publish","consent","notifications","calls","landscape","moving","blink","bold","autoHide")
 private val numberKeys=setOf("minutes","size","speed","shake","zoom","index","customMs","startMs","atMs")
 private val arrayKeys=setOf("ids","clipIds","sources","order","options")

 fun validate(module:String,op:String,args:JSONObject){
  val tool="$module/$op"
  required[tool]?.split(',')?.forEach{key->require(args.has(key)&&!args.isNull(key)&&args.opt(key).toString().isNotBlank()){
   "$tool 缺少参数 $key，请从用户输入或真实查询补齐；不知道就询问，不编造。"
  }}
  // optBoolean/optInt/optJSONObject otherwise silently turn malformed values into defaults.
  args.keys().forEach{key->
   val value=args.get(key)
   require(value!==JSONObject.NULL){"$tool 的 $key 不能为 null；沿用当前值时请省略字段。"}
   when{
    key in arrayKeys||module=="今天"&&key=="enabled"->require(value is JSONArray){"$key 必须是数组"}
    key=="contacts"->require(value is JSONObject){"contacts 必须是联系方式对象"}
    key in booleanKeys->require(value is Boolean){"$key 必须是 true 或 false"}
    key in numberKeys->require(value is Number&&value.toDouble().isFinite()){"$key 必须是有限数字"}
    else->require(value is String){"$key 必须是文字"}
   }
  }
  if(op=="configure"||module=="设置"||op in setOf("notification","widgets"))
   require(args.length()>0){"没有提供要修改的设置；请把用户要求的字段放入 args。"}
  listOf("source","word").forEach{args.nonBlank(it)}
  required[tool]?.split(',')?.forEach{key->if(args.opt(key) is String)args.nonBlank(key,Int.MAX_VALUE)}
  when(module){
   "资料库"->when(op){
    "save_attachments"->args.strings("ids",nonEmpty=true,unique=true)
    "search"->if(args.has("kind"))LibraryKind.parse(args.getString("kind"))
    "edit"->{args.nonBlank("name",200);args.maxText("remark",4000)}
   }
   "抽签"->when(op){
    "save"->{
     require(args.optString("id").isNotBlank()||args.has("options")){"新建轮盘需要 options；修改已有轮盘请提供真实 id。"}
     args.nonBlank("name",60)
     if(args.has("options")){
      val options=args.getJSONArray("options");require(options.length() in 1..60){"轮盘选项数需为1至60"}
      var positive=false
      repeat(options.length()){i->
       val option=options.optJSONObject(i)?:error("第 ${i+1} 个轮盘选项必须是包含 name 和可选 weight 的对象")
       require(option.opt("name") is String){"轮盘选项需要文字名称"};option.nonBlank("name",60)
       val value=if(option.has("weight"))option.opt("weight")else 1.0
       require(value is Number&&value.toDouble().isFinite()&&value.toDouble() in 0.0..1e9){"权重需为0至10亿之间的数字"}
       if(value.toDouble()>0)positive=true
      }
      require(positive){"至少一个选项权重大于0"}
     }
    }
    "configure"->{args.choice("duration","长","短","自定义");args.number("customMs",100.0,60000.0,integer=true)}
   }
   "日历"->when(op){
    "view"->{args.date("date");args.choice("list","","DDL","TDL");args.choice("overlap","并排","叠放","智能折叠")}
    "category_add"->{args.nonBlank("name",40);require(args.getString("name").trim()!="无分类"){"无分类已存在"}}
    "category_delete"->require(args.getString("name").trim() !in setOf("课程","无分类")){"课程和无分类不能删除"}
   }
   "账单"->if(op=="month")YearMonth.parse(args.getString("month"))
   "晨签"->when(op){
    "month"->YearMonth.parse(args.getString("month"))
    "configure"->{args.date("start",true);args.date("end",true);args.time("time");args.orderedDates()}
   }
   "天气"->if(op=="notification"){args.choice("period","晨间","晚间");args.time("time")}
   "信息查询"->args.choice("section","个人信息","老师信息","校园服务")
   "专注"->args.number("minutes",1.0,480.0,integer=true)
   "手持弹幕"->{
    args.nonBlank("text",300);args.number("size",32.0,240.0);args.number("speed",20.0,300.0);args.number("shake",0.0,12.0)
    listOf("foreground","background").forEach{key->if(args.has(key))require(args.getString(key).matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))){"$key 请使用 #RRGGBB 颜色"}}
   }
   "官网通知"->if(args.has("sources"))args.strings("sources")
   "实时翻译"->{
    args.language("from");args.language("to");args.number("size",16.0,26.0);args.nonBlank("text",6000)
    if(args.has("from")&&args.has("to"))require(args.getString("from")!=args.getString("to")){"输入与输出语言不能相同"}
   }
   "课堂生词"->{args.choice("level","基础","高中","四级","六级");args.nonBlank("word",80)}
   "地图"->when(op){
    "heat_view"->{args.choice("mode","导航","热力图");args.choice("range","今天","近7天","近30天","近半年","自定义");args.date("start");args.date("end");args.orderedDates()}
    "view"->{args.choice("orientation","固定","跟随");args.choice("focus","","自己","全校");args.number("zoom",.65,7.0)}
    "route_select"->args.number("index",1.0,Int.MAX_VALUE.toDouble(),integer=true)
   }
   "会议纪要"->{
    args.language("language");args.maxText("asrContext",400);args.maxText("summaryHint",1200)
    args.choice("kind","课程","会议","其他");args.choice("tab","转写","摘要");args.nonBlank("title",120);args.nonBlank("name",120)
    args.number("startMs",0.0,Long.MAX_VALUE.toDouble(),integer=true);args.number("atMs",0.0,Long.MAX_VALUE.toDouble(),integer=true);args.nonBlank("text",20000)
    if(op=="rename")args.exactlyOneTarget()
    if(op=="export"){
     args.choice("format","audio","transcript","summary");args.exactlyOneTarget()
     if(args.getString("format")=="audio")require(args.optString("clipId").isNotBlank()){"导出音频需指定 clipId"}
    }
    if(op=="view")require(!(args.optBoolean("all")&&args.optString("clipId").isNotBlank())){"全部与单条录音不能同时选择"}
    if(op=="reorder")args.strings("clipIds",unique=true)
    if(op=="record_start")AssistantRecordingTarget.validate(args)
    // record_start/summarize/view can use the current folder; do not require its id.
   }
   "二手信息"->{
    if(op=="search")args.choice("kind","","buy","sell")else args.choice("kind","buy","sell")
    args.choice("status","sold","withdrawn");args.maxText("title",60);args.maxText("description",3000)
    if(op=="compose")args.nonBlank("title",60)
    if(args.has("id")&&args.getString("id").isNotBlank())require(args.getString("id").matches(Regex("[a-zA-Z0-9-]+"))){"信息 id 格式不正确"}
    args.optJSONObject("contacts")?.let{contacts->contacts.keys().forEach{key->
     require(key in setOf("QQ","微信","手机")){"联系方式仅支持 QQ、微信、手机"}
     require(contacts.opt(key) is String){"联系方式必须是文字"}
     contacts.maxText(key,60);if(op=="compose")contacts.nonBlank(key,60)
    }}
    if(op=="compose"&&args.optBoolean("publish"))require((args.optJSONObject("contacts")?.length()?:0)>0&&args.optBoolean("consent")){
     "发布前需提供联系方式并同意公开；没有这些信息时只能先建草稿。"
    }
    // publish can use consent already granted in the editor; the handler checks that state.
   }
   "今天"->{
    listOf("enabled","order").forEach{key->if(args.has(key)){
     val names=args.strings(key,nonEmpty=key=="order",unique=key=="order")
     require(names.all{name->HomeWidget.entries.any{it.title==name}}){"请使用真实小组件名称"}
    }}
    args.choice("noticeScope","ALL","FOLLOWED")
   }
   "设置"->when(op){
    "theme"->{
     args.choice("mode","system","light","dark")
     if(args.has("shape"))UiShape.parse(args.getString("shape"))
     if(args.has("palette"))UiColorPalette.parse(args.getString("palette"))
     if(args.has("style")){
      require(UiStyle.entries.any{it.label==args.getString("style")||it.name.equals(args.getString("style"),true)}){"请选择已有界面风格"}
      require(!args.has("shape")&&!args.has("palette")){"旧风格与独立形状/配色不能同时指定"}
     }
    }
   }
  }
 }
 private fun JSONObject.nonBlank(key:String,max:Int=4000){if(has(key))require(getString(key).trim().let{it.isNotBlank()&&it.length<=max}){"$key 不能为空且最多 $max 字"}}
 private fun JSONObject.maxText(key:String,max:Int){if(has(key))require(getString(key).length<=max){"$key 最多 $max 字"}}
 private fun JSONObject.choice(key:String,vararg choices:String){if(has(key))require(getString(key) in choices){"$key 仅支持 ${choices.joinToString()}"}}
 private fun JSONObject.language(key:String){if(has(key))require(getString(key) in TranslationLanguages.names){"$key 请选择 ${TranslationLanguages.names.joinToString()}"}}
 private fun JSONObject.number(key:String,min:Double,max:Double,integer:Boolean=false){if(has(key)){
  val value=getDouble(key);require(value.isFinite()&&value in min..max&&(!integer||value%1.0==0.0)){"$key 需要 $min 至 $max 之间的${if(integer)"整数"else"数字"}"}
 }}
 private fun JSONObject.strings(key:String,nonEmpty:Boolean=false,unique:Boolean=false):List<String>{
  val array=getJSONArray(key)
  val values=(0 until array.length()).map{i->require(array.opt(i) is String&&array.getString(i).isNotBlank()){"$key 必须只包含非空文字"};array.getString(i)}
  require(!nonEmpty||values.isNotEmpty()){"$key 不能为空数组"};require(!unique||values.distinct().size==values.size){"$key 不可重复"}
  return values
 }
 private fun JSONObject.date(key:String,allowBlank:Boolean=false){if(has(key)){val value=getString(key);if(!allowBlank||value.isNotBlank())LocalDate.parse(value)}}
 private fun JSONObject.time(key:String){if(has(key))LocalTime.parse(getString(key))}
 private fun JSONObject.orderedDates(){
  val start=optString("start");val end=optString("end")
  if(start.isNotBlank()&&end.isNotBlank())require(LocalDate.parse(start)<=LocalDate.parse(end)){"起始日期不能晚于结束日期"}
 }
 private fun JSONObject.exactlyOneTarget(){require(optString("folderId").isNotBlank()!=optString("clipId").isNotBlank()){"请提供 folderId 或 clipId，只选择一个真实对象"}}
}

package cn.dapan.assistant

import androidx.compose.runtime.*
import org.json.*
import java.util.UUID

data class SurfaceTool(val operation:String,val description:String,val keys:Set<String>,val writes:Boolean=true,val prepare:(JSONObject)->PreparedTool)
data class PreparedTool(val title:String,val details:String,val run:suspend ()->String,val guard:()->Unit={},val evidence:()->String={""})
data class SurfaceBinding(val owner:String,val module:String,val priority:Int,val state:JSONObject,val tools:List<SurfaceTool>)
object AssistantSurfaces{
 private val bindings=linkedMapOf<String,SurfaceBinding>()
 fun canonical(name:String)=when(name){"取件码"->"快递";"会议摘要","录音摘要"->"会议纪要";"校园热力图","热力图","导航"->"地图";else->name}
 fun put(b:SurfaceBinding){bindings[b.owner]=b}
 fun remove(owner:String){bindings.remove(owner)}
 fun current(module:String):SurfaceBinding?{val all=bindings.values.filter{it.module==canonical(module)}.sortedBy{it.priority};if(all.isEmpty())return null;val state=JSONObject();all.forEach{b->b.state.keys().forEach{state.put(it,b.state.get(it))}};return SurfaceBinding(all.joinToString{it.owner},canonical(module),all.last().priority,state,all.flatMap{it.tools}.groupBy{it.operation}.map{it.value.last()})}
 private fun identity(b:SurfaceBinding?):String{if(b==null)return "";val result=JSONObject();listOf("selectedId","selectedDate","month","section","folderId","clipId","selected","page","query","from","to","draft").forEach{if(b.state.has(it))result.put(it,b.state.get(it))};return b.owner+result.toString()}
 // Requests care about the selected data, not Compose's ephemeral binding owners.
 // Prepared page callbacks still use identity(), so a disposed handler can never run.
 fun fingerprint(module:String):String{val b=current(module);val state=JSONObject();listOf("selectedId","selectedDate","month","section","folderId","clipId","selected","page","query","from","to","draft").forEach{if(b?.state?.has(it)==true)state.put(it,b.state.get(it))};return state.toString()}
 fun context(module:String):JSONObject{val b=current(module);return JSONObject().put("screen",module).put("state",b?.state?:JSONObject()).put("operations",JSONArray(b?.tools?.map{JSONObject().put("op",it.operation).put("description",it.description).put("fields",JSONArray(it.keys.toList())).put("writes",it.writes)}?:emptyList<JSONObject>()))}
 fun prepare(module:String,op:String,args:JSONObject):Pair<Boolean,PreparedTool>{
  val b=current(module)?:error("该页面尚未准备好，请稍后重试")
  val t=b.tools.singleOrNull{it.operation==op}?:error("这个功能不支持 $op；尚未执行任何操作")
  require(args.keys().asSequence().all{it in t.keys}){"操作包含未支持的参数，未执行"}
  val frozen=identity(b);val plan=t.prepare(args)
  return t.writes to plan.copy(guard={check(identity(current(module))==frozen){"页面或所选内容已改变，请重新发出指令，避免改错对象"};plan.guard()})
 }
}
@Composable fun AssistantSurface(module:String,state:JSONObject,tools:List<SurfaceTool> = emptyList(),priority:Int=0,enabled:Boolean=true){
 val owner=remember{UUID.randomUUID().toString()}
 SideEffect{if(enabled)AssistantSurfaces.put(SurfaceBinding(owner,AssistantSurfaces.canonical(module),priority,state,tools))else AssistantSurfaces.remove(owner)}
 DisposableEffect(owner){onDispose{AssistantSurfaces.remove(owner)}}
}
fun surfaceTool(op:String,description:String,keys:String="",writes:Boolean=true,prepare:(JSONObject)->PreparedTool)=SurfaceTool(op,description,keys.split(',').filter{it.isNotBlank()}.toSet(),writes,prepare)
fun prepared(title:String,args:JSONObject,run:suspend ()->String)=PreparedTool(title,AssistantSurfacePreview.describe(args),run)
fun JSONObject.text(key:String,max:Int=4000):String=getString(key).trim().also{require(it.isNotBlank()&&it.length<=max){"$key 内容不完整或过长"}}
fun JSONObject.onlyChoice(key:String,choices:List<String>)=text(key).also{require(it in choices){"$key 仅支持 ${choices.joinToString()}"}}

/** Readable operation previews share the exact values passed to the handler. */
object AssistantSurfacePreview {
 private val labels=mapOf("level" to "已掌握词库","word" to "单词","known" to "已掌握","language" to "识别语言","summaryHint" to "摘要偏好","source" to "来源网站","order" to "排序","name" to "名称","title" to "标题","text" to "文字","options" to "选项","weight" to "权重","id" to "记录 ID","ids" to "文件 ID","folderId" to "会议 ID","clipId" to "录音 ID","clipIds" to "录音顺序","query" to "关键词","kind" to "类型","date" to "日期","month" to "月份","start" to "开始日期","end" to "结束日期","time" to "时间","period" to "时段","minutes" to "分钟","enabled" to "启用","mode" to "模式","style" to "界面风格","remark" to "备注","description" to "详情","contacts" to "公开联系方式","publish" to "立即发布","consent" to "同意公开联系方式","spinAfter" to "创建后立即抽签","pointer" to "转动指针","duration" to "转动时长","customMs" to "毫秒","sources" to "来源网站","summary" to "显示摘要","expanded" to "展开来源","from" to "起点／源语言","to" to "终点／目标语言","orientation" to "朝向","focus" to "视图焦点","range" to "时间范围","notifications" to "允许通知","calls" to "允许电话","size" to "字号","speed" to "速度","foreground" to "文字颜色","background" to "背景颜色","landscape" to "横屏","moving" to "滚动","blink" to "闪烁","bold" to "加粗","shake" to "抖动","atMs" to "播放起点（毫秒）","startMs" to "句子时间（毫秒）","asrContext" to "识别术语","translationHint" to "翻译偏好","original" to "显示原文","notify" to "通知","section" to "分组","tab" to "栏目","list" to "列表","tool" to "工具","category" to "分类","status" to "状态","mine" to "只看自己")
 fun describe(o:JSONObject):String=o.keys().asSequence().map{key->
  if(key=="noticeScope")"通知显示：${HomeNoticeScope.entries.find{it.name==o.optString(key)}?.label?:value(o.get(key))}"
  else "${labels[key]?:key}：${value(o.get(key))}"
 }.joinToString("\n").ifBlank{"按当前设置执行"}
 private fun value(v:Any?):String=when(v){null,JSONObject.NULL->"无";is JSONObject->describe(v);is JSONArray->(0 until v.length()).joinToString("\n"){"${it+1}. ${value(v.get(it))}"};is Boolean->if(v)"是"else"否";else->when(v.toString()){"buy"->"收";"sell"->"出";"sold"->"已完成";"withdrawn"->"下架";"system"->"跟随系统";"light"->"浅色";"dark"->"深色";""->"无";else->v.toString()}}
}
object AssistantGate {
 fun check(access:AssistantAccess,writes:Boolean,confirmed:Boolean){
  check(!writes||access!=AssistantAccess.READ_ONLY){"只读模式不能执行修改、录音、计时或发布"}
  check(!writes||access!=AssistantAccess.CONFIRM||confirmed){"需要先确认操作"}
 }
}



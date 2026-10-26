package cn.dapan.assistant

import android.content.*
import androidx.core.content.ContextCompat
import org.json.*
import kotlinx.coroutines.*
import java.time.*

object AssistantGlobalTools{
 fun tools(c:Context,s:AppStore,attachments:List<AssistantFile>,favorites:ToolFavorites):Map<String,List<SurfaceTool>> = mapOf(
  "资料库" to listOf(
   surfaceTool("save_attachments","将AI栏明确指定的附件永久存入资料库；ids为附件ID数组，保存后返回不同的资料库ID","ids"){a->
    val ids=a.getJSONArray("ids").objectsAsText();require(ids.isNotEmpty()&&ids.size==ids.distinct().size){"附件列表为空或重复"}
    val files=ids.map{id->attachments.singleOrNull{it.id==id}?:error("附件已失效，请重新选择")}
    var report:LibraryAttachmentSaveReport?=null
    PreparedTool("收纳 ${files.size} 个文件",files.joinToString("\n"){it.name},run={
     report=withContext(Dispatchers.IO){LibraryStore(c).use{it.saveAttachments(files)}}
     LibraryChanges.revision++;report!!.receipt()
    },evidence={report?.evidence().orEmpty()})
   },
   surfaceTool("edit","修改文件名称/备注，id必须来自资料库","id,name,remark"){a->val id=a.text("id");val old=LibraryStore(c).use{it.find(id)}?:error("文件不存在");val name=a.optString("name",old.name);val remark=a.optString("remark",old.remark);require(name.isNotBlank()&&name.length<=200&&remark.length<=4000);PreparedTool("修改 ${old.name}","名称：${old.name} → $name\n备注：${old.remark} → $remark",{LibraryStore(c).use{it.edit(id,name,remark)};LibraryChanges.revision++;"已修改 $name"},{check(LibraryStore(c).use{it.find(id)}==old){"文件信息已变化，请重新确认"}})},
   surfaceTool("delete","删除资料库中的本机副本与索引，id必须真实","id"){a->val id=a.text("id");val old=LibraryStore(c).use{it.find(id)}?:error("文件不存在");PreparedTool("删除 ${old.name}","删除本机文件副本与索引；不会删除导入前的外部文件",{LibraryStore(c).use{it.delete(id)};LibraryChanges.revision++;"已删除库中副本：${old.name}"},{check(LibraryStore(c).use{it.find(id)}==old){"文件信息已变化"}})},
   surfaceTool("open","使用手机已安装的应用打开文件","id",false){a->val id=a.text("id");val d=LibraryStore(c).use{it.find(id)}?:error("文件不存在");prepared("打开 ${d.name}",a){LibraryStore(c).use{it.open(id)};"已请求打开 ${d.name}"}},
   surfaceTool("reindex","重建文件文字索引；图片使用当前公用或自备模式。公用图片须用户在资料库逐张确认发送至开发者服务器和DeepSeek；未确认只标记待识别，不会上传。仅在用户要求重试或重建时调用","id"){a->val id=a.text("id");val d=LibraryStore(c).use{it.find(id)}?:error("文件不存在");prepared("重建 ${d.name} 的文字索引",a){val state=withContext(Dispatchers.IO){LibraryStore(c).use{it.index(id,force=true);it.find(id)?.state.orEmpty()}};LibraryChanges.revision++;if(state==LibraryIndexState.PUBLIC_CONSENT)"尚未上传识别。请在资料库打开这张图片的文字索引，确认使用公用识别。"else if(d.kind==LibraryKind.IMAGE)"文字识别状态：$state"else"索引处理结果：$state"}}
  ),
  "晨签" to listOf(surfaceTool("configure","修改晨签设置，所有字段均可省略并保留原值，只传要改的字段。例如只改7:40就仅传time=07:40，不需要用户提供起止日期。start/end=YYYY-MM-DD或空；notify为统计时询问；lastCall为统计时间前固定1分钟的精确提醒，布尔，默认关闭，使用系统闹钟精度且可能显示闹钟图标。不得单改lastCall时间","start,end,time,notify,lastCall"){a->
   val start=if(a.has("start"))a.optString("start").takeIf{it.isNotBlank()}?.let(LocalDate::parse)else s.morningStart
   val end=if(a.has("end"))a.optString("end").takeIf{it.isNotBlank()}?.let(LocalDate::parse)else s.morningEnd
   val time=LocalTime.parse(a.optString("time",s.morningTime.toString()));val notify=a.optBoolean("notify",s.morningNotify);val lastCall=a.optBoolean("lastCall",s.morningLastCall)
   require(start==null||end==null||end>=start)
   prepared("调整晨签统计",a){s.configureMorning(start,end,time,notify,lastCall);"晨签设置已保存"+if(lastCall)when{start==null->"；请先设置统计起点";MorningLastCall.issue(c)!=null->"；${MorningLastCall.issue(c)}";else->"；最后一分钟提醒为 ${time.minusMinutes(1)}，跟随统计时间"}else""}
  }),
  "设置" to listOf(surfaceTool("theme","外观独立设置：mode=system/light/dark；shape=${UiShape.entries.joinToString("/"){it.label}}；palette=${UiColorPalette.entries.joinToString("/"){it.label}}。单改配色不变形状，单改形状不变配色。style仅兼容旧风格，会同时设置两者；不得修改助手权限或密钥","mode,shape,palette,style"){a->
   require(listOf("mode","shape","palette","style").any{a.has(it)}){"请指定形状、配色或明暗模式"}
   val mode=if(a.has("mode"))a.onlyChoice("mode",listOf("system","light","dark"))else null
   val legacy=if(a.has("style"))UiStyle.entries.singleOrNull{it.label==a.text("style")||it.name.equals(a.text("style"),true)}?:error("请选择${UiStyle.entries.joinToString("、"){it.label}}")else null
   require(legacy==null||(!a.has("shape")&&!a.has("palette"))){"旧风格与独立形状/配色不能同时指定，请使用 shape 和 palette"}
   val shape=if(a.has("shape"))UiShape.parse(a.text("shape"))else legacy?.shape
   val palette=if(a.has("palette"))UiColorPalette.parse(a.text("palette"))else legacy?.palette
   val preview=listOfNotNull(shape?.let{"形状：${it.label}"},palette?.let{"配色：${it.label}"},mode?.let{"明暗：${when(it){"light"->"白天";"dark"->"黑夜";else->"跟随系统"}}"}).joinToString("\n")
   PreparedTool("调整界面外观",preview,{
    if(shape!=null||palette!=null)AppAppearance.configure(c,shape,palette)
    mode?.let{AppAppearance.set(c,it)}
    "外观已更新：${AppAppearance.description} · ${when(AppAppearance.mode){"light"->"白天";"dark"->"黑夜";else->"跟随系统"}}"
   })
  },
   surfaceTool("pet","设置小助手：enabled布尔，开启后替代常驻AI栏，点按展开迷你AI栏、长按说话；autoHide布尔，控制闲置5秒后是否缩到边缘只露草叶，可单改任一项，与主题独立","enabled,autoHide"){a->
    require(a.has("enabled")||a.has("autoHide")){"请指定小助手开关或闲置收起设置"}
    val enabled=if(a.has("enabled"))a.getBoolean("enabled")else null
    val autoHide=if(a.has("autoHide"))a.getBoolean("autoHide")else null
    prepared("调整小助手",a){enabled?.let{AppAppearance.setPetEnabled(c,it)};autoHide?.let{AppAppearance.setPetAutoHide(c,it)};"小助手${if(AppAppearance.petEnabled)"已开启"else"已关闭"} · 闲置自动收起${if(AppAppearance.petAutoHide)"已开启"else"已关闭"}"}
   }),
  "工具" to listOf(surfaceTool("favorite","收藏或取消收藏工具；name为实际工具名称，enabled布尔","name,enabled"){a->val name=a.onlyChoice("name",toolGroups.flatMap{it.second});val enabled=a.getBoolean("enabled");prepared(if(enabled)"收藏 $name"else"取消收藏 $name",a){if((name in favorites.names)!=enabled)favorites.toggle(name);"工具收藏已更新"}}),
  "日历" to listOf(surfaceTool("import_courses","打开学校课表导入，用户自行登录自己的账号，读取后预览确认；不索取账号密码","",false){a->prepared("打开课表导入",a){c.startActivity(Intent(c,TimetableImportActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));"已打开课表导入，请在学校页面登录并读取自己的课表"}},surfaceTool("category_add","新增事项分类","name"){a->val name=a.text("name",40);require(name!="无分类");prepared("新增分类 $name",a){s.addCategory(name);"分类已添加"}},surfaceTool("category_delete","删除自定义分类（课程和无分类不可删除），使用此分类的事项改为无分类","name"){a->val name=a.text("name");require(name in s.categories&&name !in listOf("无分类","课程"));prepared("删除分类 $name",a){s.removeCategory(name);"分类已删除，相关事项已改为无分类"}})
 )
 fun descriptions(c:Context,s:AppStore,files:List<AssistantFile>,favorites:ToolFavorites)=JSONObject().also{root->tools(c,s,files,favorites).forEach{(module,tools)->root.put(module,JSONArray(tools.map{JSONObject().put("op",it.operation).put("description",it.description).put("fields",JSONArray(it.keys.toList())).put("writes",it.writes)}))}}
}
fun JSONArray.objectsAsText()=(0 until length()).map{getString(it)}


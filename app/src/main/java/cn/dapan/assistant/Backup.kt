package cn.dapan.assistant

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import org.json.*
import java.time.LocalDate

class BackupPreview private constructor(private val data: JSONObject) {
 val events get() = data.getJSONArray("entries").length()
 val notes get() = data.getJSONArray("notes").length()
 val checks get() = data.getJSONArray("checks").length()

 fun mergeInto(current: JSONObject): JSONObject {
  val result=JSONObject(current.toString())
  for ((field,key) in listOf("entries" to "id","notes" to "id","checks" to "date")) {
   val merged=linkedMapOf<String,JSONObject>()
   data.getJSONArray(field).objects().forEach { merged[it.getString(key)]=it }
   // Current records win collisions; import never silently overwrites newer edits.
   current.optJSONArray(field).objects().forEach { merged[it.getString(key)]=it }
   result.put(field,JSONArray(merged.values.toList()))
  }
  for(field in listOf("categories","groups")) {
   val values=linkedSetOf<String>()
   for(source in listOf(current,data)) source.optJSONArray(field)?.let { a -> repeat(a.length()) { values.add(a.getString(it)) } }
   result.put(field,JSONArray(values.toList()))
  }
  return result
 }

 companion object {
  const val MAX_BYTES=8*1024*1024
  fun parse(raw:String):BackupPreview {
   require(raw.toByteArray().size<=MAX_BYTES) { "备份文件超过 8 MB" }
   val o=JSONObject(raw)
   require(o.getInt("version")==1) { "不支持这个备份版本" }
   val events=o.getJSONArray("entries").objects()
   val notes=o.getJSONArray("notes").objects()
   val checks=o.getJSONArray("checks").objects()
   require(events.size+notes.size+checks.size<=50000) { "备份记录过多" }
   require(events.map{it.getString("id")}.distinct().size==events.size) { "日程存在重复标识" }
   events.forEach { e -> require(e.getString("id").isNotBlank()); require(entryFromJson(e).error()==null) { "备份中有无效日程" } }
   require(notes.map{it.getString("id")}.distinct().size==notes.size) { "笔记存在重复标识" }
   notes.forEach { n ->
    require(n.getString("id").isNotBlank() && n.getString("title").isNotBlank()) { "笔记缺少名称或标识" }
    require(n.getString("module") in listOf("待办","快递","信息查询","账单")) { "笔记分类无法识别" }
    n.optJSONArray("attachments")?.let { a -> repeat(a.length()) { require(a.getString(it).startsWith("content://")) { "附件引用格式错误" } } }
   }
   require(checks.map{it.getString("date")}.distinct().size==checks.size) { "晨签日期重复" }
   checks.forEach { LocalDate.parse(it.getString("date"));it.getBoolean("passed");if(it.has("required"))it.getBoolean("required") }
   for(field in listOf("categories","groups")) o.optJSONArray(field)?.let{a->repeat(a.length()){require(a.getString(it).isNotBlank())}}
   return BackupPreview(o)
  }
 }
}

@Composable fun BackupControls(store:AppStore) {
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 var preview by remember { mutableStateOf<BackupPreview?>(null) }
 var status by remember { mutableStateOf<String?>(null) }
 var busy by remember { mutableStateOf(false) }
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
  if(uri!=null) scope.launch {
   busy=true
   try { val raw=store.backup();withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri)?.use{it.write(raw.toByteArray())} ?: error("无法写入文件")};status="记录备份已导出" }
   catch(e:Exception){status=e.message ?: "导出失败"} finally {busy=false}
  }
 }
 val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
  if(uri!=null) scope.launch {
   busy=true
   try { preview=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use { input ->
    val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
    while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=BackupPreview.MAX_BYTES){"备份超过 8 MB"};out.write(buffer,0,n)}
    BackupPreview.parse(out.toString("UTF-8"))
   } ?: error("无法读取备份") } }
   catch(e:Exception){status="无法导入：${e.message}"} finally {busy=false}
  }
 }
 TextButton(onClick={export.launch("大盘助手-记录备份.json")},enabled=!busy){Text("导出记录")}
 TextButton(onClick={restore.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!busy){Text("从备份合并记录")}
 Text("导入保留当前设置，同标识记录以本机为准。附件只含引用，换手机需重新选择；音频请在录音摘要中单独导出。",style=MaterialTheme.typography.bodySmall)
 preview?.let { p -> AlertDialog(onDismissRequest={preview=null},title={Text("合并备份？")},text={Text("发现 ${p.events} 个日程、${p.notes} 条记录、${p.checks} 天晨签。\n\n现有记录不会被覆盖，合并前会保留一份本机恢复副本。")},confirmButton={TextButton(onClick={try{store.importRecords(p);status="已合并记录"}catch(e:Exception){status="合并失败：${e.message}"};preview=null}){Text("确认合并")}},dismissButton={TextButton(onClick={preview=null}){Text("取消")}}) }
 status?.let { AlertDialog(onDismissRequest={status=null},text={Text(it)},confirmButton={TextButton(onClick={status=null}){Text("知道了")}}) }
}

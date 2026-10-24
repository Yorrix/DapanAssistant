package cn.dapan.assistant

import android.content.Context
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/** Context vocabulary is sent to Fun-ASR, never applied as a search-and-replace to a transcript. */
object AsrHotwordRules {
 const val WORDS_LIMIT=220
 const val CONTEXT_LIMIT=400
 val defaults=listOf("玉兰卡","大盘助手","大工","大连理工大学","盘锦校区","晨签","体测","风雨操场","彩虹连廊","A01","B01","程设","数科导")
 fun parse(text:String):List<String> = text.split(Regex("[\\r\\n,，;；、]+"))
  .map{it.trim().replace(Regex("\\s+")," ")}.filter{it.isNotBlank()}.distinctBy{it.lowercase(Locale.ROOT)}
 fun size(words:List<String>)=words.joinToString("、").let{it.codePointCount(0,it.length)}
 fun error(words:List<String>):String? = when {
  words.any{it.codePointCount(0,it.length)>40}->"每个词最多 40 个字符"
  size(words)>WORDS_LIMIT->"热词合计最多 $WORDS_LIMIT 个字符，请删减后保存"
  else->null
 }
 private fun String.prefixCodePoints(max:Int)=substring(0,offsetByCodePoints(0,minOf(codePointCount(0,length),max.coerceAtLeast(0))))
 fun context(hint:String,words:List<String>):String {
  // Keep whole words and reserve space for the feature's own context. Both share one 400-character turn.
  val selected=mutableListOf<String>()
  parse(words.joinToString("\n")).forEach{word->if(error(selected+word)==null)selected+=word}
  if(selected.isEmpty())return hint.trim().prefixCodePoints(CONTEXT_LIMIT)
  val vocabulary="常用词：${selected.joinToString("、")}。"
  val rest=hint.trim()
  if(rest.isBlank())return vocabulary
  val prefix="$vocabulary\n当前场景："
  return prefix+rest.prefixCodePoints(CONTEXT_LIMIT-prefix.codePointCount(0,prefix.length))
 }
}

class AsrHotwordPreferences(c:Context) {
 private val prefs=c.applicationContext.getSharedPreferences("asr-hotwords",Context.MODE_PRIVATE)
 val enabled:Boolean get()=prefs.getBoolean("enabled",true)
 fun words():List<String> = if(prefs.contains("words"))AsrHotwordRules.parse(prefs.getString("words","").orEmpty())else AsrHotwordRules.defaults
 fun active():List<String> = if(enabled)words()else emptyList()
 fun save(enabled:Boolean,words:List<String>) {
  require(AsrHotwordRules.error(words)==null){AsrHotwordRules.error(words)!!}
  check(prefs.edit().putBoolean("enabled",enabled).putString("words",words.joinToString("\n")).commit()){"热词保存失败"}
 }
}

@Composable fun HotwordSettingsDialog(onClose:()->Unit) {
 val c=LocalContext.current;val prefs=remember(c){AsrHotwordPreferences(c)}
 var enabled by remember{mutableStateOf(prefs.enabled)}
 var draft by remember{mutableStateOf(prefs.words().joinToString("\n"))}
 var saveError by remember{mutableStateOf<String?>(null)}
 val words=remember(draft){AsrHotwordRules.parse(draft)};val error=AsrHotwordRules.error(words)
 var restore by remember{mutableStateOf(false)}
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
  Surface(Modifier.fillMaxWidth().padding(14.dp).imePadding().heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.88f),shape=AppShape(18.dp),color=CardSurface) {
   Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically) {
     Text("语音热词",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
     TextButton(onClick={runCatching{prefs.save(enabled,words)}.onSuccess{onClose()}.onFailure{saveError=it.message}},enabled=error==null){Text("保存")}
    }
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
     Text("识别时优先参考这些词",Modifier.weight(1f),fontSize=14.sp)
     Switch(checked=enabled,onCheckedChange={enabled=it})
    }
    OutlinedTextField(draft,{draft=it.take(4000);saveError=null},Modifier.fillMaxWidth(),
     label={Text("常用词、人名与地点")},placeholder={Text("玉兰卡\n高等数学\n老师姓名")},
     minLines=6,maxLines=9,isError=error!=null,
     supportingText={Text(error?:"每行一个，可直接添加、修改或删除 · ${AsrHotwordRules.size(words)}/${AsrHotwordRules.WORDS_LIMIT}",fontSize=11.sp)},shape=AppShape(10.dp))
    Text("用于 AI 栏、录音摘要、实时翻译和课堂生词。保存后，下次收听或重新转写生效。",fontSize=12.sp,color=Muted,lineHeight=17.sp)
    Text("热词与各工具的识别提示词共用 400 字符；热词优先，过长的场景提示取前段。不会强行替换已识别的文字。",fontSize=11.sp,color=Muted,lineHeight=16.sp)
    saveError?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
     TextButton(onClick={restore=true}){Text("恢复校园默认词",fontSize=12.sp)}
     TextButton(onClick=onClose){Text("取消",fontSize=12.sp)}
    }
   }
  }
 }
 if(restore)AlertDialog(onDismissRequest={restore=false},title={Text("恢复默认热词？")},text={Text("将替换当前编辑的词表，保存后生效。")},confirmButton={TextButton(onClick={draft=AsrHotwordRules.defaults.joinToString("\n");saveError=null;restore=false}){Text("恢复")}},dismissButton={TextButton(onClick={restore=false}){Text("取消")}})
}

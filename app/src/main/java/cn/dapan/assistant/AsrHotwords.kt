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

/** Words are sent through ASR's weighted vocabulary, never appended to transcript context. */
object AsrHotwordRules {
 const val WORDS_LIMIT=220
 const val CONTEXT_LIMIT=400
 val legacyDefaults=listOf("玉兰卡","大盘助手","大工","大连理工大学","盘锦校区","晨签","体测","风雨操场","彩虹连廊","A01","B01","程设","数科导")
 val previousDefaults=listOf("玉兰卡","大盘助手","大工","大连理工大学","盘锦校区","晨签","体测","风雨操场","彩虹连廊","八食堂","商业街","A01","B01","大学英语","大英","线性代数","高等数学","微积分","无机化学","程序设计","程设","数科导","习概","思政课","外教","四六级","选修课","课表","出发提醒","DDL","TDL","取件码","学分","奖学金")
 val defaults=previousDefaults+"assignment"
 fun parse(text:String):List<String> = text.split(Regex("[\\r\\n,，;；、]+"))
  .map{it.trim().replace(Regex("\\s+")," ")}.filter{it.isNotBlank()}.distinctBy{it.lowercase(Locale.ROOT)}
 fun size(words:List<String>)=words.joinToString("、").let{it.codePointCount(0,it.length)}
 fun error(words:List<String>):String? = when {
  words.any{it.any{c->c.code>127}&&it.codePointCount(0,it.length)>15}->"含中文的热词最多 15 个字符"
  words.any{it.all{c->c.code<=127}&&(it.split(' ').size>7||it.length>80)}->"英文热词最多 7 个单词、80 个字符"
  size(words)>WORDS_LIMIT->"热词合计最多 $WORDS_LIMIT 个字符，请删减后保存"
  else->null
 }
 private fun String.prefixCodePoints(max:Int)=substring(0,offsetByCodePoints(0,minOf(codePointCount(0,length),max.coerceAtLeast(0))))
 fun context(hint:String,@Suppress("UNUSED_PARAMETER") words:List<String>):String = hint.trim().prefixCodePoints(CONTEXT_LIMIT)
 fun upgrade(words:List<String>)=if(words==legacyDefaults||words==previousDefaults)defaults else words
 fun validWords(words:List<String>):List<String> {
  val result=mutableListOf<String>()
  parse(words.joinToString("\n")).forEach{if(error(result+it)==null)result+=it}
  return result
 }
}

class AsrHotwordPreferences(c:Context) {
 private val prefs=c.applicationContext.getSharedPreferences("asr-hotwords",Context.MODE_PRIVATE)
 val enabled:Boolean get()=prefs.getBoolean("enabled",true)
 fun words():List<String> = if(prefs.contains("words"))AsrHotwordRules.upgrade(AsrHotwordRules.parse(prefs.getString("words","").orEmpty()))else AsrHotwordRules.defaults
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
    Text("用于 AI 栏、录音摘要、实时翻译、课堂助手和课堂生词。保存后，下次收听或重新转写生效。",fontSize=12.sp,color=Muted,lineHeight=17.sp)
    Text("热词独立提交给语音服务，识别提示词用于补充场景。只提高匹配概率，不会把词表当成已说过的内容。",fontSize=11.sp,color=Muted,lineHeight=16.sp)
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

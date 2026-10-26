package cn.dapan.assistant

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.json.*

data class RecordingOptions(val language:String="中文",val asrContext:String="",val summaryHint:String="")
class RecordingPreferences(c:Context){
 private val prefs=c.applicationContext.getSharedPreferences("recording-settings",0)
 fun load()=RecordingOptions(prefs.getString("language","中文")?.takeIf{it in TranslationLanguages.names}?:"中文",prefs.getString("asr-context","").orEmpty(),prefs.getString("summary-hint","").orEmpty())
 fun save(value:RecordingOptions){check(prefs.edit().putString("language",value.language).putString("asr-context",value.asrContext.take(400)).putString("summary-hint",value.summaryHint.take(1200)).commit()){"设置保存失败"}}
}
object AsrHints {
 fun input(context:String,hotwords:List<String> = emptyList()):JSONObject {
  val text=AsrHotwordRules.context(context,hotwords)
  return JSONObject().also{input->if(text.isNotBlank())input.put("context",JSONArray().put(JSONObject().put("role","user").put("content",JSONArray().put(JSONObject().put("type","input_text").put("text",text)))))}
 }
}
@Composable fun AudioControlBar(left:String,onLeft:()->Unit,primary:String,onPrimary:()->Unit,right:String,onRight:()->Unit,
 active:Boolean=false,leftEnabled:Boolean=true,primaryEnabled:Boolean=true,rightEnabled:Boolean=true){
 Row(Modifier.fillMaxWidth().background(CardSurface).padding(horizontal=12.dp,vertical=3.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
  TextButton(onClick=onLeft,enabled=leftEnabled,contentPadding=PaddingValues(4.dp)){Text(left,fontSize=12.sp)}
  Button(onClick=onPrimary,enabled=primaryEnabled,modifier=Modifier.weight(1f).height(36.dp),shape=RoundedCornerShape(50)){
   LineIcon(if(active)AppGlyph.Stop else AppGlyph.Mic,Modifier.size(18.dp),MaterialTheme.colorScheme.onPrimary);Spacer(Modifier.width(6.dp));Text(primary,fontSize=14.sp)
  }
  TextButton(onClick=onRight,enabled=rightEnabled,contentPadding=PaddingValues(4.dp)){Text(right,fontSize=11.sp)}
 }
}
@Composable fun AudioSettingsDialog(title:String,asr:String,onAsr:(String)->Unit,generation:String,onGeneration:(String)->Unit,
 generationLabel:String,generationHint:String,generationLimit:Int,onClose:()->Unit,
 before:@Composable ColumnScope.()->Unit={},after:@Composable ColumnScope.()->Unit={},generationControls:@Composable ColumnScope.()->Unit={}){
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp).heightIn(max=LocalConfiguration.current.screenHeightDp.dp*.86f),color=CardSurface,shape=AppShape(18.dp)){
   Column(Modifier.imePadding().padding(horizontal=14.dp)){
    Row(Modifier.fillMaxWidth().height(46.dp),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold);TextButton(onClick=onClose){Text("完成",fontSize=13.sp)}}
    Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
     before()
     Text("语音识别 · 阿里云",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
     OutlinedTextField(asr,{onAsr(it.take(400))},Modifier.fillMaxWidth(),label={Text("识别提示词",fontSize=12.sp)},placeholder={Text("如：这节课讨论线性代数的特征值",fontSize=12.sp)},maxLines=4,supportingText={Text("${asr.length}/400 · 下次收听或转写生效；专有名词可加到语音热词",fontSize=10.sp)},shape=AppShape(10.dp))
     Text("$generationLabel · DeepSeek",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
     generationControls()
     OutlinedTextField(generation,{onGeneration(it.take(generationLimit))},Modifier.fillMaxWidth(),label={Text("${generationLabel}提示词",fontSize=12.sp)},placeholder={Text(generationHint,fontSize=12.sp)},maxLines=5,supportingText={Text("${generation.length}/$generationLimit · 下次生成生效",fontSize=10.sp)},shape=AppShape(10.dp))
     after()
    }
   }
  }
 }
}

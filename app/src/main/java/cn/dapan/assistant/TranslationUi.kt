package cn.dapan.assistant

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.speech.tts.TextToSpeech
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.*
import java.io.File
import java.util.*

data class TranslationTurn(val id:String=UUID.randomUUID().toString(),val speaker:String,val from:String,val to:String,val text:String,val translated:String="",val error:String="",val pending:Boolean=false,val hint:String="")
class TranslationHistory(c:Context){
 private val file=AtomicFile(File(c.filesDir,"live-translations.json"))
 fun read():List<TranslationTurn> = runCatching{
  JSONArray(file.openRead().bufferedReader().use{it.readText()}).objects().map{TranslationTurn(it.getString("id"),it.getString("speaker"),it.getString("from"),it.getString("to"),it.getString("text"),it.optString("translated"),it.optString("error"),hint=it.optString("hint"))}
 }.getOrDefault(emptyList())
 @Synchronized fun save(turns:List<TranslationTurn>){val rows=JSONArray(turns.takeLast(300).map{JSONObject().put("id",it.id).put("speaker",it.speaker).put("from",it.from).put("to",it.to).put("text",it.text).put("translated",it.translated).put("error",it.error).put("hint",it.hint)});val out=file.startWrite();try{out.write(rows.toString().toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}}
}
@Composable fun RecordingHub(open:(String)->Unit){
 Column(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(0.dp)){
  listOf("会议纪要" to "录音、转写与总结","实时翻译" to "连续收听，逐句翻译","课堂生词" to "听课时只看生词、释义和音标").forEach{(name,detail)->
   Row(Modifier.fillMaxWidth().clickable{open(name)}.padding(vertical=12.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(if(name=="会议纪要")"录音摘要"else name,fontSize=16.sp,fontWeight=FontWeight.Medium);Text(detail,fontSize=13.sp,color=Muted)};LineIcon(AppGlyph.Next,Modifier.size(18.dp),Muted)}
   HorizontalDivider(color=Hairline,thickness=.5.dp)
  }
 }
}
@Composable fun LanguageChoice(value:String,enabled:Boolean,onChange:(String)->Unit){
 var expanded by remember{mutableStateOf(false)}
 Box{TextButton(onClick={expanded=true},enabled=enabled,modifier=Modifier.height(34.dp),contentPadding=PaddingValues(horizontal=12.dp,vertical=0.dp)){Text("$value ▾",fontSize=14.sp,fontWeight=FontWeight.Medium)};DropdownMenu(expanded,{expanded=false}){TranslationLanguages.values.forEach{language->DropdownMenuItem(text={Text(language.name)},onClick={expanded=false;onChange(language.name)})}}}
}
@Composable fun TranslationScreen(secrets:Secrets,onBack:()->Unit={}){
 val c=LocalContext.current;val scope=rememberCoroutineScope();val history=remember{TranslationHistory(c)}
 val prefs=remember{c.getSharedPreferences("translation-settings",0)}
 val mode="live"
 var turns by rememberPageSnapshot("translation-turns"){mutableListOf<TranslationTurn>().toList()}
 var historyReady by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){try{turns=withContext(Dispatchers.IO){history.read()}}finally{historyReady=true}}
 var from by remember{mutableStateOf(prefs.getString("$mode-from","英语")!!.takeIf{it in TranslationLanguages.names}?:"英语")}
 var to by remember{mutableStateOf(prefs.getString("$mode-to","中文")!!.takeIf{it in TranslationLanguages.names}?:"中文")}
 var options by remember{mutableStateOf(false)};var keyboard by rememberSaveable{mutableStateOf(false)}
 var showOriginal by remember{mutableStateOf(prefs.getBoolean("original",true))}
 var textSize by remember{mutableFloatStateOf(prefs.getFloat("text-size",19f).coerceIn(16f,26f))}
 var asrContext by remember{mutableStateOf(prefs.getString("asr-context","").orEmpty())}
 var translationHint by remember{mutableStateOf(prefs.getString("translation-hint","").orEmpty())}
 var draftId by remember{mutableStateOf(UUID.randomUUID().toString())}
 LaunchedEffect(from,to){prefs.edit().putString("$mode-from",from).putString("$mode-to",to).apply()}

 var input by rememberSaveable{mutableStateOf("")};var message by remember{mutableStateOf<String?>(null)};var clear by remember{mutableStateOf(false)}
 var owner by remember{mutableStateOf("")};var captureFrom by remember{mutableStateOf(from)};var captureTo by remember{mutableStateOf(to)};var captureSpeaker by remember{mutableStateOf("讲话")}
 val cursor=remember{FinalSentenceCursor()};val queue=remember{Channel<TranslationTurn>(32)}
 var ttsReady by remember{mutableStateOf(false)};val tts=remember{TextToSpeech(c){ttsReady=it==TextToSpeech.SUCCESS}}
 val mine=owner.isNotBlank()&&AudioState.session==owner;val listening=mine&&AudioState.running
 suspend fun persist(){check(historyReady){"正在读取翻译记录，请稍候"};val snapshot=turns;try{withContext(Dispatchers.IO){history.save(snapshot)}}catch(e:CancellationException){throw e}catch(e:Exception){message="翻译记录保存失败，请及时复制需要的文字"}}
 fun stop(){if(AudioState.session==owner)c.startService(Intent(c,RecordingService::class.java).setAction("stop"))}
 fun enqueue(text:String,source:String,target:String,speaker:String,id:String?=null,newId:String?=null){
  if(!historyReady){message="正在读取翻译记录，请稍候";return}
  if(text.isBlank())return
  if(source==target){message="源语言和目标语言需要不同";return}
  if(!SharedAi.canUseText(c)){message=SharedAi.unavailableMessage(c);return}
  if(text.length>6000){message="这段话较长，请分段翻译";return}
  if(id!=null&&turns.any{it.id==id&&it.pending})return
  val row=TranslationTurn(id?:newId?:UUID.randomUUID().toString(),speaker,source,target,text,pending=true,hint=translationHint)
  if(queue.trySend(row).isFailure){val failed=row.copy(pending=false,error="队列已满，原文已保留，可稍后重试");turns=if(id==null)(turns+failed).takeLast(300)else turns.map{if(it.id==id)failed else it};scope.launch{persist()};message="翻译队列已满，已暂停收听；原文保留供重试";stop();return}
  turns=if(id==null)(turns+row).takeLast(300)else turns.map{if(it.id==id)row else it}
 }
 LaunchedEffect(Unit){for(row in queue){
  persist()
  try{val translated=AiClient.translate(secrets.get("deepseek"),secrets.model,row.text,row.from,row.to,row.hint);turns=turns.map{if(it.id==row.id)it.copy(translated=translated,pending=false,error="")else it}}
  catch(e:CancellationException){throw e}
  catch(e:Exception){turns=turns.map{if(it.id==row.id)it.copy(error=e.message?:"翻译失败",pending=false)else it}}
  persist()
 }}
 LaunchedEffect(AudioState.finalLines,AudioState.session,owner){
  if(owner.isNotBlank()&&AudioState.session==owner)cursor.take(AudioState.finalLines.map{it.text}).forEach{
   // Promote the visible draft with its existing lazy-list key, rather than removing it.
   enqueue(it,captureFrom,captureTo,captureSpeaker,newId=draftId)
   draftId=UUID.randomUUID().toString()
  }
 }
 DisposableEffect(Unit){onDispose{if(AudioState.session==owner&&AudioState.running)c.startService(Intent(c,RecordingService::class.java).setAction("stop"));queue.close();tts.stop();tts.shutdown()}}
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){message=if(it)"麦克风已允许，请再次点击说话"else"未允许麦克风，可以输入文字翻译"}
 fun start(){
  if(!historyReady){message="正在读取翻译记录，请稍候";return}
  if(listening){stop();return}
  if(AudioState.running){message="麦克风正在用于其他录音，请先结束";return}
  if(from==to){message="请选择不同的两种语言";return}
  if(secrets.get("aliyun").isBlank()){message="收听需要自己的阿里云百炼密钥；打字翻译可以使用公用 AI。";return};if(!SharedAi.canUseText(c)){message=SharedAi.unavailableMessage(c);return}
  if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){permission.launch(Manifest.permission.RECORD_AUDIO);return}
  tts.stop();cursor.reset();draftId=UUID.randomUUID().toString();owner="translation-"+UUID.randomUUID();captureFrom=from;captureTo=to;captureSpeaker="讲话"
  try{ContextCompat.startForegroundService(c,Intent(c,RecordingService::class.java).putExtra("voice",true).putExtra("session",owner).putExtra("language",TranslationLanguages.code(captureFrom)).putExtra("translation",true).putExtra("asr_context",asrContext))}catch(e:Exception){message="麦克风启动失败，请重试"}
 }
 fun speak(row:TranslationTurn){
  if(AudioState.running){message="请先结束说话，再播放译文，避免声音被重新识别";return}
  if(!ttsReady){message="手机语音引擎尚未准备好";return}
  val voice=tts.voices?.firstOrNull{it.locale.language==TranslationLanguages.code(row.to)&&!it.isNetworkConnectionRequired}
  if(voice==null){message="手机尚未安装${row.to}离线语音包，可先把译文展示给对方";return}
  tts.voice=voice;if(tts.speak(row.translated,TextToSpeech.QUEUE_FLUSH,null,row.id)==TextToSpeech.ERROR)message="朗读失败，请检查手机语音引擎"
 }
 AssistantSurface("实时翻译",JSONObject().put("ready",historyReady).put("from",from).put("to",to).put("listening",listening).put("asrContext",asrContext).put("translationHint",translationHint).put("recent",JSONArray(turns.takeLast(8).map{JSONObject().put("id",it.id).put("text",it.text).put("translated",it.translated)})),listOf(
  surfaceTool("configure","from/to语言名称，asrContext术语背景，translationHint翻译偏好，original布尔，size16..26","from,to,asrContext,translationHint,original,size"){a->require(!listening){"请先停止收听再切换语言"};val f=a.optString("from",from);val t=a.optString("to",to);require(f in TranslationLanguages.names&&t in TranslationLanguages.names&&f!=t);val z=a.optDouble("size",textSize.toDouble()).toFloat();require(z in 16f..26f);prepared("调整实时翻译",a){from=f;to=t;asrContext=a.optString("asrContext",asrContext).take(400);translationHint=a.optString("translationHint",translationHint).take(600);showOriginal=a.optBoolean("original",showOriginal);textSize=z;prefs.edit().putString("live-from",f).putString("live-to",t).putString("asr-context",asrContext).putString("translation-hint",translationHint).putBoolean("original",showOriginal).putFloat("text-size",textSize).apply();"翻译设置已保存"}},
  surfaceTool("translate","把text加入实时翻译记录并生成译文，可能产生DeepSeek用量","text"){a->val text=a.text("text",6000);SharedAi.requireTextAvailable(c);prepared("翻译这段文字",a){enqueue(text,from,to,"输入");"已加入翻译队列，译文将在本页显示"}},
  surfaceTool("start","开始实时收听并翻译，需要麦克风权限、个人语音密钥及可用的文字AI","",true){a->require(!AudioState.running);require(secrets.get("aliyun").isNotBlank()){ "请先配置阿里云语音密钥" };SharedAi.requireTextAvailable(c);prepared("开始实时翻译",a){start();if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)"已请求麦克风权限，授权后请再次开始"else"已提交实时翻译启动请求"}},
  surfaceTool("stop","停止本页实时收听","",true){a->require(listening){"当前未在翻译收听"};prepared("停止实时翻译",a){stop();"已请求停止收听，正在完成已识别的文字"}},
  surfaceTool("clear","清空实时翻译历史，需先停止录音并等待翻译完成","",true){a->require(historyReady&&!listening&&turns.none{it.pending});prepared("清空翻译历史",a){withContext(Dispatchers.IO){history.save(emptyList())};turns=emptyList();"翻译历史已清空"}}
 ))
 Column(Modifier.fillMaxSize().background(Paper).padding(bottom=LocalFloatingAiHeight.current)){
  Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,onBack)
   Text("实时翻译",Modifier.weight(1f),fontSize=17.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold)
   QuietIconButton("翻译设置",AppGlyph.Settings,{options=true})
  }
  Surface(Modifier.fillMaxWidth().padding(horizontal=12.dp),shape=AppShape(14.dp),color=CardSurface,border=BorderStroke(.5.dp,Hairline)){
   Column{
    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
     Box(Modifier.weight(1f),contentAlignment=Alignment.Center){LanguageChoice(from,!AudioState.running){from=it}}
     TextButton(onClick={val old=from;from=to;to=old},enabled=!AudioState.running,modifier=Modifier.size(40.dp),contentPadding=PaddingValues(0.dp)){Text("⇄",fontSize=22.sp)}
     Box(Modifier.weight(1f),contentAlignment=Alignment.Center){LanguageChoice(to,!AudioState.running){to=it}}
    }
    HorizontalDivider(Modifier.padding(horizontal=12.dp),color=Hairline,thickness=.5.dp)
    AudioControlBar(if(keyboard)"收起输入"else"打字",{keyboard=!keyboard},if(listening)"暂停收听"else"开始收听",{start()},if(showOriginal)"隐藏原文"else"显示原文",{showOriginal=!showOriginal;prefs.edit().putBoolean("original",showOriginal).apply()},active=listening,primaryEnabled=!AudioState.running||(listening&&AudioState.ready))
   }
  }
  Row(Modifier.fillMaxWidth().height(30.dp).padding(horizontal=18.dp),verticalAlignment=Alignment.CenterVertically){
   if(listening){
    val pulse by rememberInfiniteTransition(label="listening").animateFloat(.35f,1f,infiniteRepeatable(tween(700),RepeatMode.Reverse),label="pulse")
    Box(Modifier.size(6.dp).alpha(pulse).background(DutBlue,CircleShape));Spacer(Modifier.width(6.dp))
   }
   Text(when{listening&&!AudioState.ready->"正在收尾";listening&&!AudioState.connectionReady->"连接语音服务中…";listening->"正在听 · $captureFrom";turns.any{it.pending}->"正在翻译 ${turns.count{it.pending}} 段";turns.isEmpty()->"逐句翻译 · 记录留在本机";else->"已暂停 · ${turns.size} 段记录"},fontSize=11.sp,color=if(listening)DutBlue else Muted)
   Spacer(Modifier.weight(1f))
  }
  TranslationTranscript(
   turns=turns, draftId=draftId, listening=listening,
   partial=if(mine)AudioState.partial else "", from=captureFrom, to=captureTo,
   showOriginal=showOriginal, textSize=textSize,
   onRetry={enqueue(it.text,it.from,it.to,it.speaker,it.id)},onSpeak={speak(it)},
   modifier=Modifier.weight(1f).fillMaxWidth()
  )
  if(mine&&AudioState.error!=null)Text(AudioState.error!!,Modifier.padding(horizontal=16.dp,vertical=4.dp),fontSize=11.sp,color=Coral)
  if(keyboard){
   Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
    OutlinedTextField(input,{input=it.take(6000)},Modifier.weight(1f),placeholder={Text("输入需要翻译的文字",fontSize=13.sp)},maxLines=3,shape=AppShape(12.dp))
    FilledIconButton(onClick={enqueue(input,from,to,"文字");if(SharedAi.canUseText(c)&&from!=to)input=""},enabled=historyReady&&input.isNotBlank()){LineIcon(AppGlyph.Send,Modifier.size(18.dp),OnAccent)}
   }
  }
 }

 if(options)AudioSettingsDialog("实时翻译设置",asrContext,{asrContext=it;prefs.edit().putString("asr-context",it).apply()},translationHint,{translationHint=it;prefs.edit().putString("translation-hint",it).apply()},"翻译","如：保留专业术语的英文，使用简明口语",600,{options=false},after={
  Row(verticalAlignment=Alignment.CenterVertically){Text("同时显示原文",Modifier.weight(1f),fontSize=13.sp);Switch(showOriginal,{showOriginal=it;prefs.edit().putBoolean("original",it).apply()})}
  Row(verticalAlignment=Alignment.CenterVertically){Text("译文字号",fontSize=13.sp);Slider(textSize,{textSize=it;prefs.edit().putFloat("text-size",it).apply()},Modifier.weight(1f),valueRange=16f..26f,steps=4)}
  TextButton(onClick={if(listening||turns.any{it.pending})message="请先暂停收听，等待翻译完成"else{options=false;clear=true}},contentPadding=PaddingValues(0.dp)){Text("清空本页记录",color=Coral,fontSize=12.sp)}
 })

 if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("清空本页翻译记录？")},text={Text("本机保存的原文和译文将全部删除，无法恢复。")},confirmButton={TextButton(onClick={clear=false;tts.stop();turns=emptyList();scope.launch{persist()}}){Text("清空")}},dismissButton={TextButton(onClick={clear=false}){Text("取消")}})
 message?.let{AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}})}
}

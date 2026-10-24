package cn.dapan.assistant

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.*
import java.util.Locale

@Composable fun VocabularyScreen(onBack:()->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope();val prefs=remember{VocabularyPreferences(c)}
 val dictionary=remember(c,scope){VocabularyComponentController(c.applicationContext,scope)}
 var level by remember{mutableStateOf(prefs.level)};var hint by remember{mutableStateOf(prefs.context)}
 var rows by remember{mutableStateOf(emptyList<HeardWord>())};var ready by remember{mutableStateOf(false)}
 val feed=rememberLazyListState()
 var unseen by remember{mutableStateOf(emptySet<String>())}
 var options by remember{mutableStateOf(false)};var searching by rememberSaveable{mutableStateOf(false)};var query by rememberSaveable{mutableStateOf("")}
 var clear by remember{mutableStateOf(false)};var knownDialog by remember{mutableStateOf(false)};var known by remember{mutableStateOf(emptySet<String>())}
 var license by remember{mutableStateOf<String?>(null)};var stats by remember{mutableStateOf(JSONObject())}
 var message by remember{mutableStateOf<String?>(null)}
 val snackbar=remember{SnackbarHostState()}
 LaunchedEffect(message){message?.let{snackbar.showSnackbar(it);message=null}}
 LaunchedEffect(dictionary){dictionary.refresh()}
 LaunchedEffect(Unit){try{stats=withContext(Dispatchers.IO){JSONObject(c.assets.open("vocabulary/metadata.json").bufferedReader().use{it.readText()})}}catch(e:CancellationException){throw e}catch(_:Exception){message="词表说明暂时无法读取"}}
 LaunchedEffect(VocabularyState.revision,level,query){
  try{
   val result=withContext(Dispatchers.IO){VocabularyStore(c).use{it.list(level,query) to it.known()}}
   val added=if(ready)result.first.map{it.entry.word}.toSet()-rows.map{it.entry.word}.toSet()else emptySet()
   val following=feed.firstVisibleItemIndex==0&&!feed.isScrollInProgress
   rows=result.first;known=result.second;ready=true
   if(added.isNotEmpty()&&query.isBlank()){
    if(following){feed.requestScrollToItem(0);unseen=emptySet()}else unseen=unseen+added
   }
  }
  catch(e:CancellationException){throw e}catch(e:Exception){message="生词记录读取失败，请重新打开"}
 }
 LaunchedEffect(level,query){unseen=emptySet();feed.requestScrollToItem(0)}
 LaunchedEffect(feed){snapshotFlow{feed.firstVisibleItemIndex==0&&feed.firstVisibleItemScrollOffset==0}.collect{if(it)unseen=emptySet()}}
 suspend fun mark(word:String,value:Boolean){withContext(Dispatchers.IO){VocabularyStore(c).use{it.mark(word,value)}};VocabularyState.revision++}
 fun action(block:suspend ()->Unit){scope.launch{try{block()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"操作未完成，请重试"}}}
 fun stop(){if(VocabularyState.running)c.startService(Intent(c,VocabularyService::class.java).setAction("stop"))}
 fun launchListening(){
  VocabularyDictionary.requireInstalled(c)
  check(dictionary.installed){dictionary.error?:VocabularyDictionary.MISSING}
  check(!AudioState.running){"麦克风正在使用，请先结束其他录音"}
  check(Secrets(c).get("aliyun").isNotBlank()){"请先在今天页设置中填写阿里云百炼 API Key"}
  check(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"请先允许麦克风权限"}
  ContextCompat.startForegroundService(c,Intent(c,VocabularyService::class.java))
 }
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){allowed->if(allowed)action{launchListening()}else message="需要麦克风权限才能收听课堂"}
 fun start(){
  if(VocabularyState.running){stop();return}
  if(!dictionary.installed){message=VocabularyDictionary.MISSING;return}
  if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){permission.launch(Manifest.permission.RECORD_AUDIO);return}
  action{launchListening()}
 }
 AssistantSurface("课堂生词",JSONObject().put("ready",ready).put("dictionaryInstalled",dictionary.installed).put("dictionaryDownloading",dictionary.active).put("level",level.label).put("listening",VocabularyState.running).put("query",query).put("words",JSONArray(rows.take(30).map{it.json()})),listOf(
  surfaceTool("configure","已掌握词库level=基础/高中/四级/六级，逐级累计过滤；asrContext为课程术语最多400字，下次开始生效","level,asrContext"){a->val next=if(a.has("level"))VocabularyLevel.parse(a.getString("level"))else level;prepared("调整课堂生词过滤",a){prefs.level=next;level=next;hint=a.optString("asrContext",hint).take(400);prefs.context=hint;"已掌握词库设为${next.label}；识别提示词下次开始生效"}},
  surfaceTool("start","持续收听课堂，只保留超出已掌握词库的英语生词。使用阿里云语音识别计费，需要麦克风权限","",true){a->VocabularyDictionary.requireInstalled(c);require(dictionary.installed){dictionary.error?:VocabularyDictionary.MISSING};require(!AudioState.running){"麦克风正在使用"};require(Secrets(c).get("aliyun").isNotBlank()){"请先配置阿里云百炼密钥"};prepared("开始课堂生词收听",a){if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){permission.launch(Manifest.permission.RECORD_AUDIO);"已请求麦克风权限，授权后开始收听"}else{launchListening();"已提交启动请求，连接状态显示在课堂生词页"}}},
  surfaceTool("stop","暂停课堂生词收听，保留生词记录","",true){a->require(VocabularyState.running){"课堂生词未在收听"};prepared("暂停课堂生词",a){stop();"正在停止麦克风并保存最后一批生词"}},
  surfaceTool("known","把word标为已掌握或恢复，known=true/false；只接受真实词库单词，默认true","word,known"){a->val word=a.text("word",80).lowercase(Locale.ROOT);prepared("调整已掌握单词",a){val entry=withContext(Dispatchers.IO){VocabularyDictionary.open(c).lookup(word)}?:error("词库未收录这个单词，请检查拼写");val value=a.optBoolean("known",true);mark(entry.word,value);if(value)"${entry.word} 已设为掌握，以后过滤"else"${entry.word} 已恢复，仍按所选词库过滤"}},
  surfaceTool("search","搜索已记录生词的英文或中文释义","query",false){a->prepared("查找课堂生词",a){query=a.optString("query").take(100);searching=query.isNotBlank();"已筛选课堂生词"}},
  surfaceTool("clear","新一课：清空本机本课生词，保留词库设置及个人已掌握词，必须先停止收听","",true){a->require(!VocabularyState.running);prepared("清空本课生词",a){check(!VocabularyState.running);withContext(Dispatchers.IO){VocabularyStore(c).use{it.clear()}};VocabularyState.revision++;"本课生词已清空，已掌握词保留"}}
 ))
 Column(Modifier.fillMaxSize().background(Paper).padding(bottom=LocalFloatingAiHeight.current)){
  Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,onBack)
   Text("课堂生词",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
   QuietIconButton("搜索生词",AppGlyph.Search,{searching=!searching;if(!searching)query=""})
   QuietIconButton("课堂生词设置",AppGlyph.Settings,{options=true})
  }
  Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
   Text("已掌握",fontSize=12.sp,color=Muted)
   VocabularyLevel.entries.forEach{choice->
    Surface(Modifier.weight(1f).height(32.dp).clickable{level=choice;prefs.level=choice},shape=AppShape(50.dp),color=if(level==choice)AccentSurface else SoftSurface){Box(contentAlignment=Alignment.Center){Text(choice.label,fontSize=12.sp,color=if(level==choice)DutBlue else Muted,fontWeight=if(level==choice)FontWeight.SemiBold else FontWeight.Normal)}}
   }
  }
  if(!dictionary.installed||dictionary.active)Surface(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp),shape=AppShape(10.dp),color=SoftSurface){VocabularyComponentPanel(dictionary,Modifier.padding(horizontal=10.dp,vertical=8.dp))}
  AudioControlBar("新一课",{clear=true},if(VocabularyState.running)"暂停收听"else"开始收听",{start()},"我会的词",{knownDialog=true},active=VocabularyState.running,leftEnabled=!VocabularyState.running,primaryEnabled=ready&&dictionary.installed&&!dictionary.active&&!dictionary.checking&&(!AudioState.running||VocabularyState.running))
  Row(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
   Box(Modifier.size(5.dp).background(if(VocabularyState.running)DutBlue else Muted,CircleShape));Spacer(Modifier.width(6.dp))
   Text(if(dictionary.installed)VocabularyState.status else "尚未安装离线词典 · 历史生词保留",Modifier.weight(1f),fontSize=11.sp,color=Muted,maxLines=2)
   Text("${rows.size}${if(rows.size==600)"+"else""} 词",fontSize=11.sp,color=Muted)
  }
  if(searching)CompactSearchField(query,{query=it.take(100)},"单词或中文释义",Modifier.padding(horizontal=14.dp,vertical=2.dp))
  VocabularyState.error?.let{Text(it,Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=4.dp),color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
  if(rows.isEmpty())Column(Modifier.weight(1f).padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
   Text(if(query.isNotBlank())"没有匹配的生词"else if(VocabularyState.running)"生词会实时出现在最上方"else"开始收听，留住陌生词",fontSize=17.sp,fontWeight=FontWeight.Medium)
   Text(if(query.isNotBlank())"试试英文原词或中文意思。"else"选择已掌握词库，自动略过简单词、熟词和整句。点词条展开释义。",fontSize=13.sp,lineHeight=19.sp,color=Muted)
   if(query.isBlank())Text("音频交给阿里云识别，会产生语音用量；本机仅保留生词。离开本页会继续收听，可从通知栏结束。",fontSize=11.sp,lineHeight=18.sp,color=Muted)
  }else Box(Modifier.weight(1f)){
   LazyColumn(Modifier.fillMaxSize(),state=feed,contentPadding=PaddingValues(horizontal=14.dp,vertical=2.dp)){
   items(rows,key={it.entry.word}){row->
    var expanded by rememberSaveable(row.entry.word){mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().animateItem().clickable{expanded=!expanded}.padding(vertical=6.dp)){
     Row(verticalAlignment=Alignment.CenterVertically){
      Text(row.entry.word,Modifier.weight(1f),fontSize=21.sp,lineHeight=25.sp,fontWeight=FontWeight.SemiBold,color=Ink)
      if(row.count>1)Text("×${row.count}",fontSize=11.sp,color=Muted)
      TextButton(onClick={action{mark(row.entry.word,true)}},contentPadding=PaddingValues(horizontal=6.dp,vertical=0.dp),modifier=Modifier.height(32.dp)){Text("我会了",fontSize=12.sp)}
     }
     if(row.entry.phonetic.isNotBlank())Text("/${row.entry.phonetic}/",fontSize=12.sp,lineHeight=17.sp,color=Muted)
     Text(row.entry.meaning.replace("\\n","；").replace('\n','；'),Modifier.padding(top=3.dp),fontSize=14.sp,lineHeight=20.sp,color=Ink,maxLines=if(expanded)20 else 2,overflow=TextOverflow.Ellipsis)
    }
    HorizontalDivider(color=Hairline,thickness=.5.dp)
   }
   }
   if(unseen.isNotEmpty()&&query.isBlank())FilledTonalButton(
    onClick={scope.launch{feed.animateScrollToItem(0);unseen=emptySet()}},
    modifier=Modifier.align(Alignment.TopCenter).padding(top=6.dp).height(34.dp),
    contentPadding=PaddingValues(horizontal=14.dp,vertical=0.dp)
   ){Text("↑ ${unseen.size} 个新词",fontSize=12.sp)}
  }
  SnackbarHost(snackbar)
 }
 if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("开始新一课？")},text={Text("清空本课生词。你标记的已掌握单词和词库选择会保留。")},confirmButton={TextButton(onClick={clear=false;action{check(!VocabularyState.running);withContext(Dispatchers.IO){VocabularyStore(c).use{it.clear()}};VocabularyState.revision++}}){Text("清空生词")}},dismissButton={TextButton(onClick={clear=false}){Text("取消")}})
 if(knownDialog)AlertDialog(onDismissRequest={knownDialog=false},title={Text("我会的词 · ${known.size}",fontSize=17.sp)},text={
  if(known.isEmpty())Text("在生词旁点“我会了”，以后就不再显示。",fontSize=13.sp)else LazyColumn(Modifier.heightIn(max=400.dp)){
   items(known.sorted(),key={it}){word->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(word,Modifier.weight(1f),fontSize=15.sp);TextButton(onClick={action{mark(word,false)}}){Text("恢复",fontSize=12.sp)}}}
  }
 },confirmButton={TextButton(onClick={knownDialog=false}){Text("完成")}})
 if(options)AlertDialog(onDismissRequest={options=false},title={Text("课堂生词设置",fontSize=17.sp)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
  VocabularyComponentPanel(dictionary,manage=true)
  HorizontalDivider(color=Hairline)
  Text("已掌握词库",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
  Text("基础词始终过滤。高中、四级、六级依次叠加，选择六级也会过滤高中与四级词。标记“我会了”可补充个人掌握情况。",fontSize=12.sp,color=Muted)
  Text("高中 ${stats.optInt("gk")} 词 · 四级 ${stats.optInt("cet4")} 词 · 六级 ${stats.optInt("cet6")} 词\n词表有重叠；按 ECDICT 标签整理，并非官方大纲。",fontSize=11.sp,color=Muted)
  OutlinedTextField(hint,{hint=it.take(400);prefs.context=hint},Modifier.fillMaxWidth(),label={Text("课程术语提示",fontSize=12.sp)},placeholder={Text("如：calculus, eigenvalue, organic chemistry",fontSize=12.sp)},maxLines=4,supportingText={Text("与全局热词共用 400 字符，下次收听生效。",fontSize=10.sp)})
  Text("中英混合识别。音标和释义来自本地词典，未收录的词会略过。",fontSize=12.sp,color=Muted)
  TextButton(onClick={action{license=withContext(Dispatchers.IO){c.assets.open("vocabulary/LICENSE.txt").bufferedReader().use{it.readText()}}}}){Text("词库来源与许可",fontSize=12.sp)}
 }},confirmButton={TextButton(onClick={options=false}){Text("完成")}})
 license?.let{text->AlertDialog(onDismissRequest={license=null},title={Text("ECDICT · MIT",fontSize=17.sp)},text={Column(Modifier.verticalScroll(rememberScrollState())){Text("github.com/skywind3000/ECDICT",fontSize=12.sp);Text(text,fontSize=11.sp)}},confirmButton={TextButton(onClick={license=null}){Text("关闭")}})}
}

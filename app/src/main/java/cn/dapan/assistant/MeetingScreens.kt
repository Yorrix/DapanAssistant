package cn.dapan.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun meetingDate(ms:Long)=Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))

@OptIn(ExperimentalFoundationApi::class)
@Composable fun MeetingsScreen(secrets:Secrets,onBack:()->Unit) {
 val c=LocalContext.current;val scope=rememberCoroutineScope();val library=remember{MeetingLibrary(c)}
 val recordingPreferences=remember{RecordingPreferences(c)}
 var recordingOptions by remember{mutableStateOf(recordingPreferences.load())}
 var audioSettings by remember{mutableStateOf(false)}
 var folderId by rememberSaveable{mutableStateOf<String?>(null)}
 var selectedId by rememberSaveable{mutableStateOf<String?>(null)}
 var viewAll by rememberSaveable{mutableStateOf(true)}
 var tab by rememberSaveable{mutableIntStateOf(0)}
 var filter by rememberSaveable{mutableStateOf("全部")}
 var searching by rememberSaveable{mutableStateOf(false)};var query by rememberSaveable{mutableStateOf("")}
 var message by remember{mutableStateOf<String?>(null)}
 var confirm by remember{mutableStateOf<PendingChange?>(null)}
 var editFolder by remember{mutableStateOf<MeetingFolder?>(null)};var createFolder by remember{mutableStateOf(false)}
 var editClip by remember{mutableStateOf<Clip?>(null)};var editLine by remember{mutableStateOf<Pair<Clip,Int>?>(null)}
 var folderMenu by remember{mutableStateOf(false)};var clipMenu by remember{mutableStateOf(false)};var clipPicker by remember{mutableStateOf(false)}
 var exportClip by remember{mutableStateOf<Clip?>(null)};var exportText by remember{mutableStateOf("")}
 var exportBusy by remember{mutableStateOf(false)};var pendingRecording by remember{mutableStateOf<String?>(null)}
 val player=remember{MeetingPlayback(c){message=it}}
 val lifecycle=(c as ComponentActivity).lifecycle
 DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)player.pause()};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);player.stop()}}
 LaunchedEffect(AudioState.running){if(AudioState.running)player.stop()}
 LaunchedEffect(player.clipId){while(player.clipId!=null){player.tick();delay(250)}}
 var load by rememberPageSnapshot("meeting-list"){Result.success(emptyList<Clip>() to emptyList<MeetingFolder>())}
 var loading by remember{mutableStateOf(true)}
 LaunchedEffect(AudioState.revision,MeetingChanges.revision){
  loading=true
  try{load=withContext(Dispatchers.IO){runCatching{val clips=Recordings.clips(c);clips to library.folders(clips)}}}
  finally{loading=false}
 }
 val all=load.getOrNull()?.first.orEmpty();val folders=load.getOrNull()?.second.orEmpty()
 val summaryPreviews by produceState<Map<String,String>>(emptyMap(),folders.map{it.id},MeetingChanges.revision){
  value=withContext(Dispatchers.IO){folders.mapNotNull{f->runCatching{library.summary(f.id)?.text?.lineSequence()?.firstOrNull{it.isNotBlank()&&!it.startsWith("#")}}.getOrNull()?.let{f.id to it}}.toMap()}
 }
 val folder=folders.firstOrNull{it.id==folderId}
 var orderedFor by remember{mutableStateOf<String?>(null)}
 val ordered by produceState(emptyList<Clip>(),folderId,all,MeetingChanges.revision){
  value=emptyList();orderedFor=null;val id=folderId
  if(id!=null)value=withContext(Dispatchers.IO){runCatching{library.order(id,all)}.getOrDefault(emptyList())}
  orderedFor=id
 }
 val selected=if(viewAll)null else ordered.firstOrNull{it.id==selectedId}?:ordered.lastOrNull()
 val savedState=produceState<MeetingSummary?>(null,folderId,MeetingChanges.revision){
  value=null;val id=folderId
  if(id!=null)value=withContext(Dispatchers.IO){runCatching{library.summary(id)}.getOrNull()}
 }
 val saved=savedState.value
 val currentSummary=remember(saved,ordered,folder?.kind,recordingOptions.summaryHint){saved?.isCurrent(ordered,folder?.kind?:"会议",recordingOptions.summaryHint)==true}
 val recordingHere=AudioState.clipId!=null&&AudioState.session==folderId
 val busyFolder=ordered.any{it.id==AudioState.clipId||it.id==TranscriptionState.runningId}||MeetingSummaryState.session==folderId
 val listState=rememberLazyListState()
 fun exitFolder(){player.stop();folderId=null;selectedId=null;query="";searching=false;tab=0}
 BackHandler(folderId!=null){exitFolder()}
 fun openFolder(id:String,initialQuery:String=""){player.stop();folderId=id;selectedId=null;viewAll=true;query=initialQuery;searching=initialQuery.isNotBlank();tab=0}
 fun choose(clip:Clip){if(viewAll||selectedId!=clip.id)player.stop();viewAll=false;selectedId=clip.id;scope.launch{listState.scrollToItem(0)}}
 fun play(clip:Clip,at:Long?=null){if(AudioState.running){message="请先结束录音或语音输入，再回听";return};player.play(clip,at)}
 fun record(id:String){
  if(AudioState.running){message="麦克风正在使用，请先结束当前录音或语音输入";return}
  player.stop()
  ContextCompat.startForegroundService(c,Intent(c,RecordingService::class.java).putExtra("session",id))
 }
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->val id=pendingRecording;pendingRecording=null;if(granted&&id!=null)record(id)else if(!granted)message="未获得麦克风权限，已有记录仍可查看"}
 fun startRecording(){val id=folder?.id?:return;if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){pendingRecording=id;permission.launch(Manifest.permission.RECORD_AUDIO)}else record(id)}
 fun action(block:()->Unit){try{block()}catch(e:Exception){message=e.message?:"操作未完成"}}
 fun copy(text:String){(c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("录音摘要",text));message="已复制"}
 val audioExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")){uri->val clip=exportClip;if(uri!=null&&clip!=null)scope.launch{exportBusy=true;try{withContext(Dispatchers.IO){c.contentResolver.openOutputStream(uri)?.use{out->clip.audio.inputStream().use{it.copyTo(out)}}?:error("文件无法写入")};message="音频已导出"}catch(e:Exception){message=e.message}finally{exportBusy=false}}}
 val textExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->val text=exportText;if(uri!=null)scope.launch{exportBusy=true;try{withContext(Dispatchers.IO){c.contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray())}?:error("文件无法写入")};message="文本已导出"}catch(e:Exception){message=e.message}finally{exportBusy=false}}}
 val markdownExport=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")){uri->val text=exportText;if(uri!=null)scope.launch{exportBusy=true;try{withContext(Dispatchers.IO){c.contentResolver.openOutputStream(uri)?.use{it.write(text.toByteArray())}?:error("文件无法写入")};message="摘要已导出为 Markdown"}catch(e:Exception){message=e.message}finally{exportBusy=false}}}
 fun summarize(){
  val f=folder?:return
  if(!SharedAi.canUseText(c)){message=SharedAi.unavailableMessage(c);return}
  if(ordered.none{it.lines.isNotEmpty()}){message="还没有可总结的文字，请先录音转写或补转写";return}
  confirm=PendingChange("将“${f.title}”的 ${ordered.size} 段录音转写发送给 DeepSeek，按${f.kind}整理摘要与可回听的来源。长内容会分段处理并产生多次请求；完成后替换原摘要，失败时保留原摘要。"){
   tab=1
   ContextCompat.startForegroundService(c,Intent(c,MeetingSummaryService::class.java).putExtra("session",f.id))
  }
 }
 LaunchedEffect(MeetingSummaryState.message){MeetingSummaryState.message?.let{message=it;MeetingSummaryState.message=null}}
 LaunchedEffect(TranscriptionState.message){TranscriptionState.message?.let{message=it;TranscriptionState.message=null}}
 LaunchedEffect(AudioState.error,AudioState.running){if(!AudioState.running&&AudioState.session==folderId)AudioState.error?.let{message=it}}
 LaunchedEffect(folderId,AudioState.clipId){if(recordingHere){if(!viewAll)selectedId=AudioState.clipId;tab=0}}

 AssistantSurface("会议纪要",org.json.JSONObject().put("ready",!loading&&(folderId==null||orderedFor==folderId)).put("folderId",folder?.id).put("clipId",selected?.id).put("all",viewAll).put("selectedClipIds",org.json.JSONArray((if(viewAll)ordered else listOfNotNull(selected)).map{it.id})).put("clips",org.json.JSONArray(ordered.mapIndexed{index,clip->org.json.JSONObject().put("id",clip.id).put("number",index+1).put("name",clip.name)})).put("query",query).put("tab",if(tab==0)"转写"else"摘要").put("recording",recordingHere).put("folders",org.json.JSONArray(folders.map{org.json.JSONObject().put("id",it.id).put("title",it.title)})),listOf(
  surfaceTool("configure","设置录音摘要：language识别语言、asrContext识别术语最多400字、summaryHint摘要偏好最多1200字；只影响后续录音和摘要","language,asrContext,summaryHint"){a->
   val language=if(a.has("language"))a.onlyChoice("language",TranslationLanguages.names.toList())else recordingOptions.language
   val asr=a.optString("asrContext",recordingOptions.asrContext);val hint=a.optString("summaryHint",recordingOptions.summaryHint)
   require(asr.length<=400&&hint.length<=1200){"识别术语最多400字，摘要偏好最多1200字"}
   prepared("调整录音摘要设置",a){recordingPreferences.save(RecordingOptions(language,asr,hint));recordingOptions=recordingPreferences.load();"已保存，下次录音或生成摘要时使用"}
  },
  surfaceTool("create","新建录音记录，title名称，kind=课程/会议/其他","title,kind"){a->val title=a.text("title",120);val kind=a.onlyChoice("kind",listOf("课程","会议","其他"));prepared("新建 $title",a){val id=library.create(title,kind);openFolder(id);"已新建 $kind：$title"}},
  surfaceTool("view","查看录音记录：folderId；clipId选单条，all=true选全部；不传两者则保持当前范围。query仅在选中范围搜索；tab=转写/摘要","folderId,clipId,all,query,tab",false){a->
   val id=a.optString("folderId",folder?.id.orEmpty());require(folders.any{it.id==id}){"请指定录音记录"}
   val clip=a.optString("clipId");require(clip.isBlank()||all.any{it.id==clip&&it.session==id})
   val allMode=if(a.has("all"))a.getBoolean("all")else if(clip.isNotBlank())false else if(id==folderId)viewAll else true
   require(!allMode||clip.isBlank()){"全部与单条录音不能同时选择"}
   val target=clip.ifBlank{if(id==folderId)selected?.id.orEmpty()else ""};require(allMode||target.isNotBlank()){"请选择录音或全部"}
   val t=a.optString("tab","转写");require(t in listOf("转写","摘要"))
   prepared("查看录音内容",a){if(folderId!=id)openFolder(id);viewAll=allMode;selectedId=target.ifBlank{null};query=a.optString("query",query);searching=query.isNotBlank();tab=if(t=="摘要")1 else 0;"已按选中范围查看录音内容"}
  },
  surfaceTool("record_start","开始录音并保存到folderId；如需新建同时录音则提供title和kind；可能产生ASR费用","folderId,title,kind"){a->require(!AudioState.running){"麦克风正在使用"};val destination=AssistantRecordingTarget.resolve(a,folder?.id,folders);prepared("开始录音：${destination.title}",a){val target=destination.folderId?:library.create(destination.title,destination.kind);openFolder(target);if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){pendingRecording=target;permission.launch(Manifest.permission.RECORD_AUDIO);"已请求麦克风权限，授权后开始录音"}else{record(target);"已请求开始录音，状态以录音指示为准"}}},
  surfaceTool("record_stop","结束会议录音，不停止其他功能的语音输入","",true){a->require(AudioState.clipId!=null){"当前没有会议录音"};prepared("结束会议录音",a){c.startService(Intent(c,RecordingService::class.java).setAction("stop"));"已请求结束录音并保存"}},
  surfaceTool("summarize","生成或更新folderId的摘要，发送转写给DeepSeek并计费","folderId"){a->val id=a.optString("folderId",folder?.id.orEmpty());val f=folders.firstOrNull{it.id==id}?:error("请指定会议");val clips=library.order(id,all);require(clips.any{it.lines.isNotEmpty()}&&clips.none{it.id==AudioState.clipId||it.id==TranscriptionState.runningId}){"请先完成录音和转写"};require(MeetingSummaryState.session==null){ "请先等待当前摘要完成" };SharedAi.requireTextAvailable(c);prepared("生成 ${f.title} 的摘要（云端）",a){ContextCompat.startForegroundService(c,Intent(c,MeetingSummaryService::class.java).putExtra("session",id));openFolder(id);tab=1;"已启动摘要生成，完成后会保存；尚未生成完成"}},
  surfaceTool("summary_cancel","取消正在进行的摘要任务","",true){a->require(MeetingSummaryState.session!=null);prepared("取消摘要生成",a){c.startService(Intent(c,MeetingSummaryService::class.java).setAction("cancel"));"已请求取消摘要，原摘要保留"}},
  surfaceTool("play","回听clipId，atMs为毫秒起点（默认0）","clipId,atMs",false){a->val clip=all.firstOrNull{it.id==a.text("clipId")}?:error("录音不存在");val at=a.optLong("atMs",0);require(at in 0..clip.durationMs()&&!AudioState.running);prepared("回听 ${clip.name}",a){if(folderId!=clip.session)openFolder(clip.session);selectedId=clip.id;player.play(clip,at);"已请求从 ${audioTime(at)} 回听"}},
  surfaceTool("pause","暂停当前回听","",false){a->prepared("暂停回听",a){player.pause();"已暂停回听"}},
  surfaceTool("rename","folderId重命名会议或clipId重命名录音，两者只填一个，name新名称","folderId,clipId,name"){a->val name=a.text("name",120);val cid=a.optString("clipId");val fid=a.optString("folderId");require(cid.isBlank()!=fid.isBlank());val clip=all.firstOrNull{it.id==cid};val f=folders.firstOrNull{it.id==fid};require(clip!=null||f!=null);require(clip==null||clip.id!=AudioState.clipId&&clip.id!=TranscriptionState.runningId);prepared("重命名为 $name",a){if(clip!=null)Recordings.rename(c,clip,name)else library.rename(f!!,name,f.kind);"名称已更新"}},
  surfaceTool("reorder","folderId及其全部clipIds数组，按希望顺序排列","folderId,clipIds"){a->val f=folders.firstOrNull{it.id==a.text("folderId")}?:error("会议不存在");val ids=a.getJSONArray("clipIds").objectsAsText();val actual=all.filter{it.session==f.id}.map{it.id};require(ids.size==actual.size&&ids.toSet()==actual.toSet());prepared("调整录音顺序",a){library.saveOrder(f,ids);"录音顺序已更新"}},
  surfaceTool("transcribe","向阿里云发送clipId音频进行补转写/重转写，旧文字保留一份","clipId"){a->val clip=all.firstOrNull{it.id==a.text("clipId")}?:error("录音不存在");require(clip.id!=AudioState.clipId&&TranscriptionState.runningId==null);require(secrets.get("aliyun").isNotBlank()){ "请配置阿里云密钥" };prepared("重新转写 ${clip.name}（云端）",a){ContextCompat.startForegroundService(c,Intent(c,TranscriptionService::class.java).putExtra("clip",clip.id));"已启动补转写；完成后替换文字，原音频保留"}},
  surfaceTool("edit_line","修正某句转写：clipId、startMs原时间戳、text新文字","clipId,startMs,text"){a->val clip=all.firstOrNull{it.id==a.text("clipId")}?:error("录音不存在");require(clip.id!=AudioState.clipId&&clip.id!=TranscriptionState.runningId);val start=a.getLong("startMs");val index=clip.lines.indexOfFirst{it.start==start};require(index>=0);val text=a.text("text",20000);PreparedTool("修正 ${clip.name} 转写",clip.lines[index].text+" → "+text,{check(Recordings.clips(c).firstOrNull{it.id==clip.id}?.lines==clip.lines){"转写已经变化"};Recordings.replaceLines(c,clip.id,clip.lines.toMutableList().also{it[index]=it[index].copy(text=text)});"转写已修正，摘要可能需要更新"})},
  surfaceTool("restore_text","恢复clipId上次转写文本，原音频保持不变","clipId"){a->val clip=all.firstOrNull{it.id==a.text("clipId")}?:error("录音不存在");require(clip.id!=AudioState.clipId&&clip.id!=TranscriptionState.runningId&&MeetingSummaryState.session!=clip.session);prepared("恢复 ${clip.name} 上次文字",a){Recordings.restorePrevious(c,clip.id);"已恢复上次文字，摘要可能需要更新"}},
  surfaceTool("export","导出会议转写/摘要或单段音频/转写，folderId或clipId，format=audio/transcript/summary；需手动选择保存位置","folderId,clipId,format"){a->require(!exportBusy);val format=a.onlyChoice("format",listOf("audio","transcript","summary"));val cid=a.optString("clipId");val clip=all.firstOrNull{it.id==cid};val f=folders.firstOrNull{it.id==(clip?.session?:a.optString("folderId"))}?:error("请指定会议或录音");if(format=="audio")require(clip!=null&&clip.id!=AudioState.clipId);val output=when(format){"summary"->library.summary(f.id)?.text?:error("尚无摘要");"transcript"->if(clip!=null)clip.transcriptText()else RecordingTranscript.export(library.order(f.id,all));else->""};prepared("导出 ${clip?.name?:f.title}",a){if(format=="audio"){exportClip=clip;audioExport.launch("${clip!!.name}.wav")}else{exportText=if(format=="summary")RecordingMarkdown.normalize(output)else output;if(format=="summary")markdownExport.launch("${f.title}-摘要.md")else textExport.launch("${clip?.name?:f.title}.txt")};"已打开保存位置选择器，等待你选择后导出"}},
  surfaceTool("delete_folder","永久删除folderId会议及其所有录音、转写、摘要","folderId"){a->val f=folders.firstOrNull{it.id==a.text("folderId")}?:error("会议不存在");val clips=library.order(f.id,all);require(AudioState.session!=f.id||!AudioState.running);require(MeetingSummaryState.session!=f.id&&clips.none{it.id==TranscriptionState.runningId});PreparedTool("永久删除 ${f.title}","包括 ${clips.size} 段录音、全部转写及摘要；不可恢复",{check(Recordings.clips(c).filter{it.session==f.id}.map{it.id}.toSet()==clips.map{it.id}.toSet()){ "会议内容已变化" };player.stop();clips.forEach{Recordings.delete(c,it)};library.remove(f);if(folderId==f.id)exitFolder();"会议及全部关联内容已删除"})},
  surfaceTool("delete_clip","永久删除clipId对应音频和文字","clipId"){a->val clip=all.firstOrNull{it.id==a.text("clipId")}?:error("录音不存在");require(clip.id!=AudioState.clipId&&clip.id!=TranscriptionState.runningId&&MeetingSummaryState.session!=clip.session);prepared("永久删除 ${clip.name}",a){if(player.clipId==clip.id)player.stop();Recordings.delete(c,clip);"录音及文字已删除"}}
 ))
 Column(Modifier.fillMaxSize().padding(bottom=LocalFloatingAiHeight.current)){
  Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,{if(folderId!=null)exitFolder()else onBack()})
   Text(folder?.title?:"录音摘要",Modifier.weight(1f),fontSize=17.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold,color=Ink,maxLines=1,overflow=TextOverflow.Ellipsis)
   QuietIconButton("搜索转写",AppGlyph.Search,{searching=!searching;if(!searching)query=""else if(folder!=null)tab=0})
   QuietIconButton("录音摘要设置",AppGlyph.Settings,{audioSettings=true})
   if(folder==null)QuietIconButton("新建录音记录",AppGlyph.Add,{createFolder=true})else Box{
    TextButton(onClick={folderMenu=true},contentPadding=PaddingValues(0.dp),modifier=Modifier.width(36.dp)){Text("•••",color=Ink)}
    DropdownMenu(folderMenu,{folderMenu=false}){
     DropdownMenuItem(text={Text("${folder.kind} · ${ordered.size} 次录音 · ${audioTime(ordered.sumOf{it.durationMs()})}",fontSize=12.sp,lineHeight=16.sp,color=Muted)},onClick={},enabled=false)
     DropdownMenuItem(text={Text("使用说明")},onClick={folderMenu=false;message="点文字回听，长按修正。上方控制栏添加录音，自动保存在当前记录；未配置阿里云密钥时只保存音频。"})
     DropdownMenuItem(text={Text("名称与类型")},onClick={folderMenu=false;editFolder=folder},enabled=!busyFolder)
     DropdownMenuItem(text={Text("导出全部转写")},onClick={folderMenu=false;exportText=folder.title+"\n\n"+RecordingTranscript.export(ordered);textExport.launch("${folder.title}-转写.txt")},enabled=ordered.any{it.lines.isNotEmpty()}&&!exportBusy)
     DropdownMenuItem(text={Text("删除记录",color=Coral)},onClick={folderMenu=false;confirm=PendingChange("永久删除“${folder.title}”以及其中 ${ordered.size} 段本地录音、转写与摘要？"){
      require(!AudioState.running||AudioState.session!=folder.id){"请先结束录音"};require(MeetingSummaryState.session!=folder.id&&ordered.none{it.id==TranscriptionState.runningId}){"请先结束处理任务"};player.stop();ordered.forEach{Recordings.delete(c,it)};library.remove(folder);exitFolder()
     }},enabled=!busyFolder)
    }
   }
  }
  if(searching)Row(Modifier.padding(horizontal=12.dp).fillMaxWidth().background(SoftSurface,AppShape(8.dp)).heightIn(min=40.dp).padding(start=10.dp),verticalAlignment=Alignment.CenterVertically){
   BasicTextField(query,{query=it},Modifier.weight(1f).semantics{contentDescription="搜索转写"},singleLine=true,textStyle=TextStyle(fontSize=14.sp,color=Ink),decorationBox={field->Box{if(query.isEmpty())Text(if(folder==null)"名称或转写关键词"else if(viewAll)"搜索全部录音"else"搜索第 ${ordered.indexOf(selected)+1} 次录音",fontSize=13.sp,lineHeight=17.sp,color=Muted);field()}})
   MeetingAction("取消搜索",onClick={query="";searching=false}){LineIcon(AppGlyph.Close,Modifier.size(16.dp),Muted)}
  }
  if(load.isFailure)MeetingStatus("记录暂时无法读取，原文件未改动：${load.exceptionOrNull()?.message}"){}
  message?.let{MeetingStatus(it){message=null}}
  if(folder==null){
   Row(Modifier.padding(horizontal=12.dp,vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf("全部","课程","会议","其他").forEach{label->MeetingPill(label,filter==label){filter=label}}}
   val shown=folders.filter{f->(filter=="全部"||f.kind==filter)&&(query.isBlank()||f.title.contains(query,true)||all.any{clip->clip.session==f.id&&clip.lines.any{it.text.contains(query,true)}})}
   LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=12.dp)){
    if(shown.isEmpty())item{MeetingEmpty(if(folders.isEmpty())"还没有录音记录"else"没有找到匹配记录",if(folders.isEmpty())"点右上角 + 新建，同一条记录可连续添加多段录音。"else"试试其他名称或转写关键词。")}
    items(shown,key={it.id}){f->
     val clips=all.filter{it.session==f.id};val active=AudioState.session==f.id&&AudioState.clipId!=null
     Column(Modifier.fillMaxWidth().clickable{openFolder(f.id,query.takeIf{q->q.isNotBlank()&&clips.any{clip->clip.lines.any{it.text.contains(q,true)}}}.orEmpty())}.padding(vertical=8.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){Text(f.title,fontSize=15.sp,lineHeight=19.sp,fontWeight=FontWeight.SemiBold,color=Ink,modifier=Modifier.weight(1f).padding(end=8.dp),maxLines=2,overflow=TextOverflow.Ellipsis);Text(if(active)"● 录音中"else if(MeetingSummaryState.session==f.id)"整理中"else f.kind,fontSize=11.sp,lineHeight=15.sp,color=if(active)Coral else Muted)}
      Text("${clips.size} 次录音 · ${audioTime(clips.sumOf{it.durationMs()})} · ${meetingDate(clips.maxOfOrNull{it.created}?:f.created)}",fontSize=11.sp,lineHeight=15.sp,color=Muted,modifier=Modifier.padding(top=2.dp))
      val preview=if(query.isNotBlank())clips.flatMap{it.lines}.firstOrNull{it.text.contains(query,true)}?.text else summaryPreviews[f.id]
      val excerpt=preview?:clips.firstNotNullOfOrNull{it.lines.firstOrNull()?.text}
      if(excerpt!=null)Text(excerpt,fontSize=12.sp,lineHeight=16.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=2.dp))
     };HorizontalDivider(color=Hairline)
    }
   }
  }else{
   Row(Modifier.fillMaxWidth().heightIn(min=40.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
    MeetingTab("转写",tab==0){tab=0};MeetingTab("摘要",tab==1){tab=1}
    if(tab==0&&ordered.isNotEmpty()){
     Spacer(Modifier.width(12.dp))
     Box(Modifier.weight(1f)){
      MeetingAction("选择录音",Modifier.fillMaxWidth(),onClick={clipPicker=true}) {Text(if(viewAll)"全部录音 (${ordered.size}) ▾"else"第 ${ordered.indexOf(selected)+1} 次 ▾",fontSize=12.sp,lineHeight=16.sp,color=Ink,maxLines=1,overflow=TextOverflow.Ellipsis)}
      DropdownMenu(clipPicker,{clipPicker=false}){
       DropdownMenuItem(text={Text((if(viewAll)"✓ "else"")+"全部录音 · ${ordered.size} 次",fontSize=13.sp)},onClick={clipPicker=false;player.stop();viewAll=true;scope.launch{listState.scrollToItem(0)}})
       HorizontalDivider(color=Hairline)
       ordered.forEachIndexed{index,clip->DropdownMenuItem(text={Column{Text((if(!viewAll&&selected?.id==clip.id)"✓ "else"")+"第 ${index+1} 次 · ${clip.name}",fontSize=13.sp);Text("${meetingDate(clip.created)} · ${audioTime(clip.durationMs())}",fontSize=10.sp,lineHeight=14.sp,color=Muted)}},onClick={clipPicker=false;choose(clip)})}
      }
     }
     selected?.let{clip->val locked=clip.id==AudioState.clipId||clip.id==TranscriptionState.runningId
      Box{MeetingAction("管理当前录音",onClick={clipMenu=true}){Text("管理",fontSize=12.sp,lineHeight=16.sp,color=Muted)}
       DropdownMenu(clipMenu,{clipMenu=false}){
        DropdownMenuItem(text={Text("重命名")},onClick={clipMenu=false;editClip=clip},enabled=!locked)
        listOf("上移" to -1,"下移" to 1).forEach{(label,delta)->val index=ordered.indexOf(clip);DropdownMenuItem(text={Text(label)},onClick={clipMenu=false;action{val ids=ordered.map{it.id}.toMutableList();java.util.Collections.swap(ids,index,index+delta);library.saveOrder(folder,ids)}},enabled=!busyFolder&&index+delta in ordered.indices)}
        DropdownMenuItem(text={Text(if(clip.lines.isEmpty())"补转写"else"重新转写")},onClick={clipMenu=false;confirm=PendingChange("将“${clip.name}”音频发送至阿里云转写，可能产生费用，耗时接近录音时长。完成后替换文字，原文字保留一份可恢复副本。") {require(secrets.get("aliyun").isNotBlank()){"请先配置阿里云 API Key"};ContextCompat.startForegroundService(c,Intent(c,TranscriptionService::class.java).putExtra("clip",clip.id))}},enabled=!locked&&TranscriptionState.runningId==null)
        DropdownMenuItem(text={Text("导出音频")},onClick={clipMenu=false;exportClip=clip;audioExport.launch("${clip.name}.wav")},enabled=!locked&&!exportBusy)
        DropdownMenuItem(text={Text("导出转写")},onClick={clipMenu=false;exportText=folder.title+"\n"+clip.name+"\n\n"+clip.transcriptText();textExport.launch("${clip.name}.txt")},enabled=clip.lines.isNotEmpty()&&!exportBusy)
        DropdownMenuItem(text={Text("恢复上次文字")},onClick={clipMenu=false;confirm=PendingChange("恢复“${clip.name}”上次保存前的文字，录音保持不变。") {Recordings.restorePrevious(c,clip.id)}},enabled=!locked)
        DropdownMenuItem(text={Text("删除录音",color=Coral)},onClick={clipMenu=false;confirm=PendingChange("永久删除“${clip.name}”及其转写？") {if(player.clipId==clip.id)player.stop();Recordings.delete(c,clip);selectedId=null}},enabled=!locked&&MeetingSummaryState.session!=folder.id)
       }
      }
     }
    }
   }
   AudioControlBar("回听",{(selected?:ordered.firstOrNull())?.let{play(it)}},if(recordingHere)"结束录音"else"开始录音",{
    if(recordingHere)c.startService(Intent(c,RecordingService::class.java).setAction("stop"))else startRecording()
   },if(saved==null)"生成摘要"else"更新摘要",{summarize()},active=recordingHere,leftEnabled=!AudioState.running&&ordered.isNotEmpty(),primaryEnabled=if(recordingHere)AudioState.ready else !AudioState.running,rightEnabled=MeetingSummaryState.session==null&&!busyFolder&&ordered.any{it.lines.isNotEmpty()})
   if(recordingHere){
    HorizontalDivider(color=Hairline)
    var now by remember{mutableLongStateOf(System.currentTimeMillis())};LaunchedEffect(Unit){while(true){now=System.currentTimeMillis();delay(1000)}}
    val active=ordered.firstOrNull{it.id==AudioState.clipId}
    Row(Modifier.fillMaxWidth().heightIn(min=30.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
     Text("● ${audioTime(now-(active?.created?:now))}",fontSize=12.sp,lineHeight=16.sp,color=Coral,fontWeight=FontWeight.Medium)
     Text(if(!AudioState.ready)"正在保存"else if(AudioState.connectionReady)"录音 · 实时转写"else"录音 · 仅音频",Modifier.weight(1f).padding(start=10.dp),fontSize=11.sp,lineHeight=15.sp,color=Muted,maxLines=1)
    }
    AudioState.error?.let{Text(it,fontSize=10.sp,lineHeight=14.sp,color=Coral,modifier=Modifier.padding(horizontal=16.dp).padding(bottom=4.dp),maxLines=2,overflow=TextOverflow.Ellipsis)}
   }
   HorizontalDivider(color=Hairline)
   if(tab==0){
    if(ordered.any{it.id==TranscriptionState.runningId}){
     Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text("补转写 ${(TranscriptionState.progress*100).toInt()}%",fontSize=11.sp,lineHeight=15.sp,color=DutBlue);LinearProgressIndicator(progress={TranscriptionState.progress},modifier=Modifier.weight(1f).padding(horizontal=8.dp));TextButton(onClick={c.startService(Intent(c,TranscriptionService::class.java).setAction("cancel"))}){Text("取消",fontSize=11.sp)}}
    }
    val sections=remember(ordered,viewAll,selected?.id,query){RecordingTranscript.sections(ordered,viewAll,selected?.id,query)}
    val rowCount=sections.sumOf{it.lines.size}
    val timeGutter=if(sections.any{section->section.lines.any{it.value.start>=3600000}})56.dp else 44.dp
    val liveInScope=recordingHere&&(viewAll||selected?.id==AudioState.clipId)
    val liveVisible=liveInScope&&AudioState.partial.isNotBlank()&&(query.isBlank()||AudioState.partial.contains(query.trim(),true))
    if(searching)Text("${if(viewAll)"全部 ${ordered.size} 次录音"else"第 ${ordered.indexOf(selected)+1} 次录音"} · ${if(query.isBlank())"输入关键词搜索"else"找到 $rowCount 处"}",Modifier.padding(horizontal=14.dp,vertical=3.dp),fontSize=10.sp,lineHeight=14.sp,color=Muted)
    val scopeKey="$folderId|$viewAll|${selected?.id}|$query"
    var lastScope by rememberSaveable{mutableStateOf(scopeKey)}
    LaunchedEffect(scopeKey){if(lastScope!=scopeKey){listState.scrollToItem(0);lastScope=scopeKey}}
    LaunchedEffect(rowCount,AudioState.partial){
     if(liveInScope&&query.isBlank()&&!listState.isScrollInProgress){
      val last=listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?:0
      val count=listState.layoutInfo.totalItemsCount
      if(last>=(count-4).coerceAtLeast(0)){withFrameNanos{};listState.animateScrollToItem((listState.layoutInfo.totalItemsCount-1).coerceAtLeast(0))}
     }
    }
    LazyColumn(Modifier.weight(1f),state=listState,contentPadding=PaddingValues(horizontal=12.dp,vertical=4.dp)){
     if(sections.isEmpty()&&!liveVisible)item{MeetingEmpty(if(query.isNotBlank())"没有匹配的转写"else"还没有录音",if(query.isNotBlank())"换个关键词，或切换上方的录音范围。"else"点上方开始录音，录音会保存在这里。")}
     sections.forEach{section->val clip=section.clip
      if(viewAll)stickyHeader(key="header:${clip.id}"){
       Row(Modifier.fillMaxWidth().background(Paper).padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
        Text("第 ${section.number} 次",fontSize=11.sp,lineHeight=15.sp,color=DutBlue,fontWeight=FontWeight.Medium)
        Text(clip.name,Modifier.weight(1f).padding(horizontal=8.dp),fontSize=11.sp,lineHeight=15.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        Text(audioTime(clip.durationMs()),fontSize=10.sp,lineHeight=14.sp,color=Muted)
       }
      }
      if(section.lines.isEmpty()&&query.isBlank())item(key="empty:${clip.id}"){Text(if(clip.id==AudioState.clipId)"正在收听…"else"暂无转写 · 可选择这条录音，在管理中补转写",Modifier.padding(vertical=10.dp,horizontal=8.dp),fontSize=12.sp,lineHeight=16.sp,color=Muted)}
      items(section.lines,key={"${clip.id}:${it.index}"}){indexed->val index=indexed.index;val line=indexed.value
       val highlight=player.clipId==clip.id&&player.position>=line.start&&player.position<line.end
       Row(Modifier.fillMaxWidth().background(if(highlight)AccentSurface else androidx.compose.ui.graphics.Color.Transparent,AppShape(4.dp)).combinedClickable(onClick={play(clip,line.start)},onLongClick={if(clip.id!=AudioState.clipId&&clip.id!=TranscriptionState.runningId)editLine=clip to index else message="请等这段录音或转写结束后再修正文字"}).padding(vertical=4.dp)){
        Text(audioTime(line.start).removePrefix("00:"),Modifier.width(timeGutter).padding(top=3.dp,end=6.dp),fontSize=10.sp,lineHeight=14.sp,color=if(highlight)DutBlue else Muted,maxLines=1)
        Text(meetingHighlighted(line.text,query.trim()),Modifier.weight(1f),fontSize=14.sp,lineHeight=20.sp,color=Ink)
       }
      }
      if(liveVisible&&clip.id==AudioState.clipId)item(key="partial:${clip.id}"){Text(AudioState.partial,fontSize=14.sp,lineHeight=20.sp,color=Muted,modifier=Modifier.padding(start=timeGutter,top=4.dp,bottom=4.dp))}
     }
     if(liveVisible&&sections.none{it.clip.id==AudioState.clipId})item(key="live-only"){Text("第 ${ordered.indexOfFirst{it.id==AudioState.clipId}+1} 次 · ${AudioState.partial}",Modifier.padding(8.dp),fontSize=14.sp,color=Muted)}
    }
    val awayFromLatest by remember{derivedStateOf{listState.canScrollForward}}
    if(liveInScope&&rowCount>0&&query.isBlank()&&awayFromLatest)MeetingAction("跳至最新转写",Modifier.align(Alignment.End),onClick={scope.launch{listState.animateScrollToItem((listState.layoutInfo.totalItemsCount-1).coerceAtLeast(0))}}){Text("最新转写 ↓",fontSize=11.sp,lineHeight=15.sp,color=DutBlue)}
   }else{
    if(MeetingSummaryState.session==folder.id)Row(Modifier.padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp);Text(MeetingSummaryState.progress,Modifier.weight(1f).padding(start=8.dp),fontSize=11.sp,lineHeight=15.sp,color=Muted);TextButton(onClick={c.startService(Intent(c,MeetingSummaryService::class.java).setAction("cancel"))}){Text("取消",fontSize=11.sp)}}
    if(saved!=null){
     Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){Text(if(!currentSummary)"原文或摘要偏好已变更 · 建议更新"else "AI 摘要 · 点时间回听",fontSize=10.sp,lineHeight=14.sp,color=if(!currentSummary)Coral else Muted,modifier=Modifier.weight(1f));MeetingAction("复制摘要",onClick={copy(saved.text)}){Text("复制",fontSize=11.sp,lineHeight=15.sp,color=DutBlue)};MeetingAction("导出摘要",enabled=!exportBusy,onClick={exportText=RecordingMarkdown.normalize(saved.text);markdownExport.launch("${folder.title}-摘要.md")}){Text("导出",fontSize=11.sp,lineHeight=15.sp,color=DutBlue)}}
     LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(horizontal=16.dp,vertical=4.dp)){
      item(key="markdown-summary"){MarkdownContent(saved.text,Modifier.fillMaxWidth()){ref,time->
       val id=saved.clipIds.getOrNull(ref-1);val clip=ordered.firstOrNull{it.id==id}
       if(clip==null)message="该摘要对应的录音已不存在，或旧摘要没有可回听标记"else if(time>clip.durationMs())message="该时间标记超出录音，请查看原文核对"else{play(clip,time)}
      }}
     }
    }else Box(Modifier.weight(1f).fillMaxWidth()){MeetingEmpty("还没有摘要","完成转写后，点上方“生成摘要”。")}
   }
   if(!recordingHere){
    // Keep playback visible on both transcript and summary tabs so citations can be replayed immediately.
    val playbackClip=ordered.firstOrNull{it.id==player.clipId}?:selected
    if(playbackClip!=null&&!AudioState.running){
     HorizontalDivider(color=Hairline)
     MeetingPlayerBar(playbackClip,ordered.indexOf(playbackClip)+1,player,{player.toggle(playbackClip)})
    }
   }
  }
 }
 if(audioSettings)AudioSettingsDialog("录音摘要设置",recordingOptions.asrContext,{value->action{recordingPreferences.save(recordingOptions.copy(asrContext=value));recordingOptions=recordingPreferences.load()}},recordingOptions.summaryHint,{value->action{recordingPreferences.save(recordingOptions.copy(summaryHint=value));recordingOptions=recordingPreferences.load()}},"摘要","如：重点整理考试要点；列出定义、例题和易错点；用中文总结",1200,{audioSettings=false},before={
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("识别语言",Modifier.weight(1f),fontSize=13.sp);LanguageChoice(recordingOptions.language,true){value->action{recordingPreferences.save(recordingOptions.copy(language=value));recordingOptions=recordingPreferences.load()}}}
 },after={Text("设置用于本功能的所有记录。识别提示也用于补转写；摘要偏好用于分段整理与最终合并，保留 Markdown 和回听引用。",fontSize=11.sp,lineHeight=16.sp,color=Muted)})
 if(createFolder||editFolder!=null){
  val editing=editFolder;var name by remember(editing){mutableStateOf(editing?.title.orEmpty())};var kind by remember(editing){mutableStateOf(editing?.kind?:"会议")}
  AlertDialog(onDismissRequest={createFolder=false;editFolder=null},title={Text(if(editing==null)"新建录音记录"else"名称与类型")},text={Column{Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("会议","课程","其他").forEach{MeetingPill(it,kind==it){kind=it}}};OutlinedTextField(name,{name=it},singleLine=true,label={Text("名称")},modifier=Modifier.padding(top=8.dp));Text("多段录音归在同一条记录中。",fontSize=11.sp,lineHeight=15.sp,color=Muted,modifier=Modifier.padding(top=6.dp))}},confirmButton={TextButton(onClick={action{if(editing==null)openFolder(library.create(name,kind))else library.rename(editing,name,kind);createFolder=false;editFolder=null}},enabled=name.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick={createFolder=false;editFolder=null}){Text("取消")}})
 }
 editClip?.let{clip->var name by remember(clip.id){mutableStateOf(clip.name)};AlertDialog(onDismissRequest={editClip=null},title={Text("录音名称")},text={OutlinedTextField(name,{name=it},singleLine=true)},confirmButton={TextButton(onClick={action{Recordings.rename(c,clip,name);editClip=null}},enabled=name.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick={editClip=null}){Text("取消")}})}
 editLine?.let{(clip,index)->var text by remember(clip.id,index){mutableStateOf(clip.lines[index].text)};AlertDialog(onDismissRequest={editLine=null},title={Text("修正转写 · ${audioTime(clip.lines[index].start)}")},text={OutlinedTextField(text,{text=it},minLines=3,maxLines=8)},confirmButton={TextButton(onClick={action{require(clip.id!=AudioState.clipId&&clip.id!=TranscriptionState.runningId){"请先结束转写"};val fresh=Recordings.clips(c).first{it.id==clip.id};require(fresh.lines==clip.lines){"文字已更新，请重新打开编辑"};Recordings.replaceLines(c,clip.id,clip.lines.toMutableList().also{it[index]=it[index].copy(text=text.trim())});editLine=null}},enabled=text.isNotBlank()){Text("保存")}},dismissButton={TextButton(onClick={editLine=null}){Text("取消")}})}
 confirm?.let{plan->AlertDialog(onDismissRequest={confirm=null},title={Text("确认操作")},text={Text(plan.description,fontSize=14.sp)},confirmButton={TextButton(onClick={action{plan.apply()};confirm=null}){Text("确认")}},dismissButton={TextButton(onClick={confirm=null}){Text("取消")}})}
}

@Composable private fun MeetingPill(label:String,selected:Boolean,onClick:()->Unit){Surface(onClick=onClick,color=if(selected)AccentSurface else SoftSurface,shape=RoundedCornerShape(50)){Text(label,color=if(selected)DutBlue else Muted,fontSize=12.sp,lineHeight=16.sp,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal,modifier=Modifier.padding(horizontal=14.dp,vertical=7.dp))}}
@Composable private fun MeetingAction(label:String,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit,content:@Composable RowScope.()->Unit){
 Row(modifier.heightIn(min=40.dp).widthIn(min=40.dp).alpha(if(enabled)1f else .4f).semantics{contentDescription=label}.clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center,content=content)
}
@Composable private fun MeetingTab(label:String,active:Boolean,onClick:()->Unit){
 Column(Modifier.width(48.dp).semantics{selected=active}.clickable(role=Role.Tab,onClick=onClick),horizontalAlignment=Alignment.CenterHorizontally){
  Box(Modifier.heightIn(min=38.dp),contentAlignment=Alignment.Center){Text(label,fontSize=13.sp,lineHeight=17.sp,fontWeight=if(active)FontWeight.SemiBold else FontWeight.Normal,color=if(active)DutBlue else Muted)}
  Box(Modifier.width(20.dp).height(2.dp).background(if(active)DutBlue else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(1.dp)))
 }
}
@Composable private fun MeetingEmpty(title:String,detail:String){Column(Modifier.fillMaxWidth().padding(horizontal=4.dp,vertical=14.dp)){Text(title,fontSize=14.sp,fontWeight=FontWeight.Medium,color=Ink);Text(detail,fontSize=12.sp,lineHeight=18.sp,color=Muted,modifier=Modifier.padding(top=4.dp))}}
@Composable private fun MeetingStatus(text:String,onDismiss:()->Unit){Row(Modifier.fillMaxWidth().background(AccentSurface).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){Text(text,Modifier.weight(1f),fontSize=11.sp,lineHeight=16.sp,color=Ink,maxLines=3,overflow=TextOverflow.Ellipsis);QuietIconButton("关闭提示",AppGlyph.Close,onDismiss)}}
@Composable private fun meetingHighlighted(text:String,query:String):AnnotatedString{val color=DutBlue;return remember(text,query,color){buildAnnotatedString{append(text);if(query.isNotBlank()){var start=text.indexOf(query,ignoreCase=true);while(start>=0){addStyle(SpanStyle(color=color,fontWeight=FontWeight.Bold),start,start+query.length);start=text.indexOf(query,start+query.length,ignoreCase=true)}}}}}

@Composable private fun MeetingPlayerBar(clip:Clip,number:Int,player:MeetingPlayback,toggle:()->Unit){
 val loaded=player.clipId==clip.id;val duration=if(loaded)player.duration else clip.durationMs();val position=if(loaded)player.position else 0L
 var dragging by remember(clip.id){mutableStateOf<Float?>(null)}
 var menu by remember{mutableStateOf(false)}
 Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
  val accent=DutBlue
  MeetingAction(if(loaded&&player.playing)"暂停"else"播放第 $number 次录音",onClick=toggle){
   if(loaded&&!player.ready)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp)
   else Canvas(Modifier.size(18.dp)){
    if(loaded&&player.playing){drawRect(accent,Offset(2f,0f),Size(size.width*.27f,size.height));drawRect(accent,Offset(size.width*.62f,0f),Size(size.width*.27f,size.height))}
    else drawPath(Path().apply{moveTo(size.width*.18f,0f);lineTo(size.width,size.height/2);lineTo(size.width*.18f,size.height);close()},accent)
   }
  }
  Column(horizontalAlignment=Alignment.CenterHorizontally){Text("第 $number 次",fontSize=10.sp,lineHeight=14.sp,color=Muted);Text(audioTime(dragging?.toLong()?:position),fontSize=10.sp,lineHeight=14.sp,color=Ink)}
  Slider(value=(dragging?:position.toFloat()).coerceIn(0f,duration.coerceAtLeast(1).toFloat()),onValueChange={dragging=it},onValueChangeFinished={val at=dragging?.toLong()?:position;if(loaded&&player.ready)player.seek(at)else player.play(clip,at);dragging=null},valueRange=0f..duration.coerceAtLeast(1).toFloat(),modifier=Modifier.weight(1f).padding(horizontal=6.dp).semantics{contentDescription="播放进度"})
  Text(audioTime(duration),fontSize=10.sp,lineHeight=14.sp,color=Muted)
  Box{
   MeetingAction("播放选项",onClick={menu=true}){Text("${player.speed}×",fontSize=11.sp,lineHeight=15.sp,color=DutBlue)}
   DropdownMenu(menu,{menu=false}){
    DropdownMenuItem(text={Text("第 $number 次 · ${clip.name}",fontSize=12.sp)},onClick={},enabled=false)
    DropdownMenuItem(text={Text("后退 10 秒")},onClick={menu=false;if(loaded)player.seek(position-10000)else player.play(clip)})
    DropdownMenuItem(text={Text("前进 10 秒")},onClick={menu=false;if(loaded)player.seek(position+10000)else player.play(clip,10000)})
    listOf(.75f,1f,1.25f,1.5f,2f).forEach{speed->DropdownMenuItem(text={Text("${if(player.speed==speed)"✓ "else ""}${speed}×")},onClick={player.changeSpeed(speed);menu=false})}
   }
  }
 }
}


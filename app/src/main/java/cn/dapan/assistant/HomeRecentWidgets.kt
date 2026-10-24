package cn.dapan.assistant

import android.content.Context
import android.util.JsonReader
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable fun HomeLibrary(open:()->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope()
 var docs by rememberPageSnapshot("home-library"){emptyList<LibraryDocument>()}
 var loaded by remember{mutableStateOf(false)};var importing by remember{mutableStateOf(false)}
 var selected by remember{mutableStateOf<LibraryDocument?>(null)};var error by remember{mutableStateOf<String?>(null)}
 LaunchedEffect(LibraryChanges.revision){
  try{docs=withContext(Dispatchers.IO){LibraryStore(c).use{it.search("",3)}};error=null}
  catch(e:CancellationException){throw e}catch(_:Exception){error="文件暂时无法读取"}finally{loaded=true}
 }
 fun openExternal(doc:LibraryDocument){runCatching{LibraryStore(c).use{it.open(doc.id)}}.onFailure{error="未找到可打开此文件的应用，请进入资料库导出"}}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->
  if(uris.isNotEmpty()&&!importing)scope.launch{
   importing=true;error=null
   try{
    val result=withContext(Dispatchers.IO){LibraryStore(c).use{store->var done=0;var failed=0;uris.forEach{uri->runCatching{store.import(uri)}.onSuccess{done++}.onFailure{failed++}};done to failed}}
    LibraryChanges.revision++
    if(result.second>0)error="已收纳 ${result.first} 个，${result.second} 个未能导入"
   }catch(e:CancellationException){throw e}catch(_:Exception){error="本次收纳未完成，请重试"}finally{importing=false}
  }
 }
 HomeWidgetCard("资料库",open){
  if(docs.isEmpty())Text(if(loaded)"收到的文件先收好，需要时再找。"else"正在读取最近文件…",fontSize=12.sp,color=Muted,lineHeight=18.sp)
  docs.forEachIndexed{index,doc->
   if(index>0)HorizontalDivider(color=Hairline,thickness=.5.dp)
   Row(Modifier.fillMaxWidth().clickable{selected=doc}.padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    LibraryThumbnail(doc,Modifier.size(34.dp,38.dp),edge=128)
    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
     Text(doc.name,fontSize=13.sp,lineHeight=18.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
     Text("${doc.kind.label} · ${libraryFileSize(doc.size)}",fontSize=10.sp,lineHeight=14.sp,color=Muted)
    }
    LineIcon(AppGlyph.Next,Modifier.size(12.dp),Muted)
   }
  }
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
   Text(if(importing)"正在收纳文件…"else"最近收纳",Modifier.weight(1f),fontSize=10.sp,color=Muted)
   TextButton(onClick={picker.launch(arrayOf("*/*"))},enabled=!importing,contentPadding=PaddingValues(horizontal=5.dp,vertical=0.dp),modifier=Modifier.height(32.dp)){LineIcon(AppGlyph.Add,Modifier.size(14.dp),DutBlue);Spacer(Modifier.width(3.dp));Text("收纳文件",fontSize=12.sp)}
  }
  error?.let{Text(it,fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.error)}
 }
 selected?.let{doc->
  if(doc.kind==LibraryKind.IMAGE)LibraryImageViewer(doc,docs.filter{it.kind==LibraryKind.IMAGE},onSelect={selected=it},onClose={selected=null},onEdit={},onExport={},onDelete={},onExternal={openExternal(doc)},readOnly=true)
  else AlertDialog(onDismissRequest={selected=null},title={Text(doc.name,fontSize=16.sp,maxLines=3,overflow=TextOverflow.Ellipsis)},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
   if(doc.kind==LibraryKind.PDF)LibraryThumbnail(doc,Modifier.fillMaxWidth().height(180.dp),edge=768)
   Text("${doc.kind.label} · ${libraryFileSize(doc.size)}",fontSize=12.sp,color=Muted)
   if(doc.remark.isNotBlank())Text(doc.remark,fontSize=13.sp,maxLines=4,overflow=TextOverflow.Ellipsis)
  }},confirmButton={TextButton(onClick={openExternal(doc)}){Text("打开文件")}},dismissButton={TextButton(onClick={selected=null}){Text("关闭")}})
 }
}

private data class HomeRecording(val session:String,val title:String,val count:Int,val duration:Long,val summary:String)
/** Skip transcript arrays: a home preview must not parse every classroom sentence. */
private fun latestHomeRecording(c:Context):HomeRecording?{
 data class Audio(val id:String,val session:String,val created:Long,val bytes:Long)
 val clips=File(c.filesDir,"recordings").listFiles().orEmpty().asSequence().filter{it.extension=="json"}.mapNotNull{file->runCatching{
  var id="";var session="";var created=0L
  JsonReader(file.reader()).use{reader->reader.beginObject();while(reader.hasNext())when(reader.nextName()){"id"->id=reader.nextString();"session"->session=reader.nextString();"created"->created=reader.nextLong();else->reader.skipValue()};reader.endObject()}
  require(id.matches(Regex("[a-fA-F0-9-]{36}"))&&session.isNotBlank())
  Audio(id,session,created,File(file.parentFile,"$id.wav").length())
 }.getOrNull()}.toList()
 val library=MeetingLibrary(c);val folders=library.folders(emptyList())
 val recent=folders.maxByOrNull{folder->maxOf(folder.created,clips.filter{it.session==folder.id}.maxOfOrNull{it.created}?:0)}
 val last=clips.maxByOrNull{it.created}
 val session=if(last!=null&&last.created>(recent?.created?:0L))last.session else recent?.id?:return null
 val belonging=clips.filter{it.session==session}
 return HomeRecording(session,library.title(session),belonging.size,belonging.sumOf{(it.bytes-44).coerceAtLeast(0)*1000/32000},library.summary(session)?.text.orEmpty())
}

@Composable fun HomeRecordings(open:()->Unit){
 val c=LocalContext.current;var recent by rememberPageSnapshot<HomeRecording?>("home-recordings"){null}
 var loaded by remember{mutableStateOf(false)};var failed by remember{mutableStateOf(false)};var detail by remember{mutableStateOf(false)}
 LaunchedEffect(MeetingChanges.revision,AudioState.revision,AudioState.running){
  try{recent=withContext(Dispatchers.IO){latestHomeRecording(c)};failed=false}catch(e:CancellationException){throw e}catch(_:Exception){failed=true}finally{loaded=true}
 }
 HomeWidgetCard("录音摘要",open){
  val item=recent
  if(item==null)Text(if(failed)"录音记录暂时无法读取"else if(!loaded)"正在读取最近记录…"else"暂无录音记录",fontSize=12.sp,color=Muted,modifier=Modifier.padding(vertical=4.dp))
  else Column(Modifier.fillMaxWidth().clickable{if(item.summary.isNotBlank())detail=true else open()}.padding(bottom=4.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text(item.title,fontSize=14.sp,lineHeight=19.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
   Text("${item.count} 段录音 · ${audioTime(item.duration)}"+(if(AudioState.running&&AudioState.clipId!=null&&AudioState.session==item.session)" · 正在录音"else""),fontSize=11.sp,color=Muted)
   Text(if(item.summary.isBlank())"尚未生成摘要"else HomeWidgetText.summaryExcerpt(item.summary),fontSize=12.sp,lineHeight=18.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
  }
 }
 if(detail&&recent!=null)AlertDialog(onDismissRequest={detail=false},title={Text(recent!!.title,fontSize=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){MarkdownContent(recent!!.summary,audioReferences=false){_,_->}}},confirmButton={TextButton(onClick={detail=false;open()}){Text("录音摘要")}},dismissButton={TextButton(onClick={detail=false}){Text("关闭")}})
}

@Composable fun HomeVocabulary(open:()->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope()
 var words by rememberPageSnapshot("home-vocabulary"){emptyList<HeardWord>()}
 var loaded by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
 LaunchedEffect(VocabularyState.revision){
  try{words=withContext(Dispatchers.IO){VocabularyStore(c).use{it.list(VocabularyPreferences(c).level,limit=3)}};error=null}catch(e:CancellationException){throw e}catch(_:Exception){error="生词记录暂时无法读取"}finally{loaded=true}
 }
 HomeWidgetCard("课堂生词",open){
  if(words.isEmpty())Text(if(!loaded)"正在读取最近生词…"else if(VocabularyState.running)"正在收听，生词会出现在这里"else"暂无待掌握生词",fontSize=12.sp,color=Muted,modifier=Modifier.padding(vertical=4.dp))
  words.forEachIndexed{index,word->
   if(index>0)HorizontalDivider(color=Hairline,thickness=.5.dp)
   Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
    Column(Modifier.weight(1f).clickable(onClick=open),verticalArrangement=Arrangement.spacedBy(2.dp)){
     Text(word.entry.word,fontSize=16.sp,lineHeight=21.sp,fontWeight=FontWeight.Medium)
     Text(word.entry.meaning.replace("\\n","；").replace('\n','；'),fontSize=12.sp,lineHeight=17.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
    }
    TextButton(onClick={scope.launch{try{withContext(Dispatchers.IO){VocabularyStore(c).use{it.mark(word.entry.word,true)}};VocabularyState.revision++}catch(e:CancellationException){throw e}catch(_:Exception){error="未能保存，请重试"}}},contentPadding=PaddingValues(horizontal=6.dp),modifier=Modifier.height(34.dp)){Text("我会了",fontSize=11.sp)}
   }
  }
  if(VocabularyState.running&&words.isNotEmpty())Text("正在收听 · 仅显示未掌握词",fontSize=10.sp,color=DutBlue,modifier=Modifier.padding(bottom=2.dp))
  error?.let{Text(it,fontSize=11.sp,color=MaterialTheme.colorScheme.error)}
 }
}

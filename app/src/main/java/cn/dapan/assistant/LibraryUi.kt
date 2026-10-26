package cn.dapan.assistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File

@Composable fun LibraryScreen(onBack:()->Unit){
 val c=LocalContext.current;val focus=LocalFocusManager.current;val scope=rememberCoroutineScope();val store=remember{LibraryStore(c)}
 var docs by rememberPageSnapshot("library-docs"){emptyList<LibraryDocument>()}
 var query by rememberSaveable{mutableStateOf("")};var kind by rememberSaveable{mutableStateOf(LibraryKind.ALL)}
 var grid by rememberSaveable{mutableStateOf(false)};var selected by remember{mutableStateOf<LibraryDocument?>(null)}
 var busy by remember{mutableStateOf(false)};var loading by remember{mutableStateOf(true)};var migrated by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")};var rename by remember{mutableStateOf(false)};var deleting by remember{mutableStateOf(false)};var exporting by remember{mutableStateOf<LibraryDocument?>(null)}
 var reading by remember{mutableStateOf(false)}
 var publicOcr by remember{mutableStateOf<LibraryDocument?>(null)}
 var evidence by remember{mutableStateOf(emptyMap<String,LibraryTextMatch>())}
 fun task(work:suspend ()->Unit){if(busy)return;scope.launch{busy=true;error="";try{work()}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"操作未完成"}finally{busy=false}}}
 fun choose(d:LibraryDocument){focus.clearFocus();reading=false;selected=d}
 fun openExternal(d:LibraryDocument){runCatching{store.open(d.id)}.onFailure{error="无法打开，可导出后用其他应用查看";android.widget.Toast.makeText(c,error,android.widget.Toast.LENGTH_SHORT).show()}}
 LaunchedEffect(query,kind,LibraryChanges.revision,migrated){
  if(!migrated)return@LaunchedEffect
  loading=true
  try{delay(100);val results=withContext(Dispatchers.IO){store.searchResults(query,kind=kind)};docs=results.map{it.document};evidence=results.associate{it.document.id to it.match};selected=selected?.let{withContext(Dispatchers.IO){store.find(it.id)}}}
  catch(e:CancellationException){throw e}catch(e:Exception){error="资料读取未完成：${e.message}"}finally{loading=false}
 }
 LaunchedEffect(Unit){
  try{withContext(Dispatchers.IO){store.migrate()}}catch(e:CancellationException){throw e}catch(e:Exception){error="部分旧文件未能导入：${e.message}"}finally{migrated=true}
 }
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()){uris->if(uris.isNotEmpty())task{
  var done=0;val failures=mutableListOf<String>()
  withContext(Dispatchers.IO){uris.forEach{uri->runCatching{store.import(uri)}.onSuccess{done++}.onFailure{failures+=it.message?:"未知错误"}}}
  LibraryChanges.revision++;if(failures.isNotEmpty())error("已收纳 $done 个，${failures.size} 个失败：${failures.first()}")
 }}
 val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri->val d=exporting;exporting=null;if(uri!=null&&d!=null)task{withContext(Dispatchers.IO){c.contentResolver.openOutputStream(uri)?.use{out->File(d.path).inputStream().use{it.copyTo(out)}}?:error("无法导出")}}}
 fun exportFile(d:LibraryDocument){if(!busy){exporting=d;export.launch(d.name)}}
 val aiState=remember(query,kind,selected,docs){JSONObject().put("query",query).put("kind",kind.label).put("selected",selected?.json()?:JSONObject()).put("visible",JSONArray(docs.take(30).map{it.json(query)}))}
 AssistantSurface("资料库",aiState,listOf(
  surfaceTool("search","筛选资料库，query为关键词，kind可选全部/图片/PDF/文档/表格/演示/音频/视频/压缩包/其他","query,kind",false){a->val q=a.optString("query");val type=LibraryKind.parse(a.optString("kind","全部"));prepared("搜索资料库",a){query=q;kind=type;grid=type==LibraryKind.IMAGE;selected=null;"已筛选${type.label}：$q"}},
  surfaceTool("select","查看文件，图片直接预览，id来自资料库查询","id",false){a->val d=store.find(a.text("id"))?:error("文件不存在");prepared("查看 ${d.name}",a){choose(d);"已打开 ${d.name}"}},
  surfaceTool("import","打开系统文件选择器，用户可选任意类型的文件","",true){a->prepared("收纳文件",a){picker.launch(arrayOf("*/*"));"已打开文件选择器，等待你选择文件；尚未保存"}}
 ))
 Column(Modifier.fillMaxSize()){
  Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,{focus.clearFocus();onBack()})
   Text("资料库",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
   QuietIconButton(if(grid)"切换列表"else"切换缩略图网格",if(grid)AppGlyph.File else AppGlyph.Tools,{grid=!grid})
   QuietIconButton("收纳文件",AppGlyph.Add,{focus.clearFocus();picker.launch(arrayOf("*/*"))},enabled=!busy)
  }
  CompactSearchField(query,{query=it},"搜索文件、备注或正文",Modifier.padding(horizontal=12.dp),onSearch={focus.clearFocus()})
  LazyRow(Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=12.dp,vertical=6.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){
   items(LibraryKind.entries){type->Text(type.label,Modifier.clip(AppShape(16.dp)).background(if(kind==type)AccentSurface else SoftSurface)
    .clickable{kind=type;grid=type==LibraryKind.IMAGE;focus.clearFocus()}.padding(horizontal=12.dp,vertical=7.dp),fontSize=12.sp,color=if(kind==type)DutBlue else Muted,fontWeight=if(kind==type)FontWeight.SemiBold else FontWeight.Normal)}
  }
  Box(Modifier.fillMaxWidth().height(2.dp)){if(busy||loading)LinearProgressIndicator(Modifier.fillMaxSize())}
  if(error.isNotBlank())Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=14.dp,vertical=4.dp))
  Row(Modifier.fillMaxWidth().padding(horizontal=14.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
   Text(if(loading)"正在读取…"else"${docs.size}${if(docs.size==100)"+"else""} 个文件",fontSize=11.sp,color=Muted)
   if(docs.size==100)Text(" · 可按类型或关键词继续查找",fontSize=11.sp,color=Muted)
  }
  if(kind==LibraryKind.IMAGE||docs.any{it.kind==LibraryKind.IMAGE&&LibraryIndexState.resumable(it.state)})Text(if(SharedAi.mode(c)==SharedAi.Mode.SHARED)"公用图片识别需逐张确认；打开图片的文字索引后选择识别。"else"新图片自动发往 DeepSeek 识字，使用个人 API 额度；原图保留。",Modifier.padding(horizontal=14.dp,vertical=3.dp),fontSize=11.sp,lineHeight=15.sp,color=Muted)
  if(docs.isEmpty()&&!loading)Box(Modifier.weight(1f).fillMaxWidth().padding(16.dp)){
   Text(if(query.isBlank()&&kind==LibraryKind.ALL)"点右上角＋收纳文件，之后随时搜索或让 AI 帮你找。"else"没有匹配的文件，试试其他类型或关键词。",fontSize=13.sp,color=Muted)
  }else if(grid){
   LazyVerticalGrid(GridCells.Adaptive(104.dp),Modifier.weight(1f),contentPadding=PaddingValues(start=12.dp,end=12.dp,top=4.dp,bottom=LocalFloatingAiHeight.current+8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
    items(docs,key={it.id}){d->Column(Modifier.clip(AppShape(8.dp)).clickable{choose(d)}){
     LibraryThumbnail(d,Modifier.fillMaxWidth().aspectRatio(1f))
     Text(d.name,Modifier.padding(top=4.dp),fontSize=12.sp,lineHeight=16.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
     Text(if(LibraryIndexState.resumable(d.state))d.state else libraryFileSize(d.size),fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
    }}
   }
  }else{
   LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=LocalFloatingAiHeight.current+8.dp)){
    items(docs,key={it.id}){d->
     Row(Modifier.fillMaxWidth().clickable{choose(d)}.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
      LibraryThumbnail(d,Modifier.size(48.dp));Spacer(Modifier.width(10.dp))
      Column(Modifier.weight(1f)){
       Text(d.name,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
       Text("${d.kind.label} · ${libraryFileSize(d.size)}",fontSize=11.sp,lineHeight=16.sp,color=Muted)
       val hit=evidence[d.id]?.evidence?.firstOrNull()
       val excerpt=hit?.quote?:d.remark
       if(excerpt.isNotBlank())Text(excerpt,fontSize=12.sp,lineHeight=16.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
       if(hit!=null)Text(hit.part.label,Modifier.clickable{choose(d);reading=true},fontSize=10.sp,color=DutBlue)
       else if(d.kind==LibraryKind.IMAGE)Text(d.state,fontSize=10.sp,lineHeight=14.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
      }
      LineIcon(AppGlyph.Next,Modifier.size(14.dp),Muted)
     };HorizontalDivider(color=Hairline)
    }
   }
  }
 }
 selected?.let{d->
  if(d.kind==LibraryKind.IMAGE)LibraryImageViewer(d,docs.filter{it.kind==LibraryKind.IMAGE},onSelect={selected=it},onClose={selected=null},
   onEdit={rename=true},onExport={exportFile(d)},onDelete={deleting=true},onExternal={openExternal(d)},onText={reading=true})
  else AlertDialog(onDismissRequest={selected=null},title={Text(d.name,fontSize=16.sp,maxLines=3,overflow=TextOverflow.Ellipsis)},text={
   Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
    if(d.kind==LibraryKind.PDF)LibraryThumbnail(d,Modifier.fillMaxWidth().height(180.dp),edge=768)
    Text("${d.kind.label} · ${libraryFileSize(d.size)}",fontSize=12.sp,color=Muted)
    if(d.remark.isNotBlank())Text(d.remark,fontSize=13.sp)
    Text(d.state,fontSize=11.sp,color=Muted)
    TextButton(onClick={reading=true}){Text(if(d.text.isBlank())"文字索引"else"查看正文")}
    Row{TextButton(onClick={rename=true}){Text("编辑")};TextButton(onClick={exportFile(d)},enabled=!busy){Text("导出")};TextButton(onClick={deleting=true},enabled=!busy){Text("删除")}}
   }
  },confirmButton={TextButton(onClick={openExternal(d)}){Text("打开文件")}},dismissButton={TextButton(onClick={selected=null}){Text("关闭")}})
  if(reading)LibraryTextViewer(d,query,onClose={reading=false},onRetry={
   if(d.kind==LibraryKind.IMAGE&&SharedAi.mode(c)==SharedAi.Mode.SHARED)publicOcr=d
   else task{withContext(Dispatchers.IO){store.index(d.id,force=true)};LibraryChanges.revision++}
  },retryEnabled=!busy&&(!LibraryIndexState.resumable(d.state)||LibraryIndexState.needsAiSetup(d.state)))
  if(rename){
   var name by remember(d.id){mutableStateOf(d.name)};var remark by remember(d.id){mutableStateOf(d.remark)}
   AlertDialog(onDismissRequest={rename=false},title={Text("名称与备注")},text={Column{
    OutlinedTextField(name,{name=it},label={Text("名称")},maxLines=2)
    OutlinedTextField(remark,{remark=it},label={Text("备注 / 关键词")},minLines=2,maxLines=5)
   }},confirmButton={TextButton(enabled=!busy&&name.isNotBlank(),onClick={task{selected=withContext(Dispatchers.IO){store.edit(d.id,name,remark);store.find(d.id)};rename=false;LibraryChanges.revision++}}){Text("保存")}},dismissButton={TextButton(onClick={rename=false}){Text("取消")}})
  }
  if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除库中副本？")},text={Text("${d.name}\n导入前的外部原文件不受影响。")},confirmButton={TextButton(enabled=!busy,onClick={task{withContext(Dispatchers.IO){store.delete(d.id)};selected=null;deleting=false;LibraryChanges.revision++}}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
 }
 publicOcr?.let{d->AlertDialog(onDismissRequest={publicOcr=null},title={Text("用公用 AI 识别这张图片？",fontSize=17.sp)},
  text={Text("${d.name}\n\n这张图片将经过开发者部署的服务器，再发送给 DeepSeek 识别文字，消耗公用额度。仅处理这张图片，其他图片不会自动上传。",fontSize=13.sp,lineHeight=19.sp)},
  confirmButton={TextButton(enabled=!busy,onClick={publicOcr=null;task{withContext(Dispatchers.IO){store.index(d.id,force=true,publicImageConsent=true)};LibraryChanges.revision++}}){Text("识别这张图片")}},
  dismissButton={TextButton(onClick={publicOcr=null}){Text("取消")}})}
}

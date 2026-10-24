package cn.dapan.assistant

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File

/** SDK outputs are durable files; listing from disk also recovers exports after process death. */
@Composable fun ScannerExports(revision:Int,query:String=""){
 val context=LocalContext.current
 val root=remember{File(context.filesDir,"documents/scanner")}
 val scope=rememberCoroutineScope()
 var refresh by remember{mutableIntStateOf(0)}
 val files by produceState<List<File>?>(null,revision,refresh){value=withContext(Dispatchers.IO){
  val current=if(root.exists())root.walkTopDown().filter{it.isFile&&it.extension.lowercase() in setOf("pdf","jpg","png")}.toList()else emptyList()
  val legacy=root.parentFile?.listFiles()?.filter{it.isFile&&it.extension.lowercase() in setOf("jpg","pdf","png")}.orEmpty()
  (current+legacy).sortedByDescending{it.lastModified()}
 }}
 var pending by remember{mutableStateOf<File?>(null)}
 var deleting by remember{mutableStateOf<File?>(null)}
 var message by remember{mutableStateOf("")}
 var busy by remember{mutableStateOf(false)}
 var showLicense by remember{mutableStateOf(false)}
 fun mime(file:File)=when(file.extension.lowercase()){ "pdf"->"application/pdf";"png"->"image/png";else->"image/jpeg" }
 val save=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
  val source=pending;pending=null
  val target=result.data?.data
  if(result.resultCode==android.app.Activity.RESULT_OK&&source!=null&&target!=null)scope.launch{
   busy=true
   try{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(target)?.use{out->source.inputStream().use{it.copyTo(out)}}?:error("无法写入所选位置")};message="已导出 ${source.name}"}
   catch(e:CancellationException){throw e}catch(e:Exception){message="导出失败，请重试"}finally{busy=false}
  }
 }
 Text("扫描文件",fontSize=13.sp,color=Muted)
 if(message.isNotBlank())Text(message,fontSize=12.sp,color=Muted)
 if(files==null)Text("正在读取…",fontSize=12.sp,color=Muted)
 else if(files!!.isEmpty()&&query.isBlank())Text("扫描完成后可在这里打开或导出。",fontSize=12.sp,color=Muted)
 files.orEmpty().filter{query.isBlank()||("扫描文件 "+it.name).contains(query,true)}.forEach{file->
  Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
   Column(Modifier.weight(1f)){
    Text(file.name,fontSize=14.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
    Text("${file.extension.uppercase()} · ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(file.lastModified()))}",fontSize=10.sp,color=Muted)
   }
   TextButton(onClick={runCatching{
    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime(file)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
   }.onFailure{message="没有可打开此文件的应用，可先导出"}},enabled=!busy){Text("打开",fontSize=12.sp)}
   TextButton(onClick={pending=file;save.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime(file)).putExtra(Intent.EXTRA_TITLE,file.name))},enabled=!busy&&pending==null){Text("导出",fontSize=12.sp)}
   QuietIconButton("删除 ${file.name}",AppGlyph.Trash,{if(!busy)deleting=file},color=Muted)
  }
 }
 TextButton(onClick={showLicense=true}){Text("Open Android Doc Scanner · 开源说明",fontSize=11.sp,color=Muted)}
 if(showLicense){
  val notice by produceState(""){value=withContext(Dispatchers.IO){listOf("NOTICE","LICENSE","THIRD-PARTY-NOTICES.md").joinToString("\n\n"){name->context.assets.open("scanner-license/$name").bufferedReader().use{it.readText()}}}}
  AlertDialog(onDismissRequest={showLicense=false},title={Text("开源扫描组件 0.7.0")},text={Text(notice,Modifier.heightIn(max=360.dp).verticalScroll(rememberScrollState()),fontSize=12.sp)},confirmButton={TextButton(onClick={showLicense=false}){Text("关闭")}})
 }
 deleting?.let{file->AlertDialog(onDismissRequest={deleting=null},title={Text("删除扫描文件？")},text={Text(file.name)},confirmButton={TextButton(onClick={
  deleting=null
  scope.launch{busy=true;try{withContext(Dispatchers.IO){check(file.delete()||!file.exists())};refresh++}catch(e:Exception){message="删除失败，请重试"}finally{busy=false}}
 }){Text("删除")}},dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})}
}

package cn.dapan.assistant

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*
import java.util.Locale

@Composable internal fun ComponentSettings(){
 val context=LocalContext.current
 val application=context.applicationContext
 val owner=context as androidx.activity.ComponentActivity
 val scope=rememberCoroutineScope()
 var revision by remember{mutableIntStateOf(0)}
 var error by remember{mutableStateOf<String?>(null)}
 var status by remember{mutableStateOf<String?>(null)}
 var installed by remember{mutableStateOf(ScannerModelComponent.isInstalled(application))}
 var downloading by remember{mutableStateOf(false)}
 var removing by remember{mutableStateOf(false)}
 var cancelling by remember{mutableStateOf(false)}
 var confirmDelete by remember{mutableStateOf(false)}
 var job by remember{mutableStateOf<Job?>(null)}
 val spec=remember{ScannerModelComponent.spec()}
 var downloaded by remember{mutableLongStateOf(0L)}
 var total by remember{mutableLongStateOf(spec.bytes)}
 val active=downloading||removing
 fun size(bytes:Long)=String.format(Locale.ROOT,"%.1f MB",bytes.coerceAtLeast(0L)/1_048_576.0)

 DisposableEffect(owner){
  val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)revision++}
  owner.lifecycle.addObserver(observer)
  onDispose{owner.lifecycle.removeObserver(observer)}
 }
 LaunchedEffect(revision){if(!active)installed=ScannerModelComponent.isInstalled(application)}

 fun download(){
  if(active)return
  downloading=true;cancelling=false;downloaded=0L;total=spec.bytes;error=null;status=null
  job=scope.launch{
   val requestContext=coroutineContext
   var lastUpdate=0L
   try{
    ScannerModelComponent.ensureReady(application){done,expected->
     requestContext.ensureActive()
     val now=SystemClock.elapsedRealtime()
     if(done==expected||now-lastUpdate>=100L){
      lastUpdate=now
      scope.launch(Dispatchers.Main){if(requestContext.isActive){downloaded=done;total=expected.takeIf{it>0L}?:spec.bytes}}
     }
    }
    installed=true;status="模型已下载，可直接开始扫描。"
   }catch(e:CancellationException){status="已取消下载";throw e}
   catch(e:Exception){error=e.message?.take(160)?:"下载失败，请检查网络后重试"}
   finally{installed=ScannerModelComponent.isInstalled(application);downloading=false;cancelling=false;job=null}
  }
 }
 fun remove(){
  if(active)return
  removing=true;error=null;status=null
  job=scope.launch{
   try{withContext(Dispatchers.IO){ScannerModelComponent.remove(application)};installed=false;status="模型已删除，扫描文件仍保留。"}
   catch(e:CancellationException){throw e}
   catch(e:Exception){error=e.message?.take(160)?:"模型删除失败，请重试"}
   finally{installed=ScannerModelComponent.isInstalled(application);removing=false;job=null}
  }
 }

 SettingsSection("离线扫描"){
  Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    Column(Modifier.weight(1f)){
     Text(if(installed)"扫描模型已下载"else"离线扫描模型",fontSize=13.sp,color=Ink)
     Text("下载 ${size(spec.bytes)}",fontSize=11.sp,color=Muted)
    }
    when{
     removing->Text("删除中",fontSize=12.sp,color=Muted)
     downloading->TextButton(onClick={cancelling=true;job?.cancel()},enabled=!cancelling,contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.height(34.dp)){Text("取消",fontSize=12.sp)}
     !installed->FilledTonalButton(onClick={download()},contentPadding=PaddingValues(horizontal=12.dp,vertical=0.dp),modifier=Modifier.height(34.dp)){Text(if(error==null)"下载模型"else"重试下载",fontSize=12.sp)}
     else->TextButton(onClick={confirmDelete=true},contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.height(34.dp)){Text("删除模型",fontSize=12.sp)}
    }
   }
   if(active){
    if(removing||cancelling||downloaded>=total)LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
    else LinearProgressIndicator(progress={(downloaded.toFloat()/total.coerceAtLeast(1L)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(3.dp))
    Text(when{removing->"正在删除模型…";cancelling->"正在取消…";downloaded>=total->"正在校验模型…";else->"${size(downloaded)} / ${size(total)}"},fontSize=11.sp,color=Muted)
   }else{
    Text(if(installed)"拍摄、裁剪和增强均可离线使用。"else"首次下载一次即可使用，不需要安装另一个应用。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
    status?.let{Text(it,fontSize=11.sp,color=Muted)}
   }
   if(installed&&!active)TextButton(onClick={runCatching{context.startActivity(Intent(context,DocumentScanActivity::class.java))}.onFailure{error="暂时无法打开扫描，请重试"}},contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.height(32.dp)){Text("开始扫描",fontSize=12.sp)}
   error?.let{Text(it,fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.error)}
  }
 }
 SettingsSection("离线词典"){Column(Modifier.padding(12.dp)){VocabularyComponentControls()}}
 if(confirmDelete)AlertDialog(
  onDismissRequest={confirmDelete=false},
  title={Text("删除扫描模型？",fontSize=17.sp)},
  text={Text("只删除离线识别模型，已保存的扫描文件和资料库内容会保留。下次扫描前需要重新下载。",fontSize=13.sp)},
  confirmButton={TextButton(onClick={confirmDelete=false;remove()},enabled=!active){Text("删除模型")}},
  dismissButton={TextButton(onClick={confirmDelete=false}){Text("取消")}}
 )
}

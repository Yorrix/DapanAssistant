package cn.dapan.assistant

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.io.File

@Composable internal fun TimetableBrowserDownload(onClose:()->Unit,onOpen:()->Unit){
 val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
 val scope=rememberCoroutineScope();var job by remember{mutableStateOf<Job?>(null)}
 var installed by remember{mutableStateOf(TimetableBrowserComponent.installed(context))}
 var canInstall by remember{mutableStateOf(context.packageManager.canRequestPackageInstalls())}
 var file by remember{mutableStateOf<File?>(null)}
 var busy by remember{mutableStateOf(false)};var progress by remember{mutableFloatStateOf(0f)}
 var error by remember{mutableStateOf<String?>(null)}
 var licenses by remember{mutableStateOf(false)}
 DisposableEffect(lifecycle){
  val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){installed=TimetableBrowserComponent.installed(context);canInstall=context.packageManager.canRequestPackageInstalls()}}
  lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer);job?.cancel()}
 }
 fun attempt(action:()->Unit){try{action()}catch(e:Exception){error=e.message?.take(180)?:"无法继续，请重试"}}
 AlertDialog(onDismissRequest={job?.cancel();onClose()},title={Text("备用课表浏览器")},text={
  Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text("系统网页打不开时使用。首次下载独立组件，随后由手机确认安装；不增加主安装包体积。",fontSize=13.sp)
   Text("学校账号需要重新登录。登录信息仅交给学校，读取后回到本机确认课表。",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
   if(!installed)Text("下载约 ${TimetableComponentCatalog.browser.bytes/1_000_000} MB · 仅支持 64 位安卓手机",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
   TextButton(onClick={licenses=true},contentPadding=PaddingValues(0.dp)){Text("许可与来源",fontSize=12.sp)}
   if(busy){LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth());Text("正在下载 ${(progress*100).toInt()}%",fontSize=12.sp)}
   if(file!=null&&!installed&&!canInstall)Text("需要允许大盘助手安装此组件。完成授权后返回，点“安装组件”。",fontSize=12.sp)
   error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
  }
 },confirmButton={TextButton(enabled=!busy,onClick={
  error=null
  when{
   installed->attempt{onOpen();onClose()}
   file!=null&&!canInstall->attempt{context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}")))}
   file!=null->attempt{context.startActivity(TimetableBrowserComponent.installer(context,file!!))}
   else->{
    if("arm64-v8a" !in android.os.Build.SUPPORTED_ABIS){error="此组件适用于 64 位安卓手机，当前设备暂不支持。"}
    else {busy=true;progress=0f;job=scope.launch{
     try{
      file=runInterruptible(Dispatchers.IO){
       var previous=-1
       OptionalComponents.download(context,TimetableComponentCatalog.browser){done,total->
        val percent=(done*100/total).toInt();if(percent!=previous){previous=percent;scope.launch{progress=percent/100f}}
       }.also{TimetableBrowserComponent.verifyArchive(context,it)}
      }
     }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?.take(180)?:"下载失败，请重试"}finally{busy=false}
    }}
   }
  }
 }){Text(when{installed->"打开兼容模式";file!=null&&!canInstall->"允许安装组件";file!=null->"安装组件";error!=null->"重新下载";else->"下载组件"})}},dismissButton={TextButton(onClick={job?.cancel();onClose()}){Text(if(busy)"取消下载"else"暂不用")}})
 if(licenses)TimetableBrowserLicenses{licenses=false}
}

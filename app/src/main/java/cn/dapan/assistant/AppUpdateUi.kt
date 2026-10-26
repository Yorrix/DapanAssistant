package cn.dapan.assistant

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*

@Composable internal fun AppUpdateHost(enabled:Boolean){
 val c=LocalContext.current;val updates=remember{AppUpdates.get(c)}
 val owner=androidx.compose.ui.platform.LocalLifecycleOwner.current
 var foreground by remember{mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
 var revision by remember{mutableIntStateOf(0)}
 DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->
  if(event==Lifecycle.Event.ON_RESUME){foreground=true;revision++}
  if(event==Lifecycle.Event.ON_PAUSE)foreground=false
 };owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
 LaunchedEffect(enabled,foreground,revision,updates.auto){if(enabled&&foreground){delay(1500);updates.check()}}
 if(enabled&&updates.offer)AlertDialog(onDismissRequest={updates.dismiss()},title={Text("大盘助手有新版本")},text={Column(Modifier.verticalScroll(rememberScrollState())){AppUpdateDetails(updates)}},confirmButton={TextButton(onClick={updates.dismiss()}){Text(if(updates.downloading)"后台下载"else"稍后")}})
}

@Composable internal fun AppUpdateSettings(){
 val c=LocalContext.current;val updates=remember{AppUpdates.get(c)}
 SettingsSection("版本与更新"){
  SettingsRow("当前版本",BuildConfig.VERSION_NAME,AppGlyph.File){updates.check(true)}
  Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){Text("自动检查新版",fontSize=14.sp);Text("打开应用时检查，每 6 小时至多一次",fontSize=12.sp,color=Muted)}
   Switch(checked=updates.auto,onCheckedChange={updates.changeAuto(it)})
  }
  Text("下载前会征求同意；安装需要手机系统确认，保留现有记录。",Modifier.padding(12.dp),fontSize=12.sp,color=Muted)
 }
 OutlinedButton(onClick={updates.check(true)},enabled=!updates.busy){Text(if(updates.checking)"正在检查…"else"检查更新")}
 AppUpdateDetails(updates)
}

@Composable private fun AppUpdateDetails(updates:AppUpdates){
 val c=LocalContext.current;val scope=rememberCoroutineScope()
 var opening by remember{mutableStateOf(false)}
 var canInstall by remember{mutableStateOf(c.packageManager.canRequestPackageInstalls())}
 val owner=androidx.compose.ui.platform.LocalLifecycleOwner.current
 val installer=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()){updates.returnedFromInstaller()}
 DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)canInstall=c.packageManager.canRequestPackageInstalls()};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  if(updates.status.isNotEmpty())Text(updates.status,fontSize=13.sp,color=Muted)
  updates.release?.let{r->
   Text("${r.name} · ${"%.1f".format(r.bytes/1_000_000.0)} MB",fontSize=15.sp)
   Text(r.notes,fontSize=13.sp,lineHeight=19.sp)
   if(updates.downloading){
    LinearProgressIndicator(progress={updates.progress},modifier=Modifier.fillMaxWidth())
    Row(verticalAlignment=Alignment.CenterVertically){Text("${(updates.progress*100).toInt()}%",Modifier.weight(1f),fontSize=12.sp);TextButton(onClick={updates.cancelDownload()}){Text("取消下载")}}
   }else if(updates.file!=null){
    if(!canInstall)Text("首次更新需允许大盘助手安装应用；授权后返回，点击安装。",fontSize=12.sp,color=Muted)
    Button(enabled=!opening&&!updates.checking,onClick={
     if(!canInstall)runCatching{c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${c.packageName}")))}.onFailure{updates.failed(Exception("请在手机设置中允许大盘助手安装应用。"))}
     else{opening=true;scope.launch{try{val intent=updates.installer();installer.launch(intent);updates.installedRequested()}catch(e:CancellationException){throw e}catch(e:Exception){updates.failed(e)}finally{opening=false}}}
    }){Text(if(opening)"正在校验…"else if(canInstall)"安装更新"else"允许安装应用")}
   }else Button(onClick={updates.download()},enabled=!updates.busy){Text("下载更新")}
  }
  if(updates.error.isNotEmpty())Text(updates.error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
 }
}

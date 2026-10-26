package cn.dapan.assistant

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

internal object AppConsent {
 const val VERSION=1
 private fun prefs(c:Context)=c.getSharedPreferences("usage-declaration",0)
 fun accepted(c:Context)=prefs(c).getInt("version",0)>=VERSION
 fun accept(c:Context)=prefs(c).edit().putInt("version",VERSION).putLong("acceptedAt",System.currentTimeMillis()).commit()
 fun permissionsSeen(c:Context)=prefs(c).getBoolean("permissionsSeen",false)
 fun finishPermissions(c:Context){prefs(c).edit().putBoolean("permissionsSeen",true).apply()}
}

internal object PermissionDecisions {
 // A denied permission is never treated as granted. A permanent denial opens system settings.
 fun useSettings(granted:Boolean,requested:Boolean,rationale:Boolean,systemOnly:Boolean=false)=
  systemOnly||granted||(requested&&!rationale)
}

@Composable internal fun UsageDeclaration(){
 Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
  Text("开始前，请了解数据和权限的用途。",fontSize=15.sp)
  Text("本机记录\n日程、资料和聊天记录主要保存在本机；删除应用前请先备份需要的内容。",fontSize=13.sp)
  Text("AI 服务\n只有使用相关 AI 功能时，才会发送对话、所选文件、语音，以及任务需要的日程等上下文。公用 AI 经开发者服务器转发给 DeepSeek、阿里云等服务商；自备 API 直接调用所配置的服务商。图片识别会单独让你选择。",fontSize=13.sp)
  Text("校园服务\n校园邮箱登录、二手发布等数据会发送到开发者服务器；主动发布的内容可被其他用户查看。课表登录信息只交给学校，课表在本机解析。",fontSize=13.sp)
  Text("设备权限\n通知用于提醒；麦克风用于语音和录音；相机用于扫描；位置用于导航和你主动开启的热力记录。可逐项拒绝或在系统设置中关闭，不影响其他功能。文件通过系统选择器选取，不索取整个存储空间。",fontSize=13.sp)
  Text("应用更新\n默认自动联网检查新版，可在设置中关闭。检查仅获取公开版本清单，服务器可见通常的网络访问信息；不会附带聊天或个人资料。下载需你点击，安装由系统确认。",fontSize=13.sp)
  Text("使用提示\nAI 可能理解有误，请核对操作结果；需要批准模式会先展示更改。校园信息和参考路线请结合实际情况使用。",fontSize=13.sp)
 }
}

/** No home widgets, AI, location or background network jobs are initialized before acceptance. */
@Composable internal fun AppEntryGate(content:@Composable ()->Unit){
 val c=LocalContext.current
 var accepted by rememberSaveable{mutableStateOf(AppConsent.accepted(c))}
 var permissionsSeen by rememberSaveable{mutableStateOf(AppConsent.permissionsSeen(c))}
 var error by remember{mutableStateOf<String?>(null)}
 if(!accepted){
  Surface(Modifier.fillMaxSize()){
   Column(Modifier.safeDrawingPadding().padding(20.dp)){
    Text("欢迎使用大盘助手",fontSize=22.sp)
    Text("使用与数据声明",Modifier.padding(vertical=12.dp),fontSize=14.sp,color=Muted)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){UsageDeclaration()}
    error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
    Button(onClick={if(AppConsent.accept(c))accepted=true else error="未能保存，请重试"},modifier=Modifier.fillMaxWidth().padding(top=12.dp)){Text("同意并继续")}
    TextButton(onClick={(c as? Activity)?.finish()},modifier=Modifier.fillMaxWidth()){Text("暂不使用")}
   }
  }
 }else if(!permissionsSeen){
  Surface(Modifier.fillMaxSize()){
   Column(Modifier.safeDrawingPadding().padding(16.dp)){
    Text("按需要开启权限",fontSize=22.sp)
    Text("建议先允许通知，以便接收日程和晨签提醒。其他权限可等用到时再开。",Modifier.padding(vertical=12.dp),fontSize=13.sp,color=Muted)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())){AppPermissions()}
    Button(onClick={AppConsent.finishPermissions(c);permissionsSeen=true},modifier=Modifier.fillMaxWidth()){Text("继续使用")}
    Text("不授权也可以继续，之后在设置中调整。",Modifier.padding(top=6.dp),fontSize=12.sp,color=Muted)
   }
  }
 }else{
  LaunchedEffect(Unit){
   Reminders.channels(c);NoticeSubscriptions.schedule(c);ResetRadar.schedule(c)
   ClassroomSession.initialize(c);RetiredFeatures.clean(c)
  }
  content()
 }
}

private data class PermissionItem(val name:String,val detail:String,val permissions:List<String>,val glyph:AppGlyph)
@Composable internal fun AppPermissions(){
 val c=LocalContext.current;val activity=c as Activity
 val prefs=remember{c.getSharedPreferences("permission-requests",0)}
 var revision by remember{mutableIntStateOf(0)}
 var message by remember{mutableStateOf<String?>(null)}
 val launcher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){result->
  revision++;message=if(result.isNotEmpty()&&result.values.all{it})"已允许"else"未允许的权限可稍后开启，其他功能仍可使用。"
  if(result[Manifest.permission.POST_NOTIFICATIONS]==true)Reminders.reschedule(c)
 }
 val owner=androidx.compose.ui.platform.LocalLifecycleOwner.current
 DisposableEffect(owner){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)revision++};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
 val items=remember{listOf(
  PermissionItem("通知","日程、晨签、天气与关注来源的提醒",if(Build.VERSION.SDK_INT>=33)listOf(Manifest.permission.POST_NOTIFICATIONS)else emptyList(),AppGlyph.Chat),
  PermissionItem("麦克风","按住说话、录音摘要、实时翻译和课堂助手",listOf(Manifest.permission.RECORD_AUDIO),AppGlyph.Mic),
  PermissionItem("位置","导航和你主动开启的热力记录；不会自动开启记录",listOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION),AppGlyph.Pin),
  PermissionItem("相机","拍照扫描；不授权仍可选择已有图片",listOf(Manifest.permission.CAMERA),AppGlyph.File))}
 SettingsSection("设备权限"){
  items.forEachIndexed{index,item->
   val granted=remember(revision){if(item.name=="通知")NotificationManagerCompat.from(c).areNotificationsEnabled()else item.permissions.all{ContextCompat.checkSelfPermission(c,it)==PackageManager.PERMISSION_GRANTED}}
   val approximate=remember(revision){item.name=="位置"&&!granted&&ContextCompat.checkSelfPermission(c,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED}
   SettingsRow(item.name,if(granted)"已允许 · 管理"else if(approximate)"近似位置 · 调整"else"点击允许",item.glyph){
    message=null
    val primary=item.permissions.lastOrNull()
    val settings=PermissionDecisions.useSettings(granted,primary?.let{prefs.getBoolean(it,false)}?:false,primary?.let{ActivityCompat.shouldShowRequestPermissionRationale(activity,it)}?:false,item.permissions.isEmpty())
    runCatching{
     if(settings)c.startActivity(if(item.name=="通知")Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName)else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${c.packageName}")))
     else{prefs.edit().apply{item.permissions.forEach{putBoolean(it,true)}}.apply();launcher.launch(item.permissions.toTypedArray())}
    }.onFailure{message="无法打开授权界面，请到手机设置 → 应用 → 大盘助手 → 权限中调整。"}
   }
   Text(item.detail,Modifier.padding(start=41.dp,end=12.dp,bottom=9.dp),fontSize=12.sp,lineHeight=17.sp,color=Muted)
   if(index<items.lastIndex)SettingsDivider()
  }
 }
 message?.let{Text(it,Modifier.padding(vertical=8.dp),fontSize=12.sp,color=Muted)}
 Text("已授权的项目不会再弹出系统申请框。日程准时提醒与后台运行状态，可在“提醒权限”中检查。",Modifier.padding(vertical=8.dp),fontSize=12.sp,color=Muted)
}

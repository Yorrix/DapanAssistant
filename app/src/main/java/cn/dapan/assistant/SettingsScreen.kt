package cn.dapan.assistant

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import kotlinx.coroutines.*

@Composable fun SettingsScreen(secret:Secrets,store:AppStore,policy:AssistantPolicy,permission:()->Unit,clearEnabled:Boolean,onGuide:()->Unit,onBack:()->Unit,onClear:(Set<ClearDataKind>)->Unit){
 val context=LocalContext.current
 var page by rememberSaveable{mutableStateOf("")}
 var accountOpen by rememberSaveable{mutableStateOf(false)}
 var hotwordsOpen by remember{mutableStateOf(false)}
 var privacy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf<String?>(null)}
 val courses=store.entries.count{it.id.startsWith(TimetableImport.PREFIX)}
 fun back(){if(page.isEmpty())onBack()else page=""}
 BackHandler{back()}
 AssistantSurface("设置",JSONObject().put("section",page).put("theme",AppAppearance.mode).put("style",AppAppearance.description).put("shape",AppAppearance.shape.label).put("palette",AppAppearance.palette.label).put("assistantAccess",policy.access.label).put("model",secret.model))
 Column(Modifier.fillMaxSize()){
  Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,{back()})
   Text(page.ifEmpty{"设置"},fontSize=18.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold,color=Ink)
  }
  key(page){Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=14.dp).padding(bottom=LocalFloatingAiHeight.current+12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   when(page){
    ""->{
     SettingsSection("AI 助手"){
      SettingsRow("模型与语音",if(SharedAi.mode(context)==SharedAi.Mode.SHARED)"公用 AI"else if(!secret.configured("deepseek"))"自备 · 待配置"else"自备 API",AppGlyph.Chat){page="模型与语音"}
      SettingsDivider()
      SettingsRow("助手权限",policy.access.label,AppGlyph.Check){page="助手权限"}
      SettingsDivider()
      SettingsRow("语音热词","",AppGlyph.Mic){hotwordsOpen=true}
     }
     SettingsSection("日常使用"){
      SettingsRow("外观",AppAppearance.description,AppGlyph.Tools){page="外观"}
      SettingsDivider()
      SettingsRow("导入课表",if(courses==0)"未导入"else"$courses 次课程",AppGlyph.Calendar){context.startActivity(Intent(context,TimetableImportActivity::class.java))}
      SettingsDivider()
      SettingsRow("提醒权限","",AppGlyph.Clock){page="提醒权限"}
     }
     SettingsSection("数据与服务"){
      SettingsRow("按需组件","扫描 · 词典",AppGlyph.File){page="按需组件"}
      SettingsDivider()
      SettingsRow("备份与清理","",AppGlyph.File){page="备份与清理"}
      SettingsDivider()
      SettingsRow("服务连接","",AppGlyph.Settings){page="服务连接"}
     }
     SettingsSection("帮助"){
      SettingsRow("快速入门","",AppGlyph.Next,onGuide)
      SettingsDivider()
      SettingsRow("权限与声明","",AppGlyph.File){page="权限与声明"}
      SettingsDivider()
      SettingsRow("应用更新",BuildConfig.VERSION_NAME,AppGlyph.Next){page="应用更新"}
     }
    }
    "模型与语音"->{
     SharedAiSettings(secret,onLogin={accountOpen=true})
     TextButton(onClick={privacy=true}){Text("数据如何使用",fontSize=12.sp)}
    }
    "助手权限"->{Panel{AssistantAccessChooser(policy)}}
    "外观"->{ThemeChooser();StyleChooser();SettingsSection("小助手"){PetPreference()};MotionChooser()}
    "提醒权限"->{
     val owner=context as androidx.activity.ComponentActivity
     var revision by remember{mutableIntStateOf(0)}
     DisposableEffect(owner){val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_RESUME)revision++};owner.lifecycle.addObserver(observer);onDispose{owner.lifecycle.removeObserver(observer)}}
     val notifications=remember(revision){NotificationManagerCompat.from(context).areNotificationsEnabled()}
     val exact=remember(revision){android.os.Build.VERSION.SDK_INT<31||context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()}
     SettingsSection("日程与晨签"){
      SettingsRow("通知",if(notifications)"已允许"else"未允许",AppGlyph.Chat){if(notifications||android.os.Build.VERSION.SDK_INT<33)context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))else permission()}
      SettingsDivider()
      SettingsRow("准时提醒",if(exact)"已允许"else"未允许",AppGlyph.Clock){if(android.os.Build.VERSION.SDK_INT>=31)runCatching{context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,Uri.parse("package:${context.packageName}")))}.onFailure{error="请在系统设置中允许闹钟和提醒"}}
     }
     Text(if(exact)"日程使用系统定时提醒，不会向手机闹钟 App 添加闹钟。"else"通知权限与准时提醒权限不同；未允许准时提醒时，系统可能延后通知。",fontSize=12.sp,lineHeight=17.sp,color=Muted)
     ReminderHealth(store,revision){error=it}
    }
    "备份与清理"->{
     Panel{Text("备份记录",fontSize=14.sp,fontWeight=FontWeight.SemiBold);Text("包含日历、账单、信息查询、晨签；不含密钥、录音与资料库文件。",fontSize=12.sp,lineHeight=17.sp,color=Muted);BackupControls(store)}
     DataClearControls(store,clearEnabled,onClear)
     DemoDataControls(store)
    }
    "权限与声明"->{AppPermissions();SettingsSection("使用与数据声明"){Column(Modifier.padding(12.dp)){UsageDeclaration()}}}
    "应用更新"->AppUpdateSettings()
    "服务连接"->CommunitySettings()
    "按需组件"->ComponentSettings()
   }
  }}
 }
 if(hotwordsOpen)HotwordSettingsDialog{hotwordsOpen=false}
 if(accountOpen)SharedAiLoginDialog(onDismiss={accountOpen=false})
 if(privacy)AlertDialog(onDismissRequest={privacy=false},title={Text("数据如何使用")},text={Text("公用 AI：使用校园账号登录，对话、语音、图片及本次任务需要的资料会经过开发者部署的服务器，再转发给 AI 服务商。按个人与全站的日、周、月额度计量，任一额度用尽后暂停。延迟可能比自备 API 高，不会自动使用个人密钥。\n\n自备 API：DeepSeek 和阿里云密钥加密保存在本机，直接调用服务商，费用由你的账户承担，不占公用额度。\n\n调用 AI 时会发送相关对话、当前页面和事项索引；查询时还可能发送匹配的资料、通知或录音转写。对话、图片识别和持续语音均使用你选择的公用或自备模式。\n\n密钥测试只验证连通性，不生成、不录音。",fontSize=13.sp,lineHeight=20.sp)},confirmButton={TextButton(onClick={privacy=false}){Text("关闭")}})
 error?.let{AlertDialog(onDismissRequest={error=null},text={Text(it)},confirmButton={TextButton(onClick={error=null}){Text("关闭")}})}
}

@Composable internal fun SettingsSection(title:String,content:@Composable ColumnScope.()->Unit){
 Column{
  Text(title,Modifier.padding(start=3.dp,top=5.dp,bottom=5.dp),fontSize=11.sp,lineHeight=15.sp,color=Muted)
  Surface(shape=AppShape(12.dp),color=CardSurface,border=BorderStroke(.5.dp,Hairline)){Column(content=content)}
 }
}
@Composable internal fun SettingsRow(title:String,value:String,glyph:AppGlyph,onClick:()->Unit){
 Row(Modifier.fillMaxWidth().clickable(onClick=onClick).heightIn(min=46.dp).padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
  LineIcon(glyph,Modifier.size(19.dp),DutBlue)
  Text(title,Modifier.weight(1f).padding(start=10.dp),fontSize=14.sp,lineHeight=18.sp,color=Ink)
  if(value.isNotEmpty())Text(value,Modifier.widthIn(max=130.dp).padding(horizontal=8.dp),fontSize=12.sp,lineHeight=16.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
  LineIcon(AppGlyph.Next,Modifier.size(13.dp),Muted)
 }
}
@Composable internal fun SettingsDivider(){HorizontalDivider(Modifier.padding(start=41.dp,end=12.dp),color=Hairline,thickness=.5.dp)}

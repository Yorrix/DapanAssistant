package cn.dapan.assistant

import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*

@Composable internal fun ReminderHealth(store:AppStore,revision:Int,onError:(String)->Unit){
 val c=LocalContext.current;val nm=c.getSystemService(NotificationManager::class.java)
 val exempt=remember(revision){c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)}
 fun channel(id:String)=runCatching{c.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,id))}.onFailure{onError("请在系统应用设置中打开通知管理")}
 fun channelState(id:String)=when(nm.getNotificationChannel(id)?.importance){null->"尚未建立";0->"已关闭";else->"未关闭"}

 val restricted=remember(revision){android.os.Build.VERSION.SDK_INT>=28&&c.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted}
 var refreshed by remember(revision){mutableIntStateOf(0)}
 val next=remember(revision,refreshed){Reminders.nextScheduled(c)}
 val history=remember(revision,refreshed){ReminderTrace.recent(c)}
 val testStatus=remember(revision,refreshed){Reminders.diagnosticStatus(c)}
 val oplus=remember{listOf(android.os.Build.MANUFACTURER,android.os.Build.BRAND).any{it.lowercase() in setOf("oppo","oneplus","realme")}}
 val today=MorningCalendar.now().toLocalDate();val record=store.checks.find{it.date==today}
 val morningInfo=when{!store.morningNotify->"未开启";store.morningStart==null->"未设置统计起点";record?.required==false->"今天不计入，不询问";record!=null->"今天已记录，不再询问";!Holidays.required(today)->"今天非工作日，不询问";else->"每天 ${store.morningTime}"}
 SettingsSection("通知频道"){
  SettingsRow("日程与晨签",channelState(Reminders.CHANNEL),AppGlyph.Calendar){channel(Reminders.CHANNEL)}
  SettingsDivider()
  WeatherNotice.entries.forEachIndexed{index,notice->if(index>0)SettingsDivider();SettingsRow("${notice.label}天气",channelState(notice.preference),AppGlyph.Clock){channel(notice.preference)}}
 }
 SettingsSection("每日提醒"){
  Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
   Text("晨签 · $morningInfo",fontSize=12.sp,lineHeight=17.sp)
   Text("晨签最后一分钟 · "+when{!store.morningLastCall->"未开启";store.morningStart==null->"未设置统计起点";MorningLastCall.issue(c)!=null->MorningLastCall.issue(c);MorningLastCall.scheduledAt(c)<=0->"统计范围内暂无待提醒日期";else->java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(MorningCalendar.zone).format(java.time.Instant.ofEpochMilli(MorningLastCall.scheduledAt(c)))+" · 精确提醒"},fontSize=12.sp,lineHeight=17.sp)
   WeatherNotice.entries.forEach{notice->Text("${notice.label}天气 · ${if(WeatherMorning.enabled(c,notice))"每天 ${WeatherMorning.time(c,notice)}"else"未开启"}",fontSize=12.sp,lineHeight=17.sp)}
  }
 }
 SettingsSection("锁屏与后台"){
  SettingsRow("后台运行",if(exempt)"电池优化已豁免"else"未豁免电池优化",AppGlyph.Settings){runCatching{c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${c.packageName}")))}.onFailure{onError("请在系统设置中找到大盘助手的耗电管理")}}
  if(oplus){
   SettingsDivider()
   SettingsRow("自启动","在系统中确认",AppGlyph.Settings){runCatching{c.startActivity(Intent(Settings.ACTION_SETTINGS))}.onFailure{onError("请打开系统设置 → 应用 → 自启动，允许大盘助手")}}
  }
  if(restricted)Text("系统已限制本应用后台运行",Modifier.padding(horizontal=12.dp,vertical=6.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.error)
 }
 Text(if(oplus)"系统设置 → 应用 → 自启动，允许大盘助手；耗电管理选择“完全允许后台行为”。两项需分别开启。"else"在系统耗电管理中允许后台运行，并开启自启动（若有）。通知权限不代表后台运行已放开。",fontSize=12.sp,lineHeight=17.sp,color=Muted)
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
  TextButton(onClick={runCatching{Reminders.diagnostic(c);refreshed++;android.widget.Toast.makeText(c,"已安排 1 分钟后提醒，请回到桌面并锁屏等待",android.widget.Toast.LENGTH_LONG).show()}.onFailure{onError("测试提醒未能安排：${it.javaClass.simpleName}")}}){Text("1 分钟后测试提醒",fontSize=12.sp)}
  TextButton(onClick={refreshed++}){Text("刷新状态",fontSize=12.sp)}
 }
 testStatus?.let{Text(it,fontSize=12.sp,lineHeight=17.sp,color=Muted)}
 Text("下次提醒 · "+if(next>0)java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(next))else"暂无",fontSize=12.sp,color=Muted)
 var details by remember{mutableStateOf(false)}
 TextButton(onClick={details=!details}){Text(if(details)"收起触发记录"else"查看触发记录",fontSize=12.sp)}
 if(details)history.take(12).forEach{Text(it,fontSize=11.sp,lineHeight=16.sp,color=Muted)}
}


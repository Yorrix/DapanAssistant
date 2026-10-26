package cn.dapan.assistant

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable fun ResetRadarScreen(onBack:()->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope()
 var data by remember{mutableStateOf(ResetRadar.cached(c))};var busy by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf<String?>(null)};var enabled by remember{mutableStateOf(ResetRadar.enabled(c))}
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){}
 fun refresh(){if(busy)return;scope.launch{busy=true;error=null;try{data=ResetRadar.refresh(c)}catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"刷新失败"}finally{busy=false}}}
 fun notify(value:Boolean){ResetRadar.set(c,value);enabled=value;if(value){if(Build.VERSION.SDK_INT>=33)permission.launch(Manifest.permission.POST_NOTIFICATIONS);refresh()}}
 LaunchedEffect(Unit){refresh()}
 AssistantSurface("tibo雷达",JSONObject().put("notification",enabled).put("data",data),listOf(
  surfaceTool("refresh","同步 tibo 的重置记录和预告","",false){a->prepared("刷新 tibo 雷达",a){data=ResetRadar.refresh(c);"已同步 tibo 数据"}},
  surfaceTool("notification","启用或关闭新公告通知，首次启用只建立基线，不提醒历史公告","enabled"){a->val value=a.getBoolean("enabled");prepared("设置 tibo 通知",a){notify(value);if(value)"已开启新公告检查；后台到达时间受系统调度影响"else"已关闭通知"}}
 ))
 val next=RadarRules.next(data);val color=if(next.confirmed)Color(0xFF48A77E)else if(next.label=="预告")Color(0xFFE1B354)else Muted
 Column(Modifier.fillMaxSize()){
  Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){QuietIconButton("返回",AppGlyph.Back,onBack);Text("tibo雷达",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold);TextButton(onClick={refresh()},enabled=!busy){Text(if(busy)"更新中"else"刷新",fontSize=12.sp)}}
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=12.dp+LocalFloatingAiHeight.current),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("下一次重置",fontSize=13.sp,color=Muted)
   Text(next.time,fontSize=34.sp,lineHeight=40.sp,fontWeight=FontWeight.Bold,color=color)
   Text(next.label,fontSize=12.sp,color=color)
   if(next.text.isNotBlank())Text(next.text,fontSize=14.sp,lineHeight=21.sp)
   if(error!=null)Text(error!!,fontSize=12.sp,color=Coral)
   HorizontalDivider(color=Hairline)
   data.optJSONObject("data")?.optJSONObject("latest_reset")?.let{last->
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("上次已确认记录",Modifier.weight(1f),fontSize=14.sp,fontWeight=FontWeight.SemiBold);Text(if(last.optString("reset_type") in listOf("banked","banked_reset"))"可存档额度"else"普通重置",fontSize=12.sp,color=Muted)}
    Text(RadarRules.time(last.optString("announced_at")),fontSize=20.sp,fontWeight=FontWeight.Medium)
    Text(RadarRules.text(last),fontSize=13.sp,lineHeight=19.sp)
   }
   HorizontalDivider(color=Hairline)
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("新公告通知",fontSize=14.sp);Text("包含新预告与已确认记录",fontSize=11.sp,color=Muted)};Switch(enabled,{notify(it)})}
   Text("来源：tibo.cc · codex-resets.com",Modifier.clickable{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://tibo.cc/")))},color=DutBlue,fontSize=12.sp)
   Text("社区记录与预告，仅供参考，不代表个人额度已到账。后台通知可能延迟。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
   Text("更新：${RadarRules.time(data.optString("fetched_at"))}",fontSize=11.sp,color=Muted)
  }
 }
}

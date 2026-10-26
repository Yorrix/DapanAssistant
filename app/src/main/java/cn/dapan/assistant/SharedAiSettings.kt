package cn.dapan.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import java.util.Locale

@Composable fun SharedAiSettings(secrets:Secrets,onLogin:()->Unit){
 val context=LocalContext.current;val api=remember{CommunityClient(context)}
 val revision=SharedAi.revision;val mode=SharedAi.mode(context)
 var key by remember{mutableStateOf("")};var aliyun by remember{mutableStateOf("")};var model by remember{mutableStateOf(secrets.model)};var keyReady by remember{mutableStateOf(false)}
 LaunchedEffect(mode,secrets){if(mode==SharedAi.Mode.PERSONAL){keyReady=false;val values=withContext(Dispatchers.IO){secrets.get("deepseek") to secrets.get("aliyun")};key=values.first;aliyun=values.second;keyReady=true}}
 var status by remember{mutableStateOf<SharedAiStatus?>(null)};var message by remember{mutableStateOf("")}
 var refresh by remember{mutableIntStateOf(0)};var loading by remember{mutableStateOf(false)}
 var details by remember{mutableStateOf(false)}
 LaunchedEffect(mode){message=""}
 LaunchedEffect(mode,revision,refresh){if(mode==SharedAi.Mode.SHARED){loading=true;status=null;message="";try{status=SharedAi.status(context)}catch(e:CancellationException){throw e}catch(e:Exception){message=if(e.message?.contains("Not Found",true)==true)"公用服务尚未就绪"else e.message?:"暂时无法读取额度"}finally{loading=false}}}
 Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   FilterChip(mode==SharedAi.Mode.SHARED,{SharedAi.setMode(context,SharedAi.Mode.SHARED)},label={Text("公用 AI")})
   FilterChip(mode==SharedAi.Mode.PERSONAL,{SharedAi.setMode(context,SharedAi.Mode.PERSONAL)},label={Text("自备 API")})
  }
  if(mode==SharedAi.Mode.SHARED){
   Text("大工邮箱登录，无需密钥。",fontSize=13.sp,fontWeight=FontWeight.Medium)
   Text("对话、图片识别、录音和翻译共用额度，延迟可能比自备 API 高。",fontSize=12.sp,lineHeight=18.sp,color=Muted)
   if(status!=null&&status?.speechAvailable!=true)Text("公用语音暂不可用，可先打字。",fontSize=11.sp,color=Muted)
   if(!api.loggedIn){
    Text("默认每人：日 ¥1 · 周 ¥3 · 月 ¥10",fontSize=11.sp,color=Muted)
    Text("默认全站：日 ¥10 · 周 ¥30 · 月 ¥100",fontSize=11.sp,color=Muted)
   }
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    Text(if(api.loggedIn)api.user.optString("email")else"与二手信息共用账号",Modifier.weight(1f),fontSize=12.sp,color=Muted)
    TextButton(onClick={if(api.loggedIn){refresh++}else onLogin()},enabled=!api.loggedIn||!loading){Text(if(api.loggedIn&&loading)"读取中…"else if(api.loggedIn)"刷新额度"else"登录 / 注册",fontSize=12.sp)}
   }
   status?.let{s->
    if(api.loggedIn){
     Text(s.message,fontSize=12.sp,color=if(s.available)DutBlue else MaterialTheme.colorScheme.error)
     SharedQuotaRow("个人",listOf("今日" to s.day,"本周" to s.week,"本月" to s.month))
     SharedQuotaRow("全站",listOf("今日" to s.siteDay,"本周" to s.siteWeek,"本月" to s.siteMonth))
     Text("剩余 / 上限（元）· 以服务器当前设置为准",fontSize=10.sp,color=Muted)
    }
   }
   TextButton(onClick={details=!details},contentPadding=PaddingValues(0.dp)){Text(if(details)"收起额度与数据说明"else"额度与数据说明",fontSize=11.sp)}
   if(details){
    Text("北京时间每日、周一、每月 1 日恢复。按保守单价计量，处理时预留额度；不足即停止，不会改用个人密钥。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
    Text("你的对话、语音、图片和本次任务需要的资料，会经过开发者部署的服务器，再转发给 AI 服务商。个人和全站任一额度用尽后暂停。资料库的公用图片识别会在每张图片发送前说明并由你选择；录音按实际识别用量计费。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
   }
  }else{
   Text("DeepSeek 处理对话，阿里云处理语音。使用你自己的账户额度。",fontSize=12.sp,lineHeight=18.sp,color=Muted)
   SecretKeyField(key,{key=it},KeyProvider.DEEPSEEK,"DeepSeek API Key",model=model,enabled=keyReady)
   Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
    FilterChip(model=="deepseek-flash",{model="deepseek-flash"},label={Text("Flash")})
    FilterChip(model=="deepseek-v4-pro",{model="deepseek-v4-pro"},label={Text("Pro")})
   }
   SecretKeyField(aliyun,{aliyun=it},KeyProvider.ALIYUN,"阿里云百炼 API Key",enabled=keyReady,hint="北京地域百炼密钥，非 AccessKey。")
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    Text("密钥加密保存在本机。",Modifier.weight(1f),fontSize=11.sp,color=Muted)
    TextButton(onClick={runCatching{secrets.set("deepseek",key.trim());secrets.set("aliyun",aliyun.trim());secrets.model=model;SharedAi.changed();message="个人 API 已保存"}.onFailure{message=it.message?:"保存失败"}},enabled=keyReady){Text("保存配置")}
   }
  }
  if(message.isNotBlank())Text(message,fontSize=12.sp,color=Muted)
 }
}

@Composable private fun SharedQuotaRow(title:String,values:List<Pair<String,SharedAiQuota?>>){
 Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
  Text(title,Modifier.width(28.dp),fontSize=11.sp,color=Muted)
  values.forEach{(label,value)->Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)){
   Text(label,fontSize=10.sp,color=Muted)
   Text(value?.let{String.format(Locale.CHINA,"%.2f / %.2f",it.remaining/1_000_000.0,it.limit/1_000_000.0)}?:"—",fontSize=11.sp,color=Ink)
  }}
 }
}

/** Same account form and encrypted session as the second-hand page, without leaving onboarding. */
@Composable fun SharedAiLoginDialog(onDismiss:()->Unit){
 val context=LocalContext.current;val api=remember{CommunityClient(context)};val scope=rememberCoroutineScope()
 var page by remember{mutableStateOf("login")};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
 Dialog(onDismissRequest={if(!busy)onDismiss()},properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxWidth().safeDrawingPadding().imePadding().padding(16.dp).heightIn(max=620.dp),shape=AppShape(20.dp),color=CardSurface){
   Column(Modifier.padding(top=10.dp,bottom=8.dp)){
    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text("校园账号",Modifier.weight(1f),fontWeight=FontWeight.SemiBold);TextButton(onClick=onDismiss,enabled=!busy){Text("关闭")}}
    if(message.isNotBlank())Text(message,Modifier.padding(horizontal=16.dp),fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    CompositionLocalProvider(LocalFloatingAiHeight provides 0.dp){
     Box(Modifier.weight(1f,fill=false)){
      AccountForm(api,page,busy,{if(it=="browse")onDismiss()else{page=it;message=""}},task={action->scope.launch{busy=true;message="";try{action()}catch(e:CancellationException){throw e}catch(e:Exception){message=e.message?:"未能完成，请重试"}finally{busy=false}}},onSuccess={SharedAi.changed();onDismiss()},onMessage={message=it})
     }
    }
   }
  }
 }
}

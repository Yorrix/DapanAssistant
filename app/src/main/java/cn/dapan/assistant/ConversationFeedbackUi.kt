package cn.dapan.assistant

import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class ConversationFeedbackClient(private val context:Context) {
 private val client=OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
 fun snapshot(lines:List<ChatLine>,screen:String):JSONObject {
  val prefs=context.getSharedPreferences("conversation-feedback",0)
  val installation=prefs.getString("installation",null)?:UUID.randomUUID().toString().also {
   check(prefs.edit().putString("installation",it).commit()){"无法保存反馈编号，请重试"}
  }
  return ConversationFeedback.payload(lines,installation,BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE,
   "${Build.MANUFACTURER} ${Build.MODEL}",Build.VERSION.RELEASE,screen)
 }
 suspend fun send(payload:JSONObject):String=withContext(Dispatchers.IO){
  // Fixed developer endpoint: never send private chat or login tokens to a user-edited service URL.
  val request=Request.Builder().url(CommunityClient.DEFAULT_ENDPOINT+"/feedback/conversations")
   .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
  client.newCall(request).execute().use { response->
   val data=runCatching{JSONObject(response.body?.string().orEmpty())}.getOrDefault(JSONObject())
   check(response.isSuccessful){when(response.code){
    429->"反馈次数较多，请稍后再试。对话仍保存在本机。"
    413->"对话记录过长，本次未提交。对话仍保存在本机。"
    404,503->"反馈服务暂不可用，请稍后重试。对话仍保存在本机。"
    else->"提交未完成，请稍后重试。对话仍保存在本机。"
   }}
   check(data.optBoolean("received")&&data.optString("id")==payload.getString("reportId")){"未收到有效回执，请重试；重复提交不会重复建单。"}
   data.getString("id")
  }
 }
}

@Composable internal fun ConversationFeedbackDialog(lines:List<ChatLine>,screen:String,onDismiss:()->Unit){
 val context=LocalContext.current
 val scope=rememberCoroutineScope()
 val client=remember{ConversationFeedbackClient(context)}
 var sending by remember{mutableStateOf(false)}
 var receipt by remember{mutableStateOf<String?>(null)}
 var error by remember{mutableStateOf<String?>(null)}
 var payload by remember{mutableStateOf<JSONObject?>(null)}
 val snapshot=remember{lines.toList()}
 var preview by remember{mutableStateOf(false)}
 AlertDialog(onDismissRequest={if(!sending)onDismiss()},
  icon={LineIcon(if(receipt==null)AppGlyph.Issue else AppGlyph.Done,color=DutBlue)},
  title={Text(if(receipt==null)"反馈这段对话"else"感谢你的反馈",fontSize=18.sp)},
  text={Column(Modifier.heightIn(max=320.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(9.dp)){
   if(receipt==null){
    Text("遇到理解错误、重复回复或操作异常时，可以把这段对话发给开发者排查。",fontSize=13.sp)
    Text("确认后，当前保存的 ${snapshot.size} 条对话历史（包括你说的话、AI 回复和操作结果）将提交到开发者服务器，由开发者查看。",fontSize=13.sp)
    Text("同时附上应用版本、机型、Android 版本和所在页面。不会上传附件原件、录音、密钥配置或完整数据备份。对话中仍可能含有姓名、地点等个人信息，请确认愿意分享后再提交。",fontSize=12.sp,color=Muted)
    TextButton(onClick={preview=!preview},contentPadding=PaddingValues(0.dp)){Text(if(preview)"收起提交内容"else"查看提交内容",fontSize=12.sp)}
    if(preview)snapshot.forEach { line->
     Text(if(line.role=="user")"我"else"大盘",fontSize=11.sp,color=Muted)
     Text(ConversationFeedback.redact(line.text),fontSize=12.sp)
     line.operation?.let{Text(ConversationFeedback.redact(ConversationFeedback.operation(it)),fontSize=12.sp)}
     HorizontalDivider(color=Hairline)
    }
    error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
   }else{
    Text("对话已收到，我们会用于排查和改进。",fontSize=13.sp)
    Text("建议先清空历史对话，再重新尝试一次。你可以关闭此提示后，点击对话右上角的垃圾桶。",fontSize=13.sp)
    Text("本机历史没有被自动清空。反馈编号：${receipt!!.take(8)}",fontSize=11.sp,color=Muted)
   }
  }},
  confirmButton={TextButton(enabled=!sending,onClick={
   if(receipt!=null)onDismiss() else {
    sending=true;error=null
    scope.launch {
     try {
      val value=payload?:client.snapshot(snapshot,screen).also{payload=it}
      receipt=client.send(value)
     }catch(e:CancellationException){throw e}
     catch(e:Exception){error=if(e is IllegalStateException||e is IllegalArgumentException)e.message else "网络异常，尚未确认提交成功。请重试，历史对话仍保留；不会重复建单。"}
     finally{sending=false}
    }
   }
  }){if(sending){CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=2.dp);Spacer(Modifier.width(6.dp))};Text(if(receipt!=null)"知道了"else if(sending)"正在提交"else"同意并提交")}},
  dismissButton={if(receipt==null)TextButton(onClick=onDismiss,enabled=!sending){Text("取消")}},containerColor=CardSurface)
}

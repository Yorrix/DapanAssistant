package cn.dapan.assistant

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlin.coroutines.resume

internal enum class KeyProvider { DEEPSEEK, ALIYUN }
internal enum class ConnectivityStatus { PASSED, LIMITED, FAILED, CANCELLED }
internal enum class ConnectivityReason { AUTH, PERMISSION, BALANCE, RATE_LIMIT, TIMEOUT, DNS, TLS, NETWORK, ENDPOINT, SERVER, RESPONSE, INPUT }
internal data class ConnectivityResult(val status:ConnectivityStatus,val title:String,val detail:String,val reason:ConnectivityReason?=null)

/** Only fixed messages leave this classifier; an upstream response must never echo a key. */
internal object ConnectivityRules {
 fun invalidInput(key:String):ConnectivityResult?=when {
  key.isBlank()->failure(ConnectivityReason.INPUT,"请先填写 API Key","测试使用输入框里的内容，无需先保存。")
  key.length>4096||key.any{it.code !in 33..126}->failure(ConnectivityReason.INPUT,"密钥格式有误","请检查空格、换行或其他非英文字符。")
  else->null
 }
 fun http(code:Int,body:String,provider:KeyProvider):ConnectivityResult {
  val json=runCatching{JSONObject(body)}.getOrNull()
  val error=json?.optJSONObject("error")
  val kind=listOf(error?.optString("code"),error?.optString("type"),json?.optString("code"),json?.optJSONObject("header")?.optString("error_code")).filterNotNull().joinToString(" ").lowercase()
  return when {
   code==401||kind.contains("invalid_api_key")||kind.contains("invalidapikey")->failure(ConnectivityReason.AUTH,"密钥未通过鉴权",if(provider==KeyProvider.ALIYUN)"请使用北京地域的百炼 API Key；检查是否已撤销，不能填写 AccessKey 或其他地域密钥。"else"请核对 DeepSeek API Key 是否完整、有效或已被撤销。")
   code==402||listOf("insufficient_balance","insufficient_quota","arrearage","paymentrequired").any{kind.contains(it)}->failure(ConnectivityReason.BALANCE,"账户余额或额度不足","请在服务商控制台核对余额、欠费状态和额度。")
   code==403->failure(ConnectivityReason.PERMISSION,"服务拒绝访问",if(provider==KeyProvider.ALIYUN)"请核对北京地域、业务空间授权及百炼服务状态。"else"请在服务商控制台核对账户和 API 访问权限。")
   code==429||kind.contains("throttl")->failure(ConnectivityReason.RATE_LIMIT,"请求过于频繁","稍等后重新测试；当前不能确认密钥或模型可用。")
   code==408||code==504->failure(ConnectivityReason.TIMEOUT,"连接超时","请检查网络后重试。超时不代表密钥错误。")
   code in 300..399||code==404||code==405->failure(ConnectivityReason.ENDPOINT,"接口未通过验证","官方接口返回异常路径或请求方式，未判定为连接成功。")
   code>=500->failure(ConnectivityReason.SERVER,"服务暂时不可用","服务商返回异常，请稍后重试。")
   else->failure(ConnectivityReason.RESPONSE,"服务返回异常","未收到有效鉴权结果（HTTP $code），请稍后重试。")
  }
 }
 fun models(body:String,model:String):ConnectivityResult {
  val json=runCatching{JSONObject(body)}.getOrNull()
  val values=json?.optJSONArray("data")
  if(json?.optString("object")!="list"||values==null||values.length()==0)return failure(ConnectivityReason.RESPONSE,"响应格式异常","未收到有效模型列表，不能确认鉴权成功。")
  val ids=(0 until values.length()).mapNotNull{values.optJSONObject(it)?.optString("id")?.takeIf(String::isNotBlank)}
  if(ids.size!=values.length())return failure(ConnectivityReason.RESPONSE,"响应格式异常","模型列表不完整，不能确认鉴权成功。")
  return if(model in ids)ConnectivityResult(ConnectivityStatus.PASSED,"鉴权通过 · 模型可见","已取得官方模型列表；未进行生成，余额和实际调用尚未验证。")
   else ConnectivityResult(ConnectivityStatus.LIMITED,"鉴权通过 · 所选模型待核对","当前模型列表未包含所选模型；尚不能确认能否调用，可切换模型后重测。")
 }
 fun network(t:Throwable):ConnectivityResult=when(t){
  is UnknownHostException->failure(ConnectivityReason.DNS,"无法解析服务地址","请检查网络或 DNS 设置后重试。")
  is SocketTimeoutException,is TimeoutCancellationException->failure(ConnectivityReason.TIMEOUT,"连接超时","请检查网络后重试。超时不代表密钥错误。")
  is SSLException->failure(ConnectivityReason.TLS,"安全连接未建立","请检查手机时间、网络或证书配置；不会绕过证书校验。")
  is ConnectException->failure(ConnectivityReason.NETWORK,"无法连接服务","请检查网络是否可访问该服务后重试。")
  else->failure(ConnectivityReason.NETWORK,"连接未完成","网络可能中断，请稍后重试；未验证密钥成功。")
 }
 fun cancelled()=ConnectivityResult(ConnectivityStatus.CANCELLED,"测试已取消","没有保存密钥或更改设置。")
 private fun failure(reason:ConnectivityReason,title:String,detail:String)=ConnectivityResult(ConnectivityStatus.FAILED,title,detail,reason)
}

/** No inference, run-task, microphone or user payload is sent by these checks. */
internal object KeyConnectivity {
 private const val MAX_BODY=262144L
 private val client=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS)
  .callTimeout(16,TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
 suspend fun check(provider:KeyProvider,input:String,model:String):ConnectivityResult {
  val key=input.trim();ConnectivityRules.invalidInput(key)?.let{return it}
  return try {withTimeout(17_000){when(provider){KeyProvider.DEEPSEEK->models(key,model);KeyProvider.ALIYUN->speechHandshake(key)}}}
  catch(e:TimeoutCancellationException){ConnectivityRules.network(e)}
  catch(e:CancellationException){throw e}
  catch(e:Exception){ConnectivityRules.network(e)}
 }
 private suspend fun models(key:String,model:String):ConnectivityResult=suspendCancellableCoroutine{continuation->
  val call=client.newCall(Request.Builder().url("https://api.deepseek.com/models").header("Authorization","Bearer $key").header("Accept","application/json").get().build())
  val completed=AtomicBoolean(false)
  fun finish(result:ConnectivityResult){if(completed.compareAndSet(false,true)&&continuation.isActive)continuation.resume(result)}
  continuation.invokeOnCancellation{completed.set(true);call.cancel()}
  call.enqueue(object:Callback{
   override fun onFailure(call:Call,e:IOException){finish(ConnectivityRules.network(e))}
   override fun onResponse(call:Call,response:Response){
    val result=try{response.use{r->
     val source=r.body?.source()
     source?.request(MAX_BODY+1)
     val oversized=(source?.buffer?.size?:0)>MAX_BODY
     val body=if(oversized)""else source?.readUtf8().orEmpty()
     if(r.code!=200)ConnectivityRules.http(r.code,body,KeyProvider.DEEPSEEK)
     else if(oversized)ConnectivityRules.models("",model)else ConnectivityRules.models(body,model)
    }}catch(e:Exception){ConnectivityRules.network(e)}
    finish(result)
   }
  })
 }
 private suspend fun speechHandshake(key:String):ConnectivityResult=suspendCancellableCoroutine{continuation->
  val socket=AtomicReference<WebSocket?>(null);val completed=AtomicBoolean(false)
  fun finish(result:ConnectivityResult){if(completed.compareAndSet(false,true)&&continuation.isActive)continuation.resume(result)}
  continuation.invokeOnCancellation{completed.set(true);socket.get()?.cancel()}
  val created=client.newWebSocket(Request.Builder().url("wss://dashscope.aliyuncs.com/api-ws/v1/inference").header("Authorization","Bearer $key").build(),object:WebSocketListener(){
   override fun onOpen(webSocket:WebSocket,response:Response){
    // The documented inference gateway authenticates the Authorization header in this handshake.
    finish(ConnectivityResult(ConnectivityStatus.PASSED,"鉴权通过 · 语音接口已连接","北京百炼接口握手成功；未启动识别，模型权限与余额尚未验证。"))
    webSocket.close(1000,"Connectivity check finished");webSocket.cancel()
   }
   override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?){
    val result=response?.let{ConnectivityRules.http(it.code,"",KeyProvider.ALIYUN)}?:ConnectivityRules.network(t)
    runCatching{response?.close()};finish(result)
   }
   override fun onClosed(webSocket:WebSocket,code:Int,reason:String){finish(ConnectivityRules.network(IOException()))}
  })
  socket.set(created);if(!continuation.isActive)created.cancel()
 }
}

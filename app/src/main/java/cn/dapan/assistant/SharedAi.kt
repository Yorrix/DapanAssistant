package cn.dapan.assistant

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

data class SharedAiQuota(val used:Long,val held:Long,val limit:Long,val remaining:Long,val resetsAt:String)
data class SharedAiStatus(val available:Boolean,val reason:String,val message:String,
 val day:SharedAiQuota?,val week:SharedAiQuota?,val siteMonth:SharedAiQuota?,val speechAvailable:Boolean=false)
class SharedAiException(message:String):IllegalStateException(message)

/** Billing source is explicit; quota exhaustion never falls back to a personal key. */
object SharedAi {
 enum class Mode { SHARED, PERSONAL }
 @Volatile private var application:Context?=null
 var revision by mutableIntStateOf(0);private set
 fun initialize(context:Context){application=context.applicationContext}
 fun changed(){revision++}
 fun mode(context:Context):Mode {
  revision
  val saved=context.getSharedPreferences("shared-ai",0).getString("mode",null)
  return saved?.let{runCatching{Mode.valueOf(it)}.getOrNull()}
   ?:if(context.getSharedPreferences("credentials",0).getString("deepseek",null).isNullOrBlank())Mode.SHARED else Mode.PERSONAL
 }
 fun setMode(context:Context,value:Mode){check(context.getSharedPreferences("shared-ai",0).edit().putString("mode",value.name).commit());changed()}
 fun canUseText(context:Context):Boolean=when(mode(context)){
  Mode.PERSONAL->Secrets(context).get("deepseek").isNotBlank()
  Mode.SHARED->CommunityClient(context).loggedIn
 }
 fun unavailableMessage(context:Context)=if(mode(context)==Mode.SHARED)"请在设置 → 模型与语音中用大工邮箱登录，使用公用 AI。"else"请先在设置中保存自己的 DeepSeek API Key。"
 fun requireTextAvailable(context:Context){if(!canUseText(context))throw SharedAiException(unavailableMessage(context))}
 internal fun sharedRequest(body:JSONObject):Request? {
  val context=application?:return null
  if(mode(context)!=Mode.SHARED)return null
  requireTextAvailable(context)
  val api=CommunityClient(context)
  val token=Secrets(context).get("community-session")
  val messages=body.optJSONArray("messages")
  if(messages!=null)for(i in 0 until messages.length()){
   if(messages.optJSONObject(i)?.opt("content") !is String)
    throw SharedAiException("公用 AI 暂只支持文字。图片识别请在设置中选择自备 API；已识别的资料正文可用公用 AI 查询。")
  }
  val payload=JSONObject(body.toString()).put("request_id",UUID.randomUUID().toString()).put("stream",true)
  return Request.Builder().url(api.endpoint+"/ai/chat/completions")
   .header("Authorization","Bearer $token").header("Accept","text/event-stream")
   .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
 }
 internal fun httpError(code:Int,text:String):SharedAiException {
  if(code==401)application?.let{CommunityClient(it).clearSession();changed()}
  val message=runCatching{JSONObject(text).optJSONObject("detail")?.optString("message")}.getOrNull()
  return SharedAiException(message?.takeIf{it.isNotBlank()}?.take(300)?:when(code){
   401->"登录已过期，请重新登录大工邮箱。";429->"公用 AI 额度不足或正在处理请求，请稍后再试。"
   503->"公用 AI 暂时不可用，请稍后再试。";else->"公用 AI 请求未完成（$code），请稍后再试。"})
 }
 suspend fun status(context:Context):SharedAiStatus {
  val raw=CommunityClient(context).request("/ai/status")
  fun quota(key:String)=raw.optJSONObject(key)?.let{SharedAiQuota(it.optLong("used"),it.optLong("held"),it.optLong("limit"),it.optLong("remaining"),it.optString("resetsAt"))}
  return SharedAiStatus(raw.optBoolean("available"),raw.optString("reason"),raw.optString("message"),quota("day"),quota("week"),quota("siteMonth"),raw.optBoolean("speechAvailable"))
 }
}

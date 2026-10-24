package cn.dapan.assistant

import android.content.Context
import okhttp3.Request
import org.json.JSONObject

/** Only the assistant's short push-to-talk follows the selected billing source. */
object SharedSpeech {
 const val MAX_SECONDS=60
 const val MAX_BYTES=MAX_SECONDS*32000L
 fun selected(context:Context)=SharedAi.mode(context)==SharedAi.Mode.SHARED
 fun canUse(context:Context)=if(selected(context))CommunityClient(context).loggedIn else Secrets(context).get("aliyun").isNotBlank()
 fun unavailableMessage(context:Context)=if(selected(context))"请先用大工邮箱登录，公用语音与文字共享额度。"else"请先在设置中填写阿里云百炼 API Key。"
 fun request(context:Context):Request {
  check(CommunityClient(context).loggedIn){unavailableMessage(context)}
  val api=CommunityClient(context)
  return Request.Builder().url(api.endpoint+"/ai/speech")
   .header("Authorization","Bearer ${Secrets(context).get("community-session")}").build()
 }
 fun error(context:Context,header:JSONObject):String {
  if(header.optString("error_code")=="login_required"){CommunityClient(context).clearSession();SharedAi.changed()}
  return header.optString("error_message").takeIf{it.isNotBlank()}?.take(300)?:"公用语音暂时不可用，请稍后再试。"
 }
}

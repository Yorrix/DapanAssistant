package cn.dapan.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.util.concurrent.TimeUnit

class CommunityClient(val context:Context) {
 companion object { const val DEFAULT_ENDPOINT="https://39.105.161.86" }
 private val preferences=context.getSharedPreferences("community",0)
 private val secrets=Secrets(context)
 private val client=OkHttpClient.Builder().callTimeout(30,TimeUnit.SECONDS).build()
 var endpoint:String
  get()=preferences.getString("endpoint",DEFAULT_ENDPOINT)!!
  set(value){val clean=value.trim().trimEnd('/');require(clean.isBlank()||clean.startsWith("https://")||(BuildConfig.DEBUG&&clean in setOf("http://10.0.2.2:8765","http://127.0.0.1:8765"))){"服务地址需使用 HTTPS"};if(clean!=endpoint){secrets.set("community-session","");preferences.edit().remove("user").apply()};preferences.edit().putString("endpoint",clean).apply()}
 val loggedIn get()=secrets.get("community-session").isNotBlank()
 val user get()=runCatching{JSONObject(preferences.getString("user","{}")!!)}.getOrDefault(JSONObject())
 fun session(value:JSONObject){secrets.set("community-session",value.getString("token"));preferences.edit().putString("user",value.getJSONObject("user").toString()).apply()}
 fun clearSession(){secrets.set("community-session","");preferences.edit().remove("user").apply()}
 suspend fun request(path:String,method:String="GET",body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO){
  require(endpoint.isNotBlank()){"校园服务尚未连接，请先设置服务地址"}
  val builder=Request.Builder().url(endpoint+path)
  val token=secrets.get("community-session");if(token.isNotBlank())builder.header("Authorization","Bearer $token")
  if(method!="GET")builder.method(method,(body?:JSONObject()).toString().toRequestBody("application/json".toMediaType()))
  client.newCall(builder.build()).execute().use{r->
   val data=runCatching{JSONObject(r.body?.string()?:"{}")} .getOrDefault(JSONObject())
   if(!r.isSuccessful){if(r.code==401)clearSession();error(if(data.opt("detail") is String)data.getString("detail")else"请求未完成（${r.code}），请检查填写内容")}
   data
  }
 }
 suspend fun image(id:String):android.graphics.Bitmap?=withContext(Dispatchers.IO){
  if(endpoint.isBlank()||!id.matches(Regex("[a-f0-9-]{36}")))return@withContext null
  runCatching{client.newCall(Request.Builder().url("$endpoint/images/$id").build()).execute().use{r->if(!r.isSuccessful)null else r.body?.byteStream()?.use{android.graphics.BitmapFactory.decodeStream(it)}}}.getOrNull()
 }
}


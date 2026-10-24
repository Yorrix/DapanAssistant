package cn.dapan.assistant

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.net.Inet4Address
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

data class DailyQuote(val text:String,val source:String,val uuid:String)
object Hitokoto {
 const val URL="https://v1.hitokoto.cn/?c=d&c=i&c=k&max_length=48"
 private const val BACKUP="https://international.v1.hitokoto.cn/?c=d&c=i&c=k&max_length=48"
 @Volatile private var preferred=URL
 // Keep system DNS and TLS verification; retain IPv6 for IPv6-only networks.
 private val client=OkHttpClient.Builder().dns(object:Dns{
  override fun lookup(hostname:String)=Dns.SYSTEM.lookup(hostname).sortedBy{if(it is Inet4Address)0 else 1}
 })
  .connectTimeout(3,TimeUnit.SECONDS).readTimeout(4,TimeUnit.SECONDS).callTimeout(6,TimeUnit.SECONDS).build()
 private class RateLimited:IOException()
 fun decode(raw:String):DailyQuote {val o=JSONObject(raw);val text=o.getString("hitokoto").trim();require(text.isNotBlank()&&text.length<=500);return DailyQuote(text,listOf(o.optString("from_who").takeUnless{it=="null"}.orEmpty(),o.optString("from").takeUnless{it=="null"}.orEmpty()).filter{it.isNotBlank()}.distinct().joinToString(" · "),o.optString("uuid"))}
 fun fetch():String {
  var last:Exception?=null
  for(url in listOf(preferred,URL,BACKUP).distinct()){
   try{
    val raw=client.newCall(Request.Builder().url(url).header("Accept","application/json").build()).execute().use{r->
     if(r.code==429)throw RateLimited()
     if(!r.isSuccessful)throw IOException("HTTP ${r.code}")
     val body=r.body?.string()?:throw IOException("Empty response")
     try{decode(body)}catch(e:Exception){throw IOException("Invalid quote response",e)}
     body
    }
    preferred=url
    return raw
   }catch(e:RateLimited){throw e}catch(e:Exception){
    last=e
    android.util.Log.w("DapanHitokoto","${if(url==URL)"primary"else"backup"}: ${e.javaClass.simpleName}")
   }
  }
  throw last?:IOException("No quote endpoint")
 }
 fun failureMessage(e:Exception)=when(e){
  is RateLimited->"请求较频繁，请稍后再试"
  is UnknownHostException->"一言地址解析失败，请检查网络后重试"
  is SocketTimeoutException,is java.io.InterruptedIOException->"一言连接超时，请稍后重试"
  is SSLException->"一言安全连接失败，请检查网络或系统时间"
  else->"一言线路暂不可用，点右侧重试"
 }
}
@Composable fun HitokotoToday(compact:Boolean=false){
 val c=LocalContext.current;val prefs=remember{c.getSharedPreferences("hitokoto",0)}
 var quote by remember{mutableStateOf(runCatching{Hitokoto.decode(prefs.getString("quote",null)!!)}.getOrNull())}
 var loading by remember{mutableStateOf(false)};var failure by remember{mutableStateOf<String?>(null)};var refresh by remember{mutableIntStateOf(0)}
 LaunchedEffect(refresh){
  if(refresh==0&&quote!=null&&System.currentTimeMillis()-prefs.getLong("time",0)<6*3600000)return@LaunchedEffect
  loading=true;failure=null
  try{val raw=withContext(Dispatchers.IO){Hitokoto.fetch()};quote=Hitokoto.decode(raw);prefs.edit().putString("quote",raw).putLong("time",System.currentTimeMillis()).apply()}
  catch(e:CancellationException){throw e}catch(e:Exception){failure=Hitokoto.failureMessage(e)}finally{loading=false}
 }
 Column(Modifier.fillMaxWidth().padding(start=if(compact)8.dp else 24.dp,end=if(compact)8.dp else 24.dp,top=if(compact)10.dp else 32.dp),verticalArrangement=Arrangement.spacedBy(if(compact)2.dp else 14.dp)){
  Text(quote?.text?:if(loading)"正在取一句话…"else"一言暂时未能加载",fontSize=if(compact)19.sp else 25.sp,lineHeight=if(compact)28.sp else 38.sp,maxLines=if(compact)3 else Int.MAX_VALUE,overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis,fontWeight=FontWeight.Medium,color=Ink)
  failure?.let{Text(it+if(quote!=null)" · 保留上次内容"else"",fontSize=10.sp,color=Muted)}
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
   Text(quote?.source?.ifBlank{"一言"}?:"一言",fontSize=12.sp,color=Muted,modifier=Modifier.weight(1f).padding(top=12.dp).clickable{runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://hitokoto.cn/"+(quote?.uuid?.takeIf{it.matches(Regex("[a-zA-Z0-9-]+"))}?.let{"?uuid=$it"}?:""))))}})
   QuietIconButton(if(failure!=null)"重试一言"else"换一句",AppGlyph.Refresh,{refresh++},!loading,Muted)
  }
 }
}

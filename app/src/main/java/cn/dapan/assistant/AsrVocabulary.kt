package cn.dapan.assistant

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Provider vocabulary IDs belong to one credential; only our inactive IDs may be evicted. */
internal object AsrVocabulary {
 private const val MODEL="fun-asr-realtime"
 private const val URL="https://dashscope.aliyuncs.com/api/v1/services/audio/asr/customization"
 private val client=OkHttpClient.Builder().callTimeout(4,TimeUnit.SECONDS).build()
 private val lock=Any()
 private val references=java.util.concurrent.ConcurrentHashMap<String,Int>()
 private val failedUntil=mutableMapOf<String,Long>()
 private fun hash(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it)}
 private fun prefs(c:Context)=c.applicationContext.getSharedPreferences("asr-vocabulary-cache",Context.MODE_PRIVATE)
 internal fun entries(words:List<String>)=JSONArray(AsrHotwordRules.validWords(words).map{JSONObject().put("text",it).put("weight",if(it=="玉兰卡")4 else 3)})
 internal fun attach(payload:JSONObject,words:List<String>,shared:Boolean,id:String?=null):JSONObject {
  // This private field is consumed by our relay, never forwarded to Aliyun.
  if(shared){val clean=AsrHotwordRules.validWords(words);if(clean.isNotEmpty())payload.put("dapan_hotwords",JSONArray(clean))}
  else if(!id.isNullOrBlank())payload.getJSONObject("parameters").put("vocabulary_id",id)
  return payload
 }
 private fun call(key:String,input:JSONObject):JSONObject {
  val body=JSONObject().put("model","speech-biasing").put("input",input).toString().toRequestBody("application/json".toMediaType())
  return client.newCall(Request.Builder().url(URL).header("Authorization","Bearer $key").post(body).build()).execute().use{r->
   check(r.isSuccessful);val raw=r.body?.string().orEmpty();check(raw.length<100000);JSONObject(raw).getJSONObject("output")
  }
 }
 class Lease internal constructor(val id:String,private val done:()->Unit):Closeable {
  private var closed=false
  override fun close(){synchronized(this){if(!closed){closed=true;done()}}}
 }
 /** Called on an I/O or websocket thread. Failure disables bias for this session, never recognition. */
 fun acquire(c:Context,key:String,words:List<String>):Lease? = synchronized(lock){
  val clean=AsrHotwordRules.validWords(words);if(key.isBlank()||clean.isEmpty())return@synchronized null
  val account=hash(key);val signature=hash(clean.joinToString("\n"));val now=System.currentTimeMillis()
  if((failedUntil[account]?:0)>now)return@synchronized null
  val storage=prefs(c)
  val rows=runCatching{JSONArray(storage.getString(account,"[]"))}.getOrElse{JSONArray()}.objects().toMutableList()
  fun save(){storage.edit().putString(account,JSONArray(rows).toString()).apply()}
  // close() runs on the main thread during service teardown, so it must never wait for network I/O.
  fun lease(id:String):Lease {references.compute(id){_,n->(n?:0)+1};return Lease(id){references.compute(id){_,n->if((n?:1)<=1)null else n!!-1}}}
  try {
   val hit=rows.firstOrNull{it.optString("hash")==signature}
   if(hit!=null){
    val id=hit.getString("id")
    if(now-hit.optLong("checked")<86_400_000L||(references[id]?:0)>0){hit.put("used",now);save();return@synchronized lease(id)}
    val queried=runCatching{call(key,JSONObject().put("action","query_vocabulary").put("vocabulary_id",id))}.getOrNull()
    if(queried?.optString("status")=="OK"){hit.put("used",now).put("checked",now);save();return@synchronized lease(id)}
    // A revoked/not-ready vocabulary must not prevent ordinary transcription.
    failedUntil[account]=now+60_000L;return@synchronized null
   }
   // Keep no more than two dictionaries per credential, without changing an active session's words.
   while(rows.size>=2){
    val old=rows.filter{(references[it.optString("id")]?:0)==0}.minByOrNull{it.optLong("used")}?:return@synchronized null
    val id=old.getString("id")
    if(id.startsWith("vocab-dapan-"))call(key,JSONObject().put("action","delete_vocabulary").put("vocabulary_id",id))
    rows.remove(old);save()
   }
   val output=call(key,JSONObject().put("action","create_vocabulary").put("target_model",MODEL).put("prefix","dapan").put("vocabulary",entries(clean)))
   val id=output.getString("vocabulary_id");check(id.startsWith("vocab-dapan-"))
   // Persist before polling so an interrupted setup does not leak an untracked dictionary.
   val row=JSONObject().put("id",id).put("hash",signature).put("used",now).put("checked",0);rows+=row;save()
   val ready=call(key,JSONObject().put("action","query_vocabulary").put("vocabulary_id",id))
   if(ready.optString("status")!="OK"){failedUntil[account]=now+60_000L;return@synchronized null}
   row.put("checked",now);save();lease(id)
  }catch(_:Exception){failedUntil[account]=now+60_000L;null}
 }
}

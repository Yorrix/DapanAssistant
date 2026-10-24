package cn.dapan.assistant

import android.content.Context
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Scheduling metadata only: never stores event titles, message bodies or credentials. */
internal object ReminderTrace {
 private fun prefs(c:Context)=c.applicationContext.getSharedPreferences("reminder-delivery",0)
 fun planned(c:Context,key:String)=prefs(c).getLong("plan:$key",0)
 fun finished(c:Context,key:String)=prefs(c).getLong("done:$key",0)
 fun plan(c:Context,key:String,due:Long){prefs(c).edit().putLong("plan:$key",due).apply()}
 fun finish(c:Context,key:String,due:Long){prefs(c).edit().putLong("done:$key",due).apply()}
 fun forget(c:Context,key:String){prefs(c).edit().remove("plan:$key").remove("done:$key").apply()}
 fun retainPlans(c:Context,keys:Set<String>){
  val p=prefs(c);val stale=p.all.keys.filter{it.startsWith("plan:")&&it.removePrefix("plan:") !in keys}
  if(stale.isNotEmpty()){val editor=p.edit();stale.forEach{editor.remove(it)};editor.apply()}
 }
 @Synchronized fun log(c:Context,message:String){
  val p=prefs(c);val old=runCatching{JSONArray(p.getString("log","[]"))}.getOrDefault(JSONArray())
  val next=JSONArray();for(i in maxOf(0,old.length()-39) until old.length())next.put(old.optString(i))
  val at=DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.now())
  next.put("$at  $message");p.edit().putString("log",next.toString()).apply()
 }
 fun recent(c:Context):List<String>{val a=runCatching{JSONArray(prefs(c).getString("log","[]"))}.getOrDefault(JSONArray());return (0 until a.length()).map{a.optString(it)}.reversed()}
}

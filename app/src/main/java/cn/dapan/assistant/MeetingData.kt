package cn.dapan.assistant

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.*
import org.json.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object MeetingChanges { var revision by mutableIntStateOf(0) }
data class MeetingFolder(val id:String,val title:String,val kind:String,val created:Long)
data class MeetingSummary(val text:String,val signature:String,val clipIds:List<String>,val created:Long)
class MeetingLibrary(c:Context){
 private val file=AtomicFile(File(c.filesDir,"meeting-library.json"))
 private val legacy=c.getSharedPreferences("meeting-order",0)
 companion object { private val lock=Any() }
 private fun read():JSONObject=if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())JSONObject()else JSONObject(file.openRead().bufferedReader().use{it.readText()})
 private fun update(change:(JSONObject)->Unit){synchronized(lock){val data=read();change(data);val out=file.startWrite();try{out.write(data.toString().toByteArray());file.finishWrite(out)}catch(e:Exception){file.failWrite(out);throw e}};MeetingChanges.revision++}
 fun folders(clips:List<Clip>):List<MeetingFolder>{
  val data=synchronized(lock){read()};val ids=(data.keys().asSequence().toList()+clips.map{it.session}).distinct()
  return ids.map{id->val o=data.optJSONObject(id);MeetingFolder(id,o?.optString("title")?.ifBlank{null}?:id,o?.optString("kind")?.takeIf{it in listOf("课程","会议","其他")}?:"会议",o?.optLong("created")?.takeIf{it>0}?:clips.filter{it.session==id}.minOfOrNull{it.created}?:0L)}
   .sortedByDescending{folder->maxOf(folder.created,clips.filter{it.session==folder.id}.maxOfOrNull{it.created}?:0L)}
 }
 fun title(id:String)=synchronized(lock){read().optJSONObject(id)?.optString("title")?.ifBlank{null}?:id}
 fun create(title:String,kind:String):String{require(title.isNotBlank());require(kind in listOf("课程","会议","其他"));val id="meeting-"+UUID.randomUUID();update{it.put(id,JSONObject().put("title",title.trim()).put("kind",kind).put("created",System.currentTimeMillis()))};return id}
 fun rename(folder:MeetingFolder,title:String,kind:String){require(title.isNotBlank());require(kind in listOf("课程","会议","其他"));update{root->val data=root.optJSONObject(folder.id)?:JSONObject().put("created",folder.created);data.put("title",title.trim()).put("kind",kind);root.put(folder.id,data)}}
 fun order(id:String,clips:List<Clip>):List<Clip>{val data=synchronized(lock){read().optJSONObject(id)?.optJSONArray("order")};val ids=if(data!=null)(0 until data.length()).map{data.getString(it)}else legacy.getString(id,"").orEmpty().split(',');return clips.filter{it.session==id}.sortedWith(compareBy<Clip>{ids.indexOf(it.id).takeIf{n->n>=0}?:Int.MAX_VALUE}.thenBy{it.created})}
 fun saveOrder(folder:MeetingFolder,ids:List<String>){update{root->val data=root.optJSONObject(folder.id)?:JSONObject().put("title",folder.title).put("kind",folder.kind).put("created",folder.created);data.put("order",JSONArray(ids));root.put(folder.id,data)}}
 fun summary(id:String):MeetingSummary?{val o=synchronized(lock){read().optJSONObject(id)?.optJSONObject("summary")};if(o!=null)return MeetingSummary(o.getString("text"),o.optString("signature"),o.optJSONArray("clips").objectsAsStrings(),o.optLong("created"));val old=legacy.getString("summary:$id",null)?:return null;return MeetingSummary(old,legacy.getString("summary-source:$id","").orEmpty(),emptyList(),0)}
 fun saveSummary(folder:MeetingFolder,text:String,signature:String,ids:List<String>){require(text.isNotBlank());update{root->val data=root.optJSONObject(folder.id)?:JSONObject().put("title",folder.title).put("kind",folder.kind).put("created",folder.created);data.put("summary",JSONObject().put("text",text).put("signature",signature).put("clips",JSONArray(ids)).put("created",System.currentTimeMillis()));root.put(folder.id,data)}}
 fun remove(folder:MeetingFolder){update{it.remove(folder.id)};legacy.edit().remove(folder.id).remove("summary:${folder.id}").remove("summary-source:${folder.id}").apply()}
}
private fun JSONArray?.objectsAsStrings()=if(this==null)emptyList()else (0 until length()).map{getString(it)}
fun audioTime(ms:Long):String="%02d:%02d:%02d".format(ms.coerceAtLeast(0)/3600000,(ms.coerceAtLeast(0)/60000)%60,(ms.coerceAtLeast(0)/1000)%60)
fun Clip.transcriptText()=lines.joinToString("\n"){"[${audioTime(it.start)} — ${audioTime(it.end)}] ${it.text}"}
// Recordings produced by this app are 16kHz mono PCM16 WAV with a 44-byte header.
fun Clip.durationMs()=((audio.length()-44).coerceAtLeast(0)*1000/32000).coerceAtLeast(lines.maxOfOrNull{it.end}?:0)
fun meetingSource(clips:List<Clip>)=clips.mapIndexed{index,clip->"录音 R${index+1}：${clip.name}\n"+clip.lines.joinToString("\n"){"【R${index+1} ${audioTime(it.start)}】 ${it.text}"}}.joinToString("\n\n")
fun meetingSignature(clips:List<Clip>,kind:String,hint:String="")=MessageDigest.getInstance("SHA-256").digest((kind+clips.joinToString{it.id}+meetingSource(clips)+if(hint.isBlank())""else"\n摘要偏好：$hint").toByteArray()).joinToString(""){"%02x".format(it)}
fun MeetingSummary.isCurrent(clips:List<Clip>,kind:String,hint:String="")=if(signature.length==64)signature==meetingSignature(clips,kind,hint)else hint.isBlank()&&signature==clips.joinToString("\n\n"){"录音：${it.name}\n${it.transcriptText()}"}.hashCode().toString()

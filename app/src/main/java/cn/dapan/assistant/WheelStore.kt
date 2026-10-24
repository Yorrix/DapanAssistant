package cn.dapan.assistant

import android.content.Context
import android.util.AtomicFile
import androidx.compose.runtime.*
import org.json.*
import java.io.File
import java.util.UUID
import kotlin.random.Random

data class WheelOption(val id:String=UUID.randomUUID().toString(),val name:String,val weight:Double=1.0)
data class SavedWheel(val id:String=UUID.randomUUID().toString(),val name:String="新轮盘",val options:List<WheelOption> = List(4){WheelOption(name="选项 ${it+1}")})
data class WheelSettings(val pointer:Boolean=false,val duration:String="长",val customMs:Int=3000){
 val milliseconds get()=when(duration){"短"->500;"自定义"->customMs;else->3000}
}
data class WheelSector(val option:WheelOption,val start:Double,val sweep:Double)
object WheelMath {
 fun validate(w:SavedWheel){
  require(w.name.isNotBlank()&&w.name.length<=60){"轮盘名称需为1至60个字符"}
  require(w.options.size in 1..60){"选项数需为1至60"}
  require(w.options.all{it.name.isNotBlank()&&it.name.length<=60}){"每个选项请填写名称（最多60字）"}
  require(w.options.all{it.weight.isFinite()&&it.weight in 0.0..1e9}){"权重需为0至10亿之间的数字"}
  require(w.options.any{it.weight>0}){"至少一个选项权重大于0"}
  require(w.options.map{it.id}.distinct().size==w.options.size){"选项编号重复"}
 }
 fun sectors(w:SavedWheel):List<WheelSector>{
  val total=w.options.sumOf{it.weight};var start=0.0
  return w.options.map{option->val sweep=option.weight/total*360.0;WheelSector(option,start,sweep).also{start+=sweep}}
 }
 fun pick(w:SavedWheel):WheelSector{
  validate(w);val sectors=sectors(w).filter{it.sweep>0};val draw=Random.nextDouble()*360.0
  return sectors.firstOrNull{draw<it.start+it.sweep}?:sectors.last()
 }
 fun positiveAngle(angle:Float)=((angle%360f)+360f)%360f
}
class WheelStore(c:Context){
 private val file=AtomicFile(File(c.filesDir,"decision-wheels-v1.json"))
 var wheels by mutableStateOf(emptyList<SavedWheel>());private set
 var selected by mutableStateOf("");private set
 var settings by mutableStateOf(WheelSettings());private set
 var error by mutableStateOf<String?>(null);private set
 init{
  if(file.baseFile.exists()||File(file.baseFile.path+".bak").exists())reload()
  else{
   val first=SavedWheel(name="去哪儿？",options=listOf("图书馆","操场","宿舍").map{WheelOption(name=it)})
   runCatching{commit(listOf(first),first.id,settings)}.onFailure{error="轮盘保存失败，请重试"}
  }
 }
 fun reload(){
  try{
   val root=JSONObject(file.openRead().bufferedReader().use{it.readText()})
   val array=root.getJSONArray("wheels")
   val loaded=(0 until array.length()).map{index->val w=array.getJSONObject(index);val options=w.getJSONArray("options")
    SavedWheel(w.getString("id"),w.getString("name"),(0 until options.length()).map{j->val o=options.getJSONObject(j);WheelOption(o.getString("id"),o.getString("name"),o.getDouble("weight"))}).also(WheelMath::validate)
   }
   require(loaded.map{it.id}.distinct().size==loaded.size)
   val config=root.getJSONObject("settings");val next=WheelSettings(config.getBoolean("pointer"),config.getString("duration"),config.getInt("customMs"))
   require(next.duration in listOf("长","短","自定义")&&next.customMs in 100..60000)
   wheels=loaded;selected=root.optString("selected").takeIf{id->loaded.any{it.id==id}}?:loaded.firstOrNull()?.id.orEmpty();settings=next;error=null
  }catch(e:Exception){error="轮盘读取失败，已保留原文件；请重试，避免覆盖原记录。"}
 }
 private fun commit(list:List<SavedWheel>,id:String,config:WheelSettings){
  check(error==null){"原记录读取失败，禁止覆盖"}
  val root=JSONObject().put("wheels",JSONArray(list.map{w->JSONObject().put("id",w.id).put("name",w.name).put("options",JSONArray(w.options.map{o->JSONObject().put("id",o.id).put("name",o.name).put("weight",o.weight)}))}))
   .put("selected",id).put("settings",JSONObject().put("pointer",config.pointer).put("duration",config.duration).put("customMs",config.customMs))
  val stream=file.startWrite()
  try{stream.write(root.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e}
  wheels=list;selected=id;settings=config
 }
 fun save(w:SavedWheel){WheelMath.validate(w);commit(if(wheels.any{it.id==w.id})wheels.map{if(it.id==w.id)w else it}else wheels+w,w.id,settings)}
 fun select(id:String){require(wheels.any{it.id==id});commit(wheels,id,settings)}
 fun delete(id:String){val next=wheels.filterNot{it.id==id};commit(next,if(selected==id)next.firstOrNull()?.id.orEmpty()else selected,settings)}
 fun configure(value:WheelSettings){require(value.customMs in 100..60000);require(value.duration in listOf("长","短","自定义"));commit(wheels,selected,value)}
}

package cn.dapan.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Preserve complete JSON records, never substring JSON. Keep a small recent set for follow-ups. */
internal object AssistantEvidence {
 fun retain(observations:List<JSONObject>):String {
  val saved=mutableListOf<JSONObject>()
  for(observation in observations.asReversed()){
   if(!observation.has("result"))continue
   val candidate=JSONObject(observation.toString())
   if(candidate.toString().length>12000){
    val result=candidate.getJSONObject("result")
    val files=result.optJSONArray("items")?.objects()?.take(12)?.map{row->JSONObject().apply{
     listOf("id","name","title","indexState","indexVersion").forEach{if(row.has(it))put(it,row.get(it))}
    }}
    candidate.put("result",JSONObject().put("file",result.optJSONObject("file"))
     .put("items",files?.let(::JSONArray)).put("hint","上轮内容较长，只保留对象信息；请使用真实id重新读取正文。"))
   }
   if(JSONArray(saved+candidate).toString().length>18000)continue
   saved.add(0,candidate)
   if(saved.size==3)break
  }
  return if(saved.isEmpty())"" else JSONArray(saved).toString()
 }
}

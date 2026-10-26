package cn.dapan.assistant

/** Candidate retrieval only. Never rewrite names or auto-pick a record for a mutation. */
internal object AssistantRecordSearch {
 private fun key(value:String)=value.lowercase().replace(Regex("[\\s\\p{P}]+"),"")
  .replace("大学英语","英语").replace("英语课","英语").replace("大英","英语")
 fun matches(value:String,query:String):Boolean {
  if(query.isBlank())return true
  val haystack=key(value)
  val tokens=query.split(Regex("[\\s，,。；;]+" )).filter{it.isNotBlank()}.map(::key)
  return tokens.isNotEmpty()&&tokens.all{it.isNotEmpty()&&haystack.contains(it)}
 }
}

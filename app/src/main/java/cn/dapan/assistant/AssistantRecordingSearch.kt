package cn.dapan.assistant

/** Course name and content keywords can occur in different fields of the same recording. */
internal object AssistantRecordingSearch {
 fun matches(title:String,lines:List<String>,query:String):Boolean=
  LibrarySearch.terms(query).all{term->title.contains(term,true)||lines.any{it.contains(term,true)}}
 fun includeLine(title:String,line:String,query:String):Boolean{
  val terms=LibrarySearch.terms(query)
  return terms.all{title.contains(it,true)}||terms.any{line.contains(it,true)}
 }
}

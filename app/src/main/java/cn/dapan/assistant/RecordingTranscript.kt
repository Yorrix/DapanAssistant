package cn.dapan.assistant

data class RecordingTranscriptSection(val clip:Clip,val number:Int,val lines:List<IndexedValue<TranscriptLine>>)

object RecordingTranscript {
 /** Selection is applied before searching; original line indices remain valid for editing. */
 fun sections(ordered:List<Clip>,all:Boolean,selectedId:String?,query:String):List<RecordingTranscriptSection>{
  val needle=query.trim()
  return ordered.mapIndexedNotNull{index,clip->
   if(!all&&clip.id!=selectedId)return@mapIndexedNotNull null
   val lines=clip.lines.withIndex().filter{needle.isEmpty()||it.value.text.contains(needle,true)}
   if(needle.isNotEmpty()&&lines.isEmpty())null else RecordingTranscriptSection(clip,index+1,lines)
  }
 }
 fun export(ordered:List<Clip>)=ordered.mapIndexed{index,clip->"第 ${index+1} 次录音 · ${clip.name}\n${clip.transcriptText()}"}.joinToString("\n\n")
}

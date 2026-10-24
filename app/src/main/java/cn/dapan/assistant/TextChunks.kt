package cn.dapan.assistant

object TextChunks {
 /** Preserve every character, prefer line boundaries and never split a surrogate pair. */
 fun split(text:String,limit:Int):List<String> {
  require(limit>=2)
  if(text.isEmpty())return emptyList()
  val chunks=mutableListOf<String>();var start=0
  while(start<text.length){
   var end=minOf(start+limit,text.length)
   if(end<text.length){
    val line=text.lastIndexOf('\n',end-1)
    if(line>start+limit/2)end=line+1
    if(Character.isHighSurrogate(text[end-1]) && Character.isLowSurrogate(text[end]))end--
   }
   chunks.add(text.substring(start,end));start=end
  }
  return chunks
 }
}

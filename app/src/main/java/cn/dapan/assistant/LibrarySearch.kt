package cn.dapan.assistant

import java.util.Locale

/** Offsets always address the saved, unmodified extracted text (UTF-16, end exclusive). */
data class LibraryTextPart(
 val start:Int,val end:Int,val paragraph:Int,
 val page:Int?=null,val slide:Int?=null,val sheet:String?=null,val cells:String?=null
){
 val label:String get()=buildList{
  page?.let{add("第 $it 页")};slide?.let{add("第 $it 张幻灯片")}
  sheet?.let{add(it)};cells?.let{add(it)};add("第 $paragraph 段")
 }.joinToString(" · ")
}
data class LibraryEvidence(val part:LibraryTextPart,val quote:String,val matchedTerms:List<String>)
data class LibraryTextMatch(val score:Int,val matchedTerms:List<String>,val evidence:List<LibraryEvidence>)
data class LibraryReadPage(val start:Int,val end:Int,val text:String,val parts:List<LibraryTextPart>,val nextOffset:Int?)
enum class LibraryTextFormat{TEXT,PDF,SLIDES,SHEET,IMAGE}

object LibrarySearch {
 const val PART_SIZE=1200
 fun terms(query:String):List<String> = query.trim().split(Regex("[\\s,，;；、]+"))
  .map{it.trim('"','“','”','\'').take(400)}.filter{it.isNotBlank()}.distinctBy{it.lowercase(Locale.ROOT)}.take(12)
 fun format(name:String,mime:String):LibraryTextFormat=when{
  mime.startsWith("image/")||name.substringAfterLast('.',"").lowercase() in setOf("jpg","jpeg","png","webp","heic","heif","bmp","gif","avif")->LibraryTextFormat.IMAGE
  mime=="application/pdf"||name.endsWith(".pdf",true)->LibraryTextFormat.PDF
  name.endsWith(".pptx",true)->LibraryTextFormat.SLIDES
  name.endsWith(".xlsx",true)->LibraryTextFormat.SHEET
  else->LibraryTextFormat.TEXT
 }
 private val pageMarker=Regex("第([0-9]+)页")
 private val slideMarker=Regex("第([0-9]+)张幻灯片[：:]?")
 private val sheetMarker=Regex("xl/worksheets/(sheet[0-9]+)\\.xml（单元格值，不重算公式）")
 private val cellMarker=Regex("(?:^|\\s)([A-Z]{1,3}[0-9]+)=")
 private fun boundary(text:String,offset:Int):Int {
  val n=offset.coerceIn(0,text.length)
  return if(n>0&&n<text.length&&text[n].isLowSurrogate()&&text[n-1].isHighSurrogate())n-1 else n
 }
 /** Page/slide markers are trusted only for their actual file format, never guessed from prose. */
 fun parts(text:String,format:LibraryTextFormat=LibraryTextFormat.TEXT):List<LibraryTextPart>{
  val out=ArrayList<LibraryTextPart>();var lineStart=0;var paragraph=0
  var page:Int?=null;var slide:Int?=null;var sheet:String?=null
  while(lineStart<text.length){
   val newline=text.indexOf('\n',lineStart).let{if(it<0)text.length else it}
   val line=text.substring(lineStart,newline).trim()
   val p=if(format==LibraryTextFormat.PDF)pageMarker.matchEntire(line)else null
   val s=if(format==LibraryTextFormat.SLIDES)slideMarker.matchEntire(line)else null
   val t=if(format==LibraryTextFormat.SHEET)sheetMarker.matchEntire(line)else null
   when{
    p!=null->page=p.groupValues[1].toIntOrNull()
    s!=null->slide=s.groupValues[1].toIntOrNull()
    t!=null->sheet="工作表 ${t.groupValues[1].removePrefix("sheet")}" // Sheet number is not an invented workbook tab name.
    line.isNotBlank()->{
     paragraph++
     val refs=if(format==LibraryTextFormat.SHEET)cellMarker.findAll(line).map{it.groupValues[1]}.toList()else emptyList()
     val cells=refs.firstOrNull()?.let{first->if(refs.last()==first)first else "$first–${refs.last()}"}
     var start=lineStart
     while(start<newline){
      val end=boundary(text,minOf(start+PART_SIZE,newline)).coerceAtLeast(start+1)
      out+=LibraryTextPart(start,end,paragraph,page,slide,sheet,cells);start=end
     }
    }
   }
   lineStart=newline+1
  }
  return out
 }
 /** OR recall, coverage-first ranking; a missing keyword must not hide other useful evidence. */
 fun match(name:String,remark:String,text:String,query:String,format:LibraryTextFormat=LibraryTextFormat.TEXT,maxEvidence:Int=4):LibraryTextMatch{
  val terms=terms(query);if(terms.isEmpty())return LibraryTextMatch(0,emptyList(),emptyList())
  val nameTerms=terms.filter{name.contains(it,true)};val remarkTerms=terms.filter{remark.contains(it,true)}
  val textTerms=terms.filter{text.contains(it,true)}
  val matched=terms.filter{it in nameTerms||it in remarkTerms||it in textTerms}
  if(matched.isEmpty())return LibraryTextMatch(0,emptyList(),emptyList())
  val score=matched.size*100+nameTerms.size*35+remarkTerms.size*12+textTerms.size*20+
   (if(matched.size==terms.size)80 else 0)+(if(name.contains(query.trim(),true))40 else 0)
  if(textTerms.isEmpty()||maxEvidence<=0)return LibraryTextMatch(score,matched,emptyList())
  data class Candidate(val part:LibraryTextPart,val terms:List<String>,val first:Int)
  val lookAhead=textTerms.maxOf{it.length}-1
  val candidates=parts(text,format).mapNotNull{part->
   val body=text.substring(part.start,minOf(text.length,part.end+lookAhead));val found=textTerms.mapNotNull{term->body.indexOf(term,ignoreCase=true).takeIf{it>=0&&it<part.end-part.start}?.let{term to it}}
   if(found.isEmpty())null else Candidate(part.copy(end=maxOf(part.end,part.start+found.maxOf{it.second+it.first.length})),found.map{it.first},found.minOf{it.second})
  }.sortedWith(compareByDescending<Candidate>{it.terms.size}.thenBy{it.part.start})
  // Prefer coverage of new terms before collecting more examples of the same term.
  val chosen=mutableListOf<Candidate>();val covered=mutableSetOf<String>();val remaining=candidates.toMutableList()
  while(chosen.size<maxEvidence.coerceIn(1,8)&&remaining.isNotEmpty()){
   val pick=remaining.maxBy{candidate->candidate.terms.count{it !in covered}*100+candidate.terms.size}
   chosen+=pick;covered+=pick.terms;remaining.remove(pick)
  }
  val evidence=chosen.sortedBy{it.part.start}.map{candidate->
   val start=boundary(text,(candidate.part.start+candidate.first-100).coerceAtLeast(candidate.part.start))
   val end=boundary(text,minOf(candidate.part.end,start+600))
   val quote=text.substring(start,end)
   LibraryEvidence(candidate.part.copy(start=start,end=end),quote,terms.filter{quote.contains(it,true)})
  }
  return LibraryTextMatch(score,matched,evidence)
 }
 /** Consecutive pages have no gap/overlap; no prefix is passed off as the whole document. */
 fun read(text:String,offset:Int=0,limit:Int=10000,format:LibraryTextFormat=LibraryTextFormat.TEXT):LibraryReadPage{
  val start=boundary(text,offset);val end=boundary(text,(start.toLong()+limit.coerceIn(500,16000)).coerceAtMost(text.length.toLong()).toInt())
  return LibraryReadPage(start,end,text.substring(start,end),parts(text,format).filter{it.end>start&&it.start<end}.map{it.copy(start=maxOf(start,it.start),end=minOf(end,it.end))},end.takeIf{it<text.length})
 }
 fun isPartial(state:String):Boolean=state.contains("部分")||state.contains("限制")||state.contains("最多")||state.contains("前30")||state.contains("前10")
}

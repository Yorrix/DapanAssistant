package cn.dapan.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Bounded evidence envelopes, independent from UI and transport. No remote indexing is triggered. */
object LibraryRetrieval {
 /** Keep the actual matching quote, plus separately cited adjacent paragraphs. A heading alone
  * rarely answers a question; never attach its paragraph number to the following rule. */
 private fun evidence(doc:LibraryDocument,hit:LibraryEvidence):JSONObject{
  val context=LibrarySearch.parts(doc.text,doc.textFormat).filter{part->
   part.paragraph in (hit.part.paragraph-1)..(hit.part.paragraph+2)&&
    part.page==hit.part.page&&part.slide==hit.part.slide&&part.sheet==hit.part.sheet&&
    part.start < hit.part.end+1600&&part.end>hit.part.start-800
  }.take(4).map{part->
   val page=LibrarySearch.read(doc.text,part.start,600,doc.textFormat)
   val end=minOf(part.end,page.end)
   doc.evidenceJson(LibraryEvidence(part.copy(end=end),doc.text.substring(part.start,end),emptyList()))
  }
  return doc.evidenceJson(hit).put("context",JSONArray(context))
 }
 fun document(doc:LibraryDocument,query:String,offset:Int=0,limit:Int=10000):JSONObject{
  val root=JSONObject().put("module","资料库").put("query",query).put("file",doc.json())
   .put("textSource",if(doc.textFormat==LibraryTextFormat.IMAGE)"AI 图片识别文字；可能有误，重要内容需核对原图"else"本机提取的文档文字；未包含图片、样式和嵌入对象")
   .put("offsetUnit","UTF-16字符；起点含、终点不含；对应此 indexVersion 的索引文字")
  if(doc.text.isBlank())return root.put("mode","unavailable").put("evidence",JSONArray())
   .put("hint","没有可读取的正文，不能据名称或备注编造文件内容。索引状态见 file.indexState；查询不会自动重新识别。")
  if(query.isNotBlank()){
   val match=LibrarySearch.match(doc.name,doc.remark,doc.text,query,doc.textFormat,maxEvidence=8)
   return root.put("mode","search_in_file").put("matchedTerms",JSONArray(match.matchedTerms))
    .put("evidence",JSONArray(match.evidence.map{evidence(doc,it)}))
    .put("hint","已检索整个可用索引，返回相关片段而非完整文件。需要前后文时，使用同一 id、空 query 和片段 startOffset 分页读取；换词可继续检索。引用必须来自 quote，标明 citation。")
  }
  val page=LibrarySearch.read(doc.text,offset,limit,doc.textFormat)
  return root.put("mode","read_page").put("startOffset",page.start).put("endOffset",page.end)
   .put("text",page.text).put("nextOffset",page.nextOffset?:JSONObject.NULL).put("hasMore",page.nextOffset!=null)
   .put("locations",JSONArray(page.parts.take(100).map{part->JSONObject().put("startOffset",part.start).put("endOffset",part.end)
    .put("location",part.label).put("citation","${doc.name} · ${part.label}")}))
   .put("locationsTruncated",page.parts.size>100)
   .put("hint","这里只返回一个文字区间。hasMore=true 时使用同一 id、空 query、nextOffset 继续读取；partialIndex=true 表示原文件还有未索引内容。不得把一个区间或索引范围称为完整原文件。")
 }
 fun search(results:List<LibrarySearchResult>,query:String,offset:Int=0):JSONObject{
  val start=offset.coerceAtLeast(0).coerceAtMost(results.size);val page=results.drop(start).take(12)
  val items=page.map{result->result.document.json().put("remark",result.document.remark.take(600))
   .put("matchedTerms",JSONArray(result.match.matchedTerms)).put("relevance",result.match.score)
   .put("evidence",JSONArray(result.match.evidence.map{evidence(result.document,it)}))}
  val hasMore=start+page.size<results.size
  return JSONObject().put("module","资料库").put("query",query).put("mode","search_files").put("items",JSONArray(items))
   .put("returned",items.size).put("hasMore",hasMore).put("nextOffset",if(hasMore)start+page.size else JSONObject.NULL)
   .put("candidateLimitReached",results.size>=500)
   .put("hint","按多关键词相关度排序，可能只命中部分关键词；请检查 matchedTerms 和原文 evidence。无 evidence 仅表示名称/备注命中，不证明正文。更多文件使用空 id 与 nextOffset；阅读文件用真实 id，检索后部正文用 id+短关键词，连续阅读用 id+空query+offset。")
 }
}

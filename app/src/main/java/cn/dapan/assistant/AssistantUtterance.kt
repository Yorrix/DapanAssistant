package cn.dapan.assistant

/** Read assertions, not the last mention irrespective of negation (“早上，不是下午”). */
internal object AssistantUtterance {
 fun mentions(pattern:Regex,text:String):List<MatchResult> = pattern.findAll(text).filter{m->
  val prefix=text.substring(0,m.range.first).substringAfterLast('\n').substringAfterLast('。').substringAfterLast('，').takeLast(18)
  !Regex("(?:不是|并非|不要|不用|别|非|不)(?:说的?是?|指的?是?|要|在|是)?\\s*$").containsMatchIn(prefix)
 }.toList()
}

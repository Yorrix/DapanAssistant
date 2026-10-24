package cn.dapan.assistant

import org.commonmark.node.*

/** Keep Markdown structure intact; audio citations are links only in ordinary text. */
object RecordingMarkdown {
 private val citation=Regex("【R(\\d+) (\\d{2,}):(\\d{2}):(\\d{2})】")
 fun normalize(raw:String):String {
  val text=raw.trim()
  val lines=text.lines()
  if(lines.size>=3&&lines.first().lowercase() in listOf("```md","```markdown")&&lines.last()=="```"&&lines.drop(1).dropLast(1).none{it.trim()=="```"})return lines.drop(1).dropLast(1).joinToString("\n")
  return text
 }
 fun reference(link:String):Pair<Int,Long>? {
  val match=Regex("dapan-audio://seek/(\\d+)/(\\d+)").matchEntire(link)?:return null
  val number=match.groupValues[1].toIntOrNull()?:return null
  val time=match.groupValues[2].toLongOrNull()?:return null
  return if(number in 1..100000&&time>=0)number to time else null
 }
 fun linkReferences(root:Node){
  val texts=mutableListOf<Text>()
  root.accept(object:AbstractVisitor(){override fun visit(text:Text){var parent=text.parent;while(parent!=null){if(parent is Link||parent is Image)return;parent=parent.parent};texts+=text}})
  texts.forEach{node->
   val value=node.literal;var cursor=0
   citation.findAll(value).forEach matchLoop@{match->
    val number=match.groupValues[1].toIntOrNull()?:return@matchLoop
    val hour=match.groupValues[2].toLongOrNull()?:return@matchLoop
    val minute=match.groupValues[3].toInt();val second=match.groupValues[4].toInt()
    if(number !in 1..100000||hour !in 0..99999||minute !in 0..59||second !in 0..59)return@matchLoop
    if(match.range.first>cursor)node.insertBefore(Text(value.substring(cursor,match.range.first)))
    node.insertBefore(Link("dapan-audio://seek/$number/${(hour*3600+minute*60+second)*1000}",null).apply{appendChild(Text(match.value))})
    cursor=match.range.last+1
   }
   if(cursor>0){if(cursor<value.length)node.insertBefore(Text(value.substring(cursor)));node.unlink()}
  }
 }
}

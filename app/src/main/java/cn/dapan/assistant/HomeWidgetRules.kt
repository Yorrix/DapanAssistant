package cn.dapan.assistant

internal object HomeWidgetOrdering {
 fun restore(stored:String?):List<HomeWidget>{
  val old=stored.orEmpty().split(',').mapNotNull{name->HomeWidget.entries.find{it.name==name}}.distinct()
  return old+HomeLayout.defaultOrder.filterNot(old::contains)
 }
 fun requested(requested:List<HomeWidget>,current:List<HomeWidget>):List<HomeWidget>{
  require(requested.isNotEmpty()){ "请至少指定一个要排序的小组件" }
  require(requested.distinct().size==requested.size){ "排序中不能重复同一个小组件" }
  return requested+current.filterNot(requested::contains)+HomeLayout.defaultOrder.filterNot{it in requested||it in current}
 }
}

internal object HomeWidgetText {
 fun summaryExcerpt(markdown:String):String=RecordingMarkdown.normalize(markdown)
  .replace(Regex("```[\\s\\S]*?```"),"")
  .replace(Regex("!?\\[([^]\\n]+)]\\([^)]*\\)"),"$1")
  .lineSequence().map{line->line.trim().replace(Regex("^(?:#{1,6}\\s+|>\\s*|[-*+]\\s+|\\d+[.)]\\s+)"),"")}
  .filter{it.isNotBlank()&&!it.matches(Regex("[-*_]{3,}"))}
  .joinToString(" · ").replace("**","").replace("__","").replace("`","").take(200)
}

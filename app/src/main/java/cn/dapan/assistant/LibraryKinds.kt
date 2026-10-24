package cn.dapan.assistant

/** Shared by the filter chips, file badges and SQLite (filter before applying LIMIT). */
enum class LibraryKind(val label:String, val extensions:Set<String>, val mimeHints:Set<String>) {
 ALL("全部", emptySet(), emptySet()),
 IMAGE("图片", setOf("jpg","jpeg","png","webp","gif","bmp","heic","heif","avif","svg"), setOf("image/")),
 PDF("PDF", setOf("pdf"), setOf("application/pdf")),
 SHEET("表格", setOf("xls","xlsx","csv","ods"), setOf("spreadsheet","excel","text/csv")),
 SLIDES("演示", setOf("ppt","pptx","odp"), setOf("presentation","powerpoint")),
 DOCUMENT("文档", setOf("doc","docx","txt","md","rtf","odt","html","htm","json"), setOf("text/","word","opendocument.text","rtf","json")),
 AUDIO("音频", setOf("mp3","wav","m4a","aac","ogg","flac","opus","amr"), setOf("audio/")),
 VIDEO("视频", setOf("mp4","mov","mkv","avi","webm","3gp","m4v"), setOf("video/")),
 ARCHIVE("压缩包", setOf("zip","rar","7z","tar","gz","bz2","xz"), setOf("zip","rar","compressed","tar","gzip")),
 OTHER("其他", emptySet(), emptySet());
 companion object {
  fun of(name:String,mime:String):LibraryKind {
   val ext=name.substringAfterLast('.',"").lowercase();val type=mime.lowercase()
   return entries.firstOrNull{it !in listOf(ALL,OTHER) && (ext in it.extensions || it.mimeHints.any{hint->type.contains(hint)})}?:OTHER
  }
  fun parse(value:String):LibraryKind = entries.firstOrNull{it.name.equals(value,true)||it.label==value}
   ?:throw IllegalArgumentException("文件类型请选择：${entries.joinToString("、"){it.label}}")
  // Only static enum values are interpolated; user queries remain bound SQL arguments.
  val sqlCase:String by lazy { "CASE "+entries.filter{it !in listOf(ALL,OTHER)}.joinToString(" "){kind->
   val checks=kind.extensions.map{"lower(name) LIKE '%.$it'"}+kind.mimeHints.map{"instr(lower(mime),'$it')>0"}
   "WHEN (${checks.joinToString(" OR ")}) THEN '${kind.name}'"
  }+" ELSE 'OTHER' END" }
 }
}

val LibraryDocument.kind get()=LibraryKind.of(name,mime)
fun libraryFileSize(bytes:Long):String=when{
 bytes<1024->"$bytes B"
 bytes<1024*1024->"${(bytes+1023)/1024} KB"
 else->"%.1f MB".format(java.util.Locale.ROOT,bytes/1048576.0)
}

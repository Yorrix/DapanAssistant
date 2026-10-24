package cn.dapan.assistant

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Offline integration probe. Requires a separately installed .audit emulator package. */
class AssistantAuditProbeActivity : Activity() {
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  if (packageName != "cn.dapan.assistant.audit" ||
   (!Build.MODEL.contains("sdk_gphone") && !Build.FINGERPRINT.contains("generic"))) {
   finish(); return
  }
  val phase = intent.getStringExtra("phase") ?: "write"
  Thread {
   val report = JSONObject().put("phase", phase).put("package", packageName)
    .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT).put("networkRequests", 0)
   val checks = JSONArray()
   fun verify(name: String, block: () -> Unit) {
    val start = System.nanoTime()
    checks.put(JSONObject().put("name", name).also { item ->
     try { block(); item.put("passed", true) }
     catch (e: Throwable) { item.put("passed", false).put("error", "${e.javaClass.simpleName}: ${e.message}") }
     item.put("milliseconds", (System.nanoTime() - start) / 1_000_000)
    })
   }
   val scoped = AuditContext(this)
   try {
    verify("Storage namespaces are isolated") {
     check(scoped.filesDir.canonicalPath.contains("/assistant-audit/"))
     check(scoped.getDatabasePath("library-v1.db").canonicalPath.contains("/assistant-audit/"))
     check(scoped.applicationContext === scoped)
    }
    LibraryStore(scoped).use { library ->
     if (phase == "write") {
      check(library.all().isEmpty()) { "Run with a freshly installed audit package" }
      fun save(name: String, mime: String, body: ByteArray): LibraryDocument {
       val file = File(scoped.cacheDir, name).apply { writeBytes(body) }
       return library.saveAttachment(AssistantFile("attachment-$name", name, mime, file.length(), file.path, ""))
      }
      fun text(name: String, body: String) = save(name, "text/plain", body.toByteArray())
      fun office(name: String, entries: Map<String, String>): LibraryDocument {
       val file = File(scoped.cacheDir, name)
       ZipOutputStream(file.outputStream()).use { zip ->
        entries.forEach { (path, body) -> zip.putNextEntry(ZipEntry(path)); zip.write(body.toByteArray()); zip.closeEntry() }
       }
       return library.saveAttachment(AssistantFile("attachment-$name", name, "application/octet-stream", file.length(), file.path, ""))
      }
      val longText = "期末资料\n" + "课堂笔记与练习。\n".repeat(6000) + "报名截止：10月28日，携带玉兰卡。\n"
      val longDoc = text("长篇资料.txt", longText)
      verify("UTF-8 original and entire text retained") {
       check(File(longDoc.path).readText() == longText && longDoc.text == longText)
       check(longDoc.id != "attachment-长篇资料.txt")
      }
      verify("Multi-keyword tail search returns actual offsets") {
       val hit = library.searchResults("报名 玉兰卡").single { it.document.id == longDoc.id }
       check(hit.match.matchedTerms.toSet() == setOf("报名", "玉兰卡"))
       check(hit.match.evidence.any { it.part.start > 50000 && it.quote.contains("玉兰卡") })
       hit.match.evidence.forEach { check(longDoc.text.substring(it.part.start, it.part.end).contains(it.quote)) }
      }
      verify("Read beyond initial 30,000 characters") {
       val result = LibraryRetrieval.document(longDoc, "", 50000, 1000)
       check(result.getString("text") == longDoc.text.substring(50000, 51000))
       check(result.getInt("startOffset") == 50000 && result.getInt("nextOffset") == 51000)
      }
      val rules = text("资料命名.md", "# 提交规则\n文件命名\n请使用：学号_姓名_作品名称.zip\n提交截止10月30日。\n")
      verify("Heading query includes separately cited answer context") {
       val result = LibraryRetrieval.document(rules, "文件命名")
       val evidence = result.getJSONArray("evidence").getJSONObject(0)
       check(evidence.getString("quote").contains("文件命名"))
       val context = evidence.getJSONArray("context").objects()
       check(context.any { it.getString("quote").contains("学号_姓名_作品名称.zip") && it.getInt("paragraph") != evidence.getInt("paragraph") })
       context.forEach { check(rules.text.substring(it.getInt("startOffset"), it.getInt("endOffset")) == it.getString("quote")) }
      }
      verify("Search-files result preserves document ID and answer context") {
       val result = LibraryRetrieval.search(library.searchResults("文件命名"), "文件命名")
       check(result.getJSONArray("items").objects().any { it.getString("id") == rules.id && it.toString().contains("学号_姓名_作品名称.zip") })
      }
      verify("GB18030 text decoding") {
       val doc = save("旧编码.txt", "text/plain", "晚饭后到商业街领取玉兰卡。".toByteArray(charset("GB18030")))
       check(doc.text.contains("商业街") && library.search("玉兰卡").any { it.id == doc.id })
      }
      verify("DOCX extraction and search") {
       val doc = office("实验要求.docx", mapOf("word/document.xml" to """<w:document xmlns:w="urn:word"><w:body><w:p><w:r><w:t>实验报告必须包括误差分析。</w:t></w:r></w:p></w:body></w:document>"""))
       check(doc.text.contains("误差分析") && library.search("误差分析").single().id == doc.id)
      }
      verify("PPTX natural slide order and slide-specific evidence") {
       val doc = office("课堂演示.pptx", mapOf(
        "ppt/slides/slide10.xml" to "<s><p><t>第十个主题</t></p></s>",
        "ppt/slides/slide2.xml" to "<s><p><t>极限定义</t></p></s>",
        "ppt/slides/slide1.xml" to "<s><p><t>课程介绍</t></p></s>"))
       check(doc.text.indexOf("课程介绍") < doc.text.indexOf("极限定义"))
       check(doc.text.indexOf("极限定义") < doc.text.indexOf("第十个主题"))
       val evidence = LibraryRetrieval.document(doc, "极限定义").getJSONArray("evidence").getJSONObject(0)
       check(evidence.getInt("slide") == 2)
      }
      verify("XLSX cells and shared strings remain locatable") {
       val doc = office("材料表.xlsx", mapOf(
        "xl/sharedStrings.xml" to "<sst><si><t>物理实验室</t></si></sst>",
        "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row><c r="A2" t="s"><v>0</v></c><c r="B2"><v>18</v></c></row></sheetData></worksheet>"""))
       val evidence = LibraryRetrieval.document(doc, "物理实验室").getJSONArray("evidence").getJSONObject(0)
       check(evidence.getString("sheet") == "工作表 1" && evidence.getString("cells").contains("A2"))
       check(evidence.getString("quote").contains("A2=物理实验室"))
      }
      verify("PDF Android text extraction and page evidence") {
       val file = File(scoped.cacheDir, "requirements.pdf")
       val pdf = PdfDocument()
       try {
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, 1).create())
        page.canvas.drawText("Submit report before Friday", 40f, 70f, Paint().apply { textSize = 24f })
        pdf.finishPage(page); file.outputStream().use(pdf::writeTo)
       } finally { pdf.close() }
       val doc = library.saveAttachment(AssistantFile("attachment-pdf", file.name, "application/pdf", file.length(), file.path, ""))
       check(doc.text.contains("Submit report"))
       check(LibraryRetrieval.document(doc, "Friday").getJSONArray("evidence").getJSONObject(0).getInt("page") == 1)
      }
      verify("Unknown binary retains original without fabricated text") {
       val doc = save("机器数据.bin", "application/octet-stream", byteArrayOf(0, 2, 3, 127, -1))
       check(doc.text.isBlank() && File(doc.path).readBytes().contentEquals(byteArrayOf(0, 2, 3, 127, -1)))
       check(LibraryRetrieval.document(doc, "").getString("mode") == "unavailable")
      }
      verify("Broken document retains original and reports unreadable state") {
       val doc = save("损坏文档.docx", "application/octet-stream", "not a zip".toByteArray())
       check(doc.text.isBlank() && doc.state.contains("暂不可读") && File(doc.path).isFile)
      }
      verify("Rename and remark search keep ID and text version") {
       library.edit(rules.id, "提交命名规则.md", "课程项目需要参考")
       val doc = library.find(rules.id)!!
       check(doc.name == "提交命名规则.md" && doc.textVersion == rules.textVersion)
       val hit = library.searchResults("课程项目").single { it.document.id == rules.id }
       check(hit.match.evidence.isEmpty())
      }
      verify("Type filtering excludes incompatible files") {
       val docs = library.search("", kind = LibraryKind.DOCUMENT)
       check(docs.isNotEmpty() && docs.all { it.kind == LibraryKind.DOCUMENT })
       check(library.search("", kind = LibraryKind.IMAGE).isEmpty())
      }
      verify("SQL-like query is treated as text") {
       val before = library.all().map { it.id }.toSet()
       val actual = library.search("' OR 1=1 --").map { it.id }.toSet()
       // OR is an ordinary search term and may legitimately match "report".
       check(actual == library.search("OR").map { it.id }.toSet() && actual.size < before.size)
       check(library.all().map { it.id }.toSet() == before)
      }
      verify("Index limit is explicit and still searches its end") {
       val doc = text("超长资料.txt", "甲".repeat(199950) + "边界有效答案" + "乙".repeat(1000))
       check(doc.text.length == 200000 && LibrarySearch.isPartial(doc.state))
       check(library.search("边界有效答案").single().id == doc.id)
      }
      verify("Emoji pagination never starts inside surrogate pair") {
       val doc = text("表情.txt", "资料😀玉兰卡")
       val result = LibraryRetrieval.document(doc, "", 3, 600)
       check(result.getInt("startOffset") == 2 && result.getString("text").startsWith("😀"))
      }
      verify("Result limit and file pagination") {
       repeat(20) { text("分期资料$it.txt", "共同检索词：归档条目$it") }
       check(library.search("共同检索词", limit = 3).size == 3)
       val first = LibraryRetrieval.search(library.searchResults("共同检索词"), "共同检索词")
       val second = LibraryRetrieval.search(library.searchResults("共同检索词"), "共同检索词", first.getInt("nextOffset"))
       check(first.getInt("returned") == 12 && second.getInt("returned") == 8 && !second.getBoolean("hasMore"))
      }
      verify("Delete removes original and indexed record") {
       val doc = text("临时删除.txt", "唯一删除标记")
       library.delete(doc.id)
       check(library.find(doc.id) == null && !File(doc.path).exists() && library.search("唯一删除标记").isEmpty())
      }
      verify("Delete refuses paths outside owned documents directory") {
       val doc = text("路径检查.txt", "保留")
       val outside = File(scoped.cacheDir, "must-survive.txt").apply { writeText("untouched") }
       library.writableDatabase.execSQL("UPDATE documents SET path=? WHERE id=?", arrayOf(outside.path, doc.id))
       check(runCatching { library.delete(doc.id) }.isFailure && outside.readText() == "untouched")
       library.writableDatabase.execSQL("UPDATE documents SET path=? WHERE id=?", arrayOf(doc.path, doc.id))
      }
      val retryFile = File(scoped.cacheDir, "附件重试.txt").apply { writeText("重复保存仅一份，资料真实ID与附件ID不同。") }
      val retryAttachment = AssistantFile("audit-attachment-retry", retryFile.name, "text/plain", retryFile.length(), retryFile.path, "")
      verify("Same attachment retry preserves ID and creates only one document") {
       val before = library.all().size
       val first = library.saveAttachment(retryAttachment); val second = library.saveAttachment(retryAttachment)
       check(first.id == second.id && first.id != retryAttachment.id && library.all().size == before + 1)
      }
      verify("Distinct attachment IDs with identical bytes remain separate") {
       val before = library.all().size
       val first = library.saveAttachment(retryAttachment)
       val second = library.saveAttachment(retryAttachment.copy(id = "audit-attachment-different"))
       check(first.id != second.id && library.all().size == before + 1)
      }
      verify("Deleted attachment can be saved again") {
       val first = library.saveAttachment(retryAttachment); library.delete(first.id)
       val second = library.saveAttachment(retryAttachment)
       check(second.id == first.id && File(second.path).readText() == retryFile.readText())
      }
      val missing = retryAttachment.copy(id = "audit-missing-attachment", name = "缺失文件.txt", path = File(scoped.cacheDir, "does-not-exist").path)
      verify("Batch save partial failure names successes and failures") {
       val before = library.all().size
       val result = kotlinx.coroutines.runBlocking { library.saveAttachments(listOf(missing, retryAttachment)) }
       check(result.status == "partial" && result.saved.size == 1 && result.failed.size == 1)
       check(result.saved.single().reused && result.receipt().contains("1 个未保存") && result.receipt().contains("缺失文件.txt"))
       val evidence = JSONObject(result.evidence())
       check(evidence.getInt("successCount") == 1 && evidence.getInt("failedCount") == 1)
       check(evidence.getJSONArray("savedFiles").getJSONObject(0).getString("id") != retryAttachment.id)
       check(library.all().size == before)
      }
      verify("All failed attachments cannot produce successful receipt") {
       val result = kotlinx.coroutines.runBlocking { library.saveAttachments(listOf(missing)) }
       check(result.status == "failed" && result.saved.isEmpty() && runCatching { result.receipt() }.isFailure)
      }
      verify("Concurrent attachment saves produce one persistent document") {
       val before = library.all().size
       val attachment = retryAttachment.copy(id = "audit-concurrent-attachment")
       val results = List(4) { java.util.concurrent.CompletableFuture.supplyAsync { LibraryStore(scoped).use { it.saveAttachment(attachment).id } } }.map { it.get() }
       check(results.distinct().size == 1 && library.all().size == before + 1)
      }
      verify("Existing saved attachment survives source-cache deletion") {
       val before = library.all().size
       val saved = library.saveAttachment(retryAttachment); check(retryFile.delete())
       check(library.saveAttachment(retryAttachment).id == saved.id && library.all().size == before)
      }
      val chat = ChatStore(scoped)
      val lineId = chat.add("assistant", "已存入资料库。")
      val evidence = LibraryRetrieval.document(library.find(rules.id)!!, "文件命名").toString()
      chat.attachEvidence(lineId, evidence)
      verify("Chat evidence is hidden from displayed text and present in model context") {
       val line = chat.lines.single()
       check(line.text == "已存入资料库。" && !line.text.contains(rules.id))
       check(line.modelText.contains(rules.id) && line.modelText.contains("学号_姓名_作品名称.zip"))
      }
      val longLine = chat.add("assistant", "长回复".repeat(4000))
      chat.attachEvidence(longLine, "真实文件ID=${rules.id}")
      verify("Long chat text never clips attached real object IDs") {
       check(chat.lines.last().modelText.contains(rules.id) && chat.lines.last().modelText.length < 7000)
      }
      verify("Chat operation receipt survives evidence attachment") {
       val id = chat.operation(AssistantOperation(OperationStatus.DONE, "已保存资料", listOf(OperationPreview("新增", "提交命名规则.md"))), "保存完成")
       chat.attachEvidence(id, evidence)
       check(chat.lines.last().operation?.status == OperationStatus.DONE && chat.lines.last().modelText.contains(rules.id))
      }
      scoped.getSharedPreferences("conversation", 0).edit().putBoolean("audit_flush", true).commit()
      runToolStorageAudit(scoped, phase, ::verify)
      File(scoped.filesDir, "expected.json").writeText(JSONObject().put("fileId", rules.id).put("chatLineId", lineId).put("count", library.all().size).toString())
     } else {
      val expected = JSONObject(File(scoped.filesDir, "expected.json").readText())
      verify("SQLite document count survives process restart") { check(library.all().size == expected.getInt("count")) }
      verify("File contents and evidence survive process restart") {
       val doc = library.find(expected.getString("fileId"))!!
       check(doc.name == "提交命名规则.md" && File(doc.path).isFile && doc.text.contains("学号_姓名_作品名称.zip"))
       check(LibraryRetrieval.document(doc, "文件命名").toString().contains("学号_姓名_作品名称.zip"))
      }
      val chat = ChatStore(scoped)
      verify("Chat evidence survives process restart") {
       val line = chat.lines.single { it.id == expected.getString("chatLineId") }
       check(line.text == "已存入资料库。" && line.evidence.contains(expected.getString("fileId")))
      }
      verify("Executed operation status survives process restart") {
       check(chat.lines.last().operation?.status == OperationStatus.DONE && chat.lines.last().evidence.isNotBlank())
      }
      verify("Chat clear removes evidence and visible conversation together") {
       chat.clear(); check(ChatStore(scoped).lines.isEmpty())
      }
      runToolStorageAudit(scoped, phase, ::verify)
     }
    }
   } catch (e: Throwable) { report.put("fatal", "${e.javaClass.simpleName}: ${e.message}") }
   report.put("checks", checks).put("passed", checks.objects().count { it.optBoolean("passed") })
    .put("failed", checks.objects().count { !it.optBoolean("passed") } + if (report.has("fatal")) 1 else 0)
   File(filesDir, "assistant-audit-$phase.json").writeText(report.toString(2))
   runOnUiThread { finish() }
  }.start()
 }
}

private class AuditContext(base: Context) : ContextWrapper(base) {
 private val root = File(base.filesDir, "assistant-audit").apply { mkdirs() }
 override fun getApplicationContext(): Context = this
 override fun getFilesDir() = File(root, "files").apply { mkdirs() }
 override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
 override fun getDatabasePath(name: String) = File(File(root, "databases").apply { mkdirs() }, name)
 override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = baseContext.getSharedPreferences("assistant_audit_$name", mode)
 override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?): SQLiteDatabase =
  SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)
 override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?, errorHandler: DatabaseErrorHandler?): SQLiteDatabase =
  SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)
 override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
}

package cn.dapan.assistant

import android.content.*
import android.database.sqlite.*
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.*

object LibraryChanges{var revision by mutableIntStateOf(0)}
data class LibraryDocument(val id:String,val name:String,val mime:String,val path:String,val size:Long,val created:Long,val remark:String,val text:String,val state:String,val source:String,val contentHash:String="",val textVersion:String=""){
 val textFormat get()=LibrarySearch.format(name,mime)
 fun json(query:String=""):JSONObject{
  val result=JSONObject().put("id",id).put("name",name).put("mime",mime).put("bytes",size).put("created",created).put("remark",remark)
   .put("indexState",state).put("indexVersion",textVersion.ifBlank{"legacy"}).put("indexedCharacters",text.length).put("partialIndex",LibrarySearch.isPartial(state))
  if(query.isNotBlank()){
   val match=LibrarySearch.match(name,remark,text,query,textFormat)
   result.put("matchedTerms",JSONArray(match.matchedTerms)).put("evidence",JSONArray(match.evidence.map{evidenceJson(it)})).put("excerpt",match.evidence.firstOrNull()?.quote?:"")
  }
  return result
 }
 fun evidenceJson(e:LibraryEvidence):JSONObject=JSONObject().put("fileId",id).put("fileName",name)
  .put("indexVersion",textVersion.ifBlank{"legacy"}).put("startOffset",e.part.start).put("endOffset",e.part.end)
  .put("location",e.part.label).put("paragraph",e.part.paragraph).put("page",e.part.page?:JSONObject.NULL)
  .put("slide",e.part.slide?:JSONObject.NULL).put("sheet",e.part.sheet?:JSONObject.NULL).put("cells",e.part.cells?:JSONObject.NULL)
  .put("quote",e.quote).put("matchedTerms",JSONArray(e.matchedTerms)).put("citation","$name · ${e.part.label}")
}
data class LibrarySearchResult(val document:LibraryDocument,val match:LibraryTextMatch)
data class LibrarySavedAttachment(val attachmentId:String,val document:LibraryDocument,val reused:Boolean)
data class LibraryAttachmentFailure(val attachmentId:String,val name:String,val reason:String)
data class LibraryAttachmentSaveReport(val saved:List<LibrarySavedAttachment>,val failed:List<LibraryAttachmentFailure>){
 val status get()=when{saved.isEmpty()->"failed";failed.isNotEmpty()->"partial";else->"complete"}
 fun evidence():String=JSONObject().put("module","资料库").put("status",status)
  .put("successCount",saved.size).put("failedCount",failed.size)
  .put("savedFiles",JSONArray(saved.map{it.document.json().put("attachmentId",it.attachmentId).put("alreadySaved",it.reused)}))
  .put("failedFiles",JSONArray(failed.map{JSONObject().put("attachmentId",it.attachmentId).put("name",it.name).put("reason",it.reason)}))
  .put("hint","id为持久资料库ID，可用于lookup；attachmentId仅用于识别原附件，不可用于读取资料库。同一附件可安全重试，不会重复入库；请只把 savedFiles 视为已保存。")
  .toString()
 fun receipt():String{
  val failures=failed.joinToString("\n"){"未保存：${it.name} · ${it.reason}"}
  check(saved.isNotEmpty()){"没有文件保存成功（${failed.size} 个失败）。\n$failures"}
  val heading=if(failed.isEmpty())"已收纳 ${saved.size} 个文件。"else"部分完成：已收纳 ${saved.size} 个文件，${failed.size} 个未保存。"
  val successes=saved.joinToString("\n"){"${if(it.reused)"已在资料库（未重复添加）"else"已保存"}：${it.document.name}"}
  return listOf(heading,successes,failures).filter{it.isNotBlank()}.joinToString("\n")
 }
}
object LibraryIndexState{
 const val WAITING="等待文字识别"
 const val NO_KEY="等待登录公用 AI 或配置自备 API"
 const val PUBLIC_CONSENT="待选择公用图片识别 · 打开文字索引后确认"
 const val RUNNING="正在识别文字"
 fun needsAiSetup(state:String)=state==NO_KEY||state=="等待配置 DeepSeek 密钥"
 fun resumable(state:String)=state in setOf(WAITING,RUNNING)||needsAiSetup(state)
}
class LibraryStore(private val c:Context):SQLiteOpenHelper(c,"library-v1.db",null,2){
 override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE documents(id TEXT PRIMARY KEY,name TEXT NOT NULL,mime TEXT NOT NULL,path TEXT NOT NULL UNIQUE,size INTEGER,created INTEGER,remark TEXT,body TEXT,state TEXT,source TEXT,content_hash TEXT NOT NULL DEFAULT '',text_version TEXT NOT NULL DEFAULT '')")}
 override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){
  if(old<2){
   db.execSQL("ALTER TABLE documents ADD COLUMN content_hash TEXT NOT NULL DEFAULT ''")
   db.execSQL("ALTER TABLE documents ADD COLUMN text_version TEXT NOT NULL DEFAULT ''")
   db.execSQL("UPDATE documents SET state=? WHERE body='' AND (${LibraryKind.sqlCase})='IMAGE'",arrayOf("尚未识别 · 点按识别"))
  }
 }
 private fun row(cursor:android.database.Cursor)=LibraryDocument(cursor.getString(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getLong(4),cursor.getLong(5),cursor.getString(6),cursor.getString(7),cursor.getString(8),cursor.getString(9),cursor.getString(10),cursor.getString(11))
 fun all():List<LibraryDocument> = readableDatabase.rawQuery("SELECT * FROM documents ORDER BY created DESC",null).use{cur->buildList{while(cur.moveToNext())add(row(cur))}}
 fun find(id:String)=readableDatabase.rawQuery("SELECT * FROM documents WHERE id=?",arrayOf(id)).use{if(it.moveToFirst())row(it)else null}
 fun search(query:String,limit:Int=100,kind:LibraryKind=LibraryKind.ALL):List<LibraryDocument> = searchResults(query,limit,kind).map{it.document}
 fun searchResults(query:String,limit:Int=100,kind:LibraryKind=LibraryKind.ALL):List<LibrarySearchResult>{
  val words=LibrarySearch.terms(query);val conditions=mutableListOf<String>();val args=mutableListOf<String>()
  if(words.isNotEmpty()){conditions+="("+words.joinToString(" OR "){"instr(lower(name || ' ' || remark || ' ' || body),lower(?))>0"}+")";args+=words}
  if(kind!=LibraryKind.ALL){conditions+="(${LibraryKind.sqlCase})=?";args+=kind.name}
  val where=if(conditions.isEmpty())""else" WHERE "+conditions.joinToString(" AND ")
  val bound=limit.coerceIn(1,500);val results=ArrayList<LibrarySearchResult>()
  val order=compareByDescending<LibrarySearchResult>{it.match.score}.thenByDescending{it.document.created}.thenBy{it.document.id}
  // Evaluate every candidate but keep only the best bounded set of document bodies in memory.
  readableDatabase.rawQuery("SELECT * FROM documents$where ORDER BY created DESC"+(if(words.isEmpty())" LIMIT $bound"else""),args.toTypedArray()).use{cur->
   while(cur.moveToNext()){
    val d=row(cur);val match=LibrarySearch.match(d.name,d.remark,d.text,query,d.textFormat,maxEvidence=3)
    if(words.isEmpty()||match.matchedTerms.isNotEmpty()){results+=LibrarySearchResult(d,match);if(results.size>bound){results.sortWith(order);results.removeAt(results.lastIndex)}}
   }
  }
  return results.sortedWith(order).take(bound)
 }
 fun import(uri:Uri,autoIndex:Boolean=true):LibraryDocument{
  var name="未命名文件";c.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())name=it.getString(0)}
  return copy(name,c.contentResolver.getType(uri)?:"application/octet-stream","导入",autoIndex){c.contentResolver.openInputStream(uri)?:error("文件不可读取")}
 }
 fun saveAttachment(a:AssistantFile):LibraryDocument=saveAttachmentResult(a).document
 private fun saveAttachmentResult(a:AssistantFile):LibrarySavedAttachment{
  val id=attachmentDocumentId(a.id)
  return synchronized(documentLock(id)){
   val previous=find(id)
   if(previous!=null){
    check(File(previous.path).isFile){"资料库副本已丢失，请先删除这条失效记录后重新保存"}
    LibrarySavedAttachment(a.id,previous,true)
   }else{
    check(File(a.path).isFile){"原附件已失效，请重新选择这个文件"}
    LibrarySavedAttachment(a.id,copy(a.name,a.mime,"AI栏附件",true,id){File(a.path).inputStream()},false)
   }
  }
 }
 suspend fun saveAttachments(attachments:List<AssistantFile>):LibraryAttachmentSaveReport{
  require(attachments.isNotEmpty()&&attachments.map{it.id}.distinct().size==attachments.size){"附件列表为空或重复"}
  val saved=mutableListOf<LibrarySavedAttachment>();val failed=mutableListOf<LibraryAttachmentFailure>()
  attachments.forEach{a->
   currentCoroutineContext().ensureActive()
   try{saved+=saveAttachmentResult(a)}
   catch(e:CancellationException){throw e}
   catch(e:Exception){failed+=LibraryAttachmentFailure(a.id,a.name,e.message?.take(300)?:"文件保存失败")}
  }
  return LibraryAttachmentSaveReport(saved,failed)
 }
 private fun copy(name:String,mime:String,source:String,autoIndex:Boolean,id:String=UUID.randomUUID().toString(),input:()->java.io.InputStream):LibraryDocument{
  val dir=File(c.filesDir,"documents/library").apply{mkdirs()};val file=File(dir,"$id.bin")
  try{
   input().use{ins->file.outputStream().use{out->val buffer=ByteArray(65536);var size=0L;while(true){val n=ins.read(buffer);if(n<0)break;size+=n;require(size<=256L*1024*1024){"单个文件目前最多256MB"};out.write(buffer,0,n)}}}
   register(id,name,mime,file,source)
   // Saving the original is already complete; indexing failure must not encourage a duplicate import.
   if(autoIndex||LibraryKind.of(name,mime)!=LibraryKind.IMAGE)try{index(id)}
   catch(e:CancellationException){throw e}
   catch(e:Exception){runCatching{setIndexState(id,"文件已保存 · 文字索引未完成，可重试索引")}}
   return find(id)?:error("文件保存状态无法确认")
  }catch(e:Exception){if(find(id)==null)file.delete();throw e}
 }
 private fun register(id:String,name:String,mime:String,file:File,source:String){
  val image=LibraryKind.of(name,mime)==LibraryKind.IMAGE
  val values=ContentValues().apply{put("id",id);put("name",name.take(200));put("mime",mime);put("path",file.canonicalPath);put("size",file.length());put("created",file.lastModified());put("remark","");put("body","");put("state",if(image)LibraryIndexState.WAITING else "等待索引");put("source",source);put("content_hash",if(image)fileHash(file)else"")}
  writableDatabase.insertOrThrow("documents",null,values)
 }
 /** The original has been saved before the remote image request is queued. */
 fun index(id:String,force:Boolean=false,publicImageConsent:Boolean=false){
  val doc=find(id)?:error("文件已不存在")
  if(doc.kind==LibraryKind.IMAGE){
   if(!force&&doc.text.isNotBlank())return
   if(force||LibraryIndexState.resumable(doc.state)){setIndexState(id,LibraryIndexState.WAITING);LibraryImageIndexQueue.enqueue(c,id,force,publicImageConsent)}
   return
  }
  val result=runCatching{AssistantFiles.extractText(File(doc.path),doc.name,doc.mime)}.getOrElse{"" to "正文暂不可读，可搜名称和备注"}
  applyIndex(id,result.first,result.second)
 }
 fun setIndexState(id:String,state:String){writableDatabase.update("documents",ContentValues().apply{put("state",state)},"id=?",arrayOf(id))}
 fun applyIndex(id:String,text:String,state:String){
  val body=text.take(200000)
  writableDatabase.update("documents",ContentValues().apply{put("body",body);put("state",if(text.length>body.length)"部分文字 · 索引达到 20 万字上限"else state);put("text_version",digest(body.toByteArray(Charsets.UTF_8)))},"id=?",arrayOf(id))
 }
 internal fun imageHash(doc:LibraryDocument):String = doc.contentHash.ifBlank{fileHash(File(doc.path)).also{hash->writableDatabase.update("documents",ContentValues().apply{put("content_hash",hash)},"id=?",arrayOf(doc.id))}}
 internal fun sameImage(hash:String,id:String):LibraryDocument?=readableDatabase.rawQuery("SELECT * FROM documents WHERE content_hash=? AND id<>? AND body<>'' AND (state LIKE '图片文字%' OR state LIKE '部分文字%') AND instr(state,'保留上次')=0 ORDER BY created DESC LIMIT 1",arrayOf(hash,id)).use{if(it.moveToFirst())row(it)else null}
 fun resumeImageIndexing(){all().filter{it.kind==LibraryKind.IMAGE&&LibraryIndexState.resumable(it.state)}.forEach{LibraryImageIndexQueue.enqueue(c,it.id)}}
 fun edit(id:String,name:String,remark:String){require(name.isNotBlank()&&name.length<=200&&remark.length<=4000){"名称1～200字，备注最多4000字"};check(writableDatabase.update("documents",ContentValues().apply{put("name",name.trim());put("remark",remark)},"id=?",arrayOf(id))==1){"文件已不存在"}}
 fun delete(id:String){synchronized(documentLock(id)){val doc=find(id)?:error("文件已不存在");val file=File(doc.path).canonicalFile;val root=File(c.filesDir,"documents").canonicalFile;require(file.toPath().startsWith(root.toPath())){"文件位置无效"};check(!file.exists()||file.delete()){"无法删除本机文件"};writableDatabase.delete("documents","id=?",arrayOf(id))}}
 fun open(id:String){val d=find(id)?:error("文件已不存在");val f=File(d.path);check(f.isFile){"本机文件已丢失"};val uri=FileProvider.getUriForFile(c,"${c.packageName}.files",f,d.name);c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,d.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))}
 /** Discover app-owned scan outputs without moving them; copy legacy external references once. */
 fun migrate(allowRemoteIndex:Boolean=true){
  val existing=all().map{it.path}.toSet();val docs=File(c.filesDir,"documents")
  if(docs.exists())docs.walkTopDown().onEnter{it.name!="library"}.filter{it.isFile&&it.extension.lowercase() in setOf("pdf","jpg","jpeg","png")}.forEach{file->if(file.canonicalPath !in existing){val mime=if(file.extension.equals("pdf",true))"application/pdf"else"image/${if(file.extension=="png")"png"else"jpeg"}";val id=UUID.randomUUID().toString();register(id,file.name,mime,file,"扫描");if(!mime.startsWith("image/"))index(id)}}
  val prefs=c.getSharedPreferences("files",0);val old=prefs.getStringSet("uris",emptySet()).orEmpty().toSet();val remaining=old.filter{raw->runCatching{import(Uri.parse(raw),autoIndex=allowRemoteIndex)}.isFailure}.toSet();if(remaining!=old)prefs.edit().putStringSet("uris",remaining).commit()
  all().filter{it.state=="等待索引"&&it.kind!=LibraryKind.IMAGE}.forEach{index(it.id)}
  if(allowRemoteIndex)resumeImageIndexing()
 }
 companion object{
  private val documentLocks=Array(32){Any()}
  private fun documentLock(id:String)=documentLocks[(id.hashCode() and Int.MAX_VALUE)%documentLocks.size]
  internal fun attachmentDocumentId(attachmentId:String):String{
   require(attachmentId.isNotBlank()){"附件 ID 不能为空"}
   // The attachment identity, not its contents: separately chosen copies remain separate records.
   return "attachment-"+UUID.nameUUIDFromBytes(("dapan-library-attachment-v1\u0000"+attachmentId).toByteArray(Charsets.UTF_8))
  }
  private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
  private fun fileHash(file:File):String{val digest=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val bytes=ByteArray(65536);while(true){val n=input.read(bytes);if(n<0)break;digest.update(bytes,0,n)}};return digest.digest().joinToString(""){"%02x".format(it)}}
 }
}

/** Process-scoped serial queue; leaving the screen does not interrupt the current image. */
private object LibraryImageIndexQueue{
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 private val serial=Mutex();private val active=mutableSetOf<String>()
 fun enqueue(context:Context,id:String,force:Boolean=false,publicImageConsent:Boolean=false){
  synchronized(active){if(!active.add(id))return}
  val app=context.applicationContext
  scope.launch{
   try{serial.withLock{LibraryStore(app).use{lib->
    val doc=lib.find(id)?:return@use
    if(!LibraryIndexState.resumable(doc.state))return@use
    val hash=lib.imageHash(doc);val cached=if(force)null else lib.sameImage(hash,id)
    if(cached!=null){lib.applyIndex(id,cached.text,cached.state);return@use}
    if(!ImageTextIndexer.available(app)){lib.setIndexState(id,LibraryIndexState.NO_KEY);return@use}
    if(SharedAi.mode(app)==SharedAi.Mode.SHARED&&!publicImageConsent){lib.setIndexState(id,LibraryIndexState.PUBLIC_CONSENT);return@use}
    lib.setIndexState(id,LibraryIndexState.RUNNING);withContext(Dispatchers.Main){LibraryChanges.revision++}
    val result=runInterruptible{ImageTextIndexer.extract(app,File(doc.path),publicImageConsent)}
    if(result.status=="missing_key")lib.setIndexState(id,LibraryIndexState.NO_KEY)
    else if(result.text.isNotBlank())lib.applyIndex(id,result.text,result.state)
    else if(doc.text.isNotBlank())lib.setIndexState(id,"${result.state} · 保留上次识别文字")
    else lib.applyIndex(id,"",result.state)
   }}}
   catch(e:CancellationException){throw e}
   catch(e:Exception){runCatching{LibraryStore(app).use{it.setIndexState(id,"识别未完成 · 点按重试")}}}
   finally{synchronized(active){active.remove(id)};withContext(NonCancellable+Dispatchers.Main){LibraryChanges.revision++}}
  }
 }
}

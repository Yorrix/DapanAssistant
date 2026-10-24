package cn.dapan.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.ZipFile

data class AssistantFile(val id:String,val name:String,val mime:String,val bytes:Long,val path:String,val hint:String)
data class FileContext(val text:String,val images:List<String>)
object AssistantFiles {
 const val MAX_BYTES=16*1024*1024
 const val MAX_FILES=4
 private val textExtensions=setOf("txt","md","csv","tsv","json","xml","html","htm","log","srt","vtt","ics","yaml","yml","py","kt","java","c","cpp","h","js","ts","css","sql","tex","ini","toml")
 private fun ext(name:String)=name.substringAfterLast('.',"").lowercase()
 private fun readableText(name:String,mime:String)=mime.startsWith("text/")||ext(name) in textExtensions||mime in setOf("application/json","application/xml")
 fun import(c:Context,uri:Uri):AssistantFile {
  var name="附件";var advertised:Long?=null
  c.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use{cursor->if(cursor.moveToFirst()){
   val ni=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(ni>=0&&!cursor.isNull(ni))name=cursor.getString(ni).take(160).replace(Regex("[\\r\\n\\t]")," ")
   val si=cursor.getColumnIndex(OpenableColumns.SIZE);if(si>=0&&!cursor.isNull(si))advertised=cursor.getLong(si)
  }}
  require((advertised?:0)<=MAX_BYTES){"$name 超过16MB，请选择较小的文件"}
  val mime=c.contentResolver.getType(uri)?:"application/octet-stream"
  val dir=File(c.cacheDir,"assistant-files").apply{mkdirs()};val id=UUID.randomUUID().toString();val file=File(dir,id)
  try{c.contentResolver.openInputStream(uri)?.use{input->file.outputStream().use{out->val buffer=ByteArray(8192);var total=0;while(true){val n=input.read(buffer);if(n<0)break;total+=n;require(total<=MAX_BYTES){"$name 超过16MB"};out.write(buffer,0,n)}}}?:error("无法读取 $name")}
  catch(e:Exception){file.delete();throw e}
  val hint=when{mime.startsWith("image/")->"图片";ext(name)=="pdf"||mime=="application/pdf"->"PDF · 最多读取前6页";ext(name) in setOf("docx","pptx","xlsx")->"文档 · 提取文字";readableText(name,mime)->"文本文件";else->"此格式仅提供文件信息，正文暂不可读"}
  return AssistantFile(id,name,mime,file.length(),file.absolutePath,hint)
 }
 fun remove(file:AssistantFile){File(file.path).delete()}
 fun prune(c:Context){File(c.cacheDir,"assistant-files").listFiles()?.filter{System.currentTimeMillis()-it.lastModified()>86400000}?.forEach{it.delete()}}
 private fun image(bitmap:Bitmap):String {val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.JPEG,82,out);return "data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP)}
 fun prepare(c:Context,files:List<AssistantFile>):FileContext {
  val content=StringBuilder();val images=mutableListOf<String>()
  for(file in files){
   val path=File(file.path);require(path.exists()){ "附件 ${file.name} 已失效，请重新选择" }
   content.append("\n附件：${file.name}；类型：${file.mime}；大小：${file.bytes}字节\n")
   when{
    file.mime.startsWith("image/")->{require(images.size<12){"本次图片和PDF页面合计超过12张，请分次发送"};val bitmap=ImageFiles.load(c,Uri.fromFile(path),1600);try{images+=image(bitmap)}finally{bitmap.recycle()};content.append("图片内容附在本次消息中。\n")}
    ext(file.name)=="pdf"||file.mime=="application/pdf"->{ParcelFileDescriptor.open(path,ParcelFileDescriptor.MODE_READ_ONLY).use{descriptor->PdfRenderer(descriptor).use{pdf->
     val count=minOf(pdf.pageCount,6);require(images.size+count<=12){"本次图片和PDF页面合计超过12张，请分次发送"};content.append("PDF共${pdf.pageCount}页，仅提供前${count}页图像；不可推测未提供页面。\n")
     for(i in 0 until count)pdf.openPage(i).use{page->val scale=1400f/maxOf(page.width,page.height);val bitmap=Bitmap.createBitmap((page.width*scale).toInt().coerceAtLeast(1),(page.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888);try{bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);images+=image(bitmap)}finally{bitmap.recycle()}}
    }}}
    ext(file.name) in setOf("docx","pptx","xlsx")->content.append(office(path,ext(file.name)))
    readableText(file.name,file.mime)->{
     val bytes=path.inputStream().use{limited(it,262144)}
     val decoded=runCatching{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}.getOrElse{String(bytes,charset("GB18030"))}
     require(!decoded.contains('\u0000')){"${file.name} 看起来是二进制文件，无法作为文本读取"}
     content.append(decoded.take(50000));if(path.length()>bytes.size||decoded.length>50000)content.append("\n[文件过长，仅提供开头部分]")
    }
    else->content.append("仅有上述文件信息；此格式正文未被读取或传给模型。不得声称已读懂该文件，请告知用户改用可读格式。")
   }
   content.append("\n[附件结束]\n")
  }
  require(content.length<=120000){"附件文字过多，请分次发送"}
  return FileContext(content.toString(),images)
 }
 fun extractText(path:File,name:String,mime:String):Pair<String,String>{
  if(ext(name)=="pdf"||mime=="application/pdf"){
   if(android.os.Build.VERSION.SDK_INT<35)return "" to "此系统暂不支持PDF正文索引；仍可搜名称和备注"
   val out=StringBuilder();var total=0
   ParcelFileDescriptor.open(path,ParcelFileDescriptor.MODE_READ_ONLY).use{descriptor->PdfRenderer(descriptor).use{pdf->total=pdf.pageCount
    for(i in 0 until minOf(pdf.pageCount,200)){if(out.length>=200000)break;pdf.openPage(i).use{page->out.append("\n第${i+1}页\n");page.textContents.forEach{out.append(it.text).append(' ')}}}
   }}
   val text=out.toString().take(200000)
   return text to if(text.replace(Regex("第[0-9]+页"),"").isBlank())"图片型PDF，尚无可检索正文；可添加备注"else if(total>200||out.length>=200000)"部分正文索引（最多200页/20万字）"else"PDF正文已索引"
  }
  if(ext(name) in setOf("docx","pptx","xlsx")){
   val text=office(path,ext(name),200000)
   return text to if(text.contains("[仅提供"))"部分正文索引（长度/页数限制）"else"文档文字已索引（不含图片与嵌入对象）"
  }
  if(readableText(name,mime)){
   val bytes=path.inputStream().use{limited(it,1024*1024)}
   val decoded=runCatching{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()}.getOrElse{String(bytes,charset("GB18030"))}
   require(!decoded.contains('\u0000')){"非文本内容"}
   return decoded.take(200000) to if(path.length()>bytes.size||decoded.length>200000)"部分正文索引（最多20万字）"else"正文已索引"
  }
  return "" to "可搜名称和备注；该格式尚不支持正文索引"
 }
 private fun limited(input:InputStream,limit:Int):ByteArray {val out=ByteArrayOutputStream();val buffer=ByteArray(8192);var remaining=limit;while(remaining>0){val n=input.read(buffer,0,minOf(buffer.size,remaining));if(n<0)break;out.write(buffer,0,n);remaining-=n};return out.toByteArray()}
 private fun parser(xml:String):XmlPullParser {require(!xml.contains("<!DOCTYPE",true)){"不支持带外部声明的文档"};return Xml.newPullParser().apply{setInput(StringReader(xml))}}
 private fun tags(xml:String,names:Set<String>):String {val p=parser(xml);val out=StringBuilder();while(p.eventType!=XmlPullParser.END_DOCUMENT){if(p.eventType==XmlPullParser.START_TAG&&p.name in names){out.append(p.nextText()).append(' ')}else if(p.eventType==XmlPullParser.END_TAG&&p.name in setOf("p","row"))out.append('\n');p.next()};return out.toString()}
 private fun office(file:File,type:String,maxCharacters:Int=50000):String=ZipFile(file).use{zip->
  var expanded=0
  fun read(name:String):String {val entry=zip.getEntry(name)?:return "";val bytes=zip.getInputStream(entry).use{limited(it,2*1024*1024+1)};expanded+=bytes.size;require(bytes.size<=2*1024*1024&&expanded<=6*1024*1024){"文档解压内容过大，请拆分文件"};return String(bytes,Charsets.UTF_8)}
  val out=StringBuilder()
  when(type){
   "docx"->out.append(tags(read("word/document.xml"),setOf("t")))
   "pptx"->{val slides=zip.entries().asSequence().map{it.name}.filter{it.matches(Regex("ppt/slides/slide[0-9]+\\.xml"))}.sortedBy{it.substringAfterLast("slide").substringBefore('.').toInt()}.toList();slides.take(30).forEachIndexed{i,name->out.append("\n第${i+1}张幻灯片：\n").append(tags(read(name),setOf("t")))};if(slides.size>30)out.append("\n[仅提供前30张幻灯片]")}
   "xlsx"->{val shared=mutableListOf<String>();val xml=read("xl/sharedStrings.xml");if(xml.isNotBlank()){val p=parser(xml);var cell=StringBuilder();while(p.eventType!=XmlPullParser.END_DOCUMENT){if(p.eventType==XmlPullParser.START_TAG&&p.name=="si")cell=StringBuilder();if(p.eventType==XmlPullParser.START_TAG&&p.name=="t")cell.append(p.nextText());if(p.eventType==XmlPullParser.END_TAG&&p.name=="si")shared+=cell.toString();p.next()}}
    val sheets=zip.entries().asSequence().map{it.name}.filter{it.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml"))}.sortedBy{it.substringAfterLast("sheet").substringBefore('.').toInt()}.toList()
    sheets.take(10).forEach{name->out.append("\n$name（单元格值，不重算公式）\n");val p=parser(read(name));var kind="";while(p.eventType!=XmlPullParser.END_DOCUMENT){if(p.eventType==XmlPullParser.START_TAG)when(p.name){"c"->{kind=p.getAttributeValue(null,"t")?:"";out.append(p.getAttributeValue(null,"r")).append('=')};"v","t"->{val value=p.nextText();out.append(if(kind=="s")shared.getOrNull(value.toIntOrNull()?:-1)?:"[无对应文本]"else value).append(' ')}};if(p.eventType==XmlPullParser.END_TAG&&p.name=="row")out.append('\n');p.next()}}
    if(sheets.size>10)out.append("\n[仅提供前10个工作表]")
   }
  }
  require(out.isNotBlank()){ "未从文档中提取到文字，请改用PDF或图片" }
  "[文档文字；未包含图片、样式和嵌入对象]\n"+out.take(maxCharacters)+(if(out.length>maxCharacters)"\n[仅提供开头${maxCharacters}字符]"else "")
 }
}

package cn.dapan.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** No storage or Android dependency: bounded decompression is checked before installation. */
internal object VocabularyArchive {
 private val sqliteHeader="SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
 const val MAX_BYTES=256L*1024*1024
 fun extract(source:InputStream,target:OutputStream,expectedBytes:Long,expectedSha256:String){
  require(expectedBytes in sqliteHeader.size.toLong()..MAX_BYTES){"词典安装信息不完整，请更新应用"}
  require(expectedSha256.matches(Regex("[a-fA-F0-9]{64}"))){"词典校验信息不完整，请更新应用"}
  val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
  val header=ByteArray(sqliteHeader.size);var headerCount=0;var written=0L
  GZIPInputStream(source).use{input->
   while(true){
    if(Thread.currentThread().isInterrupted)throw InterruptedIOException("词典安装已取消")
    val count=input.read(buffer);if(count<0)break
    written+=count
    if(written>expectedBytes)throw IOException("词典解压大小不符，请重新下载")
    if(headerCount<header.size){val copy=minOf(count,header.size-headerCount);buffer.copyInto(header,headerCount,0,copy);headerCount+=copy}
    digest.update(buffer,0,count);target.write(buffer,0,count)
   }
  }
  if(Thread.currentThread().isInterrupted)throw InterruptedIOException("词典安装已取消")
  if(written!=expectedBytes||!header.contentEquals(sqliteHeader))throw IOException("词典文件不完整，请重新下载")
  val actual=digest.digest().joinToString(""){"%02x".format(it.toInt() and 255)}
  if(!actual.equals(expectedSha256,true))throw IOException("词典校验失败，请重新下载")
 }
}

internal object VocabularyComponent {
 fun install(c:Context,archive:File,spec:ComponentSpec){
  check(!VocabularyState.running){"请先暂停课堂生词收听，再安装词典"}
  val destination=VocabularyDictionary.file(c)
  check(destination.parentFile?.let{it.isDirectory||it.mkdirs()}==true){"词典目录无法创建"}
  val temp=File.createTempFile("vocabulary-",".installing",destination.parentFile)
  try{
   archive.inputStream().use{input->FileOutputStream(temp).use{out->VocabularyArchive.extract(input,out,spec.unpackedBytes,spec.unpackedSha256);out.fd.sync()}}
   if(Thread.currentThread().isInterrupted)throw InterruptedIOException("词典安装已取消")
   SQLiteDatabase.openDatabase(temp.path,null,SQLiteDatabase.OPEN_READONLY).use{db->
    db.rawQuery("PRAGMA quick_check(1)",null).use{check(it.moveToFirst()&&it.getString(0)=="ok"){"词典数据库校验失败，请重试"}}
    db.rawQuery("SELECT word,phonetic,meaning,tags FROM words LIMIT 1",null).use{check(it.moveToFirst()){"词典缺少单词数据，请重试"}}
    db.rawQuery("SELECT form,lemma FROM forms LIMIT 1",null).use{check(it.moveToFirst()){"词典缺少词形数据，请重试"}}
   }
   if(Thread.currentThread().isInterrupted)throw InterruptedIOException("词典安装已取消")
   VocabularyDictionary.install(c,temp)
  }finally{temp.delete()}
 }
}

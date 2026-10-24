package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

class VocabularyArchiveTest {
 private fun raw()="SQLite format 3\u0000".toByteArray()+ByteArray(1024){it.toByte()}
 private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
 private fun gzip(bytes:ByteArray)=ByteArrayOutputStream().also{out->GZIPOutputStream(out).use{it.write(bytes)}}.toByteArray()
 private fun extract(compressed:ByteArray,size:Long,sha256:String)=ByteArrayOutputStream().also{VocabularyArchive.extract(ByteArrayInputStream(compressed),it,size,sha256)}.toByteArray()

 @Test fun completeMatchingSqliteArchiveExtractsExactly(){
  val bytes=raw()
  assertArrayEquals(bytes,extract(gzip(bytes),bytes.size.toLong(),digest(bytes)))
 }
 @Test fun wrongRawHashIsRejected(){
  val bytes=raw()
  assertThrows(IOException::class.java){extract(gzip(bytes),bytes.size.toLong(),"0".repeat(64))}
 }
 @Test fun validGzipWithWrongDatabaseHeaderIsRejected(){
  val bytes=raw().also{it[0]='X'.code.toByte()}
  assertThrows(IOException::class.java){extract(gzip(bytes),bytes.size.toLong(),digest(bytes))}
 }
 @Test fun decompressionStopsAtDeclaredSizeInsteadOfWritingLargePayload(){
  val bytes=raw();val target=ByteArrayOutputStream()
  assertThrows(IOException::class.java){VocabularyArchive.extract(ByteArrayInputStream(gzip(bytes)),target,16,digest(bytes))}
  assertTrue(target.size()<=16)
 }
 @Test fun shorterUnpackedContentIsRejected(){
  val bytes=raw()
  assertThrows(IOException::class.java){extract(gzip(bytes),bytes.size+1L,digest(bytes))}
 }
 @Test fun truncatedGzipIsRejected(){
  val bytes=raw();val compressed=gzip(bytes)
  assertThrows(IOException::class.java){extract(compressed.copyOf(compressed.size-5),bytes.size.toLong(),digest(bytes))}
 }
 @Test fun unboundedOrMissingManifestMetadataIsRejected(){
  val bytes=raw()
  listOf(0L,15L,VocabularyArchive.MAX_BYTES+1).forEach{size->assertThrows(IllegalArgumentException::class.java){extract(gzip(bytes),size,digest(bytes))}}
  assertThrows(IllegalArgumentException::class.java){extract(gzip(bytes),bytes.size.toLong(),"")}
 }
 @Test fun interruptedInstallationStopsBeforeWriting(){
  val bytes=raw();val target=ByteArrayOutputStream()
  Thread.currentThread().interrupt()
  try{
   assertThrows(InterruptedIOException::class.java){VocabularyArchive.extract(ByteArrayInputStream(gzip(bytes)),target,bytes.size.toLong(),digest(bytes))}
   assertEquals(0,target.size())
  }finally{Thread.interrupted()}
 }
}

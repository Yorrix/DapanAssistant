package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.File
import java.security.MessageDigest

class ComponentFilesTest {
 private val payload="dictionary payload".toByteArray()
 private val hash=MessageDigest.getInstance("SHA-256").digest(payload).joinToString(""){"%02x".format(it)}
 private fun transfer(data:ByteArray=payload,size:Long=payload.size.toLong(),digest:String=hash):ByteArray {
  val output=ByteArrayOutputStream()
  ComponentFiles.copyVerified(ByteArrayInputStream(data),output,size,digest){ }
  return output.toByteArray()
 }
 @Test fun completePayloadRetainsExactBytes(){assertArrayEquals(payload,transfer())}
 @Test fun truncatedResponseRejected(){assertThrows(IllegalArgumentException::class.java){transfer(payload.dropLast(1).toByteArray())}}
 @Test fun extraBytesRejected(){assertThrows(IllegalArgumentException::class.java){transfer(payload+byteArrayOf(0))}}
 @Test fun corruptPayloadRejected(){assertThrows(IllegalArgumentException::class.java){transfer(payload.copyOf().also{it[0]=0})}}
 @Test fun cancellationStopsBeforeWriting(){
  Thread.currentThread().interrupt()
  try{assertThrows(InterruptedIOException::class.java){transfer()}}finally{Thread.interrupted()}
 }
 @Test fun unsafeDestinationsRejected(){
  val base=ComponentSpec("dictionary","words.gz","https://39.105.161.86/components/words.gz",1,"a".repeat(64))
  ComponentFiles.validateSpec(base)
  listOf(base.copy(fileName="../words.gz"),base.copy(url="http://39.105.161.86/components/words.gz"),base.copy(url="https://example.com/words.gz"),base.copy(bytes=0),base.copy(bytes=200_000_000),base.copy(sha256="unknown")).forEach{spec->
   assertThrows(IllegalArgumentException::class.java){ComponentFiles.validateSpec(spec)}
  }
 }
 @Test fun cachedFilesRequireBothExpectedSizeAndHash(){
  val file=File.createTempFile("component-cache-",".gz")
  try{
   file.writeBytes(payload)
   assertTrue(ComponentFiles.matches(file,payload.size.toLong(),hash))
   assertFalse(ComponentFiles.matches(file,payload.size+1L,hash))
   file.writeBytes(payload.copyOf().also{it[0]=0})
   assertFalse(ComponentFiles.matches(file,payload.size.toLong(),hash))
  }finally{file.delete()}
 }
 @Test fun queryRedirectAndAlternateHostsAreRejected(){
  val base=ComponentSpec("dictionary","words.gz","https://39.105.161.86/components/words.gz",1,"a".repeat(64))
  listOf("https://39.105.161.86/components/words.gz?redirect=https://example.com", "https://39.105.161.86.example.com/components/words.gz", "https://39.105.161.86@evil.example/components/words.gz", "https://39.105.161.86/other/words.gz").forEach{url->
   assertThrows(IllegalArgumentException::class.java){ComponentFiles.validateSpec(base.copy(url=url))}
  }
 }
}

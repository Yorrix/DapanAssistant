package cn.dapan.assistant

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.InterruptedIOException
import java.security.MessageDigest

internal object ComponentFiles {
 fun validateSpec(spec:ComponentSpec){
  require(spec.fileName.matches(Regex("[a-zA-Z0-9._-]+"))&&!spec.fileName.startsWith("."))
  require(spec.url=="https://39.105.161.86/components/${spec.fileName}")
  require(spec.bytes in 1..180_000_000&&spec.sha256.matches(Regex("[a-f0-9]{64}")))
 }
 fun matches(file:File,bytes:Long,sha256:String):Boolean {
  if(!file.isFile||file.length()!=bytes)return false
  val digest=MessageDigest.getInstance("SHA-256")
  file.inputStream().use{source->val buffer=ByteArray(65536);while(true){
   if(Thread.currentThread().isInterrupted)throw InterruptedIOException("已取消")
   val read=source.read(buffer);if(read<0)break;digest.update(buffer,0,read)
  }}
  return digest.digest().joinToString(""){"%02x".format(it)}==sha256
 }
 fun copyVerified(source:InputStream,target:OutputStream,bytes:Long,sha256:String,progress:(Long)->Unit){
  val digest=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);var count=0L
  while(true){
   if(Thread.currentThread().isInterrupted)throw InterruptedIOException("已取消")
   val read=source.read(buffer);if(read<0)break
   count+=read;require(count<=bytes){"组件超出预期大小，已停止下载"}
   target.write(buffer,0,read);digest.update(buffer,0,read);progress(count)
  }
  require(count==bytes){"组件下载不完整，请重试"}
  require(digest.digest().joinToString(""){"%02x".format(it)}==sha256){"组件校验未通过，请重试"}
 }
}

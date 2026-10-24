package cn.dapan.assistant

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

internal data class ComponentSpec(val id:String,val fileName:String,val url:String,val bytes:Long,val sha256:String,val unpackedBytes:Long=0,val unpackedSha256:String="")

/** One shared daemon, idle threads expire; each download removes its own watchdog in finally. */
internal object ComponentCancellation {
 private val watcher=ScheduledThreadPoolExecutor(1){task->Thread(task,"component-download-cancellation").apply{isDaemon=true}}.apply{
  removeOnCancelPolicy=true
  setKeepAliveTime(30,TimeUnit.SECONDS)
  allowCoreThreadTimeOut(true)
 }
 fun <T> watch(cancel:()->Unit,block:()->T):T {
  val worker=Thread.currentThread()
  if(worker.isInterrupted)throw InterruptedIOException("下载已取消")
  val finished=AtomicBoolean(false)
  val task=watcher.scheduleWithFixedDelay({
   if(worker.isInterrupted&&finished.compareAndSet(false,true))cancel()
  },0,100,TimeUnit.MILLISECONDS)
  try{
   val result=block()
   if(worker.isInterrupted)throw InterruptedIOException("下载已取消")
   return result
  }catch(e:IOException){
   if(worker.isInterrupted)throw InterruptedIOException("下载已取消").apply{initCause(e)}
   throw e
  }finally{finished.set(true);task.cancel(false)}
 }
 fun <T> locked(lock:ReentrantLock,block:()->T):T {
  try{lock.lockInterruptibly()}catch(e:InterruptedException){Thread.currentThread().interrupt();throw InterruptedIOException("下载已取消").apply{initCause(e)}}
  try{return block()}finally{lock.unlock()}
 }
}

/** Fixed, content-verified downloads only. No credentials and no remotely supplied executable path. */
internal object OptionalComponents {
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS)
  .callTimeout(15,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
 private val locks=java.util.concurrent.ConcurrentHashMap<String,ReentrantLock>()
 fun dictionarySpec()=ComponentCatalog.dictionary
 fun download(context:Context,spec:ComponentSpec,onProgress:(Long,Long)->Unit):File {
  require(spec==ComponentCatalog.scannerModel||spec==dictionarySpec()){"组件清单不受支持，请更新应用"}
  ComponentFiles.validateSpec(spec)
  return ComponentCancellation.locked(locks.getOrPut(spec.id){ReentrantLock()}){
   val directory=File(context.cacheDir,"components")
   check(directory.isDirectory||directory.mkdirs()){"无法创建组件缓存，请检查存储空间"}
   val file=File(directory,spec.fileName)
   val partial=File(directory,"${spec.fileName}.part")
   fun progress(count:Long){if(Thread.currentThread().isInterrupted)throw InterruptedIOException("下载已取消");onProgress(count,spec.bytes)}
   try {
    check(!partial.exists()||partial.delete()){"无法清理上次下载，请重试"}
    progress(0)
    if(file.exists()){
     if(ComponentFiles.matches(file,spec.bytes,spec.sha256)){progress(spec.bytes);return@locked file}
     check(file.delete()){"无法清理损坏组件，请重试"}
    }
    require(directory.usableSpace>=spec.bytes+spec.unpackedBytes+8*1024*1024){"手机存储空间不足，请清理后重试"}
    val call=client.newCall(Request.Builder().url(spec.url).header("Accept-Encoding","identity").build())
    ComponentCancellation.watch(call::cancel){
     call.execute().use{response->
      check(response.code==200){if(response.code==404)"组件暂未发布，请稍后重试"else"下载失败（${response.code}），请稍后重试"}
      val body=response.body?:error("组件下载为空")
      require(body.contentLength()==-1L||body.contentLength()==spec.bytes){"组件大小异常，已停止下载"}
      body.byteStream().use{input->partial.outputStream().use{output->
       ComponentFiles.copyVerified(input,output,spec.bytes,spec.sha256,::progress);output.fd.sync()
      }}
     }
    }
    progress(spec.bytes)
    check(partial.renameTo(file)){"无法保存下载组件，请重试"}
    file
   }finally{partial.delete()}
  }
 }
 fun clearCachedDownloads(context:Context){
  val dir=File(context.cacheDir,"components")
  val specs=listOf(ComponentCatalog.scannerModel,dictionarySpec())
  dir.listFiles()?.filter{it.isFile&&(it.name.endsWith(".apk")||it.name.endsWith(".gz")||it.name.endsWith(".ort")||it.name.endsWith(".part"))}?.forEach{file->
   val spec=specs.firstOrNull{file.name==it.fileName||file.name=="${it.fileName}.part"}
   if(spec==null)file.delete()
   else{
    val lock=locks.getOrPut(spec.id){ReentrantLock()}
    if(lock.tryLock())try{file.delete()}finally{lock.unlock()}
   }
  }
 }
}

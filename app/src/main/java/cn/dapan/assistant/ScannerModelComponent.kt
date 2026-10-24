package cn.dapan.assistant

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.FileOutputStream
import java.io.InterruptedIOException
import java.util.concurrent.locks.ReentrantLock

/** Download data only. Scanner code and native runtime remain in the signed application. */
internal object ScannerModelComponent {
 private val lock=ReentrantLock()
 fun spec()=ComponentCatalog.scannerModel
 private fun file(context:Context)=File(context.noBackupFilesDir,"scanner-models/docquadnet256.ort")
 // Cheap status for Compose; full integrity is checked on IO before every scanner launch.
 fun isInstalled(context:Context)=file(context).let{it.isFile&&it.length()==spec().bytes}
 suspend fun ensureReady(context:Context,onProgress:(Long,Long)->Unit):Unit=runInterruptible(Dispatchers.IO){
  ComponentCancellation.locked(lock){
   val model=spec();val destination=file(context)
   if(ComponentFiles.matches(destination,model.bytes,model.sha256)){
    onProgress(model.bytes,model.bytes);return@locked
   }
   val downloaded=OptionalComponents.download(context,model,onProgress)
   check(destination.parentFile?.let{it.isDirectory||it.mkdirs()}==true){"无法创建扫描模型目录"}
   val temporary=File.createTempFile("docquad-",".part",destination.parentFile)
   try{
    downloaded.inputStream().use{input->FileOutputStream(temporary).use{output->
     ComponentFiles.copyVerified(input,output,model.bytes,model.sha256){};output.fd.sync()
    }}
    if(Thread.currentThread().isInterrupted)throw InterruptedIOException("下载已取消")
    // Same private directory/filesystem: the old model is replaced only by verified complete data.
    android.system.Os.rename(temporary.path,destination.path)
    downloaded.delete()
   }finally{temporary.delete()}
   Unit
  }
 }
 fun remove(context:Context)=ComponentCancellation.locked(lock){
  val destination=file(context)
  check(!destination.exists()||destination.delete()){"模型删除失败，请重试"}
  val cached=File(context.cacheDir,"components/${spec().fileName}")
  if(cached.isFile)cached.delete()
 }
}

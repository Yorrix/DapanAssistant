package cn.dapan.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Shared across settings and the foreground prompt. No authentication or user data is sent. */
internal class AppUpdates private constructor(private val context:Context){
 companion object {
  @Volatile private var instance:AppUpdates?=null
  fun get(c:Context)=instance?:synchronized(this){instance?:AppUpdates(c.applicationContext).also{instance=it}}
 }
 private val prefs=context.getSharedPreferences("app-updates",0)
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(10,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build()
 private var work:Job?=null
 var auto by mutableStateOf(prefs.getBoolean("auto",true));private set
 var release by mutableStateOf<AppRelease?>(null);private set
 var file by mutableStateOf<File?>(null);private set
 var checking by mutableStateOf(false);private set
 var downloading by mutableStateOf(false);private set
 var progress by mutableFloatStateOf(0f);private set
 var status by mutableStateOf("");private set
 var error by mutableStateOf("");private set
 var offer by mutableStateOf(false);private set
 var installRequested by mutableStateOf(false);private set
 val busy get()=checking||downloading
 fun changeAuto(value:Boolean){auto=value;prefs.edit().putBoolean("auto",value).apply()}
 fun dismiss(){offer=false;release?.let{prefs.edit().putInt("postponedCode",it.code).putLong("postponedAt",System.currentTimeMillis()).apply()}}
 fun check(manual:Boolean=false){
  if(busy||!AppConsent.accepted(context))return
  val now=System.currentTimeMillis()
  if(!manual&&!AppUpdateRules.due(auto,prefs.getLong("lastAttempt",0),now))return
  prefs.edit().putLong("lastAttempt",now).apply();checking=true;error="";status="正在检查新版本"
  work=scope.launch{
   try{
    val next=runInterruptible(Dispatchers.IO){
     val call=client.newCall(Request.Builder().url(AppUpdateRules.FEED).header("Cache-Control","no-cache").build())
     call.timeout().timeout(25,TimeUnit.SECONDS)
     ComponentCancellation.watch(call::cancel){call.execute().use{response->
      check(response.code==200){"暂时无法检查更新（${response.code}），请稍后重试"}
      val body=response.body?:error("更新信息为空")
      require(body.contentLength()<=65536){"更新信息过大"}
      body.byteStream().use{input->
       val data=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
       while(true){val count=input.read(buffer);if(count<0)break;require(data.size()+count<=65536){"更新信息过大"};data.write(buffer,0,count)}
       AppUpdateRules.parse(data.toString("UTF-8"))
      }
     }}
    }
    prefs.edit().putLong("lastSuccess",now).apply()
    when{
     next.code<=BuildConfig.VERSION_CODE->{release=null;file=null;status="已是最新版本";offer=false}
     !AppUpdateRules.compatible(next,Build.VERSION.SDK_INT,Build.SUPPORTED_ABIS.toList())->{release=null;file=null;status="发现 ${next.name}，暂不支持这台设备";offer=false}
     else->{
      if(release!=next)file=null
      release=next;status="发现新版本 ${next.name}"
      if(!manual&&(prefs.getInt("postponedCode",0)!=next.code||now-prefs.getLong("postponedAt",0)>=24*60*60*1000L))offer=true
     }
    }
   }catch(e:CancellationException){throw e}catch(e:Exception){error=friendly(e);status="检查未完成"}finally{checking=false}
  }
 }
 fun cancelDownload(){if(downloading){work?.cancel();status="下载已取消，可重新下载"}}
 fun download(){
  val target=release?:return;if(busy)return
  downloading=true;progress=0f;error="";status="正在下载"
  work=scope.launch{
   try{
    file=runInterruptible(Dispatchers.IO){
     val dir=File(context.cacheDir,"updates");check(dir.isDirectory||dir.mkdirs()){"无法保存安装包，请检查存储空间"}
     val ready=File(dir,target.fileName);val partial=File(dir,target.fileName+".part")
     // This directory contains only updater-owned files. Keep at most the selected release.
     dir.listFiles()?.filter{it.isFile&&it!=ready}?.forEach{it.delete()}
     if(ready.exists()&&ComponentFiles.matches(ready,target.bytes,target.sha256)){verify(target,ready);return@runInterruptible ready}
     ready.delete();require(dir.usableSpace>=target.bytes+8*1024*1024){"手机存储空间不足，请清理后重试"}
     val call=client.newCall(Request.Builder().url(target.url).header("Accept-Encoding","identity").build())
     try{
      ComponentCancellation.watch(call::cancel){call.execute().use{response->
       check(response.code==200){"下载未完成（${response.code}），请重试"}
       val body=response.body?:error("安装包下载为空")
       require(body.contentLength()==-1L||body.contentLength()==target.bytes){"安装包大小异常"}
       var last=-1
       body.byteStream().use{input->partial.outputStream().use{output->
        ComponentFiles.copyVerified(input,output,target.bytes,target.sha256){count->
         val percent=(count*100/target.bytes).toInt()
         if(percent!=last){last=percent;scope.launch{progress=percent/100f}}
        };output.fd.sync()
       }}
      }}
      verify(target,partial)
      check(partial.renameTo(ready)){"无法保存安装包，请重试"}
      ready
     }finally{partial.delete()}
    }
    status="下载完成，校验通过";progress=1f
   }catch(e:CancellationException){throw e}catch(e:Exception){currentCoroutineContext().ensureActive();file=null;error=friendly(e);status="下载未完成"}finally{downloading=false}
  }
 }
 @Suppress("DEPRECATION") private fun verify(target:AppRelease,apk:File){
  val pm=context.packageManager
  val archive=pm.getPackageArchiveInfo(apk.absolutePath,PackageManager.GET_SIGNATURES)?:error("安装包无法读取")
  val own=pm.getPackageInfo(context.packageName,PackageManager.GET_SIGNATURES)
  val code=if(Build.VERSION.SDK_INT>=28)archive.longVersionCode else archive.versionCode.toLong()
  val installed=if(Build.VERSION.SDK_INT>=28)own.longVersionCode else own.versionCode.toLong()
  AppUpdateRules.validateArchive(target,archive.packageName,context.packageName,code,installed,archive.signatures.orEmpty().map{it.toCharsString()}.toSet(),own.signatures.orEmpty().map{it.toCharsString()}.toSet())
 }
 suspend fun installer():Intent {
  val target=release?:error("请先检查更新");val apk=file?:error("请先下载更新")
  try{
   withContext(Dispatchers.IO){require(ComponentFiles.matches(apk,target.bytes,target.sha256)){"安装包已失效，请重新下载"};verify(target,apk)}
  }catch(e:CancellationException){throw e}catch(e:Exception){file=null;throw e}
  return Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context,"${context.packageName}.files",apk),"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
 }
 fun installedRequested(){installRequested=true;status="已打开系统安装界面，请按提示确认"}
 fun returnedFromInstaller(){if(installRequested){installRequested=false;status="若未完成安装，可再次点击安装更新。"}}
 fun failed(e:Exception){error=friendly(e)}
 private fun friendly(e:Exception)=when(e){
  is java.net.UnknownHostException->"无法连接更新服务器，请检查网络后重试。"
  is java.net.SocketTimeoutException->"连接超时，请稍后重试。"
  is javax.net.ssl.SSLException->"安全连接未通过，请稍后重试。"
  else->e.message?.replace("组件","安装包")?.take(150)?:"更新未完成，请重试。"
 }
}

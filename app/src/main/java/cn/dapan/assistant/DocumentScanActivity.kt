package cn.dapan.assistant

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.offlinescan.core.*
import dev.offlinescan.ui.ScannerFlow
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.UUID

/** The scanner runs inside this app; only its detection model is downloaded on demand. */
class DocumentScanActivity : ComponentActivity() {
 private var ready by mutableStateOf(false)
 private var checking by mutableStateOf(false)
 private var downloading by mutableStateOf(false)
 private var cancelling by mutableStateOf(false)
 private var received by mutableLongStateOf(0L)
 private var total by mutableLongStateOf(0L)
 private var message by mutableStateOf("")
 private var completed by mutableStateOf(false)
 private var downloadJob:Job?=null
 private var sessionId=""

 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  enableEdgeToEdge()
  AppAppearance.load(this)
  sessionId=savedInstanceState?.getString("sessionId")?.takeIf{runCatching{UUID.fromString(it)}.isSuccess}
   ?:UUID.randomUUID().toString()
  val output=File(filesDir,"documents/scanner/$sessionId")
  total=ScannerModelComponent.spec().bytes
  checking=ScannerModelComponent.isInstalled(this)
  setContent{DapanTheme{
   if(ready){
    if(!completed)ScannerFlow(
     config=remember{ScanConfig(mode=ScanMode.DOCUMENT,detectionMode=DetectionMode.AI,autoCapture=true)},
     outputDirectory=output,
     saveDestination={Text("资料库 · 扫描文件")},
     onResult={result->
      completed=true
      when(result){
       is ScanResult.Completed->setResult(RESULT_OK,Intent().putExtra("pageCount",result.output.pageCount))
       is ScanResult.Failed->setResult(RESULT_CANCELED,Intent().putExtra("scanError",when(result.error.code){
        ErrorCode.PERMISSION_DENIED->"未授予相机权限，可重新扫描或导入图片"
        ErrorCode.CAMERA->"相机暂时不可用，请重试"
        ErrorCode.STORAGE,ErrorCode.EXPORT->"扫描文件未能保存，请检查剩余存储空间"
        else->"扫描未完成，请重试或切换标准识别"
       }))
       ScanResult.Cancelled->setResult(RESULT_CANCELED)
      }
      finish()
     }
    )
    SideEffect{WindowCompat.getInsetsController(window,window.decorView).isAppearanceLightStatusBars=false}
   }else{
    BackHandler{leave()}
    Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.fillMaxSize().safeDrawingPadding()){
     Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
      QuietIconButton("返回",AppGlyph.Back,{leave()})
      Text("扫描文件",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
     }
     Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=24.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
       LineIcon(AppGlyph.File,Modifier.size(22.dp),DutBlue)
       Text(if(checking)"正在准备扫描"else"下载离线扫描模型",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
      }
      if(!checking)Text("首次下载 ${bytesText(total)}，之后可直接拍摄、裁剪和增强，无需联网。",fontSize=13.sp,lineHeight=19.sp,color=Muted)
      if(checking)LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
      else if(downloading){
       if(cancelling)LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
       else LinearProgressIndicator(progress={(received.toFloat()/total.coerceAtLeast(1L)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(3.dp))
       Text(if(cancelling)"正在取消…"else if(received>=total&&total>0L)"正在校验模型…"else"${bytesText(received)} / ${bytesText(total)}",fontSize=12.sp,color=Muted)
      }
      if(message.isNotBlank())Text(message,fontSize=12.sp,lineHeight=18.sp,color=Muted)
      if(downloading&&!checking)TextButton(onClick={cancelling=true;downloadJob?.cancel()},enabled=!cancelling){Text("取消下载")}
      else if(!checking)Button(onClick={download()},modifier=Modifier.fillMaxWidth().height(42.dp)){Text(if(message.isBlank())"下载并开始扫描"else"重新下载")}
     }
    }}
   }
  }}
  if(checking)download()
 }

 private fun download(){
  if(downloading||ready)return
  downloading=true;cancelling=false;message="";received=0L
  downloadJob=lifecycleScope.launch{
   val requestContext=coroutineContext
   var lastUpdate=0L
   try{
    ScannerModelComponent.ensureReady(this@DocumentScanActivity){done,size->
     requestContext.ensureActive()
     val now=SystemClock.elapsedRealtime()
     if(done==size||now-lastUpdate>=100L){
      lastUpdate=now
      runOnUiThread{if(requestContext.isActive&&!isFinishing){checking=false;received=done;total=size.takeIf{it>0L}?:ScannerModelComponent.spec().bytes}}
     }
    }
    requestContext.ensureActive()
    ready=true
   }catch(e:CancellationException){message="下载已取消，稍后可重新下载。";throw e}
   catch(e:Exception){message=e.message?.take(160)?:"下载失败，请检查网络后重试。"}
   finally{checking=false;downloading=false;cancelling=false;downloadJob=null}
  }
 }

 private fun leave(){downloadJob?.cancel();setResult(RESULT_CANCELED);finish()}
 override fun onSaveInstanceState(outState:Bundle){outState.putString("sessionId",sessionId);super.onSaveInstanceState(outState)}
 private fun bytesText(bytes:Long)=String.format(Locale.ROOT,"%.1f MB",bytes.coerceAtLeast(0L)/1_048_576.0)
}

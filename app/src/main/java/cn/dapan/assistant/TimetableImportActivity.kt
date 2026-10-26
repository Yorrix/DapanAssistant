package cn.dapan.assistant

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import org.json.*
import java.time.LocalDate

class TimetableImportActivity:ComponentActivity() {
 private var browser:TimetableWebLease<WebView>?=null
 private val web:WebView? get()=browser?.value
 private val callbacks=Handler(Looper.getMainLooper())
 private var resumeUrl=TimetableImport.ENTRY
 private var browserGeneration by mutableStateOf(0)
 private var browserReady by mutableStateOf(false)
 private var browserFailure by mutableStateOf<TimetableBrowserFailure?>(null)
 private var providerPackage:String?=null
 private var providerDescription by mutableStateOf<String?>(null)
 private var term:TeachingTerm?=null
 private var waiting=false
 private var poll=0
 private var navigation=0
 private var attempt=0
 private var pageIssue by mutableStateOf<String?>(null)
 private var message by mutableStateOf("登录自己的学校账号，再进入‘我的课表’读取。")
 private var loading by mutableStateOf(false)
 private var reading by mutableStateOf(false)
 private var preview by mutableStateOf<TeachingImport?>(null)
 private var host by mutableStateOf("学校统一认证")
 private var showBackup by mutableStateOf(false)
 private val backup=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){result->
  if(result.resultCode==RESULT_OK){
   val uri=result.data?.data
   if(uri==null){message="备用浏览器没有返回课表，请重新读取。"}
   else lifecycleScope.launch{
    reading=true
    runCatching{withContext(Dispatchers.IO){TimetableBrowserComponent.readResult(this@TimetableImportActivity,uri)}}
     .onSuccess{preview=it;message="课表已读取，请确认后导入。"}.onFailure{message=it.message?.take(180)?:"课表读取失败，请重试"}
    reading=false
   }
  }
 }
 override fun onCreate(savedInstanceState:Bundle?) {
  super.onCreate(savedInstanceState);enableEdgeToEdge();AppAppearance.load(this)
  setContent { DapanTheme {
   Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){
    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()){
     Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
      TextButton(onClick={if(preview!=null)preview=null else finish()}){Text("返回")}
      Text(if(preview==null)"导入课表"else"确认课表",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold)
      if(preview==null){TextButton(onClick={showBackup=true},contentPadding=PaddingValues(horizontal=5.dp)){Text("兼容",fontSize=12.sp)};TextButton(onClick={openCoursePage()},enabled=browserReady,contentPadding=PaddingValues(horizontal=5.dp)){Text("课表")};TextButton(onClick={readPage()},enabled=browserReady&&!reading&&!loading&&pageIssue==null,contentPadding=PaddingValues(horizontal=5.dp)){Text(if(reading)"读取中"else"读取")}}
     }
     val p=preview
     if(p!=null) TeachingPreview(p,AppStore.get(this@TimetableImportActivity),onBack={preview=null},onFinish={finish()})
     else {
      val failure=browserFailure
      if(failure==null){
       Row(Modifier.fillMaxWidth().padding(start=14.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically){
        Text("$host · $message",Modifier.weight(1f).padding(vertical=4.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(pageIssue!=null)TextButton(onClick={retryBrowser()}){Text("重试",fontSize=12.sp)}
       }
       if(loading||reading)LinearProgressIndicator(Modifier.fillMaxWidth())
       key(browserGeneration){
        AndroidView(factory={TimetableWebFrame(it).also(::initializeBrowser)},
         onRelease={frame->frame.lease?.let{releaseBrowser(it)};frame.lease=null},
         modifier=Modifier.weight(1f).fillMaxWidth())
       }
      }else{
       TimetableBrowserError(failure,providerDescription,onRetry={retryBrowser()},onSettings={openComponentSettings()},onBackup={showBackup=true},modifier=Modifier.weight(1f).fillMaxWidth())
      }
      Text("登录信息仅交给学校；课表在本机解析，不上传给 AI。",Modifier.padding(horizontal=14.dp,vertical=5.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
     }
    }
   }
   if(showBackup)TimetableBrowserDownload(onClose={showBackup=false},onOpen={invalidateRead();backup.launch(TimetableBrowserComponent.launch(this@TimetableImportActivity))})
   BackHandler(enabled=!showBackup) {
    if(preview!=null)preview=null
    else if(browserFailure!=null)finish()
    else {val v=web;if(v!=null&&runCatching{v.canGoBack()}.getOrDefault(false))withBrowser{it.goBack()}else finish()}
   }
  } }
 }
 private fun isActive(lease:TimetableWebLease<WebView>,view:WebView):Boolean=
  !isFinishing&&!isDestroyed&&browser===lease&&lease.value===view

 private fun invalidateRead(){attempt++;navigation++;waiting=false;reading=false;callbacks.removeCallbacksAndMessages(null)}

 private fun releaseBrowser(lease:TimetableWebLease<WebView>,rendererGone:Boolean=false){
  val v=lease.takeForRelease()?:return
  if(browser===lease){browser=null;browserReady=false;loading=false;invalidateRead()}
  // The dead instance must leave the hierarchy before destroy; later disposal becomes a no-op.
  val parent=v.parent as? ViewGroup
  if(parent is TimetableWebFrame&&parent.lease===lease)parent.lease=null
  runCatching{parent?.removeView(v)}
  if(!rendererGone)runCatching{v.stopLoading()}
  runCatching{v.destroy()}
 }

 private fun componentFailed(failure:TimetableBrowserFailure){
  browser?.let{releaseBrowser(it)};invalidateRead();term=null;loading=false;browserReady=false;pageIssue=null;browserFailure=failure
 }

 private fun retryBrowser(){
  browser?.let{releaseBrowser(it)};invalidateRead();term=null;pageIssue=null;browserFailure=null
  loading=false;browserReady=false;message="正在重新打开学校页面…";browserGeneration++
 }

 private fun initializeBrowser(frame:TimetableWebFrame){
  if(isFinishing||isDestroyed)return
  browser?.let{releaseBrowser(it)}
  providerPackage=null;providerDescription=null
  // No hard-coded package or version threshold: the OS selects its own WebView implementation.
  // A failed diagnostic query is not proof that construction will fail on a vendor ROM.
  var providerChecked=false
  val provider=try{WebView.getCurrentWebViewPackage().also{providerChecked=true}}catch(_:Exception){null}catch(_:LinkageError){null}
  providerPackage=provider?.packageName
  providerDescription=provider?.let{listOfNotNull(it.packageName,it.versionName).joinToString(" · ")}
  try{
   val v=WebView(this)
   val lease=TimetableWebLease(v);frame.lease=lease;browser=lease
   configureBrowser(v,lease)
   frame.addView(v,FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT))
   v.loadUrl(resumeUrl)
   if(lease.value===v)browserReady=true
  }catch(_:Exception){componentFailed(if(providerChecked&&provider==null)TimetableBrowserFailure.NO_PROVIDER else TimetableBrowserFailure.INITIALIZATION)}
   catch(_:LinkageError){componentFailed(if(providerChecked&&provider==null)TimetableBrowserFailure.NO_PROVIDER else TimetableBrowserFailure.INITIALIZATION)}
 }

 @SuppressLint("SetJavaScriptEnabled")
 private fun configureBrowser(v:WebView,lease:TimetableWebLease<WebView>){
  v.settings.apply{
   javaScriptEnabled=true;domStorageEnabled=true;allowFileAccess=false;allowContentAccess=false
   mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
   setSupportZoom(true);builtInZoomControls=true;displayZoomControls=false;useWideViewPort=true;loadWithOverviewMode=true
   setSupportMultipleWindows(false)
   // The school uses a desktop table even on phones; keep a stable, zoomable layout.
   val chrome=Regex("Chrome/[0-9.]+").find(userAgentString)?.value
   if(chrome!=null)userAgentString="Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36"
  }
  CookieManager.getInstance().setAcceptThirdPartyCookies(v,false)
  v.webChromeClient=WebChromeClient()
  v.webViewClient=object:WebViewClient(){
   override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean {
    if(!isActive(lease,view))return true
    val allowed=TimetableImport.allowedUrl(request.url.toString())
    if(!allowed&&request.isForMainFrame){message="这个链接不属于已适配的学校教务或认证站点。";invalidateRead()}
    return !allowed
   }
   override fun onPageStarted(view:WebView,url:String,favicon:Bitmap?){
    if(!isActive(lease,view))return
    if(!TimetableImport.allowedUrl(url)){
     // shouldOverrideUrlLoading is not called for every kind of navigation (for example POST).
     view.stopLoading();loading=false;invalidateRead();pageIssue="这个链接不属于已适配的学校教务或认证站点。";message=pageIssue!!;return
    }
    navigation++;pageIssue=null;loading=true;host=runCatching{java.net.URI(url).host}.getOrNull()?:"学校页面"
    resumeUrl=TimetableWebRecovery.retryUrl(url)
    if(!TimetableImport.courseUrl(url)){term=null;invalidateRead()}
   }
   override fun onPageFinished(view:WebView,url:String){
    if(!isActive(lease,view))return
    loading=false;if(pageIssue!=null){message=pageIssue!!;waiting=false;reading=false}else if(waiting&&url.contains("/info/all-courses")){poll=0;pollCourses(attempt)}else if(!reading)message="进入自己的课表，选择学期后点右上角读取。"
   }
   override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:SslError){
    handler.cancel()
    if(!isActive(lease,view))return
    pageIssue="学校连接的证书校验失败，请稍后重试。";loading=false;invalidateRead();message=pageIssue!!
   }
   override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(isActive(lease,view))resourceFailed(request)}
   override fun onReceivedHttpError(view:WebView,request:WebResourceRequest,error:WebResourceResponse){if(isActive(lease,view))resourceFailed(request)}
   override fun onRenderProcessGone(view:WebView,detail:RenderProcessGoneDetail):Boolean{
    if(lease.value!==view)return true
    val current=browser===lease&&lease.value===view
    // Never call reload/stopLoading on a WebView whose renderer has exited.
    releaseBrowser(lease,rendererGone=true)
    if(current&&!isFinishing&&!isDestroyed){
     term=null;pageIssue=null
     browserFailure=if(detail.didCrash())TimetableBrowserFailure.RENDERER_CRASH else TimetableBrowserFailure.RENDERER_RELEASED
    }
    return true
   }
  }
 }

 private fun withBrowser(action:(WebView)->Unit){
  val v=web?:return
  try{action(v)}catch(_:Exception){componentFailed(TimetableBrowserFailure.INITIALIZATION)}
   catch(_:LinkageError){componentFailed(TimetableBrowserFailure.INITIALIZATION)}
 }

 private fun openCoursePage(){
  invalidateRead();term=null;pageIssue=null;resumeUrl=TimetablePage.COURSE_URL
  withBrowser{it.loadUrl(resumeUrl)}
 }

 private fun openComponentSettings(){
  val intents=buildList{
   providerPackage?.let{add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.fromParts("package",it,null)))}
   add(Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));add(Intent(Settings.ACTION_SETTINGS))
  }
  for(intent in intents)try{startActivity(intent);return}catch(_:Exception){/* OEM settings may omit a destination. */}
  message="无法打开系统设置，请手动进入系统应用，启用或更新网页组件后返回重试。"
  android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_LONG).show()
 }
 private fun resourceFailed(request:WebResourceRequest){
  val path=request.url.path.orEmpty()
  val scriptOrStyle=request.url.host in setOf("jxgl.dlut.edu.cn","cdn-dlut.supwisdom.com")&&(path.endsWith(".js")||path.endsWith(".css"))
  if(!request.isForMainFrame&&!scriptOrStyle)return
  pageIssue=if(request.isForMainFrame)"学校页面暂时无法连接。可重试，或点右上角‘兼容’使用备用浏览器。"else"学校脚本或样式未加载。可重试，或点右上角‘兼容’切换浏览器。"
  message=pageIssue!!;loading=false;invalidateRead()
 }
 private fun snapshot(done:(Result<JSONObject>)->Unit){
  val lease=browser;val v=lease?.value
  if(lease==null||v==null){done(Result.failure(IllegalStateException("学校页面尚未就绪，请重试")));return}
  val generation=navigation;val request=attempt
  withBrowser{
   if(!TimetableImport.courseUrl(v.url.orEmpty())){done(Result.failure(IllegalStateException("请先登录并进入我的课表")));return@withBrowser}
   v.evaluateJavascript(TimetablePage.snapshot){raw->
   if(!isActive(lease,v)||generation!=navigation||request!=attempt)return@evaluateJavascript
   done(runCatching{
    require(raw.orEmpty().length<=600000){"课表过大，暂时无法读取"}
    val decoded=JSONTokener(raw.orEmpty()).nextValue() as? String?:error("学校页面未返回课表")
    val o=JSONObject(decoded);if(o.has("error"))error(o.getString("error"));o
   })
   }
  }
 }
 private fun readPage(){
  invalidateRead();reading=true;val current=attempt
  callbacks.postDelayed({
   if(reading&&attempt==current&&!isFinishing&&!isDestroyed)fail(IllegalStateException("学校页面未返回课表，请等待页面加载后重试。"))
  },18000)
  snapshot{result->result.onSuccess{o->runCatching{
   if(o.getString("kind")=="courses"){
    val t=term?:error("请点‘课表’，先从周课表读取学期起点与时间，再读取明细")
    preview=TimetableImport.read(t,o);reading=false
   }else{
    term=TimetableImport.term(o);waiting=true;poll=0;message="正在读取该学期的全部课程明细…"
    withBrowser{it.evaluateJavascript(TimetablePage.OPEN_COURSES,null)}
    callbacks.postDelayed({if(waiting&&attempt==current&&!isFinishing&&!isDestroyed){reading=false;waiting=false;message="明细读取未完成，请在全部课程页面点击‘读取’重试。"}},12000)
   }
  }.onFailure{fail(it)}}.onFailure{fail(it)}}
 }
 private fun pollCourses(current:Int){
  if(!waiting||isFinishing||isDestroyed||attempt!=current)return
  snapshot{result->
   result.onSuccess{o->runCatching{preview=TimetableImport.read(term?:error("学期信息已失效，请重新读取"),o);waiting=false;reading=false}.onFailure{fail(it)}}
    .onFailure{e->if(++poll<16)callbacks.postDelayed({pollCourses(current)},400)else fail(e)}
  }
 }
 private fun fail(e:Throwable){invalidateRead();message=e.message?.take(240)?:"课表读取失败，请重试"}
 override fun onDestroy(){invalidateRead();browser?.let{releaseBrowser(it)};super.onDestroy()}
}

private class TimetableWebFrame(context:Context):FrameLayout(context){var lease:TimetableWebLease<WebView>?=null}
private enum class TimetableBrowserFailure(val heading:String,val detail:String){
 NO_PROVIDER("网页组件不可用","系统未提供可用的网页组件，课表暂时无法打开。请在系统应用中启用 WebView 或系统浏览器组件，或通过手机自带应用商店更新，然后返回重试。"),
 INITIALIZATION("网页组件未能启动","课表未能打开。可以先重试；仍失败时，请在系统应用中检查网页组件，或通过手机自带应用商店更新。"),
 RENDERER_CRASH("学校页面已停止","网页渲染进程意外退出。重试会重新打开学校页面；若反复出现，请更新系统网页组件。"),
 RENDERER_RELEASED("学校页面已释放","系统回收了网页进程。点重试重新打开学校页面，必要时重新登录。")
}

@Composable private fun TimetableBrowserError(failure:TimetableBrowserFailure,provider:String?,onRetry:()->Unit,onSettings:()->Unit,onBackup:()->Unit,modifier:Modifier=Modifier){
 Box(modifier){
  Column(Modifier.align(Alignment.TopCenter).widthIn(max=480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=18.dp,vertical=18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
   Text(failure.heading,fontSize=17.sp,fontWeight=FontWeight.SemiBold)
   Text(failure.detail,fontSize=13.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
   provider?.let{Text("当前组件：$it",fontSize=10.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
   Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Button(onClick=onRetry,contentPadding=PaddingValues(horizontal=18.dp,vertical=6.dp)){Text("重试")}
    TextButton(onClick=onSettings){Text("组件设置")}
    TextButton(onClick=onBackup){Text("备用浏览器")}
   }
  }
 }
}

@Composable private fun TeachingPreview(p:TeachingImport,s:AppStore,onBack:()->Unit,onFinish:()->Unit){
 var futureOnly by remember(p){mutableStateOf(true)}
 var remind by remember(p){mutableStateOf(true)}
 var selected by remember(p){mutableStateOf(p.courses.filterIndexed{index,_->p.issues.none{it.index==index}}.map{it.code}.toSet())}
 var ignored by remember(p){mutableStateOf(emptySet<Int>())}
 var expanded by remember(p){mutableStateOf<Int?>(null)}
 var result by remember{mutableStateOf<String?>(null)}
 val values=p.entries.filter{e->selected.any{e.notes.contains("课程编号：$it\n")}&&(!futureOnly||e.start.toLocalDate()>=LocalDate.now())}.map{if(remind)it else it.copy(reminderMode=ReminderMode.OFF)}
 val merge=TimetableImport.merge(values,s.entries)
 Column(Modifier.fillMaxSize().padding(horizontal=14.dp)){
  Text(p.term.name,fontSize=16.sp,fontWeight=FontWeight.SemiBold)
  Text("第 1 周始于 ${p.term.monday} · 时间来自学校课表",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
  Row(verticalAlignment=Alignment.CenterVertically){Checkbox(futureOnly,{futureOnly=it},Modifier.size(34.dp));Text("只导入今天起的课程",fontSize=12.sp);Spacer(Modifier.weight(1f));Checkbox(remind,{remind=it},Modifier.size(34.dp));Text("出发提醒",fontSize=12.sp)}
  Text("路程 20 分钟${if(remind)"，出发时提醒"else"，不提醒"}；导入后可逐项修改。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(p.issues.isNotEmpty())Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
   Text("${p.issues.size} 门暂无完整时间，确认忽略后导入其余课程。",Modifier.weight(1f),fontSize=11.sp,color=MaterialTheme.colorScheme.error)
   TextButton(onClick={ignored=p.issues.map{it.index}.toSet()}){Text("忽略这些",fontSize=12.sp)}
  }
  LazyColumn(Modifier.weight(1f).fillMaxWidth()){
   itemsIndexed(p.courses,key={index,c->"$index:${c.code}"}){index,c->
    val issue=p.issues.firstOrNull{it.index==index}
    val entries=p.entries.filter{it.notes.contains("课程编号：${c.code}\n")};val absent=entries.isEmpty()
    Row(Modifier.fillMaxWidth().clickable{expanded=if(expanded==index)null else index}.padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){
     if(!absent)Checkbox(c.code in selected,{selected=if(it)selected+c.code else selected-c.code},Modifier.size(34.dp))
     Column(Modifier.weight(1f)){Text(c.title.ifBlank{"第 ${index+1} 门课程"},fontSize=14.sp,fontWeight=FontWeight.Medium);Text(issue?.reason?:if(absent)"暂无可导入时间"else"学期共 ${entries.size} 次 · 点开核对时间与地点",fontSize=11.sp,color=if(issue!=null&&index !in ignored)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)}
     if(issue!=null)TextButton(onClick={ignored=if(index in ignored)ignored-index else ignored+index}){Text(if(index in ignored)"已忽略"else"忽略",fontSize=12.sp)}
    }
    if(expanded==index){
     Text(c.schedule,Modifier.padding(bottom=4.dp),fontSize=11.sp)
     entries.take(4).forEach{Text("${it.start.toLocalDate()}  ${it.start.toLocalTime()}–${it.end.toLocalTime()}  ${it.location}",fontSize=11.sp)}
     if(entries.size>4)Text("另有 ${entries.size-4} 次，按上方周次展开",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
   }
  }
  Text("新增 ${merge.fresh.size} 次 · 已有 ${merge.unchanged} 次 · 差异 ${merge.changed.size} 次",fontSize=13.sp,fontWeight=FontWeight.Medium)
  if(merge.changed.isNotEmpty())Text("有差异的已有日程将保留，不自动覆盖。",fontSize=11.sp,color=MaterialTheme.colorScheme.error)
  Text("按教务周次导入；调课或教室更新后请重新核对。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
  if(p.entries.isEmpty())Text("本学期暂没有可导入的完整课程，不会创建空日程。",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
  Button(onClick={runCatching{
   require(p.issues.all{it.index in ignored}&&values.isNotEmpty()){"请先确认忽略未排课条目"}
   s.importTeaching(values)+(if(ignored.isNotEmpty())"\n已按你的选择忽略 ${ignored.size} 门课程。"else"")
  }.onSuccess{result=it}.onFailure{result=it.message?:"未能保存，请重试"}},enabled=merge.fresh.isNotEmpty()&&p.issues.all{it.index in ignored},modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)){Text("确认导入 ${merge.fresh.size} 次")}
 }
 result?.let{msg->AlertDialog(onDismissRequest={result=null},title={Text("课表导入")},text={Text("$msg\n\n以后可从 今天 → 右上角设置 → 导入课表 再次进入。")},confirmButton={TextButton(onClick=onFinish){Text("完成")}},dismissButton={TextButton(onClick={result=null;onBack()}){Text("查看教务")}})}
}

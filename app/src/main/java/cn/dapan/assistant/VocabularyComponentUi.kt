package cn.dapan.assistant

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import java.util.Locale

internal class VocabularyComponentController(private val context:Context,private val scope:CoroutineScope){
 val spec=OptionalComponents.dictionarySpec()
 var installed by mutableStateOf(false);private set
 var checking by mutableStateOf(true);private set
 var active by mutableStateOf(false);private set
 var installing by mutableStateOf(false);private set
 var removing by mutableStateOf(false);private set
 var cancelling by mutableStateOf(false);private set
 var downloaded by mutableLongStateOf(0);private set
 var total by mutableLongStateOf(spec.bytes);private set
 var error by mutableStateOf<String?>(null);private set
 var status by mutableStateOf<String?>(null);private set
 private var job:Job?=null
 suspend fun refresh(){
  checking=true
  try{
   installed=withContext(Dispatchers.IO){if(VocabularyDictionary.installed(context)){VocabularyDictionary.open(context);true}else false}
   error=null
  }catch(e:CancellationException){throw e}catch(e:Exception){installed=false;error=e.message?:"词典未能打开，请重新下载"}
  finally{checking=false}
 }
 fun download(){
  if(active||checking)return
  if(VocabularyState.running){error="请先暂停课堂生词收听";return}
  active=true;installing=false;cancelling=false;downloaded=0;total=spec.bytes;error=null;status=null
  job=scope.launch{
   try{
    var updated=0L
    val archive=runInterruptible(Dispatchers.IO){OptionalComponents.download(context,spec){done,size->
     val now=System.nanoTime()
     if(done==size||now-updated>=100_000_000L){updated=now;scope.launch(Dispatchers.Main){downloaded=done;total=size.takeIf{it>0}?:spec.bytes}}
    }}
    installing=true
    runInterruptible(Dispatchers.IO){VocabularyComponent.install(context,archive,spec);archive.delete()}
    installed=true;status="词典已安装，可开始收听"
   }catch(e:CancellationException){status="已取消，生词记录仍保留";throw e}
   catch(e:Exception){error=e.message?:"词典下载失败，请检查网络后重试"}
   finally{syncInstalled();active=false;installing=false;cancelling=false}
  }
 }
 fun cancel(){cancelling=true;status="正在取消…";job?.cancel()}
 fun remove(){
  if(active||checking)return
  if(AudioState.running||VocabularyState.running){error="请先结束录音，再删除词典";return}
  active=true;removing=true;error=null;status=null
  job=scope.launch{
   try{withContext(Dispatchers.IO){VocabularyDictionary.remove(context)};installed=false;status="词典已删除，生词记录和已掌握词保留"}
   catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"词典删除失败，请重试"}
   finally{syncInstalled();active=false;removing=false}
  }
 }
 private suspend fun syncInstalled(){
  installed=withContext(NonCancellable+Dispatchers.IO){runCatching{if(VocabularyDictionary.installed(context)){VocabularyDictionary.open(context);true}else false}.getOrDefault(false)}
 }
}

private fun dictionarySize(bytes:Long)=String.format(Locale.ROOT,"%.1f MB",bytes/1024.0/1024.0)

/** Shared by classroom settings and the application component manager. */
@Composable fun VocabularyComponentControls(modifier:Modifier=Modifier){
 val context=LocalContext.current.applicationContext;val scope=rememberCoroutineScope()
 val state=remember(context,scope){VocabularyComponentController(context,scope)}
 LaunchedEffect(state){state.refresh()}
 VocabularyComponentPanel(state,modifier,manage=true)
}

@Composable internal fun VocabularyComponentPanel(state:VocabularyComponentController,modifier:Modifier=Modifier,manage:Boolean=false){
 var confirmDelete by remember{mutableStateOf(false)}
 Column(modifier,verticalArrangement=Arrangement.spacedBy(4.dp)){
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){
    Text("离线词典",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
    Text("下载 ${dictionarySize(state.spec.bytes)} · 安装 ${dictionarySize(state.spec.unpackedBytes)}",fontSize=11.sp,color=Muted)
   }
   when{
    state.checking->Text("检查中",fontSize=11.sp,color=Muted)
    state.active->TextButton(onClick={state.cancel()},enabled=!state.cancelling&&!state.removing,contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.height(34.dp)){Text(if(state.removing)"删除中"else"取消",fontSize=12.sp)}
    state.installed->if(manage)TextButton(onClick={confirmDelete=true},enabled=!AudioState.running&&!VocabularyState.running,contentPadding=PaddingValues(horizontal=8.dp),modifier=Modifier.height(34.dp)){Text("删除词典",fontSize=12.sp)}else Text("已安装",fontSize=11.sp,color=DutBlue)
    else->FilledTonalButton(onClick={state.download()},enabled=!VocabularyState.running,contentPadding=PaddingValues(horizontal=12.dp,vertical=0.dp),modifier=Modifier.height(34.dp)){Text(if(state.error==null)"下载词典"else"重试下载",fontSize=12.sp)}
   }
  }
  if(state.active){
   if(state.installing||state.removing||state.cancelling)LinearProgressIndicator(Modifier.fillMaxWidth().height(3.dp))
   else LinearProgressIndicator(progress={(state.downloaded.toFloat()/state.total.coerceAtLeast(1)).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(3.dp))
   Text(when{state.cancelling->"正在取消…";state.removing->"正在删除离线词典…";state.installing->"正在校验并安装…";else->"${dictionarySize(state.downloaded)} / ${dictionarySize(state.total)}"},fontSize=11.sp,color=Muted)
  }else{
   Text(if(state.installed)"已可离线查词。删除词典不影响生词记录与已掌握词。"else"首次收听前下载一次；下载后可离线查词，语音识别仍需联网。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
   state.status?.let{Text(it,fontSize=11.sp,color=Muted)}
  }
  state.error?.let{Text(it,fontSize=11.sp,lineHeight=16.sp,color=MaterialTheme.colorScheme.error)}
  if(state.installed&&(AudioState.running||VocabularyState.running)&&manage)Text("录音中，结束后可删除词典。",fontSize=11.sp,color=Muted)
 }
 if(confirmDelete)AlertDialog(onDismissRequest={confirmDelete=false},title={Text("删除离线词典？",fontSize=17.sp)},text={Text("释放约 ${dictionarySize(state.spec.unpackedBytes)}。生词记录、已掌握词和设置会保留；下次收听前需要重新下载。",fontSize=13.sp)},confirmButton={TextButton(enabled=!AudioState.running&&!VocabularyState.running,onClick={confirmDelete=false;state.remove()}){Text("删除词典")}},dismissButton={TextButton(onClick={confirmDelete=false}){Text("取消")}})
}

package cn.dapan.assistant

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.*

/** Shared by settings and onboarding. Keys and probe state are never saveable UI state. */
@Composable internal fun SecretKeyField(value:String,onValue:(String)->Unit,provider:KeyProvider,label:String,
 modifier:Modifier=Modifier,enabled:Boolean=true,model:String="deepseek-flash",hint:String=""){
 val scope=rememberCoroutineScope()
 val lifecycle=(LocalContext.current as? ComponentActivity)?.lifecycle
 var job by remember{mutableStateOf<Job?>(null)}
 var checking by remember{mutableStateOf(false)}
 var result by remember{mutableStateOf<ConnectivityResult?>(null)}
 var attempt by remember{mutableIntStateOf(0)}
 fun cancel(show:Boolean){attempt++;job?.cancel();job=null;checking=false;result=if(show)ConnectivityRules.cancelled()else null}
 LaunchedEffect(value,model,provider,enabled){cancel(false)}
 DisposableEffect(lifecycle){
  val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP&&checking)cancel(true)}
  lifecycle?.addObserver(observer)
  onDispose{lifecycle?.removeObserver(observer);job?.cancel()}
 }
 fun test(){
  if(checking||!enabled)return
  attempt++;val current=attempt;val currentValue=value;val currentModel=model
  result=null;checking=true
  job=scope.launch{
   try{val tested=KeyConnectivity.check(provider,currentValue,currentModel);if(current==attempt)result=tested}
   catch(e:CancellationException){throw e}
   finally{if(current==attempt){checking=false;job=null}}
  }
 }
 Column(modifier.fillMaxWidth()){
  OutlinedTextField(value,{cancel(false);onValue(it)},enabled=enabled,label={Text(label,fontSize=12.sp)},
   visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth(),shape=AppShape(10.dp))
  if(hint.isNotBlank())Text(hint,Modifier.padding(top=3.dp),fontSize=11.sp,lineHeight=16.sp,color=Muted)
  Row(Modifier.fillMaxWidth().heightIn(min=36.dp),verticalAlignment=Alignment.CenterVertically){
   Text(if(checking)"正在验证…"else"仅测连接，不生成或录音",Modifier.weight(1f),fontSize=10.sp,color=Muted)
   if(checking){
    CircularProgressIndicator(Modifier.size(13.dp),strokeWidth=1.5.dp)
    TextButton(onClick={cancel(true)},contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text("取消",fontSize=12.sp)}
   }else TextButton(onClick={test()},enabled=enabled&&value.isNotBlank(),contentPadding=PaddingValues(horizontal=8.dp,vertical=0.dp)){Text("测试连通性",fontSize=12.sp)}
  }
  result?.let{r->
   val tint=when(r.status){ConnectivityStatus.FAILED->MaterialTheme.colorScheme.error;ConnectivityStatus.PASSED->DutBlue;else->Muted}
   Column(Modifier.fillMaxWidth().padding(bottom=3.dp).semantics{liveRegion=LiveRegionMode.Polite}){
    Text(r.title,fontSize=12.sp,lineHeight=17.sp,fontWeight=FontWeight.Medium,color=tint)
    Text(r.detail,fontSize=11.sp,lineHeight=16.sp,color=Muted)
   }
  }
 }
}

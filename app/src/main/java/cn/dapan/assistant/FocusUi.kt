package cn.dapan.assistant

import org.json.*

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.*

@Composable fun FocusScreen(s:AppStore,onBack:()->Unit={}){
 val c=LocalContext.current
 var hours by rememberSaveable{mutableIntStateOf(FocusControl.minutes(c)/60)}
 var minutes by rememberSaveable{mutableIntStateOf(FocusControl.minutes(c)%60)}
 var notifications by remember{mutableStateOf(FocusControl.allowNotifications(c))}
 var calls by remember{mutableStateOf(FocusControl.allowCalls(c))}
 var presetRequest by remember{mutableIntStateOf(0)}
 var presetMinutes by remember{mutableIntStateOf(FocusControl.minutes(c))}
 var granted by remember{mutableStateOf(FocusControl.access(c))}
 var precise by remember{mutableStateOf(Reminders.precise(c))}
 var error by remember{mutableStateOf("")}
 val lifecycle=androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
 DisposableEffect(lifecycle){val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME){granted=FocusControl.access(c);precise=Reminders.precise(c)}};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
 AssistantSurface("专注",JSONObject().put("minutes",hours*60+minutes).put("notifications",notifications).put("calls",calls),listOf(surfaceTool("configure","设置时长 minutes=1..480，可选notifications/calls布尔","minutes,notifications,calls"){a->val m=a.optInt("minutes",hours*60+minutes);require(m in 1..480);val n=a.optBoolean("notifications",notifications);val ca=a.optBoolean("calls",calls);prepared("调整专注设置",a){FocusControl.setMinutes(c,m);presetMinutes=m;presetRequest++;hours=m/60;minutes=m%60;notifications=n;calls=ca;FocusControl.option(c,"notifications",n);FocusControl.option(c,"calls",ca);"专注设置已调整，尚未开始计时"}}))
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp).padding(bottom=LocalFloatingAiHeight.current+10.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(6.dp)){
  Row(Modifier.fillMaxWidth().height(42.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,onBack)
   Text("专注",fontSize=17.sp,fontWeight=FontWeight.SemiBold)
  }
  Row(Modifier.fillMaxWidth().height(180.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
   FocusWheel(hours,8,"小时",presetRequest,presetMinutes/60){hours=it;if(hours==8)minutes=0}
   Text("小时",fontSize=12.sp,color=Muted)
   FocusWheel(minutes,if(hours==8)0 else 59,"分钟",presetRequest,presetMinutes%60){minutes=it}
   Text("分钟",fontSize=12.sp,color=Muted)
  }
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
   listOf(25,45,60,90).forEach{value->
    Box(Modifier.clip(RoundedCornerShape(50)).background(if(hours*60+minutes==value)AccentSurface else SoftSurface).clickable{presetMinutes=value;hours=value/60;minutes=value%60;presetRequest++}.padding(horizontal=12.dp,vertical=6.dp)){
     Text("$value 分",fontSize=12.sp,color=if(hours*60+minutes==value)DutBlue else Muted)
    }
   }
  }
  Spacer(Modifier.height(4.dp))
  Panel{
   FocusOption("允许通知",notifications,{
    notifications=it;FocusControl.option(c,"notifications",it)
    if(it){calls=true;FocusControl.option(c,"calls",true)}
   },Build.VERSION.SDK_INT>=29)
   FocusOption("允许来电响铃",calls,{calls=it;FocusControl.option(c,"calls",it)},!notifications&&Build.VERSION.SDK_INT>=29)
   Text(if(notifications)"通知按系统设置接收；单独静音来电需先关闭通知。"else"静音通知，可保留来电；闹钟仍允许，来电不会被自动挂断。",fontSize=10.sp,color=Muted)
   if(Build.VERSION.SDK_INT<29)Text("此系统版本仅支持全屏计时。",fontSize=10.sp,color=Muted)
  }
  if(!notifications&&!granted)TextButton(onClick={runCatching{FocusControl.openAccess(c)}.onFailure{error="无法打开授权页，请在系统设置中允许免打扰"}}){Text("允许大盘助手控制免打扰",fontSize=12.sp)}
  if(!precise)TextButton(onClick={runCatching{if(Build.VERSION.SDK_INT>=31)c.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,android.net.Uri.parse("package:${c.packageName}")))}.onFailure{error="请在系统设置中开启准时提醒"}}){Text("开启准时结束权限",fontSize=12.sp)}
  Button(onClick={runCatching{FocusControl.start(c,s,hours*60+minutes)}.onFailure{error=it.message?:"未能开始专注"}},enabled=hours*60+minutes in 1..480&&(notifications||granted),modifier=Modifier.fillMaxWidth().height(46.dp),shape=RoundedCornerShape(50),colors=ButtonDefaults.buttonColors(containerColor=DutBlue,contentColor=OnAccent)){
   Text("开始专注",fontSize=15.sp,fontWeight=FontWeight.SemiBold)
  }
  if(error.isNotBlank())Text(error,fontSize=11.sp,color=MaterialTheme.colorScheme.error)
  if(!precise)Text("未开启准时结束权限，后台结束可能受系统省电影响。",fontSize=10.sp,color=Muted)
  Spacer(Modifier.height(6.dp))
 }
}

@Composable private fun FocusOption(label:String,value:Boolean,onChange:(Boolean)->Unit,enabled:Boolean=true){
 Row(Modifier.fillMaxWidth().height(38.dp),verticalAlignment=Alignment.CenterVertically){Text(label,Modifier.weight(1f),fontSize=13.sp,color=if(enabled)Ink else Muted);Switch(value,onChange,enabled=enabled)}
}

@Composable private fun FocusWheel(value:Int,max:Int,label:String,request:Int,requestedValue:Int,onChange:(Int)->Unit){
 val state=rememberLazyListState(initialFirstVisibleItemIndex=value.coerceAtMost(max))
 val scope=rememberCoroutineScope();val change by rememberUpdatedState(onChange)
 var programmatic by remember{mutableStateOf(false)}
 var appliedRequest by remember{mutableIntStateOf(request)}
 val stopAtEdge=remember{object:NestedScrollConnection{
  override fun onPostScroll(consumed:Offset,available:Offset,source:NestedScrollSource)=Offset(0f,available.y)
  override suspend fun onPostFling(consumed:Velocity,available:Velocity)=Velocity(0f,available.y)
 }}
 LaunchedEffect(state,max){snapshotFlow{
  val layout=state.layoutInfo;val center=(layout.viewportStartOffset+layout.viewportEndOffset)/2
  layout.visibleItemsInfo.minByOrNull{abs(it.offset+it.size/2-center)}?.index
 }.distinctUntilChanged().collect{index->if(index!=null&&state.isScrollInProgress&&!programmatic)change(index.coerceIn(0,max))}}
 LaunchedEffect(request,max){
  val target=(if(appliedRequest!=request)requestedValue else value).coerceIn(0,max)
  programmatic=true
  try{state.scrollToItem(target);change(target);appliedRequest=request}finally{programmatic=false}
 }
 Box(Modifier.width(96.dp).height(180.dp).semantics{contentDescription=label;stateDescription=value.toString()},contentAlignment=Alignment.Center){
  Box(Modifier.fillMaxWidth().height(36.dp).background(AccentSurface,AppShape(9.dp)))
  LazyColumn(state=state,modifier=Modifier.fillMaxSize().nestedScroll(stopAtEdge),contentPadding=PaddingValues(vertical=72.dp),flingBehavior=rememberSnapFlingBehavior(state),horizontalAlignment=Alignment.CenterHorizontally){
   items(max+1){index->Box(Modifier.fillMaxWidth().height(36.dp).clickable{scope.launch{programmatic=true;try{change(index);state.animateScrollToItem(index)}finally{programmatic=false}}},contentAlignment=Alignment.Center){
    Text("%02d".format(index),fontSize=if(index==value)27.sp else 20.sp,fontWeight=if(index==value)FontWeight.SemiBold else FontWeight.Normal,color=if(index==value)DutBlue else Muted.copy(alpha=(1f-abs(index-value)*.24f).coerceIn(.15f,.6f)))
   }}
  }
 }
}

@Composable fun FocusImmersive(s:AppStore,onDone:()->Unit){
 val context=LocalContext.current
 val activity=remember(context){context.hostActivity()} ?: return
 val lifecycle=androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
 var now by remember{mutableLongStateOf(System.currentTimeMillis())}
 val end=s.focusEnd
 val duration=remember(end){FocusControl.duration(activity,end)}
 var confirmExit by remember{mutableStateOf(false)}
 var error by remember{mutableStateOf("")}
 var policyActive by remember{mutableStateOf(false)}
 fun finish(completed:Boolean){FocusControl.finish(activity,s,completed);onDone()}
 AssistantSurface("专注",JSONObject().put("end",end).put("active",true),listOf(surfaceTool("stop","结束当前专注，恢复相关免打扰设置","",true){a->prepared("结束专注",a){finish(false);"已结束专注"}}),priority=10)
 // Leaving the active focus screen is offered only by the bottom finish button.
 BackHandler{}
 LaunchedEffect(end){
  runCatching{FocusControl.apply(activity,end)}.onFailure{error=it.message?:"免打扰未启用"}
  while(s.focusEnd==end&&end>0){
   now=System.currentTimeMillis();policyActive=FocusControl.ruleActive(activity)
   if(now>=end){finish(true);break}
   delay(500)
  }
 }
 DisposableEffect(activity,lifecycle){
  val controller=WindowCompat.getInsetsController(activity.window,activity.window.decorView)
  controller.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
  controller.hide(WindowInsetsCompat.Type.systemBars())
  val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)controller.hide(WindowInsetsCompat.Type.systemBars())}
  lifecycle.addObserver(observer)
  onDispose{
   lifecycle.removeObserver(observer)
   controller.show(WindowInsetsCompat.Type.systemBars())

  }
 }
 val remaining=((end-now+999)/1000).coerceAtLeast(0)
 val time=if(remaining>=3600)"%d:%02d:%02d".format(remaining/3600,remaining/60%60,remaining%60)else"%02d:%02d".format(remaining/60,remaining%60)
 val background=if(AppAppearance.dark)Color(0xFF0C1726)else Color(0xFFF2F6FC)
 val foreground=if(AppAppearance.dark)Color(0xFFE9F1FF)else Color(0xFF18365C)
 val accent=if(AppAppearance.dark)Color(0xFF8FB9F4)else DutBlue
 val transition=rememberInfiniteTransition(label="focus-breath")
 val breath by transition.animateFloat(.94f,1.04f,infiniteRepeatable(tween(4000,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="breath")
 Surface(Modifier.fillMaxSize(),color=background){
  Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal=24.dp,vertical=12.dp),horizontalAlignment=Alignment.CenterHorizontally){
   Spacer(Modifier.weight(1f))
   BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center){
    val diameter=minOf(maxWidth,310.dp)
    Box(Modifier.size(diameter),contentAlignment=Alignment.Center){
     Canvas(Modifier.fillMaxSize()){
      val radius=size.minDimension*.42f
      drawCircle(Brush.radialGradient(listOf(accent.copy(alpha=.10f),Color.Transparent),center,radius*1.18f),radius*1.18f*breath)
      drawCircle(accent.copy(alpha=.07f),radius*breath,style=Stroke(1.dp.toPx()))
      drawCircle(accent.copy(alpha=.10f),radius*.94f,style=Stroke(2.dp.toPx()))
      val ring=radius*.94f;val progress=((end-now).toFloat()/duration).coerceIn(0f,1f)
      drawArc(accent.copy(alpha=.8f),-90f,360f*progress,false,Offset(center.x-ring,center.y-ring),Size(ring*2,ring*2),style=Stroke(3.dp.toPx(),cap=StrokeCap.Round))
      val angle=(-90+360*progress)*PI/180
      drawCircle(accent,4.dp.toPx(),Offset(center.x+cos(angle).toFloat()*ring,center.y+sin(angle).toFloat()*ring))
     }
     Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(7.dp)){
      Text(time,fontSize=if(remaining>=3600)42.sp else 54.sp,fontWeight=FontWeight.Light,fontFamily=FontFamily.Monospace,color=foreground)
      Text("专心于此刻",fontSize=12.sp,color=foreground.copy(alpha=.48f),letterSpacing=3.sp)
     }
    }
   }
   Spacer(Modifier.height(18.dp))
   Text(if(policyActive)if(FocusControl.allowCalls(activity))"通知静音 · 来电可响铃"else"通知与来电静音"else"通知按系统设置",fontSize=11.sp,color=foreground.copy(alpha=.5f),modifier=Modifier.padding(top=5.dp))
   if(error.isNotBlank())Text(error,fontSize=11.sp,color=foreground.copy(alpha=.7f),modifier=Modifier.padding(top=6.dp))
   Spacer(Modifier.weight(1f))
   TextButton(onClick={confirmExit=true},modifier=Modifier.fillMaxWidth().height(44.dp)){Text("结束本次专注",fontSize=13.sp,color=foreground.copy(alpha=.65f))}
  }
 }
 if(confirmExit)AlertDialog(onDismissRequest={confirmExit=false},title={Text("结束本次专注？",fontSize=18.sp)},text={Text("还剩 $time。结束后恢复本次限制。",fontSize=13.sp)},confirmButton={TextButton(onClick={finish(false)}){Text("结束专注")}},dismissButton={TextButton(onClick={confirmExit=false}){Text("继续专注")}})
}

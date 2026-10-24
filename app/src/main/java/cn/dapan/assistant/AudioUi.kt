package cn.dapan.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.UUID

/** One recording session shared by the composer and companion; moving controls never owns the mic. */
@Stable class AssistantVoiceController {
 var active by mutableStateOf(false);internal set
 var ending by mutableStateOf(false);internal set
 var cancelled by mutableStateOf(false);internal set
 var outside by mutableStateOf(false);internal set
 var recording by mutableStateOf(false);internal set
 var seconds by mutableIntStateOf(0);internal set
 var limitReached by mutableStateOf(false);internal set
 var shared by mutableStateOf(false);internal set
 var feedback by mutableStateOf<String?>(null);internal set
 internal var begin:()->Boolean={false}
 internal var finish:(Boolean)->Unit={}
 fun start()=begin()
 fun move(cancel:Boolean){if(active&&!ending)outside=cancel}
 fun stop(cancel:Boolean=false)=finish(cancel)
 val label get()=when{ending->if(cancelled)"已取消"else"识别中…";active&&outside->"松开取消";active&&limitReached->"已录满 · 松开发送";recording->"松开发送 ${seconds/60}:${(seconds%60).toString().padStart(2,'0')}"+(if(shared)" / 1:00"else"");active->"准备中…";else->"按住说话"}
}

@Composable fun rememberAssistantVoice(secrets:Secrets,enabled:Boolean=true,onStart:()->Unit={},onText:(String)->Unit):AssistantVoiceController{
 val controller=remember{AssistantVoiceController()}
 AssistantVoiceSession(controller,secrets,enabled,onStart,onText)
 return controller
}

// ASR partials and the timer recompose this small host, not the calendar/map behind it.
@Composable private fun AssistantVoiceSession(controller:AssistantVoiceController,secrets:Secrets,enabled:Boolean,onStart:()->Unit,onText:(String)->Unit){
 val c=LocalContext.current;val haptic=LocalHapticFeedback.current
 var message by remember{mutableStateOf<String?>(null)}
 var session by remember{mutableStateOf("")}
 val currentEnabled by rememberUpdatedState(enabled)
 val currentText by rememberUpdatedState(onText);val currentStart by rememberUpdatedState(onStart)
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()){granted->message=if(granted)"麦克风已允许，请再次按住说话"else"未允许麦克风，可直接打字"}
 val recording=controller.active&&!controller.ending&&AudioState.session==session&&AudioState.ready
 SideEffect{controller.recording=recording;controller.limitReached=controller.active&&AudioState.session==session&&AudioState.limitReached}
 LaunchedEffect(recording){
  if(recording){val startedAt=SystemClock.elapsedRealtime();controller.seconds=0;while(true){controller.seconds=((SystemClock.elapsedRealtime()-startedAt)/1000).toInt();delay(250)}}
 }
 LaunchedEffect(AudioState.running,AudioState.session,controller.ending){
  if(controller.active&&AudioState.session==session&&!AudioState.running){
   val released=controller.ending
   // The capped recording waits for the same release/cancel gesture as a normal one.
   if(AudioState.limitReached&&AudioState.error==null&&!released)return@LaunchedEffect
   controller.active=false;controller.ending=false;controller.feedback=null
   if(!controller.cancelled){
    if(VoiceRelease.shouldSend(released,controller.cancelled,AudioState.error,AudioState.text))currentText(AudioState.text)
    message=AudioState.error?:if(!released)"收听意外结束，本次未发送，请重试"else if(AudioState.text.isBlank())"没有识别到内容，请再按住说一次"else null
   }
  }
 }
 LaunchedEffect(controller.active,controller.ending,recording,controller.outside,controller.cancelled,controller.limitReached,AudioState.text,AudioState.session){
  controller.feedback=if(!controller.active||controller.cancelled)null else if(controller.ending)"正在整理识别结果…"else if(controller.outside)"松开取消 · 移回继续"else if(controller.limitReached)"已录满 60 秒，松开发送，上滑取消\n"+AudioState.text else if(AudioState.session==session&&AudioState.text.isNotBlank())AudioState.text else if(recording)if(controller.shared)"公用语音最多 60 秒 · 松开发送，上滑取消"else"正在听 · 松开发送，上滑取消"else"正在打开麦克风…"
 }
 fun stop(cancel:Boolean=false){
  if(controller.active&&(cancel||!controller.ending)){controller.cancelled=cancel;controller.ending=true;haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
   if(AudioState.session==session&&!AudioState.running&&AudioState.limitReached){if(cancel){AudioState.text="";AudioState.partial="";AudioState.finalLines=emptyList()};return}
   runCatching{c.startService(Intent(c,RecordingService::class.java).setAction(if(cancel)"cancel-voice"else"stop").putExtra("session",session))}.onFailure{
    controller.active=false;controller.feedback=null;message="本次语音未发送，请重试"
   }
  }
 }
 fun start():Boolean{
  if(controller.active||!currentEnabled)return false
  if(AudioState.running){message="麦克风正在使用中，请先结束当前录音";return false}
  if(!SharedSpeech.canUse(c)){message=SharedSpeech.unavailableMessage(c);return false}
  if(c.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){permission.launch(Manifest.permission.RECORD_AUDIO);return false}
  currentStart();session="input-"+UUID.randomUUID();controller.ending=false;controller.cancelled=false;controller.outside=false;controller.seconds=0;controller.limitReached=false;controller.shared=SharedSpeech.selected(c);controller.active=true;message=null
  haptic.performHapticFeedback(HapticFeedbackType.LongPress)
  try{ContextCompat.startForegroundService(c,Intent(c,RecordingService::class.java).putExtra("voice",true).putExtra("session",session))}catch(e:Exception){controller.active=false;message="麦克风启动失败，请重试";return false}
  return true
 }
 SideEffect{controller.begin={start()};controller.finish={stop(it)}}
 DisposableEffect(Unit){onDispose{if(controller.active){controller.cancelled=true;if(AudioState.running&&AudioState.session==session)runCatching{c.startService(Intent(c,RecordingService::class.java).setAction("cancel-voice").putExtra("session",session))}else if(AudioState.session==session&&AudioState.limitReached){AudioState.text="";AudioState.partial="";AudioState.finalLines=emptyList()}};controller.begin={false};controller.finish={}}}
 val lifecycle=(c as androidx.activity.ComponentActivity).lifecycle
 DisposableEffect(lifecycle){val observer=androidx.lifecycle.LifecycleEventObserver{_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP)stop(true)};lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}}
 if(message!=null)AlertDialog(onDismissRequest={message=null},containerColor=CardSurface,text={Text(message!!)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}})
}

@Composable fun VoiceButton(controller:AssistantVoiceController,modifier:Modifier=Modifier.width(164.dp),compact:Boolean=false){
 var pressed by remember{mutableStateOf(false)}
 val own=controller.active;val recording=controller.recording
 val tint by androidx.compose.animation.animateColorAsState(if(own&&controller.outside)Color(0xFFBA3D44)else if(own)DutBlue else if(pressed)AccentSurface else SoftSurface,label="voiceTint")
 val foreground=if(own)OnAccent else Muted
 val label=controller.label
 Row(modifier.height(36.dp).clip(CircleShape).background(tint).semantics{
  contentDescription="语音输入";stateDescription=label;role=Role.Button;liveRegion=LiveRegionMode.Polite
  onClick{if(own)controller.stop()else controller.start();true}
 }.pointerInput(controller){awaitEachGesture{
  val down=awaitFirstDown(requireUnconsumed=false)
  pressed=true
  val accepted=controller.start()
  if(accepted)down.consume()
  var released=false
  try{
   while(true){
    val event=awaitPointerEvent()
    val change=event.changes.firstOrNull{it.id==down.id}?:break
    val outside=!VoiceRelease.inside(change.position.x,change.position.y,size.width.toFloat(),size.height.toFloat())
    if(accepted)controller.move(outside)
    if(accepted)change.consume()
    if(!change.pressed){released=true;if(accepted)controller.stop(outside);break}
   }
  }finally{pressed=false;if(accepted&&!released)controller.stop(true)}
 }},horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
  if(own&&!recording&&!controller.limitReached)CircularProgressIndicator(Modifier.size(13.dp),color=foreground,strokeWidth=1.5.dp)
  else if(recording){
   val pulse=rememberInfiniteTransition(label="recordingPulse")
   val alpha by pulse.animateFloat(.4f,1f,infiniteRepeatable(tween(650),RepeatMode.Reverse),label="recordingDot")
   Box(Modifier.size(7.dp).clip(CircleShape).background(foreground.copy(alpha=alpha)))
  }else LineIcon(AppGlyph.Mic,Modifier.size(15.dp),foreground)
  if(!compact){Spacer(Modifier.width(7.dp));Text(label,fontSize=12.sp,lineHeight=16.sp,color=foreground,maxLines=1)}
 }
}

package cn.dapan.assistant

import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.random.Random

enum class PetEdge { LEFT, RIGHT, TOP, BOTTOM }
private val PetSize=60.dp
private val PetSproutTouchSize=44.dp
private const val PetVisible=.8f

/** Geometry uses a visible 48dp touch target even while the companion is parked. */
internal object PetDockGeometry {
 fun span(total:Float,size:Float)=(total-size).coerceAtLeast(0f)
 fun x(edge:PetEdge,ratio:Float,width:Float,size:Float)=when(edge){
  PetEdge.LEFT->-size*(1-PetVisible);PetEdge.RIGHT->width-size*PetVisible
  else->ratio.coerceIn(0f,1f)*span(width,size)
 }
 fun y(edge:PetEdge,ratio:Float,height:Float,size:Float)=when(edge){
  PetEdge.TOP->-size*(1-PetVisible);PetEdge.BOTTOM->height-size*PetVisible
  else->ratio.coerceIn(0f,1f)*span(height,size)
 }
 fun nearest(x:Float,y:Float,width:Float,height:Float)=listOf(x to PetEdge.LEFT,(width-x) to PetEdge.RIGHT,y to PetEdge.TOP,(height-y) to PetEdge.BOTTOM).minBy{it.first}.second
 fun ratio(edge:PetEdge,x:Float,y:Float,width:Float,height:Float,size:Float):Float {
  val span=if(edge==PetEdge.LEFT||edge==PetEdge.RIGHT)span(height,size)else span(width,size)
  return if(span<=0f)0f else ((if(edge==PetEdge.LEFT||edge==PetEdge.RIGHT)y else x)/span).coerceIn(0f,1f)
 }
 fun hiddenX(edge:PetEdge,ratio:Float,width:Float,size:Float)=when(edge){
  PetEdge.LEFT->-size*1.4f;PetEdge.RIGHT->width+size*.4f;else->x(edge,ratio,width,size)
 }
 fun hiddenY(edge:PetEdge,ratio:Float,height:Float,size:Float)=when(edge){
  PetEdge.TOP->-size*1.4f;PetEdge.BOTTOM->height+size*.4f;else->y(edge,ratio,height,size)
 }
 fun sproutX(edge:PetEdge,ratio:Float,width:Float,petSize:Float,touchSize:Float)=when(edge){
  PetEdge.LEFT->0f;PetEdge.RIGHT->span(width,touchSize)
  else->(x(edge,ratio,width,petSize)+(petSize-touchSize)/2).coerceIn(0f,span(width,touchSize))
 }
 fun sproutY(edge:PetEdge,ratio:Float,height:Float,petSize:Float,touchSize:Float)=when(edge){
  PetEdge.TOP->0f;PetEdge.BOTTOM->span(height,touchSize)
  else->(y(edge,ratio,height,petSize)+(petSize-touchSize)/2).coerceIn(0f,span(height,touchSize))
 }
}

internal enum class PetPressPhase { WAITING, DRAGGING, RECORDING }
/** A gesture may become a drag OR a recording, never both. */
internal class PetPressState(private val touchSlop:Float){
 var phase=PetPressPhase.WAITING;private set
 fun move(dx:Float,dy:Float):PetPressPhase {
  if(phase==PetPressPhase.WAITING&&dx*dx+dy*dy>touchSlop*touchSlop)phase=PetPressPhase.DRAGGING
  return phase
 }
 fun hold():PetPressPhase {if(phase==PetPressPhase.WAITING)phase=PetPressPhase.RECORDING;return phase}
}
internal object PetInteractionRules {
 fun cancelVoice(x:Float,y:Float,width:Float,height:Float)=!VoiceRelease.inside(x,y,width,height)
 fun canAutoHide(autoHide:Boolean,expanded:Boolean,voiceActive:Boolean,keyboardVisible:Boolean,pressed:Boolean,dragging:Boolean)=
  autoHide&&!expanded&&!voiceActive&&!keyboardVisible&&!pressed&&!dragging
}
private class PetDock(context:Context){
 private val prefs=context.applicationContext.getSharedPreferences("companion-pet",Context.MODE_PRIVATE)
 var edge by mutableStateOf(runCatching{PetEdge.valueOf(prefs.getString("edge","RIGHT")!!)}.getOrDefault(PetEdge.RIGHT));private set
 var ratio by mutableFloatStateOf(prefs.getFloat("ratio",.58f).takeIf{it.isFinite()}?.coerceIn(0f,1f)?:.58f);private set
 fun dock(next:PetEdge,value:Float){edge=next;ratio=value.coerceIn(0f,1f);prefs.edit().putString("edge",next.name).putFloat("ratio",ratio).apply()}
}

/** Tap for the mini composer, hold to speak, or move before the hold to reposition. */
@Composable fun CompanionPetOverlay(
 modifier:Modifier=Modifier,
 autoHide:Boolean,
 expanded:Boolean,
 voiceActive:Boolean,
 voiceCancelling:Boolean=false,
 onToggleAssistant:()->Unit,
 onVoiceStart:()->Boolean,
 onVoiceMove:(Boolean)->Unit,
 onVoiceEnd:(Boolean)->Unit,
 onDockChanged:(PetEdge)->Unit={}
){
 if(!AppAppearance.petEnabled)return
 val context=LocalContext.current
 val lifecycle=(context as? androidx.lifecycle.LifecycleOwner)?.lifecycle
 var resumed by remember{mutableStateOf(lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)!=false)}
 DisposableEffect(lifecycle){
  val observer=androidx.lifecycle.LifecycleEventObserver{_,_->resumed=lifecycle?.currentState?.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)!=false}
  lifecycle?.addObserver(observer);onDispose{lifecycle?.removeObserver(observer)}
 }
 if(!resumed)return
 val dock=remember{PetDock(context)}
 SideEffect{onDockChanged(dock.edge)}
 val latestToggle by rememberUpdatedState(onToggleAssistant)
 val latestStart by rememberUpdatedState(onVoiceStart)
 val latestMove by rememberUpdatedState(onVoiceMove)
 val latestEnd by rememberUpdatedState(onVoiceEnd)
 BoxWithConstraints(modifier.fillMaxSize().clipToBounds()){
  val density=LocalDensity.current
  val boxW=with(density){maxWidth.toPx()};val boxH=with(density){maxHeight.toPx()};val size=with(density){PetSize.toPx()}
  val sproutSize=with(density){PetSproutTouchSize.toPx()}
  val keyboardVisible=WindowInsets.ime.getBottom(density)>0
  if(boxW<size||boxH<size)return@BoxWithConstraints
  var dragging by remember{mutableStateOf(false)}
  var dragX by remember{mutableFloatStateOf(0f)};var dragY by remember{mutableFloatStateOf(0f)}
  var tilt by remember{mutableFloatStateOf(0f)}
  var interaction by remember{mutableIntStateOf(0)}
  var greeting by remember{mutableStateOf(false)}
  var pressed by remember{mutableStateOf(false)}
  var holding by remember{mutableStateOf(false)}
  var cancelling by remember{mutableStateOf(false)}
  var hidden by remember{mutableStateOf(false)}
  val dockX by animateFloatAsState(if(dragging)dragX else PetDockGeometry.x(dock.edge,dock.ratio,boxW,size),if(dragging)snap()else spring(dampingRatio=.82f,stiffness=Spring.StiffnessMedium),label="petX")
  val dockY by animateFloatAsState(if(dragging)dragY else PetDockGeometry.y(dock.edge,dock.ratio,boxH,size),if(dragging)snap()else spring(dampingRatio=.82f,stiffness=Spring.StiffnessMedium),label="petY")
  val hiddenFraction by animateFloatAsState(if(hidden)1f else 0f,tween(280,easing=FastOutSlowInEasing),label="petHide")
  val x=dockX+(PetDockGeometry.hiddenX(dock.edge,dock.ratio,boxW,size)-dockX)*hiddenFraction
  val y=dockY+(PetDockGeometry.hiddenY(dock.edge,dock.ratio,boxH,size)-dockY)*hiddenFraction
  val currentX by rememberUpdatedState(x)
  val currentY by rememberUpdatedState(y)
  LaunchedEffect(autoHide,expanded,voiceActive,keyboardVisible,pressed,dragging,holding,interaction){
   if(!autoHide||expanded||voiceActive||holding)hidden=false
   if(PetInteractionRules.canAutoHide(autoHide,expanded,voiceActive||holding,keyboardVisible,pressed,dragging)){
    delay(5000);hidden=true
   }
  }
  LaunchedEffect(interaction){greeting=interaction>0;delay(1300);greeting=false}
  DisposableEffect(Unit){onDispose{if(holding){holding=false;latestEnd(true)}}}
  fun settle(){
   val edge=PetDockGeometry.nearest(dragX+size/2,dragY+size/2,boxW,boxH)
   dock.dock(edge,PetDockGeometry.ratio(edge,dragX,dragY,boxW,boxH,size));dragging=false;tilt=0f;interaction++
  }
  fun wake(){hidden=false;interaction++}
  fun toggle(){wake();latestToggle()}
  fun endVoice(cancel:Boolean){
   if(holding){holding=false;cancelling=false;latestEnd(cancel)}
  }
  val gesture=if(hidden)Modifier else Modifier.pointerInput(boxW,boxH){awaitEachGesture{
   val down=awaitFirstDown(requireUnconsumed=false)
   down.consume();pressed=true;interaction++
   val press=PetPressState(viewConfiguration.touchSlop)
   var last=down
   var released=false
   var aborted=false
   try{
    // AwaitPointerEventScope's timeout keeps pointer dispatch synchronous; no competing
    // tap/drag detectors can turn the same long press into two independent gestures.
    val completedBeforeHold=withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis){
     while(true){
      val event=awaitPointerEvent()
      val change=event.changes.firstOrNull{it.id==down.id}
      if(change==null||event.changes.any{it.id!=down.id&&it.pressed}){aborted=true;break}
      last=change;change.consume()
      if(!change.pressed){released=true;break}
      if(press.move(change.position.x-down.position.x,change.position.y-down.position.y)==PetPressPhase.DRAGGING)break
     }
     true
    }
    if(!aborted){
     when{
      released->toggle()
      completedBeforeHold==null->{
       press.hold();cancelling=false
       val accepted=latestStart()
       holding=accepted
       if(accepted){
        cancelling=PetInteractionRules.cancelVoice(last.position.x,last.position.y,size,size)
        latestMove(cancelling)
       }
       while(true){
        val event=awaitPointerEvent()
        val change=event.changes.firstOrNull{it.id==down.id}
        if(change==null||event.changes.any{it.id!=down.id&&it.pressed})break
        change.consume()
        val outside=PetInteractionRules.cancelVoice(change.position.x,change.position.y,size,size)
        if(accepted&&cancelling!=outside){cancelling=outside;latestMove(outside)}
        if(!change.pressed){released=true;if(accepted)endVoice(outside);break}
       }
      }
      press.phase==PetPressPhase.DRAGGING->{
       dragX=(currentX+last.position.x-down.position.x).coerceIn(-size*.2f,boxW-size*.8f)
       dragY=(currentY+last.position.y-down.position.y).coerceIn(-size*.2f,boxH-size*.8f)
       dragging=true
       while(true){
        val event=awaitPointerEvent()
        val change=event.changes.firstOrNull{it.id==down.id}?:break
        val amount=change.positionChange();change.consume()
        dragX=(dragX+amount.x).coerceIn(-size*.2f,boxW-size*.8f)
        dragY=(dragY+amount.y).coerceIn(-size*.2f,boxH-size*.8f)
        tilt=(amount.x/28f).coerceIn(-1f,1f)
        if(!change.pressed){released=true;break}
       }
      }
     }
    }
   }finally{
    pressed=false
    if(holding)endVoice(true)
    if(dragging)settle()
    interaction++
   }
  }}.semantics{
   contentDescription="小助手，点按${if(expanded)"收起"else"展开"} AI，长按说话，拖动贴边"
   stateDescription=if(holding||voiceActive)if(cancelling||voiceCancelling)"松开取消"else"松开发送"else if(expanded)"AI 已展开"else"AI 已收起"
   role=Role.Button
   onClick(if(expanded)"收起 AI"else"展开 AI"){toggle();true}
  }
  if(hiddenFraction<.999f){
   Box(Modifier.offset{IntOffset(x.roundToInt(),y.roundToInt())}.size(PetSize).systemGestureExclusion().then(gesture)){
    PetSprite(dragging=dragging,expanded=greeting||expanded||holding||voiceActive,clinging=!dragging&&!hidden,edge=dock.edge,tilt=tilt,visible=PetVisible,hangOut=0f)
    if(holding||voiceActive){
     val tint=if(cancelling||voiceCancelling)MaterialTheme.colorScheme.error else DutBlue
     Canvas(Modifier.fillMaxSize()){
      drawCircle(tint.copy(alpha=.17f),radius=this.size.minDimension*.48f)
      drawCircle(tint,radius=this.size.minDimension*.45f,style=Stroke(2.dp.toPx()))
     }
     Surface(Modifier.align(Alignment.BottomCenter).size(20.dp),shape=AppShape(10.dp),color=tint){
      Box(contentAlignment=Alignment.Center){LineIcon(if(cancelling||voiceCancelling)AppGlyph.Close else AppGlyph.Mic,Modifier.size(13.dp),OnAccent)}
     }
    }
   }
  }
  if(hiddenFraction>.001f){
   Box(Modifier.offset{IntOffset(PetDockGeometry.sproutX(dock.edge,dock.ratio,boxW,size,sproutSize).roundToInt(),PetDockGeometry.sproutY(dock.edge,dock.ratio,boxH,size,sproutSize).roundToInt())}
    .size(PetSproutTouchSize).systemGestureExclusion().graphicsLayer{alpha=hiddenFraction}
    .then(if(hidden)Modifier.clickable(onClick={wake()}).semantics{contentDescription="唤出小助手";role=Role.Button}else Modifier)){
    PetSprout(dock.edge)
   }
  }
 }
}

/** Only the sprout is visible; its transparent 44dp target stays against the edge. */
@Composable private fun PetSprout(edge:PetEdge){
 val tint=UiPalette.companion
 Canvas(Modifier.fillMaxSize()){
  val start=when(edge){PetEdge.LEFT->Offset(2.dp.toPx(),size.height/2);PetEdge.RIGHT->Offset(size.width-2.dp.toPx(),size.height/2);PetEdge.TOP->Offset(size.width/2,2.dp.toPx());PetEdge.BOTTOM->Offset(size.width/2,size.height-2.dp.toPx())}
  val rotation=when(edge){PetEdge.BOTTOM->0f;PetEdge.LEFT->90f;PetEdge.TOP->180f;PetEdge.RIGHT->270f}
  rotate(rotation,pivot=start){
   val tip=start+Offset(0f,-17.dp.toPx())
   drawLine(tint,start,tip,strokeWidth=2.3.dp.toPx(),cap=StrokeCap.Round)
   rotate(-33f,pivot=tip){drawOval(tint,topLeft=tip+Offset(-8.dp.toPx(),-4.dp.toPx()),size=Size(9.dp.toPx(),5.dp.toPx()))}
   rotate(32f,pivot=tip){drawOval(tint.copy(alpha=.85f),topLeft=tip+Offset(0f,-4.dp.toPx()),size=Size(9.dp.toPx(),5.dp.toPx()))}
  }
 }
}

/** One hand-drawn companion: breathes, blinks, grips the border with both hands and leans while dragged. */
@Composable private fun PetSprite(dragging:Boolean,expanded:Boolean,clinging:Boolean,edge:PetEdge,tilt:Float,visible:Float,hangOut:Float){
 val palette=UiPalette
 val life=rememberInfiniteTransition(label="petLife")
 val float by life.animateFloat(-1f,1f,infiniteRepeatable(tween(2400,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="petFloat")
 val puff by life.animateFloat(1f,1.05f,infiniteRepeatable(tween(2400,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="petPuff")
 val sway by life.animateFloat(-9f,9f,infiniteRepeatable(tween(3200,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="petSway")
 val halo by life.animateFloat(.16f,.4f,infiniteRepeatable(tween(1800,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="petHalo")
 val squeeze by life.animateFloat(1f,1.12f,infiniteRepeatable(tween(1500,easing=FastOutSlowInEasing),RepeatMode.Reverse),label="petSqueeze")
 var blink by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){while(true){delay(Random.nextLong(2600,5400));blink=true;delay(110);blink=false}}
 val eyeOpen by animateFloatAsState(if(blink).06f else if(dragging)1.18f else 1f,tween(90),label="petEye")
 val arm by animateFloatAsState(if(expanded)1f else 0f,spring(dampingRatio=Spring.DampingRatioMediumBouncy,stiffness=Spring.StiffnessLow),label="petArm")
 // Parked on an edge: both hands come out and cling to the screen border.
 val gripRaw by animateFloatAsState(if(clinging)1f else 0f,spring(dampingRatio=Spring.DampingRatioMediumBouncy,stiffness=Spring.StiffnessLow),label="petCling")
 var peeking by remember{mutableStateOf(false)}
 LaunchedEffect(clinging){
  peeking=false
  if(clinging)while(true){delay(Random.nextLong(6000,11000));peeking=true;delay(950);peeking=false}
 }
 val peek by animateFloatAsState(if(peeking&&clinging)1f else 0f,spring(dampingRatio=.62f,stiffness=Spring.StiffnessLow),label="petPeek")
 val grip=gripRaw*(1f-peek*.5f) // it loosens the hold a little while leaning out to peek
 val horizontal=edge==PetEdge.LEFT||edge==PetEdge.RIGHT
 val inwardX=when(edge){PetEdge.LEFT->1f;PetEdge.RIGHT->-1f;else->0f}
 val inwardY=when(edge){PetEdge.TOP->1f;PetEdge.BOTTOM->-1f;else->0f}
 val reveal=LocalDensity.current.run{PetSize.toPx()}*.34f
 val lean=tilt*13f+(if(horizontal)inwardX*30f else inwardY*22f)*hangOut
 Canvas(Modifier.fillMaxSize().graphicsLayer{
  rotationZ=lean // hanging off the border: it leans right over so both eyes clear the edge
  translationX=inwardX*reveal*peek
  translationY=float*2.2f+inwardY*reveal*peek
  scaleX=puff*(if(horizontal)1f-.05f*grip else 1f)
  scaleY=(2f-puff)*(if(!horizontal)1f-.05f*grip else 1f)
 }){
  val w=size.width;val h=size.height
  val top=h*.13f;val bodyH=h*.78f
  // Live border line inside the body, derived from how much is currently revealed:
  // hands are anchored to it, so they stay glued to the screen edge at any reveal.
  val wallX=when(edge){PetEdge.LEFT->w*(1f-visible);PetEdge.RIGHT->w*visible;else->w*.5f}
  val wallY=when(edge){PetEdge.TOP->h*(1f-visible);PetEdge.BOTTOM->h*visible;else->h*.5f}
  val skin=Brush.linearGradient(listOf(palette.accent,palette.companion),start=Offset(w*.15f,0f),end=Offset(w*.85f,h))
  if(expanded)drawCircle(palette.accent.copy(alpha=halo*.45f),radius=w*.6f,center=Offset(w*.5f,h*.55f))
  // Ground shadow keeps it feeling physical while it floats.
  drawOval(Color.Black.copy(alpha=.12f),topLeft=Offset(w*.22f,top+bodyH*.92f),size=Size(w*.56f,h*.07f))
  // Sprout on top, swaying with the breath.
  rotate(sway,pivot=Offset(w*.5f,top)){
   drawLine(palette.companion,Offset(w*.5f,top+h*.03f),Offset(w*.5f,top-h*.13f),strokeWidth=2.4f,cap=StrokeCap.Round)
   rotate(-32f,pivot=Offset(w*.5f,top-h*.13f)){drawOval(palette.companion.copy(alpha=.85f),topLeft=Offset(w*.5f-w*.18f,top-h*.18f),size=Size(w*.18f,h*.09f))}
   rotate(32f,pivot=Offset(w*.5f,top-h*.13f)){drawOval(palette.companion.copy(alpha=.6f),topLeft=Offset(w*.5f,top-h*.18f),size=Size(w*.18f,h*.09f))}
  }
  // Arms pop out to hold the panel open.
  if(arm>.02f)for(side in listOf(-1f,1f))drawCircle(palette.accent,radius=w*.075f*arm,center=Offset(w*.5f+side*w*.49f,h*.52f-arm*h*.12f))
  // Body.
  drawRoundRect(skin,topLeft=Offset(w*.05f,top),size=Size(w*.9f,bodyH),cornerRadius=CornerRadius(w*.44f,w*.44f))
  drawRoundRect(palette.line.copy(alpha=.3f),topLeft=Offset(w*.05f,top),size=Size(w*.9f,bodyH),cornerRadius=CornerRadius(w*.44f,w*.44f),style=Stroke(1f))
  drawCircle(Color.White.copy(alpha=.26f),radius=w*.24f,center=Offset(w*.37f,h*.34f))
  // The whole face slides towards the screen while hanging, so both eyes make it past the edge.
  translate(inwardX*w*.07f*hangOut,inwardY*h*.07f*hangOut){
   // Eyes follow the drag and blink now and then.
   val eyeY=h*.46f;val dx=w*.185f
   for(side in listOf(-1f,1f)){
    val ex=w*.5f+dx*side
    if(eyeOpen>.25f){
     drawOval(Color.White,topLeft=Offset(ex-w*.075f,eyeY-h*.1f*eyeOpen),size=Size(w*.15f,h*.2f*eyeOpen))
     drawCircle(Color(0xFF20243A),radius=w*.042f,center=Offset(ex+tilt*w*.022f+inwardX*w*.02f*grip,eyeY+inwardY*h*.02f*grip))
     drawCircle(Color.White,radius=w*.014f,center=Offset(ex-w*.017f,eyeY-h*.022f))
    }else drawLine(palette.ink,Offset(ex-w*.06f,eyeY),Offset(ex+w*.06f,eyeY),strokeWidth=2.4f,cap=StrokeCap.Round)
   }
   val blush=Color(0xFFFF8FB0)
   drawCircle(blush.copy(alpha=.38f),radius=w*.058f,center=Offset(w*.5f-dx*1.3f,h*.58f))
   drawCircle(blush.copy(alpha=.38f),radius=w*.058f,center=Offset(w*.5f+dx*1.3f,h*.58f))
   // Mouth: a little "o" of effort while dragged, a small determined line while clinging, a smile otherwise.
   if(dragging)drawCircle(palette.ink,radius=w*.032f,center=Offset(w*.5f,h*.63f))
   else if(grip>.4f)drawLine(palette.ink,Offset(w*.45f,h*.62f),Offset(w*.55f,h*.62f),strokeWidth=2.2f,cap=StrokeCap.Round)
   else drawArc(palette.ink,18f,144f,false,topLeft=Offset(w*.42f,h*.55f),size=Size(w*.16f,h*.1f),style=Stroke(2.4f,cap=StrokeCap.Round))
  }
  if(expanded)drawCircle(Color.White.copy(alpha=.92f),radius=w*.028f,center=Offset(w*.76f,h*.22f))
  // Both hands gripping the border: palm tucked in, fingertips curled over the line.
  if(grip>.03f){
   val palm=if(horizontal)w*.095f else h*.095f
   val along=if(horizontal)h*.3f else w*.3f
   // Undo the lean and the peek shift for the hands only: they stay welded to the real border
   // while the body swings, which is exactly how hanging works.
   rotate(-lean,pivot=Offset(w*.5f,h*.5f)){
    translate(-inwardX*reveal*peek,-inwardY*reveal*peek){
     for(step in listOf(-1f,1f)){
      val hx=if(horizontal)wallX+inwardX*palm else w*.5f+along*step
      val hy=if(horizontal)h*.5f+along*step else wallY+inwardY*palm
      drawCircle(palette.accent,radius=w*.082f*grip*squeeze,center=Offset(hx,hy))
      drawCircle(palette.accent.copy(alpha=.35f),radius=w*.11f*grip*squeeze,center=Offset(hx,hy))
      for(finger in listOf(-1f,0f,1f)){
       val fx=if(horizontal)wallX+inwardX*w*.028f else hx+finger*w*.052f
       val fy=if(horizontal)hy+finger*h*.052f else wallY+inwardY*h*.028f
       drawCircle(palette.accent,radius=w*.026f*grip*squeeze,center=Offset(fx,fy))
      }
     }
    }
   }
  }
 }
}

@Composable fun PetPreference(){
 val context=LocalContext.current
 var error by remember{mutableStateOf<String?>(null)}
 fun change(value:Boolean){runCatching{AppAppearance.setPetEnabled(context,value)}.onFailure{error="设置未保存，请重试"}}
 Column(Modifier.fillMaxWidth()){
  Row(Modifier.fillMaxWidth().heightIn(min=48.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
   Column(Modifier.weight(1f)){
    Text("悬浮小助手",fontSize=14.sp,color=Ink)
    Text("开启后替代 AI 栏。点按聊天，长按说话。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
   }
   Switch(AppAppearance.petEnabled,{change(it)},Modifier.semantics{contentDescription="悬浮小助手"})
  }
  if(AppAppearance.petEnabled){
   Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
    Box(Modifier.size(68.dp).semantics{contentDescription="小助手外观预览"}){
     PetSprite(dragging=false,expanded=false,clinging=false,edge=PetEdge.RIGHT,tilt=0f,visible=1f,hangOut=0f)
    }
    Column(verticalArrangement=Arrangement.spacedBy(3.dp)){
     Text("小助手预览",fontSize=12.sp,color=Ink)
     Text("外观随当前配色变化。",fontSize=11.sp,color=Muted)
    }
   }
   Row(Modifier.fillMaxWidth().heightIn(min=42.dp).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
    Column(Modifier.weight(1f)){
     Text("闲置自动收起",fontSize=13.sp,color=Ink)
     Text("闲置 5 秒只露草叶；点草叶唤出。右下角也可切换。",fontSize=11.sp,lineHeight=16.sp,color=Muted)
    }
    Switch(AppAppearance.petAutoHide,{value->runCatching{AppAppearance.setPetAutoHide(context,value)}.onFailure{error="设置未保存，请重试"}})
   }
  }
  error?.let{Text(it,Modifier.padding(horizontal=10.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.error)}
 }
}

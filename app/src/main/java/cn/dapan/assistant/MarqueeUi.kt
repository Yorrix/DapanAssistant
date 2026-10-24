package cn.dapan.assistant

import org.json.*

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.core.view.*
import androidx.lifecycle.*
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.math.*

data class BannerOptions(
 val text:String="大工，加油！",val size:Float=96f,val speed:Float=100f,
 val foreground:Int=0xFFFFFF66.toInt(),val background:Int=0xFF000000.toInt(),
 val landscape:Boolean=true,val moving:Boolean=false,val blink:Boolean=false,val bold:Boolean=false,val shake:Float=0f
){
 fun json()=JSONObject().put("text",text).put("size",size).put("speed",speed).put("foreground",foreground).put("background",background)
  .put("landscape",landscape).put("moving",moving).put("blink",blink).put("bold",bold).put("shake",shake).toString()
 companion object{
  fun parse(raw:String?):BannerOptions=runCatching{
   val o=JSONObject(raw?:return BannerOptions())
   fun number(key:String,default:Float,min:Float,max:Float)=o.optDouble(key,default.toDouble()).toFloat().let{if(it.isFinite())it.coerceIn(min,max)else default}
   BannerOptions(o.optString("text","大工，加油！").take(300),number("size",96f,32f,240f),number("speed",100f,20f,300f),o.optInt("foreground",0xFFFFFF66.toInt()) or 0xFF000000.toInt(),o.optInt("background",0xFF000000.toInt()) or 0xFF000000.toInt(),o.optBoolean("landscape",true),o.optBoolean("moving"),o.optBoolean("blink"),o.optBoolean("bold"),number("shake",0f,0f,12f))
  }.getOrDefault(BannerOptions())
  fun load(c:Context)=parse(c.getSharedPreferences("handheld-banner",0).getString("options",null))
 }
}

@Composable fun MarqueeScreen(){
 val c=LocalContext.current;val keyboard=LocalSoftwareKeyboardController.current
 var options by remember{mutableStateOf(BannerOptions.load(c))}
 var colorTarget by remember{mutableStateOf<String?>(null)};var error by remember{mutableStateOf("")}
 val latest by rememberUpdatedState(options)
 fun persist(value:BannerOptions){c.getSharedPreferences("handheld-banner",0).edit().putString("options",value.json()).apply()}
 LaunchedEffect(options){delay(250);persist(options)}
 DisposableEffect(Unit){onDispose{persist(latest)}}
 AssistantSurface("手持弹幕",JSONObject(options.json()),listOf(
  surfaceTool("configure","弹幕 text,size(32-240),speed(20-300),foreground/background(#RRGGBB),landscape,moving,blink,bold,shake(0-12)","text,size,speed,foreground,background,landscape,moving,blink,bold,shake"){a->if(a.has("text"))a.text("text",300);listOf(Triple("size",32.0,240.0),Triple("speed",20.0,300.0),Triple("shake",0.0,12.0)).forEach{(key,min,max)->if(a.has(key))require(a.getDouble(key) in min..max){"$key 超出范围"}};val o=JSONObject(options.json());a.keys().forEach{key->if(key in listOf("foreground","background"))o.put(key,android.graphics.Color.parseColor(a.getString(key)))else o.put(key,a.get(key))};val next=BannerOptions.parse(o.toString());prepared("调整手持弹幕",a){options=next;persist(next);"弹幕设置已保存"}},
  surfaceTool("show","全屏显示当前弹幕","",false){a->require(options.text.isNotBlank());prepared("显示弹幕",a){c.startActivity(Intent(c,MarqueeDisplayActivity::class.java).putExtra("options",options.json()));"已打开全屏弹幕；长按屏幕显示退出按钮，点退出才结束展示"}}
 ))
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=12.dp).padding(bottom=LocalFloatingAiHeight.current+10.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
  OutlinedTextField(options.text,{options=options.copy(text=it.take(300))},modifier=Modifier.fillMaxWidth(),placeholder={Text("请输入弹幕内容…",fontSize=14.sp)},minLines=1,maxLines=3,textStyle=TextStyle(fontSize=15.sp),shape=AppShape(13.dp))
  Box(Modifier.fillMaxWidth().height(80.dp).clip(AppShape(12.dp))){
   BannerCanvas(options,Modifier.fillMaxSize(),preview=true)
   Text("预览",Modifier.align(Alignment.TopEnd).padding(6.dp).background(Color.Black.copy(alpha=.35f),CircleShape).padding(horizontal=6.dp,vertical=2.dp),fontSize=9.sp,color=Color.White)
  }
  Panel{
   BannerSlider("文字大小","${options.size.roundToInt()}",options.size,32f..240f){options=options.copy(size=it)}
   BannerSlider("滚动速度","${options.speed.roundToInt()}",options.speed,20f..300f,options.moving){options=options.copy(speed=it)}
  }
  Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
   BannerColorButton("文字颜色",options.foreground,Modifier.weight(1f)){colorTarget="文字颜色"}
   BannerColorButton("背景颜色",options.background,Modifier.weight(1f)){colorTarget="背景颜色"}
  }
  Panel{
   BannerChoice("显示方向",listOf("横屏","竖屏"),if(options.landscape)0 else 1){options=options.copy(landscape=it==0)}
   BannerChoice("显示方式",listOf("静止","滚动"),if(options.moving)1 else 0){options=options.copy(moving=it==1)}
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
    Row(Modifier.weight(1f).clickable{options=options.copy(blink=!options.blink)},verticalAlignment=Alignment.CenterVertically){Checkbox(options.blink,{options=options.copy(blink=it)});Text("文字闪烁",fontSize=12.sp)}
    Row(Modifier.weight(1f).clickable{options=options.copy(bold=!options.bold)},verticalAlignment=Alignment.CenterVertically){Checkbox(options.bold,{options=options.copy(bold=it)});Text("加粗字体",fontSize=12.sp)}
   }
   BannerSlider("文字抖动",if(options.shake<.1f)"关"else"${options.shake.roundToInt()}",options.shake,0f..12f){options=options.copy(shake=it)}
  }
  Text(if(options.moving)"从右向左循环滚动，短文字也会滚动。"else"静止长文本自动换行，过长时缩小以适配屏幕。",fontSize=10.sp,color=Muted)
  Button(onClick={
   runCatching{check(options.text.isNotBlank()){ "先输入弹幕内容" };check(c.getSharedPreferences("handheld-banner",0).edit().putString("options",options.json()).commit()){ "设置保存失败，请重试" };keyboard?.hide();c.startActivity(Intent(c,MarqueeDisplayActivity::class.java).putExtra("options",options.json()))}.onFailure{error=it.message?:"无法打开弹幕"}
  },enabled=options.text.isNotBlank(),modifier=Modifier.fillMaxWidth().height(44.dp),shape=AppShape(12.dp)){Text("显示弹幕",fontSize=14.sp)}
  if(error.isNotBlank())Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
  Text("展示时保持亮屏；长按屏幕显示退出按钮，点退出才结束展示。",fontSize=10.sp,color=Muted)
  Spacer(Modifier.height(4.dp))
 }
 colorTarget?.let{target->BannerColorPicker(target,if(target=="文字颜色")options.foreground else options.background,{colorTarget=null}){value->options=if(target=="文字颜色")options.copy(foreground=value)else options.copy(background=value);colorTarget=null}}
}

@Composable private fun BannerSlider(label:String,valueLabel:String,value:Float,range:ClosedFloatingPointRange<Float>,enabled:Boolean=true,onChange:(Float)->Unit){
 Row(Modifier.fillMaxWidth().height(39.dp),verticalAlignment=Alignment.CenterVertically){
  Text(label,Modifier.width(64.dp),fontSize=12.sp,color=if(enabled)Ink else Muted)
  Slider(value,onChange,modifier=Modifier.weight(1f),valueRange=range,enabled=enabled)
  Text(valueLabel,Modifier.width(30.dp),fontSize=10.sp,color=Muted,textAlign=androidx.compose.ui.text.style.TextAlign.End)
 }
}
@Composable private fun BannerChoice(label:String,choices:List<String>,selected:Int,onChange:(Int)->Unit){
 Row(Modifier.fillMaxWidth().height(36.dp),verticalAlignment=Alignment.CenterVertically){
  Text(label,Modifier.width(72.dp),fontSize=12.sp,color=Muted)
  choices.forEachIndexed{i,name->Box(Modifier.weight(1f).padding(horizontal=3.dp).height(30.dp).clip(CircleShape).background(if(selected==i)AccentSurface else SoftSurface).clickable{onChange(i)}.semantics{this.selected=selected==i},contentAlignment=Alignment.Center){Text(name,fontSize=12.sp,color=if(selected==i)DutBlue else Muted)}}
 }
}
@Composable private fun BannerColorButton(label:String,color:Int,modifier:Modifier,onClick:()->Unit){
 Row(modifier.height(42.dp).clip(CircleShape).background(CardSurface).clickable(onClick=onClick).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
  Box(Modifier.size(19.dp).background(Color(color),CircleShape).border(.5.dp,Muted,CircleShape));Text(label,fontSize=12.sp)
 }
}
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun BannerColorPicker(title:String,current:Int,onDismiss:()->Unit,onChoose:(Int)->Unit){
 var hex by remember{mutableStateOf("%06X".format(current and 0xFFFFFF))}
 val parsed=hex.takeIf{it.length==6}?.toLongOrNull(16)?.let{it.toInt() or 0xFF000000.toInt()}
 val palette=listOf(0xFF000000,0xFFFFFFFF,0xFFFFFF66,0xFFFF5C69,0xFFFF9D44,0xFF62DC99,0xFF74DFFF,0xFF5288ED,0xFFB795FF,0xFFFF90C6,0xFF333333,0xFF0041B7)
 AlertDialog(onDismissRequest=onDismiss,title={Text(title,fontSize=18.sp)},text={Column(verticalArrangement=Arrangement.spacedBy(9.dp)){
  FlowRow(horizontalArrangement=Arrangement.spacedBy(9.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){palette.forEach{value->Box(Modifier.size(32.dp).clip(CircleShape).background(Color(value)).border(if(parsed==value.toInt())3.dp else .5.dp,if(parsed==value.toInt())DutBlue else Muted,CircleShape).clickable{hex="%06X".format(value and 0xFFFFFF)}.semantics{contentDescription="颜色 #${"%06X".format(value and 0xFFFFFF)}"})}}
  OutlinedTextField(hex,{hex=it.removePrefix("#").filter{ch->ch.isDigit()||ch.lowercaseChar() in 'a'..'f'}.take(6).uppercase()},label={Text("自定义颜色 · 6位 HEX",fontSize=12.sp)},prefix={Text("#")},singleLine=true)
  Box(Modifier.fillMaxWidth().height(26.dp).background(parsed?.let{Color(it)}?:Color.Transparent,AppShape(6.dp)))
 }},confirmButton={TextButton(onClick={parsed?.let(onChoose)},enabled=parsed!=null){Text("使用")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

class MarqueeDisplayActivity:ComponentActivity(){
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  val options=BannerOptions.parse(intent.getStringExtra("options"))
  window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(options.background))
  requestedOrientation=if(options.landscape)ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
  window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  WindowCompat.setDecorFitsSystemWindows(window,false)
  setContent{
   var showExit by rememberSaveable{mutableStateOf(false)}
   BackHandler{showExit=true}
   var hint by remember{mutableStateOf(true)}
   val haptic=LocalHapticFeedback.current
   LaunchedEffect(Unit){delay(2500);hint=false}
   Box(Modifier.fillMaxSize().background(Color(options.background)).pointerInput(Unit){
    detectTapGestures(onTap={showExit=false},onLongPress={hint=false;showExit=true;haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)})
   }.semantics{
    contentDescription="弹幕：${options.text}，长按显示退出按钮"
    onLongClick("显示退出按钮"){hint=false;showExit=true;true}
   }){
    BannerCanvas(options,Modifier.fillMaxSize())
    if(hint&&!showExit)Text("长按屏幕显示退出按钮",Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(12.dp).background(Color.Black.copy(alpha=.6f),CircleShape).padding(horizontal=12.dp,vertical=5.dp),fontSize=12.sp,color=Color.White)
    if(showExit)Surface(Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),shape=CircleShape,color=Color(0xEE202124),shadowElevation=4.dp){
     Row(Modifier.padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
      TextButton(onClick={showExit=false},colors=ButtonDefaults.textButtonColors(contentColor=Color(0xFFDADCE0))){Text("继续展示",fontSize=13.sp)}
      VerticalDivider(Modifier.height(16.dp),color=Color.White.copy(alpha=.25f))
      TextButton(onClick={finish()},colors=ButtonDefaults.textButtonColors(contentColor=Color.White)){Text("退出",fontSize=14.sp,fontWeight=FontWeight.SemiBold)}
     }
    }
   }
  }
 }
 override fun onResume(){super.onResume();WindowCompat.getInsetsController(window,window.decorView).apply{systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;hide(WindowInsetsCompat.Type.systemBars())}}
}

@Composable private fun BannerCanvas(options:BannerOptions,modifier:Modifier,preview:Boolean=false){
 val lifecycle=(LocalContext.current as ComponentActivity).lifecycle
 val elapsed=remember{mutableFloatStateOf(0f)}
 LaunchedEffect(options){
  elapsed.floatValue=0f
  if(options.moving||options.blink||options.shake>0f)lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
   var last=0L
   while(isActive){withFrameNanos{now->if(last!=0L)elapsed.floatValue+=(now-last).coerceAtMost(100_000_000L)/1_000_000_000f;last=now}}
  }
 }
 val paint=remember(options){android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{color=options.foreground;typeface=android.graphics.Typeface.create("sans-serif",if(options.bold)android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)}}
 // Cache layouts across animation frames; Canvas drawing does not allocate text layouts each frame.
 val cache=remember(options){BannerLayoutCache()}
 Canvas(modifier.background(Color(options.background))){
  val padding=if(preview)8.dp.toPx()else 18.dp.toPx()
  val width=(size.width-padding*2).coerceAtLeast(1f);val height=(size.height-padding*2).coerceAtLeast(1f)
  val font=if(preview)(options.size/240f*46.sp.toPx()).coerceAtLeast(14.sp.toPx())else options.size.sp.toPx()
  val text=if(options.moving)options.text.replace('\n',' ')else options.text
  val layout=cache.get(text,paint,font,width.toInt().coerceAtLeast(1),height,options.moving)
  val time=elapsed.floatValue
  paint.alpha=if(options.blink&&time%1.2f>=.6f)0 else 255
  val shake=options.shake.dp.toPx()*(if(preview).35f else 1f)
  val dx=sin(time*PI*8).toFloat()*shake;val dy=sin(time*PI*6).toFloat()*shake*.65f
  val native=drawContext.canvas.nativeCanvas
  native.save();native.clipRect(0f,0f,size.width,size.height)
  if(options.moving){
   val textWidth=paint.measureText(text);val gap=width*.15f
   val speed=options.speed.dp.toPx()*(if(preview).5f else 1f)
   val x=size.width-(time*speed%(size.width+textWidth+gap))
   val y=size.height/2-(paint.ascent()+paint.descent())/2
   native.drawText(text,x+dx,y+dy,paint)
  }else{
   native.translate(padding+dx,(size.height-layout!!.height)/2+dy);layout.draw(native)
  }
  native.restore()
 }
}
private class BannerLayoutCache{
 private var key="";private var layout:android.text.StaticLayout?=null
 fun get(text:String,paint:android.text.TextPaint,font:Float,width:Int,height:Float,moving:Boolean):android.text.StaticLayout?{
  val next="$width/$height/$font/$moving"
  if(next==key)return layout
  key=next;paint.textSize=font
  if(moving){layout=null;return null}
  fun build()=android.text.StaticLayout.Builder.obtain(text,0,text.length,paint,width).setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
  var current=build()
  if(current.height>height){
   var low=1f;var high=font
   repeat(12){val mid=(low+high)/2;paint.textSize=mid;val candidate=build();if(candidate.height>height)high=mid else low=mid}
   paint.textSize=low;current=build()
  }
  layout=current;return current
 }
}

package cn.dapan.assistant

import org.json.*

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.random.Random

@Composable fun WheelScreen(onBack:()->Unit){
 val context=LocalContext.current;val store=remember{WheelStore(context)};val scope=rememberCoroutineScope()
 val wheel=store.wheels.find{it.id==store.selected}
 var editor by remember{mutableStateOf<SavedWheel?>(null)}
 var choosing by remember{mutableStateOf(false)};var settings by remember{mutableStateOf(false)}
 var spinning by remember{mutableStateOf(false)};var result by remember{mutableStateOf<WheelOption?>(null)}
 var message by remember{mutableStateOf<String?>(null)}
 val angle=remember{Animatable(0f)}
 LaunchedEffect(wheel,store.settings){if(!spinning){angle.snapTo(0f);result=null}}
 fun act(block:()->Unit){runCatching(block).onFailure{message=it.message?:"保存失败，请重试"}}
 fun spin(target:SavedWheel?=wheel){
  if(spinning||target==null||store.error!=null)return
  spinning=true;result=null
  val chosen=WheelMath.pick(target)
  val landing=(chosen.start+chosen.sweep*(.2+Random.nextDouble()*.6)).toFloat()
  val config=store.settings
  scope.launch{
   try{
    val start=WheelMath.positiveAngle(angle.value);angle.snapTo(start)
    val target=WheelMath.positiveAngle(if(config.pointer)landing else -landing)
    val turns=(config.milliseconds/800).coerceIn(1,10)
    angle.animateTo(start+turns*360f+WheelMath.positiveAngle(target-start),tween(config.milliseconds,easing=CubicBezierEasing(.15f,.72f,.18f,1f)))
    angle.snapTo(target);result=chosen.option
   }finally{spinning=false}
  }
 }
 AssistantSurface("抽签",JSONObject().put("selectedId",wheel?.id).put("spinning",spinning).put("wheels",JSONArray(store.wheels.map{w->JSONObject().put("id",w.id).put("name",w.name).put("options",JSONArray(w.options.map{o->JSONObject().put("name",o.name).put("weight",o.weight)}))})),listOf(
  surfaceTool("save","创建/修改轮盘；id仅修改时填，name标题，options=[{name,weight}]，权重比例不要求和为1，spinAfter=true表示保存后立即抽签","id,name,options,spinAfter"){a->require(!spinning);val id=a.optString("id");val old=store.wheels.singleOrNull{it.id==id};require(id.isBlank()||old!=null){"轮盘不存在"};val options=a.optJSONArray("options")?.objects()?.map{WheelOption(name=it.text("name",60),weight=it.optDouble("weight",1.0))}?:old?.options?:error("请提供每个选项名称和权重");val next=SavedWheel(id=old?.id?:java.util.UUID.randomUUID().toString(),name=a.optString("name",old?.name?:"新轮盘"),options=options);WheelMath.validate(next);PreparedTool(if(old==null)"新建轮盘 ${next.name}"else"修改轮盘 ${next.name}",next.options.joinToString("\n"){"${it.name}：权重 ${it.weight}"},{store.save(next);if(a.optBoolean("spinAfter")){spin(next);"已保存并开始抽签：${next.name}，结果将在轮盘中显示"}else"已保存轮盘 ${next.name}，${next.options.size} 个选项；点击圆心或说开始抽签"},evidence={JSONObject().put("module","抽签").put("id",next.id).put("name",next.name).put("options",JSONArray(next.options.map{JSONObject().put("name",it.name).put("weight",it.weight)})).toString()})},
  surfaceTool("select","选择已保存轮盘","id",false){a->val id=a.text("id");require(store.wheels.any{it.id==id});require(!spinning);prepared("选择轮盘",a){store.select(id);"已选中轮盘"}},
  surfaceTool("delete","删除已保存轮盘","id"){a->val w=store.wheels.singleOrNull{it.id==a.text("id")}?:error("轮盘不存在");require(!spinning);prepared("删除 ${w.name}",a){store.delete(w.id);"已删除 ${w.name}"}},
  surfaceTool("spin","按当前轮盘的真实权重抽签；不得编造结果","",false){a->require(wheel!=null&&!spinning);prepared("开始抽签 ${wheel.name}",a){spin();"抽签已开始，结果由轮盘随机抽取后显示"}},
  surfaceTool("configure","pointer布尔=指针转，duration=长/短/自定义，customMs=100..60000","pointer,duration,customMs"){a->val next=WheelSettings(a.optBoolean("pointer",store.settings.pointer),a.optString("duration",store.settings.duration),a.optInt("customMs",store.settings.customMs));require(next.duration in listOf("长","短","自定义")&&next.customMs in 100..60000&&!spinning);prepared("调整抽签设置",a){store.configure(next);"抽签设置已保存"}}
 ))
 Column(Modifier.fillMaxSize().padding(bottom=LocalFloatingAiHeight.current)){
  Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,onBack)
   Text("抽签",Modifier.weight(1f),fontSize=17.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold)
   QuietIconButton("新建轮盘",AppGlyph.Add,{editor=SavedWheel()},enabled=!spinning&&store.error==null)
   QuietIconButton("抽签设置",AppGlyph.Settings,{settings=true},enabled=!spinning&&store.error==null)
  }
  if(store.error!=null)Column(Modifier.padding(16.dp)){Text(store.error!!,fontSize=13.sp,color=MaterialTheme.colorScheme.error);TextButton(onClick={store.reload()}){Text("重新读取")}}
  else if(wheel==null)Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
   Text("还没有轮盘",fontSize=18.sp,fontWeight=FontWeight.Medium);TextButton(onClick={editor=SavedWheel()}){Text("新建一个")}
  }else{
   Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){
    Box(Modifier.weight(1f)){
     Row(Modifier.fillMaxWidth().height(44.dp).clip(AppShape(14.dp)).background(AccentSurface).clickable(enabled=!spinning){choosing=true}.padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically){
      Text(wheel.name,Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.Medium,color=DutBlue,maxLines=1,overflow=TextOverflow.Ellipsis)
      Text("⌄",fontSize=17.sp,color=DutBlue)
     }
     DropdownMenu(choosing,{choosing=false},modifier=Modifier.heightIn(max=300.dp)){
      store.wheels.forEach{item->DropdownMenuItem(text={Text(item.name,maxLines=1,overflow=TextOverflow.Ellipsis)},onClick={act{store.select(item.id)};choosing=false},trailingIcon={if(item.id==wheel.id)LineIcon(AppGlyph.Check,Modifier.size(16.dp),DutBlue)})}
     }
    }
    TextButton(onClick={editor=wheel},enabled=!spinning){Text("编辑",fontSize=13.sp)}
   }
   BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),contentAlignment=Alignment.Center){
    val diameter=minOf(maxWidth,maxHeight).coerceAtLeast(1.dp)
    WheelFace(wheel,angle.value,store.settings.pointer,result?.id,spinning,Modifier.size(diameter),{spin()})
   }
   Column(Modifier.fillMaxWidth().height(64.dp).padding(horizontal=18.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center){
    Text(result?.name?:if(spinning)"正在做个决定…"else"点击圆心，抽一个",fontSize=if(result!=null)20.sp else 13.sp,fontWeight=if(result!=null)FontWeight.SemiBold else FontWeight.Normal,color=if(result!=null)DutBlue else Muted,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.clickable(enabled=result!=null){message=result?.name}.semantics{liveRegion=LiveRegionMode.Polite})
    if(result!=null)Text("点击圆心再抽一次",fontSize=10.sp,color=Muted)
    else if(!spinning)Text("${wheel.options.size} 个选项 · ${if(store.settings.pointer)"指针转"else"转盘转"} · ${store.settings.milliseconds/1000.0} 秒",fontSize=10.sp,color=Muted)
   }
  }
 }
 editor?.let{w->WheelEditor(w,store.wheels.any{it.id==w.id},onDismiss={editor=null},onSave={store.save(it);editor=null},onDelete={store.delete(w.id);editor=null})}
 if(settings)WheelSettingsDialog(store.settings,{settings=false}){store.configure(it);settings=false}
 message?.let{AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}})}
}

@Composable private fun WheelFace(w:SavedWheel,angle:Float,pointer:Boolean,resultId:String?,spinning:Boolean,modifier:Modifier,onSpin:()->Unit){
 val sectors=remember(w){WheelMath.sectors(w).filter{it.sweep>0}}
 val colors=listOf(DutBlue,AccentSurface,lerp(DutBlue,CardSurface,.3f),SoftSurface)
 val labelColors=colors.map{background->
  val preferred=if(background.luminance()>.179f)Ink else OnAccent
  val contrast=(maxOf(preferred.luminance(),background.luminance())+.05f)/(minOf(preferred.luminance(),background.luminance())+.05f)
  if(contrast>=4.5f)preferred else if(background.luminance()>.179f)Color.Black else Color.White
 }
 val hub=CardSurface;val ink=Ink;val accent=DutBlue
 Box(modifier,contentAlignment=Alignment.Center){
  Canvas(Modifier.fillMaxSize()){
   val radius=size.minDimension*.48f;val rect=Offset(center.x-radius,center.y-radius);val diameter=Size(radius*2,radius*2)
   rotate(if(pointer)0f else angle){
    sectors.forEachIndexed{index,s->
     val start=(s.start-90).toFloat();val sweep=s.sweep.toFloat()
     drawArc(colors[index%colors.size],start,sweep,true,rect,diameter)
     if(sweep<359.99f)drawArc(hub.copy(alpha=.3f),start,sweep,true,rect,diameter,style=Stroke(.6.dp.toPx()))
     if(s.option.id==resultId)drawArc(accent,start,sweep,false,rect,diameter,style=Stroke(3.dp.toPx()))
     if(sweep>=3f){
      val mid=start+sweep/2;val a=Math.toRadians(mid.toDouble());val small=sweep<12f
      val distance=radius*(if(small).81f else .64f)
      val x=center.x+cos(a).toFloat()*distance;val y=center.y+sin(a).toFloat()*distance
      val paint=android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{
       textSize=if(small)9.sp.toPx()else minOf(14.sp.toPx(),radius*.085f)
       color=labelColors[index%labelColors.size].toArgb()
       textAlign=android.graphics.Paint.Align.CENTER;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
      }
      val label=if(small)"${w.options.indexOf(s.option)+1}"else android.text.TextUtils.ellipsize(s.option.name,paint,radius*.52f,android.text.TextUtils.TruncateAt.END).toString()
      val native=drawContext.canvas.nativeCanvas;native.save()
      native.rotate(mid+if(cos(a)<0)180f else 0f,x,y)
      native.drawText(label,x,y-(paint.ascent()+paint.descent())/2,paint);native.restore()
     }
    }
   }
   rotate(if(pointer)angle else 0f){
    val arrow=Path().apply{moveTo(center.x,center.y-radius*.35f);lineTo(center.x-radius*.115f,center.y-radius*.20f);lineTo(center.x+radius*.115f,center.y-radius*.20f);close()}
    drawPath(arrow,hub);drawCircle(hub,radius*.27f)
    drawCircle(accent.copy(alpha=.15f),radius*.19f,style=Stroke(3.dp.toPx()))
   }
  }
  Box(Modifier.fillMaxSize(.26f).clip(CircleShape).clickable(enabled=!spinning,onClick=onSpin).semantics{contentDescription=if(spinning)"转动中"else"开始抽签"},contentAlignment=Alignment.Center){
   Text(if(spinning)"···"else"转",fontSize=20.sp,fontWeight=FontWeight.Medium,color=ink)
  }
 }
}

private data class WheelDraft(val id:String,val name:String,val weight:String)
@Composable private fun WheelEditor(w:SavedWheel,existing:Boolean,onDismiss:()->Unit,onSave:(SavedWheel)->Unit,onDelete:()->Unit){
 var name by rememberSaveable(w.id){mutableStateOf(w.name)}
 var countText by rememberSaveable(w.id){mutableStateOf(w.options.size.toString())}
 var drafts by remember(w.id){mutableStateOf(w.options.map{WheelDraft(it.id,it.name,it.weight.toString().removeSuffix(".0"))})}
 var error by remember{mutableStateOf("")};var deleting by remember{mutableStateOf(false)}
 val count=countText.toIntOrNull()?.takeIf{it in 1..60}
 LaunchedEffect(count){if(count!=null&&count>drafts.size)drafts=drafts+List(count-drafts.size){i->WheelOption(name="选项 ${drafts.size+i+1}").let{WheelDraft(it.id,it.name,"1")}}}
 val shown=drafts.take(count?:0);val total=shown.sumOf{it.weight.toDoubleOrNull()?.takeIf{v->v.isFinite()&&v>=0}?:0.0}
 Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxWidth(.96f).fillMaxHeight(.92f),shape=AppShape(20.dp),color=Paper){
   Column(Modifier.padding(horizontal=12.dp)){
    Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){QuietIconButton("取消编辑",AppGlyph.Back,onDismiss);Text(if(existing)"编辑轮盘"else"新建轮盘",Modifier.weight(1f),fontSize=17.sp,fontWeight=FontWeight.SemiBold);TextButton(onClick={
     runCatching{require(count!=null){"选项数需为1至60"};require(shown.size==count){"正在准备选项，请稍后保存"};onSave(w.copy(name=name.trim(),options=shown.map{WheelOption(it.id,it.name.trim(),it.weight.toDoubleOrNull()?:error("请填写有效权重"))}))}.onFailure{error=it.message?:"保存失败"}
    }){Text("保存")}}
    OutlinedTextField(name,{name=it.take(60)},Modifier.fillMaxWidth(),label={Text("轮盘名称",fontSize=12.sp)},singleLine=true,textStyle=TextStyle(fontSize=14.sp))
    Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
     OutlinedTextField(countText,{countText=it.filter(Char::isDigit).take(2)},Modifier.width(100.dp),label={Text("选项数",fontSize=12.sp)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),singleLine=true,textStyle=TextStyle(fontSize=14.sp))
     Text("权重按比例决定概率\n0 表示不参与本次抽签",fontSize=11.sp,color=Muted,modifier=Modifier.weight(1f))
     if(existing)TextButton(onClick={deleting=true}){Text("删除",fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
    }
    if(count==null)Text("选项数需为1至60",fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    if(count!=null&&count<w.options.size)Text("保存后会移除末尾 ${w.options.size-count} 个选项",fontSize=11.sp,color=Muted)
    if(error.isNotBlank())Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(bottom=10.dp)){
     itemsIndexed(shown,key={_,d->d.id}){index,d->
      Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
       Text("${index+1}",Modifier.width(20.dp),fontSize=11.sp,color=Muted)
       OutlinedTextField(d.name,{value->drafts=drafts.map{if(it.id==d.id)it.copy(name=value.take(60))else it}},Modifier.weight(1f),label={Text("选项名称",fontSize=10.sp)},singleLine=true,textStyle=TextStyle(fontSize=13.sp))
       val weight=d.weight.toDoubleOrNull()?:0.0
       val percent=if(total>0&&weight.isFinite()&&weight>=0)"%.1f%%".format(weight/total*100)else"—"
       OutlinedTextField(d.weight,{value->drafts=drafts.map{if(it.id==d.id)it.copy(weight=value.replace(',','.').take(24))else it}},Modifier.width(94.dp),label={Text("权重 · $percent",fontSize=9.sp)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),textStyle=TextStyle(fontSize=13.sp))
      }
     }
    }
   }
  }
 }
 if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除这个轮盘？")},text={Text("“${w.name}”及其选项将被删除，无法恢复。")},confirmButton={TextButton(onClick={runCatching{onDelete()}.onFailure{deleting=false;error="删除失败，请重试"}}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}

@Composable private fun WheelSettingsDialog(current:WheelSettings,onDismiss:()->Unit,onSave:(WheelSettings)->Unit){
 var pointer by remember{mutableStateOf(current.pointer)};var duration by remember{mutableStateOf(current.duration)}
 var seconds by remember{mutableStateOf((current.customMs/1000.0).toString())};var error by remember{mutableStateOf("")}
 AlertDialog(onDismissRequest=onDismiss,title={Text("抽签设置",fontSize=18.sp)},text={Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
  Text("转动方式",fontSize=12.sp,color=Muted)
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(false to "转盘转",true to "指针转").forEach{(value,label)->FilterChip(pointer==value,{pointer=value},label={Text(label,fontSize=12.sp)})}}
  Text("单次时长",fontSize=12.sp,color=Muted)
  Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){listOf("短","长","自定义").forEach{value->FilterChip(duration==value,{duration=value},label={Text(when(value){"短"->"短 · 0.5秒";"长"->"长 · 3秒";else->value},fontSize=11.sp)})}}
  OutlinedTextField(seconds,{seconds=it.replace(',','.').take(7)},enabled=duration=="自定义",label={Text("自定义秒数（0.1–60）",fontSize=11.sp)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),textStyle=TextStyle(fontSize=14.sp))
  Text("设置应用于所有轮盘，不改变抽中概率。",fontSize=11.sp,color=Muted)
  if(error.isNotBlank())Text(error,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
 }},confirmButton={TextButton(onClick={runCatching{
  val value=seconds.toDoubleOrNull();if(duration=="自定义")require(value!=null&&value.isFinite()&&value in .1..60.0){"请输入0.1至60秒"}
  onSave(WheelSettings(pointer,duration,if(value!=null&&value.isFinite()&&value in .1..60.0)(value*1000).roundToInt()else current.customMs))
 }.onFailure{error=it.message?:"保存失败"}}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
}

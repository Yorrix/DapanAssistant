package cn.dapan.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Keep the drag in viewport coordinates; persist one complete order on release. */
@Composable fun HomeWidgetSorter(layout:HomeLayout,modifier:Modifier,onConfigure:(HomeWidget)->Unit,onFailure:()->Unit){
 var order by remember(layout.order){mutableStateOf(layout.order)}
 val list=rememberLazyListState();val scope=rememberCoroutineScope()
 val density=LocalDensity.current;val haptic=LocalHapticFeedback.current
 var active by remember{mutableStateOf<HomeWidget?>(null)}
 var top by remember{mutableFloatStateOf(0f)}
 var rowHeight by remember{mutableIntStateOf(0)}
 var settling by remember{mutableStateOf(false)}
 val edge=with(density){48.dp.toPx()};val speed=with(density){420.dp.toPx()}
 fun save(next:List<HomeWidget>){runCatching{layout.reorder(next)}.onFailure{order=layout.order;onFailure()}}
 fun finish(cancel:Boolean){
  val widget=active?:return
  if(settling)return
  settling=true
  scope.launch{
   if(cancel)order=layout.order
   // Allow the final move to reach layout before settling into its slot.
   withFrameNanos{}
   val target=list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==widget.name}?.offset?.toFloat()
   if(target!=null)Animatable(top).animateTo(target,spring(dampingRatio=1f,stiffness=650f)){top=value}
   if(!cancel)save(order)
   active=null;settling=false
  }
 }
 LaunchedEffect(active){
  if(active==null)return@LaunchedEffect
  var previous=withFrameNanos{it}
  while(active!=null){
   val now=withFrameNanos{it};val dt=((now-previous)/1_000_000_000f).coerceAtMost(.04f);previous=now
   if(settling)continue
   val info=list.layoutInfo;val center=top+rowHeight/2f
   val velocity=when{
    top<info.viewportStartOffset+edge->-speed*((info.viewportStartOffset+edge-top)/edge).coerceIn(0f,1f)
    top+rowHeight>info.viewportEndOffset-edge->speed*((top+rowHeight-info.viewportEndOffset+edge)/edge).coerceIn(0f,1f)
    else->0f
   }
   if(velocity!=0f)list.scrollBy(velocity*dt)
   val widget=active?:break
   val current=order.indexOf(widget)
   val visible=list.layoutInfo.visibleItemsInfo
   // Ignore an old layout snapshot while a previous reorder is being measured.
   if(visible.any{order.getOrNull(it.index)?.name!=it.key})continue
   val target=visible.minByOrNull{abs((it.offset+it.size/2f)-center)}?:continue
   if(target.index!=current){
    order=order.toMutableList().apply{removeAt(current);add(target.index,widget)}
   }
  }
 }
 Box(modifier.clipToBounds()){
  LazyColumn(state=list,userScrollEnabled=active==null){
   items(order,key={it.name}){w->
    HomeSortRow(w,layout,active!=null,onConfigure,onFailure,
     Modifier.animateItem(placementSpec=spring(stiffness=650f)).graphicsLayer{alpha=if(active==w)0f else 1f},
     handle=Modifier.semantics{
      contentDescription="拖动排序：${w.title}"
      stateDescription="第 ${order.indexOf(w)+1} 项，共 ${order.size} 项"
      customActions=listOfNotNull(
       if(order.indexOf(w)>0)CustomAccessibilityAction("上移"){if(active==null)save(order.toMutableList().apply{val i=indexOf(w);removeAt(i);add(i-1,w)});true}else null,
       if(order.indexOf(w)<order.lastIndex)CustomAccessibilityAction("下移"){if(active==null)save(order.toMutableList().apply{val i=indexOf(w);removeAt(i);add(i+1,w)});true}else null)
     }.pointerInput(w){
      detectDragGestures(onDragStart={
       if(active==null){list.layoutInfo.visibleItemsInfo.firstOrNull{it.key==w.name}?.let{item->
        top=item.offset.toFloat();rowHeight=item.size;active=w
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
       }}
      },onDragEnd={finish(false)},onDragCancel={finish(true)}){change,amount->
       if(active==w&&!settling){change.consume();top=(top+amount.y).coerceIn(-rowHeight*.35f,(list.layoutInfo.viewportEndOffset-rowHeight*.65f).coerceAtLeast(0f))}
      }
     })
   }
  }
  active?.let{w->
   Surface(Modifier.fillMaxWidth().height(with(density){rowHeight.toDp()}).graphicsLayer{translationY=top;scaleX=1.005f;scaleY=1.025f},
    shape=AppShape(10.dp),color=MaterialTheme.colorScheme.secondaryContainer,shadowElevation=6.dp){
    HomeSortRow(w,layout,true,{}, {},Modifier.clearAndSetSemantics{},Modifier,highlight=true)
   }
  }
 }
}

@Composable private fun HomeSortRow(w:HomeWidget,layout:HomeLayout,locked:Boolean,onConfigure:(HomeWidget)->Unit,onFailure:()->Unit,
 modifier:Modifier,handle:Modifier,highlight:Boolean=false){
 Column(modifier){
  Row(Modifier.fillMaxWidth().heightIn(min=58.dp),verticalAlignment=Alignment.CenterVertically){
   Checkbox(w in layout.enabled,{if(!locked)runCatching{layout.toggle(w)}.onFailure{onFailure()}})
   Column(Modifier.weight(1f).clickable(enabled=!locked){runCatching{layout.toggle(w)}.onFailure{onFailure()}}){
    Text(w.title,fontSize=14.sp,fontWeight=FontWeight.Medium)
    Text(if(w==HomeWidget.NOTICES)"${layout.notices.label} · 最近三条"else w.detail,fontSize=11.sp,lineHeight=15.sp,color=Muted)
   }
   if(w in setOf(HomeWidget.AGENDA,HomeWidget.NOTICES))QuietIconButton("${w.title}显示设置",AppGlyph.Settings,{if(!locked)onConfigure(w)},color=Muted)
   val ink=if(highlight)MaterialTheme.colorScheme.primary else Muted
   Box(handle.size(48.dp),contentAlignment=Alignment.Center){
    Canvas(Modifier.size(20.dp,24.dp)){
     for(x in listOf(.32f,.68f))for(y in listOf(.25f,.5f,.75f))drawCircle(ink,1.5.dp.toPx(),androidx.compose.ui.geometry.Offset(size.width*x,size.height*y))
    }
   }
  }
  HorizontalDivider(color=Hairline,thickness=.5.dp)
 }
}

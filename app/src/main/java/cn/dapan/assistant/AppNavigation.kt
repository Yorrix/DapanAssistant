package cn.dapan.assistant

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.Layout

/** One saved page per destination, with an explicit chain of entry points. */
class AppNavigation(initial:List<String>){
 var path by mutableStateOf(buildList{initial.filterNot(RetiredFeatures::removed).forEach{name->if(lastOrNull()!=name)add(name)}}.ifEmpty{listOf("今天")});private set
 var returning by mutableStateOf(false);private set
 val current get()=path.last()
 fun open(destination:String,origin:String=current){
  if(RetiredFeatures.removed(destination))return
  val target=destination;val source=origin.takeUnless(RetiredFeatures::removed)?:current
  if(target==current)return
  val base=path.lastIndexOf(source).let{if(it>=0)path.take(it+1)else listOf(source)}
  returning=false
  path=(base+target).takeLast(32)
 }
 fun tab(destination:String){if(RetiredFeatures.removed(destination))return;val target=destination;if(target!=current){returning=false;path=listOf(target)}}
 fun back(){returning=true;path=if(path.size>1)path.dropLast(1)else listOf("今天")}
 companion object {val Saver=listSaver<AppNavigation,String>(save={it.path},restore={AppNavigation(it)})}
}

enum class PageMotion(val label:String,val detail:String){
 FADE("淡入","平稳过渡，页面不位移"),SLIDE("轻滑","进入与返回使用相反方向"),ZOOM("轻缩放","轻微缩放后归位"),NONE("无动效","直接切换");
 companion object{fun from(value:String?)=entries.find{it.name==value}?:FADE}
}

/** Keep a single live destination: sensors and AI bindings cannot remain active offscreen. */
@Composable fun PageEntrance(backwards:Boolean,active:Boolean=true,content:@Composable BoxScope.()->Unit){
 val mode=AppAppearance.motion
 val progress=remember{Animatable(if(mode==PageMotion.NONE)1f else 0f)}
 val distance=with(LocalDensity.current){if(backwards)(-20).dp.toPx()else 28.dp.toPx()}
 LaunchedEffect(mode,active){
  if(!active)progress.snapTo(0f)else if(mode==PageMotion.NONE)progress.snapTo(1f)else{
   progress.snapTo(0f)
   progress.animateTo(1f,tween(120, easing=androidx.compose.animation.core.LinearOutSlowInEasing))
  }
 }
 Box(Modifier.fillMaxSize().graphicsLayer{
  val p=if(mode==PageMotion.NONE)1f else progress.value
  alpha=when(mode){PageMotion.FADE->.6f+.4f*p;PageMotion.NONE->1f;else->.65f+.35f*p}
  translationX=if(mode==PageMotion.SLIDE)distance*(1f-p)else 0f
  scaleX=if(mode==PageMotion.ZOOM)1f+(if(backwards).025f else -.025f)*(1f-p)else 1f
  scaleY=scaleX
 },content=content)
}

/** Retain only the calendar after its first visit. Hidden content is not measured,
 * placed, drawn or exposed to touch/accessibility; callers suspend its effects. */
@Composable internal fun RetainedCalendarLayer(active:Boolean,content:@Composable ()->Unit){
 var visited by remember{mutableStateOf(false)}
 SideEffect{if(active)visited=true}
 if(active||visited)Layout(content=content,modifier=Modifier.fillMaxSize()){measurables,constraints->
  val page=if(active)measurables.single().measure(constraints)else null
  layout(constraints.maxWidth,constraints.maxHeight){page?.place(0,0)}
 }
}

@Composable fun MotionChooser(){
 val context=androidx.compose.ui.platform.LocalContext.current
 androidx.compose.foundation.layout.Column{
  androidx.compose.material3.Text("页面切换动效",style=androidx.compose.material3.MaterialTheme.typography.titleSmall)
  androidx.compose.foundation.layout.Row(Modifier,horizontalArrangement=androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)){
   PageMotion.entries.forEach{mode->androidx.compose.material3.FilterChip(AppAppearance.motion==mode,{AppAppearance.setMotion(context,mode)},label={androidx.compose.material3.Text(mode.label)})}
  }
  androidx.compose.material3.Text(AppAppearance.motion.detail,style=androidx.compose.material3.MaterialTheme.typography.bodySmall,color=Muted)
 }
}

// Lightweight screen data stays in memory while navigating, so async reloads do not
// temporarily collapse a list and clamp its restored scroll position to zero.
class PageSnapshots { val values=mutableMapOf<String,Any>() }
val LocalPageSnapshots=staticCompositionLocalOf{PageSnapshots()}
@Composable fun <T> rememberPageSnapshot(key:String,initial:()->T):MutableState<T>{
 val snapshots=LocalPageSnapshots.current
 @Suppress("UNCHECKED_CAST")
 return remember(snapshots,key){snapshots.values.getOrPut(key){mutableStateOf(initial())} as MutableState<T>}
}

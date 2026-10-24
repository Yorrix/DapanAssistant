package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*

enum class UiStyle(val label:String,val description:String,val radius:Float){
 GEEK("极客","简洁线条 · 紧凑信息",1f),
 VIVID("灵动","鸢尾蓝紫 · 轻盈圆角",1.15f),
 JOURNAL("纸笺","暖白与墨棕 · 安静阅读",.7f),
 COAST("海盐","雾蓝与青绿 · 清爽平面",.9f),
 AURORA("极光","紫翠光晕 · 柔和圆角",1.35f);
 companion object{fun from(value:String?)=entries.firstOrNull{it.name==value}?:GEEK}
}

data class VisualPalette(val accent:Color,val onAccent:Color,val paper:Color,val card:Color,val soft:Color,val accentSurface:Color,val ink:Color,val muted:Color,val line:Color,val companion:Color,val glow:Color=companion)
private fun palette(vararg values:Long)=VisualPalette(Color(values[0]),Color(values[1]),Color(values[2]),Color(values[3]),Color(values[4]),Color(values[5]),Color(values[6]),Color(values[7]),Color(values[8]),Color(values[9]),Color(values.getOrElse(10){values[9]}))
private val geekDay=palette(0xFF0041B7,0xFFFFFFFF,0xFFFAFAFC,0xFFFFFFFF,0xFFF0F2F6,0xFFECF1FC,0xFF1D2129,0xFF626B79,0xFFE6E8ED,0xFF0041B7)
private val geekNight=palette(0xFF95B8FF,0xFF102345,0xFF111318,0xFF1B1E25,0xFF282C35,0xFF253956,0xFFE8EAF0,0xFFADB3C0,0xFF343944,0xFF95B8FF)
private val vividDay=palette(0xFF1249C7,0xFFFFFFFF,0xFFF1F3FF,0xFFFFFFFF,0xFFE6E9FC,0xFFDCE5FF,0xFF192342,0xFF5B6687,0xFFCFD7F2,0xFF8251DB)
private val vividNight=palette(0xFFA6C3FF,0xFF10244F,0xFF10142B,0xFF1C2344,0xFF2B3459,0xFF2C4372,0xFFF0F2FF,0xFFB4BFDE,0xFF414D73,0xFFD0B0FF)
private val journalDay=palette(0xFF79543B,0xFFFFFFFF,0xFFF7F3EB,0xFFFFFCF6,0xFFEEE7DB,0xFFECE0D0,0xFF302C26,0xFF6A5E50,0xFFDED5C7,0xFF527463)
private val journalNight=palette(0xFFE1BC98,0xFF30251A,0xFF1D1B18,0xFF282520,0xFF353029,0xFF46382A,0xFFF0E6D6,0xFFC2B49F,0xFF484036,0xFFA8C5B3)
private val coastDay=palette(0xFF165894,0xFFFFFFFF,0xFFEDF4F5,0xFFFFFFFF,0xFFE0EBEE,0xFFD8EAF4,0xFF183442,0xFF526C78,0xFFC3D6DD,0xFF237568)
private val coastNight=palette(0xFF9ACBFA,0xFF102F46,0xFF101D25,0xFF192C36,0xFF253D49,0xFF264858,0xFFE3F0F6,0xFFA9C4D1,0xFF3B5866,0xFF8ED6BD)
// Imported UI branch palette; retained as an opt-in style, independent of the pet.
private val auroraDay=palette(0xFF6C4CE6,0xFFFFFFFF,0xFFF5F3FC,0xFFFFFFFF,0xFFEBE6F9,0xFFE9E1FB,0xFF221D3A,0xFF68617F,0xFFDDD6EF,0xFF2EA8A0,0xFFFF8FD0)
private val auroraNight=palette(0xFFB9A5FF,0xFF241445,0xFF0E0B1E,0xFF1B1734,0xFF272148,0xFF352A58,0xFFEEEAFF,0xFFA79FC6,0xFF3B3559,0xFF6FE3C1,0xFFFF8FD0)
fun visualPalette(palette:UiColorPalette,dark:Boolean)=when(palette){UiColorPalette.DUT->if(dark)geekNight else geekDay;UiColorPalette.IRIS->if(dark)vividNight else vividDay;UiColorPalette.PAPER->if(dark)journalNight else journalDay;UiColorPalette.COAST->if(dark)coastNight else coastDay;UiColorPalette.AURORA->if(dark)auroraNight else auroraDay}
fun visualPalette(style:UiStyle,dark:Boolean)=visualPalette(style.palette,dark)
val UiPalette get()=visualPalette(AppAppearance.palette,AppAppearance.dark)
fun AppShape(radius:Dp)=RoundedCornerShape(radius*AppAppearance.shape.radius)
fun toolShape(shape:UiShape)=when(shape){UiShape.GEEK->RoundedCornerShape(50);UiShape.VIVID->RoundedCornerShape(14.dp);UiShape.JOURNAL->RoundedCornerShape(4.dp);UiShape.COAST->RoundedCornerShape(8.dp);UiShape.AURORA->RoundedCornerShape(16.dp)}
val ToolShape get()=toolShape(AppAppearance.shape)
val StyleElevation get()=when(AppAppearance.shape){UiShape.VIVID,UiShape.AURORA->2.dp;else->0.dp}
val StyleBorder:BorderStroke? get()=when(AppAppearance.shape){
 UiShape.JOURNAL->BorderStroke(.5.dp,Hairline.copy(alpha=.6f))
 UiShape.AURORA->BorderStroke(.75.dp,Brush.linearGradient(listOf(UiPalette.accent.copy(alpha=.3f),UiPalette.companion.copy(alpha=.25f),UiPalette.accent.copy(alpha=.12f))))
 else->null
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.shapeBackdrop(shape:UiShape,colors:VisualPalette,dark:Boolean){
 when(shape){
  UiShape.VIVID->{
   drawRect(Brush.radialGradient(listOf(colors.accent.copy(alpha=.045f),Color.Transparent),center=Offset(0f,0f),radius=size.width*1.15f))
   drawRect(Brush.radialGradient(listOf(colors.companion.copy(alpha=.035f),Color.Transparent),center=Offset(size.width,size.height),radius=size.width))
  }
  UiShape.AURORA->{
   // Decoration takes all colours from the selected palette, never the former bundled style.
   val alpha=if(dark).20f else .12f
   drawRect(Brush.radialGradient(listOf(colors.accent.copy(alpha=alpha),Color.Transparent),center=Offset(size.width*.2f,size.height*.15f),radius=size.width*1.05f))
   drawRect(Brush.radialGradient(listOf(colors.companion.copy(alpha=alpha*.75f),Color.Transparent),center=Offset(size.width*.85f,size.height*.55f),radius=size.width*.85f))
   drawRect(Brush.radialGradient(listOf(colors.glow.copy(alpha=alpha*.55f),Color.Transparent),center=Offset(size.width*.4f,size.height*.9f),radius=size.width*.75f))
  }
  else->Unit
 }
}
@Composable fun StyleBackdrop(){
 val shape=AppAppearance.shape;val colors=UiPalette;val dark=AppAppearance.dark
 Canvas(Modifier.fillMaxSize()){shapeBackdrop(shape,colors,dark)}
}

@Composable fun StyleChooser(){
 val context=LocalContext.current
 fun save(block:()->Unit){runCatching(block).onFailure{android.widget.Toast.makeText(context,"外观未保存，请重试",android.widget.Toast.LENGTH_SHORT).show()}}
 Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
  Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
   Text("形状",fontSize=14.sp,fontWeight=FontWeight.SemiBold)
   Text(AppAppearance.shape.description,Modifier.padding(start=8.dp),fontSize=10.sp,color=Muted,maxLines=1)
  }
  Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
   UiShape.entries.forEach{shape->AppearanceChoice(shape.label,AppAppearance.shape==shape,Modifier.weight(1f),{save{AppAppearance.setShape(context,shape)}}){
    Box(Modifier.fillMaxWidth().height(26.dp).padding(horizontal=5.dp).clip(RoundedCornerShape(6.dp*shape.radius)).background(SoftSurface).border(.5.dp,Hairline,RoundedCornerShape(6.dp*shape.radius)),contentAlignment=Alignment.Center){
     Box(Modifier.width(22.dp).height(9.dp).clip(toolShape(shape)).background(DutBlue.copy(alpha=.65f)))
    }
   }}
  }
  Text("配色",Modifier.padding(top=2.dp),fontSize=14.sp,fontWeight=FontWeight.SemiBold)
  Row(horizontalArrangement=Arrangement.spacedBy(5.dp)){
   UiColorPalette.entries.forEach{palette->AppearanceChoice(palette.label,AppAppearance.palette==palette,Modifier.weight(1f),{save{AppAppearance.setPalette(context,palette)}}){
    val day=visualPalette(palette,false);val night=visualPalette(palette,true)
    Canvas(Modifier.fillMaxWidth().height(26.dp)){
     val gap=3.dp.toPx();val w=(size.width-gap)/2
     drawRoundRect(day.soft,Offset.Zero,androidx.compose.ui.geometry.Size(w,size.height),CornerRadius(4.dp.toPx()))
     drawRoundRect(night.card,Offset(w+gap,0f),androidx.compose.ui.geometry.Size(w,size.height),CornerRadius(4.dp.toPx()))
     drawCircle(day.accent,4.dp.toPx(),Offset(w/2,size.height/2));drawCircle(night.accent,4.dp.toPx(),Offset(w+gap+w/2,size.height/2))
    }
   }}
  }
  Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
   AppearancePreview(AppAppearance.shape,AppAppearance.palette,false,Modifier.weight(1f))
   AppearancePreview(AppAppearance.shape,AppAppearance.palette,true,Modifier.weight(1f))
  }
  Text("形状与配色独立保存，可自由搭配。",fontSize=11.sp,color=Muted)
 }
}

@Composable private fun AppearanceChoice(label:String,chosen:Boolean,modifier:Modifier,onClick:()->Unit,preview:@Composable ()->Unit){
 val shape=RoundedCornerShape(7.dp)
 Surface(modifier.clip(shape).clickable(onClick=onClick).semantics{selected=chosen;role=Role.RadioButton;contentDescription="$label${if(chosen)"，已选"else""}"},shape=shape,color=if(chosen)AccentSurface else CardSurface,border=BorderStroke(if(chosen)1.2.dp else .5.dp,if(chosen)DutBlue else Hairline)){
  Column(Modifier.padding(horizontal=4.dp,vertical=6.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)){
   preview()
   Text(label,fontSize=11.sp,lineHeight=15.sp,color=if(chosen)DutBlue else Ink,fontWeight=if(chosen)FontWeight.SemiBold else FontWeight.Normal,maxLines=1)
  }
 }
}

@Composable private fun AppearancePreview(shape:UiShape,palette:UiColorPalette,dark:Boolean,modifier:Modifier){
 val p=visualPalette(palette,dark)
 Box(modifier.height(58.dp).clip(RoundedCornerShape(7.dp)).background(p.paper).border(.5.dp,p.line,RoundedCornerShape(7.dp))){
  Canvas(Modifier.fillMaxSize()){shapeBackdrop(shape,p,dark)}
  Row(Modifier.fillMaxSize().padding(7.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
   Text(if(dark)"黑夜"else"白天",fontSize=10.sp,color=p.muted)
   Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp*shape.radius)).background(p.card).padding(horizontal=7.dp,vertical=5.dp)){
    Text("今天的日程",fontSize=11.sp,lineHeight=15.sp,color=p.ink,fontFamily=if(shape==UiShape.JOURNAL)FontFamily.Serif else FontFamily.SansSerif)
    Text("08:00 · A01",fontSize=9.sp,lineHeight=13.sp,color=p.accent)
   }
  }
 }
}

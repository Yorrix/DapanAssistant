package cn.dapan.assistant

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** Small, theme-aware vector symbols; rain intensity changes the drops, not only the label. */
@Composable internal fun WeatherIcon(condition:String,modifier:Modifier=Modifier) {
 val cloud=Muted
 val water=if(AppAppearance.dark)Color(0xFF81BEF4)else Color(0xFF397BAD)
 val sun=if(AppAppearance.dark)Color(0xFFFFD477)else Color(0xFFC28A23)
 Canvas(modifier.size(24.dp).semantics{contentDescription=condition}){
  scale(size.width/24f,size.height/24f,pivot=Offset.Zero){
   fun line(x:Float,y:Float,ex:Float,ey:Float,color:Color=cloud,width:Float=1.5f)=drawLine(color,Offset(x,y),Offset(ex,ey),width,StrokeCap.Round)
   fun sunAt(x:Float,y:Float,r:Float){
    drawCircle(sun,r,Offset(x,y),style=Stroke(1.5f))
    repeat(8){val a=it*Math.PI/4;line(x+cos(a).toFloat()*(r+2),y+sin(a).toFloat()*(r+2),x+cos(a).toFloat()*(r+3.2f),y+sin(a).toFloat()*(r+3.2f),sun,1.3f)}
   }
   fun cloudAt(){
    val p=Path().apply{moveTo(6f,15f);cubicTo(0f,15f,1f,7f,7f,8f);cubicTo(8f,1f,18f,2f,18f,9f);cubicTo(24f,8f,24f,15f,19f,15f);close()}
    drawPath(p,cloud.copy(alpha=.12f));drawPath(p,cloud,style=Stroke(1.5f))
   }
   when{
    condition=="大风"||condition=="风"->{
     val p=Path().apply{moveTo(2f,8f);lineTo(15f,8f);cubicTo(22f,8f,20f,1f,16f,4f);moveTo(2f,12f);lineTo(19f,12f);cubicTo(25f,12f,23f,20f,19f,17f);moveTo(4f,16f);lineTo(11f,16f);cubicTo(17f,16f,15f,23f,11f,20f)}
     drawPath(p,cloud,style=Stroke(1.5f,cap=StrokeCap.Round))
    }
    condition=="晴"->sunAt(12f,12f,4f)
    condition.contains("暂无")-> {line(7f,12f,17f,12f)}
    else->{
     if(condition.contains("晴")||condition=="多云")sunAt(7f,7f,2.7f)
     cloudAt()
     when{
      condition.contains("雾")-> {line(3f,18f,21f,18f);line(5f,21f,19f,21f)}
      condition.contains("雪")||condition.contains("冰雹")->repeat(3){val x=6f+it*6;line(x-1.5f,19f,x+1.5f,21f,water);line(x+1.5f,19f,x-1.5f,21f,water);line(x,18f,x,22f,water)}
      condition.contains("雨")->{
       val drizzle=condition.contains("毛毛")
       val storm=condition.contains("暴")||condition.contains("强降")
       val drops=when{storm->4;condition.contains("大")||condition.contains("强")->3;condition.contains("中")->2;drizzle->3;else->1}
       repeat(drops){val x=12f+(it-(drops-1)/2f)*4.5f
        if(drizzle){drawCircle(water,.7f,Offset(x,18f));drawCircle(water,.7f,Offset(x-1,21f))}
        else line(x,17.5f,x-2,if(storm)23f else 21f,water,if(storm)2f else 1.5f)
       }
      }
     }
     if(condition.contains("雷")){
      val bolt=Path().apply{moveTo(14f,12f);lineTo(10f,18f);lineTo(13f,18f);lineTo(11f,23f);lineTo(18f,15f);lineTo(14f,15f);close()}
      drawPath(bolt,sun)
     }
    }
   }
  }
 }
}

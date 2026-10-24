package cn.dapan.assistant

import kotlin.math.ceil

internal data class ImageOcrRegion(val left:Int,val top:Int,val right:Int,val bottom:Int){
 val width get()=right-left
 val height get()=bottom-top
}
internal data class ImageOcrPart(val raw:ImageOcrRegion,val upright:ImageOcrRegion,val sample:Int)

/** Plan in upright reading order, then map back to the undecoded source image. */
internal object ImageOcrGeometry {
 fun parts(rawWidth:Int,rawHeight:Int,orientation:Int):List<ImageOcrPart>{
  require(rawWidth>0&&rawHeight>0)
  val rotated=orientation in 5..8
  val width=if(rotated)rawHeight else rawWidth
  val height=if(rotated)rawWidth else rawHeight
  val vertical=height.toLong()*10>width.toLong()*22
  val horizontal=width.toLong()*10>height.toLong()*22
  val long=if(vertical)height else width
  val short=if(vertical)width else height
  val count=if(vertical||horizontal)ceil(long/(short*1.8)).toInt().coerceIn(1,6)else 1
  return (0 until count).map{index->
   val overlap=short/24
   val start=(long.toLong()*index/count-if(index==0)0 else overlap).coerceAtLeast(0).toInt()
   val end=(long.toLong()*(index+1)/count+if(index==count-1)0 else overlap).coerceAtMost(long.toLong()).toInt()
   val upright=when{
    vertical->ImageOcrRegion(0,start,width,end)
    horizontal->ImageOcrRegion(start,0,end,height)
    else->ImageOcrRegion(0,0,width,height)
   }
   fun point(x:Int,y:Int):Pair<Int,Int> = when(orientation){
    2->rawWidth-x to y
    3->rawWidth-x to rawHeight-y
    4->x to rawHeight-y
    5->y to x
    6->y to rawHeight-x
    7->rawWidth-y to rawHeight-x
    8->rawWidth-y to x
    else->x to y
   }
   val corners=listOf(point(upright.left,upright.top),point(upright.right,upright.top),point(upright.left,upright.bottom),point(upright.right,upright.bottom))
   val raw=ImageOcrRegion(corners.minOf{it.first},corners.minOf{it.second},corners.maxOf{it.first},corners.maxOf{it.second})
   var sample=1
   fun scaled(value:Int)=(value.toLong()+sample-1)/sample
   while(scaled(raw.width)*scaled(raw.height)>6_000_000L||maxOf(scaled(raw.width),scaled(raw.height))>8192L)sample*=2
   ImageOcrPart(raw,upright,sample)
  }
 }
}

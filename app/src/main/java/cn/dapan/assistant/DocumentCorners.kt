package cn.dapan.assistant

data class PageCorner(val x:Float,val y:Float)

/** Conservative proposal for a bright page on a darker background; users confirm the corners. */
object DocumentCorners {
 fun detect(width:Int,height:Int,pixels:IntArray):List<PageCorner>? {
  require(width>1&&height>1&&pixels.size==width*height)
  val gray=IntArray(pixels.size){i->val p=pixels[i];(((p shr 16)and 255)*299+((p shr 8)and 255)*587+(p and 255)*114)/1000}
  val histogram=IntArray(256);gray.forEach{histogram[it]++}
  if((gray.maxOrNull()?:0)-(gray.minOrNull()?:0)<40)return null
  val total=gray.size;val sum=histogram.indices.sumOf{it.toDouble()*histogram[it]}
  var count=0;var partial=0.0;var threshold=0;var variance=0.0
  for(t in 0..254){count+=histogram[t];partial+=t.toDouble()*histogram[t];if(count==0||count==total)continue;val delta=partial/count-(sum-partial)/(total-count);val score=count.toDouble()*(total-count)*delta*delta;if(score>variance){variance=score;threshold=t}}
  val visited=BooleanArray(total);val queue=IntArray(total);var best:List<PageCorner>?=null;var bestArea=0
  for(seed in gray.indices){
   if(visited[seed]||gray[seed]<=threshold)continue
   var head=0;var tail=1;queue[0]=seed;visited[seed]=true
   var tl=seed;var tr=seed;var br=seed;var bl=seed;var borders=0
   fun sumOf(i:Int)=i%width+i/width
   fun difference(i:Int)=i%width-i/width
   while(head<tail){val p=queue[head++];val x=p%width;val y=p/width
    if(x==0)borders=borders or 1;if(x==width-1)borders=borders or 2;if(y==0)borders=borders or 4;if(y==height-1)borders=borders or 8
    if(sumOf(p)<sumOf(tl))tl=p;if(sumOf(p)>sumOf(br))br=p;if(difference(p)>difference(tr))tr=p;if(difference(p)<difference(bl))bl=p
    fun visit(n:Int){if(!visited[n]&&gray[n]>threshold){visited[n]=true;queue[tail++]=n}}
    if(x>0)visit(p-1);if(x+1<width)visit(p+1);if(y>0)visit(p-width);if(y+1<height)visit(p+width)
   }
   if(tail<total*.12||tail>total*.92||tail<=bestArea||Integer.bitCount(borders)>1)continue
   val corners=listOf(tl,tr,br,bl);if(corners.distinct().size!=4)continue
   val points=corners.map{PageCorner((it%width).toFloat()/(width-1),(it/width).toFloat()/(height-1))}
   if(!valid(points))continue
   val area=kotlin.math.abs(points.indices.sumOf{i->val a=points[i];val b=points[(i+1)%4];(a.x*b.y-b.x*a.y).toDouble()})/2
   if(area<.12||tail/(area*total)<.60)continue
   best=points;bestArea=tail
  }
  return best
 }
 fun valid(points:List<PageCorner>):Boolean {
  if(points.size!=4||points.any{!it.x.isFinite()||!it.y.isFinite()||it.x !in 0f..1f||it.y !in 0f..1f})return false
  return points.indices.all{i->val a=points[i];val b=points[(i+1)%4];val c=points[(i+2)%4];(b.x-a.x)*(c.y-b.y)-(b.y-a.y)*(c.x-b.x)>.0001f}
 }
}

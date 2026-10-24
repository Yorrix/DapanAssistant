package cn.dapan.assistant

import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import android.net.Uri

object ImageFiles {
 fun load(context:Context,uri:Uri,maxSide:Int):Bitmap {
  val resolver=context.contentResolver
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
  resolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,bounds)}
  require(bounds.outWidth>0&&bounds.outHeight>0){"无法识别图片格式"}
  var sample=1;while(maxOf(bounds.outWidth,bounds.outHeight)/sample>maxSide)sample*=2
  val bitmap=resolver.openInputStream(uri).use{BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply{inSampleSize=sample})}?:error("图片读取失败")
  val orientation=runCatching{resolver.openInputStream(uri)!!.use{ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}}.getOrDefault(1)
  val matrix=Matrix().apply{when(orientation){2->setScale(-1f,1f);3->setRotate(180f);4->{setRotate(180f);postScale(-1f,1f)};5->{setRotate(90f);postScale(-1f,1f)};6->setRotate(90f);7->{setRotate(-90f);postScale(-1f,1f)};8->setRotate(-90f)}}
  if(matrix.isIdentity)return bitmap
  return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also{if(it!==bitmap)bitmap.recycle()}
 }
 fun detect(bitmap:Bitmap):List<PageCorner>? {
  val scale=minOf(1f,320f/maxOf(bitmap.width,bitmap.height));val w=(bitmap.width*scale).toInt().coerceAtLeast(2);val h=(bitmap.height*scale).toInt().coerceAtLeast(2)
  val small=Bitmap.createScaledBitmap(bitmap,w,h,true);val pixels=IntArray(w*h);small.getPixels(pixels,0,w,0,0,w,h)
  return try{DocumentCorners.detect(w,h,pixels)}finally{if(small!==bitmap)small.recycle()}
 }
}

package cn.dapan.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/** Prevent HTTP follow-ups (including 503 Retry-After: 0) from issuing a second paid POST. */
internal fun imageOcrRequestBody(payload:String):RequestBody{
 val delegate=payload.toRequestBody("application/json; charset=utf-8".toMediaType())
 return object:RequestBody(){
  override fun contentType()=delegate.contentType()
  override fun contentLength()=delegate.contentLength()
  override fun isOneShot()=true
  override fun writeTo(sink:BufferedSink)=delegate.writeTo(sink)
 }
}

private class ImageOcrInputFailure(message:String):Exception(message)

/** Called by the library's serial IO queue. No implicit retry: each call may incur API usage. */
object ImageTextIndexer {
 private val client=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS)
  .writeTimeout(45,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).callTimeout(180,TimeUnit.SECONDS)
  .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
 fun available(context:Context)=SharedAi.canUseText(context)
 fun extract(context:Context,file:File,publicImageConsent:Boolean=false):ImageTextIndexResult {
  if(!available(context))return ImageTextIndexResult("","待识别 · 请登录公用 AI 或配置自备 API","missing_key")
  return try{
   interrupted()
   val selectedMode=SharedAi.mode(context)
   // Importing a file is not consent to relay its image. The user must choose this
   // particular public recognition in the library's disclosure dialog first.
   if(selectedMode==SharedAi.Mode.SHARED&&!publicImageConsent)
    return ImageTextIndexResult("",LibraryIndexState.PUBLIC_CONSENT,"consent_required")
   val payload=ImageOcrProtocol.request(imageParts(context,file))
   val shared=if(selectedMode==SharedAi.Mode.SHARED)SharedAi.publicRequest(context,payload)else null
   val request=if(shared!=null){
    val buffer=okio.Buffer();shared.body!!.writeTo(buffer)
    shared.newBuilder().post(imageOcrRequestBody(buffer.readUtf8())).build()
   }else{
    val key=Secrets(context).get("deepseek")
    require(key.isNotBlank()){"请先配置 DeepSeek API Key"}
    Request.Builder().url("https://api.deepseek.com/chat/completions")
     .header("Authorization","Bearer $key").post(imageOcrRequestBody(payload.toString())).build()
   }
   val call=client.newCall(request)
   ComponentCancellation.watch(call::cancel){call.execute().use{response->
    if(!response.isSuccessful){
     if(shared!=null)throw SharedAi.httpError(response.code,response.peekBody(8192).string())
     return@use ImageTextIndexResult("","识别失败 · ${ImageOcrProtocol.httpFailure(response.code)}","failed")
    }
    val body=response.body?:error("识别服务未返回内容")
    if(shared!=null)return@use body.charStream().buffered().use{ImageOcrProtocol.readStream(it)}
    // Never retain an unbounded response or expose an upstream error containing request data.
    val bytes=body.byteStream().use{input->ByteArrayOutputStream().use{output->
     val buffer=ByteArray(8192)
     while(output.size()<=1_048_576){interrupted();val n=input.read(buffer,0,minOf(buffer.size,1_048_577-output.size()));if(n<0)break;output.write(buffer,0,n)}
     output.toByteArray()
    }}
    require(bytes.size<=1_048_576){"识别返回内容过长"}
    ImageOcrProtocol.parse(bytes.toString(Charsets.UTF_8))
   }}
  }catch(e:Exception){
   if(Thread.currentThread().isInterrupted)throw InterruptedIOException("文字识别已取消").apply{initCause(e)}
   val reason=when(e){
    is SharedAiException->e.message?:"公用 AI 暂时无法识别图片"
    is ImageOcrInputFailure->e.message?:"图片暂时无法识别，请换一张图片"
    is SocketTimeoutException,is InterruptedIOException->"识别超时，可稍后重试"
    is org.json.JSONException->"识别返回不完整，请重试"
    is java.io.IOException->"无法连接 DeepSeek，请检查网络"
    else->"图片文字识别未完成，可重试或换一张清晰图片"
   }
   ImageTextIndexResult("","识别失败 · $reason","failed")
  }
 }
 private fun interrupted(){if(Thread.currentThread().isInterrupted)throw InterruptedIOException("文字识别已取消")}
 @Suppress("DEPRECATION")
 private fun imageParts(context:Context,file:File):List<String>{
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
  BitmapFactory.decodeFile(file.path,bounds)
  if(bounds.outWidth<=0||bounds.outHeight<=0)throw ImageOcrInputFailure("无法读取图片，请确认原文件未损坏")
  val orientation=runCatching{ExifInterface(file).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)}.getOrDefault(1)
  val parts=ImageOcrGeometry.parts(bounds.outWidth,bounds.outHeight,orientation)
  interrupted()
  val decoder=try{BitmapRegionDecoder.newInstance(file.path,false)}catch(_:Exception){null}
  if(decoder==null&&parts.size>1)throw ImageOcrInputFailure("此长图格式暂不支持分区识别，请转为 JPG 或 PNG 后重试")
  var encodedCharacters=0
  return try{
   parts.map{part->
    interrupted()
    val region=part.raw
    // RegionDecoder preserves the source resolution until this particular strip is sampled.
    val bitmap=if(decoder!=null)decoder.decodeRegion(Rect(region.left,region.top,region.right,region.bottom),BitmapFactory.Options().apply{inSampleSize=part.sample;inPreferredConfig=Bitmap.Config.ARGB_8888})
     ?:throw ImageOcrInputFailure("图片分区读取失败，请换一张图片")
    else ImageFiles.load(context,Uri.fromFile(file),maxOf(bounds.outWidth,bounds.outHeight)/part.sample.coerceAtLeast(1))
    var prepared:Bitmap?=null
    try{
     interrupted()
     // The fallback loader has already applied EXIF; the region path has not.
     val appliedOrientation=if(decoder==null)1 else orientation
     prepared=prepare(bitmap,appliedOrientation)
     val prefix="data:image/jpeg;base64,"
     val budget=(16*1024*1024-encodedCharacters-prefix.length)/4*3
     if(budget<=0)throw ImageOcrInputFailure("图片内容过大，请分成几张图片后识别")
     val bytes=object:ByteArrayOutputStream(){
      override fun write(value:Int){interrupted();if(size()>=budget)throw ImageOcrInputFailure("图片内容过大，请分成几张图片后识别");super.write(value)}
      override fun write(buffer:ByteArray,offset:Int,length:Int){interrupted();if(size().toLong()+length>budget)throw ImageOcrInputFailure("图片内容过大，请分成几张图片后识别");super.write(buffer,offset,length)}
     }.use{out->if(!prepared.compress(Bitmap.CompressFormat.JPEG,94,out))throw ImageOcrInputFailure("图片编码未完成，请重试或分成几张图片");out.toByteArray()}
     interrupted()
     val data=prefix+Base64.encodeToString(bytes,Base64.NO_WRAP)
     encodedCharacters+=data.length
     data
    }finally{if(prepared!==bitmap)prepared?.recycle();bitmap.recycle()}
   }
  }finally{decoder?.recycle()}
 }
 private fun prepare(bitmap:Bitmap,orientation:Int):Bitmap{
  if(orientation !in 2..8&&!bitmap.hasAlpha())return bitmap
  val matrix=Matrix().apply{when(orientation){
   2->setScale(-1f,1f)
   3->setRotate(180f)
   4->setScale(1f,-1f)
   5->{setRotate(90f);postScale(-1f,1f)}
   6->setRotate(90f)
   7->{setRotate(-90f);postScale(-1f,1f)}
   8->setRotate(-90f)
  }}
  val transformed=RectF(0f,0f,bitmap.width.toFloat(),bitmap.height.toFloat()).also{matrix.mapRect(it)}
  matrix.postTranslate(-transformed.left,-transformed.top)
  val swap=orientation in 5..8
  return Bitmap.createBitmap(if(swap)bitmap.height else bitmap.width,if(swap)bitmap.width else bitmap.height,Bitmap.Config.ARGB_8888).also{out->
   Canvas(out).apply{drawColor(Color.WHITE);drawBitmap(bitmap,matrix,Paint(Paint.FILTER_BITMAP_FLAG))}
  }
 }
}

package cn.dapan.assistant

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import kotlin.math.roundToInt

/** Bounded, off-main-thread decoding. Cache only small list thumbnails, never full-size photos. */
private object LibraryPreviewCache {
 val jobs=Semaphore(2)
 val thumbs=object:LruCache<String,Bitmap>(12*1024*1024){override fun sizeOf(key:String,value:Bitmap)=value.allocationByteCount}
 suspend fun load(doc:LibraryDocument,edge:Int):Bitmap?=withContext(Dispatchers.IO){jobs.withPermit{
  val file=File(doc.path);val key="${doc.id}:${file.lastModified()}:${file.length()}:$edge"
  if(edge<=256)thumbs.get(key)?.let{return@withPermit it}
  ensureActive()
  val bitmap=try{if(doc.kind==LibraryKind.PDF)pdf(file,edge)else image(file,edge)}catch(e:Exception){null}
  if(!isActive){bitmap?.recycle();ensureActive()}
  if(bitmap!=null&&edge<=256)thumbs.put(key,bitmap)
  bitmap
 }}
 private fun image(file:File,edge:Int):Bitmap? {
  val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true};BitmapFactory.decodeFile(file.path,bounds)
  if(bounds.outWidth<=0||bounds.outHeight<=0)return null
  var sample=1
  while(maxOf(bounds.outWidth,bounds.outHeight)/sample>edge*1.5 || bounds.outWidth.toLong()/sample*(bounds.outHeight/sample)>8_000_000)sample*=2
  val decoded=BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})?:return null
  val exif=runCatching{ExifInterface(file)}.getOrNull()?:return decoded
  val matrix=Matrix().apply{if(exif.isFlipped)postScale(-1f,1f);postRotate(exif.rotationDegrees.toFloat())}
  if(matrix.isIdentity)return decoded
  return Bitmap.createBitmap(decoded,0,0,decoded.width,decoded.height,matrix,true).also{if(it!==decoded)decoded.recycle()}
 }
 private fun pdf(file:File,edge:Int):Bitmap? = ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use{descriptor->
  PdfRenderer(descriptor).use{renderer->if(renderer.pageCount==0)null else renderer.openPage(0).use{page->
   val scale=edge.toFloat()/maxOf(page.width,page.height)
   Bitmap.createBitmap((page.width*scale).roundToInt().coerceAtLeast(1),(page.height*scale).roundToInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888).also{bitmap->
    bitmap.eraseColor(android.graphics.Color.WHITE)
    try{page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}catch(e:Exception){bitmap.recycle();throw e}
   }
  }}
 }
}

private data class LibraryPreview(val bitmap:Bitmap?=null,val loading:Boolean=true)
@Composable private fun libraryPreview(doc:LibraryDocument,edge:Int):LibraryPreview {
 val state=remember(doc.id,doc.path,doc.size,edge){mutableStateOf(LibraryPreview())}
 LaunchedEffect(state){state.value=LibraryPreview(LibraryPreviewCache.load(doc,edge),false)}
 return state.value
}

@Composable internal fun LibraryThumbnail(doc:LibraryDocument,modifier:Modifier=Modifier,edge:Int=256){
 val preview=if(doc.kind==LibraryKind.IMAGE||doc.kind==LibraryKind.PDF)libraryPreview(doc,edge)else LibraryPreview(loading=false)
 Box(modifier.clip(AppShape(7.dp)).background(SoftSurface),contentAlignment=Alignment.Center){
  if(preview.bitmap!=null)Image(preview.bitmap.asImageBitmap(),"${doc.name} 缩略图",Modifier.fillMaxSize(),contentScale=if(doc.kind==LibraryKind.IMAGE)ContentScale.Crop else ContentScale.Fit)
  else Column(horizontalAlignment=Alignment.CenterHorizontally){
   LineIcon(AppGlyph.File,Modifier.size(21.dp),Muted)
   Text(if(preview.loading)"加载中"else doc.name.substringAfterLast('.',doc.kind.label).uppercase().take(6),fontSize=9.sp,lineHeight=12.sp,color=Muted)
  }
 }
}

/** A focused photo viewer; never launches an unrelated external app just to see a JPG. */
@Composable internal fun LibraryImageViewer(doc:LibraryDocument,images:List<LibraryDocument>,
 onSelect:(LibraryDocument)->Unit,onClose:()->Unit,onEdit:()->Unit,onExport:()->Unit,onDelete:()->Unit,onExternal:()->Unit,readOnly:Boolean=false,onText:(()->Unit)?=null){
 val pages=remember(images,doc.id){if(images.any{it.id==doc.id})images else listOf(doc)+images}
 // Keep the pager alive as the selected record changes; reset zoom per page inside key(id).
 val pager=rememberPagerState(initialPage=pages.indexOfFirst{it.id==doc.id}.coerceAtLeast(0),pageCount={pages.size})
 val current by rememberUpdatedState(doc)
 val select by rememberUpdatedState(onSelect)
 LaunchedEffect(pager,pages){snapshotFlow{pager.settledPage}.collect{index->pages.getOrNull(index)?.let{if(it.id!=current.id)select(it)}}}
 var menu by remember{mutableStateOf(false)}
 var zoomed by remember{mutableStateOf(false)}
 val scope=rememberCoroutineScope()
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
  val view=LocalView.current
  SideEffect{(view.parent as? DialogWindowProvider)?.window?.let{window->
   WindowCompat.getInsetsController(window,view).apply{isAppearanceLightStatusBars=!AppAppearance.dark;isAppearanceLightNavigationBars=!AppAppearance.dark}
  }}
  Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
   Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
    QuietIconButton("返回资料库",AppGlyph.Back,onClose)
    Column(Modifier.weight(1f)){Text(doc.name,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${pager.settledPage+1} / ${pages.size} · ${libraryFileSize(doc.size)}",fontSize=11.sp,color=Muted)}
    if(onText!=null)TextButton(onClick=onText){Text("文字",fontSize=12.sp)}
    Box{QuietIconButton("图片操作",AppGlyph.Settings,{menu=true});DropdownMenu(menu,{menu=false}){
     if(!readOnly)DropdownMenuItem(text={Text("名称与备注")},onClick={menu=false;onEdit()})
     if(!readOnly)DropdownMenuItem(text={Text("导出图片")},onClick={menu=false;onExport()})
     DropdownMenuItem(text={Text("用其他应用打开")},onClick={menu=false;onExternal()})
     if(!readOnly)DropdownMenuItem(text={Text("删除")},onClick={menu=false;onDelete()})
    }}
   }
   HorizontalPager(pager,Modifier.weight(1f).fillMaxWidth(),key={pages[it].id},userScrollEnabled=!zoomed){index->key(pages[index].id){
    val image=pages[index];val preview=libraryPreview(image,2560)
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
     if(preview.bitmap!=null)key(pager.settledPage){LibraryZoomImage(preview.bitmap,image.name){if(pager.currentPage==index)zoomed=it}}
     else if(preview.loading)CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
     else Column(horizontalAlignment=Alignment.CenterHorizontally){Text("此图片暂时无法预览",fontSize=13.sp,color=Muted);TextButton(onClick=onExternal){Text("用其他应用打开")}}
    }
   }}
   LaunchedEffect(pager.settledPage){zoomed=false}
   Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
    QuietIconButton("上一张",AppGlyph.Back,{scope.launch{pager.animateScrollToPage(pager.settledPage-1)}},pager.settledPage>0)
    Text(doc.remark.ifBlank{"双指缩放 · 双击放大 · 左右切换"},Modifier.weight(1f),fontSize=11.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
    QuietIconButton("下一张",AppGlyph.Next,{scope.launch{pager.animateScrollToPage(pager.settledPage+1)}},pager.settledPage<pages.lastIndex)
   }
  }}
 }
}

@Composable private fun LibraryZoomImage(bitmap:Bitmap,name:String,onZoom:(Boolean)->Unit){
 var scale by remember{mutableFloatStateOf(1f)};var pan by remember{mutableStateOf(Offset.Zero)}
 var viewport by remember{mutableStateOf(IntSize.Zero)}
 fun clamp(value:Offset,zoom:Float):Offset {
  if(viewport.width==0||viewport.height==0)return Offset.Zero
  val fit=minOf(viewport.width.toFloat()/bitmap.width,viewport.height.toFloat()/bitmap.height)
  val x=((bitmap.width*fit*zoom-viewport.width)/2).coerceAtLeast(0f);val y=((bitmap.height*fit*zoom-viewport.height)/2).coerceAtLeast(0f)
  return Offset(value.x.coerceIn(-x,x),value.y.coerceIn(-y,y))
 }
 val transform=rememberTransformableState{zoom,delta,_->scale=(scale*zoom).coerceIn(1f,5f);pan=clamp(pan+delta,scale)}
 val zoomCallback by rememberUpdatedState(onZoom)
 LaunchedEffect(scale>1.01f){zoomCallback(scale>1.01f)}
 Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged{viewport=it;pan=clamp(pan,scale)}
  .transformable(transform,canPan={scale>1.01f})
  .pointerInput(bitmap){detectTapGestures(onDoubleTap={point->
   if(scale>1.01f){scale=1f;pan=Offset.Zero}else{scale=2.5f;pan=clamp((Offset(size.width/2f,size.height/2f)-point)*(scale-1),scale)}
  })}.semantics{stateDescription="${(scale*100).roundToInt()}%"}){
  Image(bitmap.asImageBitmap(),name,Modifier.fillMaxSize().graphicsLayer{scaleX=scale;scaleY=scale;translationX=pan.x;translationY=pan.y},contentScale=ContentScale.Fit)
  if(scale>1.01f)TextButton(onClick={scale=1f;pan=Offset.Zero},modifier=Modifier.align(Alignment.BottomStart)){Text("适应屏幕")}
 }
}

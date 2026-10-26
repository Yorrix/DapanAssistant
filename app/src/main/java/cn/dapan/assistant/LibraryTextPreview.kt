package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The same saved-text offsets used by AI evidence take the reader to the exact paragraph. */
@Composable internal fun LibraryTextPreview(
 doc:LibraryDocument,offset:Int=0,endOffset:Int=0,query:String="",onClose:()->Unit,
 onOriginal:()->Unit,onReindex:()->Unit
){
 var parts by remember(doc.id,doc.textVersion){mutableStateOf<List<LibraryTextPart>?>(null)}
 val list=rememberLazyListState()
 LaunchedEffect(doc.text,doc.textVersion){parts=withContext(Dispatchers.Default){LibrarySearch.parts(doc.text,doc.textFormat)}}
 LaunchedEffect(parts,offset){
  val rows=parts?:return@LaunchedEffect
  val at=rows.indexOfFirst{it.end>offset}.let{if(it<0)rows.lastIndex.coerceAtLeast(0)else it}
  if(rows.isNotEmpty())list.scrollToItem(at)
 }
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.fillMaxSize().systemBarsPadding()){
   Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
    QuietIconButton("返回文件",AppGlyph.Back,onClose)
    Column(Modifier.weight(1f)){
     Text(doc.name,fontSize=14.sp,fontWeight=FontWeight.Medium,maxLines=1,overflow=TextOverflow.Ellipsis)
     Text("可检索文字 · ${doc.text.length} 字",fontSize=11.sp,color=Muted)
    }
    TextButton(onClick=onOriginal){Text("原文件",fontSize=12.sp)}
   }
   Row(Modifier.fillMaxWidth().padding(start=14.dp,end=6.dp),verticalAlignment=Alignment.CenterVertically){
    Text(doc.state,Modifier.weight(1f),fontSize=11.sp,lineHeight=15.sp,color=Muted)
    TextButton(onClick=onReindex,enabled=!LibraryIndexState.resumable(doc.state)){
     Text(if(doc.text.isBlank())"识别文字"else"重建",fontSize=12.sp)
    }
   }
   if(doc.kind==LibraryKind.IMAGE)Text("图片识别使用当前 AI 模式的额度；重要文字请核对原图。",Modifier.padding(horizontal=14.dp,vertical=2.dp),fontSize=11.sp,color=Muted)
   if(LibrarySearch.isPartial(doc.state))Text("当前仅包含已提取的部分正文。",Modifier.padding(horizontal=14.dp,vertical=2.dp),fontSize=11.sp,color=MaterialTheme.colorScheme.tertiary)
   HorizontalDivider(color=Hairline)
   if(parts==null)LinearProgressIndicator(Modifier.fillMaxWidth())
   else if(parts!!.isEmpty())Text("暂无可读取文字。原文件仍保留，可返回查看或重新识别。",Modifier.padding(16.dp),fontSize=13.sp,color=Muted)
   else LazyColumn(Modifier.weight(1f),state=list,contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
    items(parts!!,key={it.start}){part->
     val focused=part.end>offset&&part.start<maxOf(endOffset,offset+1)
     Column(Modifier.fillMaxWidth()){
      Text(part.label,Modifier.padding(bottom=3.dp),fontSize=11.sp,color=if(focused)DutBlue else Muted,fontWeight=if(focused)FontWeight.Medium else FontWeight.Normal)
      val body=doc.text.substring(part.start,part.end)
      val terms=remember(query){LibrarySearch.terms(query)}
      val highlight=AccentSurface
      val text=remember(body,terms,highlight){buildAnnotatedString{
       append(body)
       terms.forEach{term->var start=body.indexOf(term,ignoreCase=true);var count=0;while(start>=0&&count++<100){addStyle(SpanStyle(background=highlight,fontWeight=FontWeight.SemiBold),start,start+term.length);start=body.indexOf(term,start+term.length,ignoreCase=true)}}
      }}
      SelectionContainer{Text(text,fontSize=14.sp,lineHeight=22.sp)}
     }
    }
   }
  }}
 }
}

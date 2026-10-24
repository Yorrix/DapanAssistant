package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

/** Index text is displayed verbatim; a tap on a hit opens its surrounding paragraphs. */
@Composable internal fun LibraryTextViewer(doc:LibraryDocument,initialQuery:String,onClose:()->Unit,onRetry:()->Unit,retryEnabled:Boolean){
 var query by remember(doc.id){mutableStateOf(initialQuery)}
 var parts by remember(doc.id){mutableStateOf(emptyList<LibraryTextPart>())}
 var matches by remember(doc.id){mutableStateOf(emptyList<LibraryEvidence>())}
 var loading by remember{mutableStateOf(true)}
 var jump by remember{mutableStateOf<Int?>(null)}
 val list=rememberLazyListState()
 LaunchedEffect(doc.id,doc.textVersion,doc.text,query){
  loading=true
  try{
   val result=withContext(Dispatchers.Default){LibrarySearch.parts(doc.text,doc.textFormat) to
    if(query.isBlank())emptyList()else LibrarySearch.match("","",doc.text,query,doc.textFormat,8).evidence}
   parts=result.first;matches=result.second
   val target=jump; jump=null
   if(target!=null&&query.isBlank())list.scrollToItem(parts.indexOfFirst{it.end>target}.coerceAtLeast(0))
   else list.scrollToItem(0)
  }finally{loading=false}
 }
 Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)){
  Surface(Modifier.fillMaxSize(),color=Paper){Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()){
   Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal=4.dp),verticalAlignment=Alignment.CenterVertically){
    QuietIconButton("返回文件",AppGlyph.Back,onClose)
    Text(doc.name,Modifier.weight(1f),fontSize=15.sp,fontWeight=FontWeight.SemiBold,maxLines=1,overflow=TextOverflow.Ellipsis)
    TextButton(onClick=onRetry,enabled=retryEnabled){Text(if(doc.text.isBlank())"识别文字"else"重新提取",fontSize=12.sp)}
   }
   Text(doc.state,Modifier.padding(horizontal=14.dp,vertical=3.dp),fontSize=11.sp,lineHeight=16.sp,color=Muted)
   if(doc.kind==LibraryKind.IMAGE)Text("图片发送至 DeepSeek，使用个人密钥额度。识别可能有误，请核对原图。",Modifier.padding(horizontal=14.dp),fontSize=11.sp,lineHeight=16.sp,color=Muted)
   CompactSearchField(query,{query=it},"在这份资料的文字中查找",Modifier.padding(horizontal=12.dp,vertical=6.dp))
   if(loading)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
   when{
    doc.text.isBlank()->Text(if(doc.state==LibraryIndexState.NO_KEY)"请先在 今天 → 设置 → 模型与语音 配置 DeepSeek，返回资料库后继续识别。"else"当前还没有可检索文字。原文件仍在，可返回查看或稍后重试。",Modifier.padding(14.dp),fontSize=13.sp,lineHeight=20.sp,color=Muted)
    query.isNotBlank()&&matches.isEmpty()&&!loading->Text("可用索引中未找到这些关键词，试试更短的关键词；部分索引或识别错误也可能导致未命中。",Modifier.padding(14.dp),fontSize=13.sp,color=Muted)
    else->LazyColumn(state=list,modifier=Modifier.weight(1f),contentPadding=PaddingValues(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
     if(query.isNotBlank()){
      item{Text("相关片段 · 最多显示 8 处，长按文字可复制",fontSize=11.sp,color=Muted)}
      items(matches,key={it.part.start}){hit->Column{
       Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text(hit.part.label,Modifier.weight(1f),fontSize=11.sp,color=DutBlue)
        TextButton(onClick={jump=hit.part.start;query=""},contentPadding=PaddingValues(horizontal=6.dp),modifier=Modifier.height(28.dp)){Text("上下文",fontSize=11.sp)}
       }
       SelectionContainer{Text(hit.quote,fontSize=13.sp,lineHeight=20.sp)}
       HorizontalDivider(Modifier.padding(top=8.dp),color=Hairline)
      }}
     }else{
      items(parts,key={it.start}){part->Column{
       Text(part.label,fontSize=10.sp,color=Muted)
       SelectionContainer{Text(doc.text.substring(part.start,part.end),fontSize=13.sp,lineHeight=20.sp)}
      }}
     }
    }
   }
  }}
 }
}

package cn.dapan.assistant

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Displays packaged text only; never evaluates license HTML in the school browser. */
@Composable internal fun TimetableBrowserLicenses(onClose:()->Unit){
 val context=LocalContext.current
 val pages=remember{listOf("说明" to "NOTICE.txt","依赖清单" to "DEPENDENCIES.txt","依赖许可" to "DEPENDENCY-NOTICES.txt","Mozilla 许可" to "Mozilla-Gecko-licenses.txt")}
 var selected by remember{mutableIntStateOf(0)}
 val paragraphs by produceState<List<String>?>(null,selected){
  value=null
  value=withContext(Dispatchers.IO){
   runCatching{context.assets.open("timetable-license/${pages[selected].second}").bufferedReader().use{it.readText()}.chunked(1800)}
    .getOrElse{listOf("许可资料暂时无法读取，请重新打开。")}
  }
 }
 AlertDialog(onDismissRequest=onClose,title={Text("许可与来源")},text={
  Column(verticalArrangement=Arrangement.spacedBy(6.dp)){
   Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
    pages.forEachIndexed{index,page->TextButton(onClick={selected=index},contentPadding=PaddingValues(horizontal=4.dp)){Text(page.first,fontSize=11.sp,color=if(index==selected)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)}}
   }
   LazyColumn(Modifier.fillMaxWidth().height(330.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
    if(paragraphs==null)item{Text("正在读取…",fontSize=12.sp)}
    else items(paragraphs!!){paragraph->SelectionContainer{Text(paragraph,fontSize=12.sp,lineHeight=18.sp)}}
   }
  }
 },confirmButton={TextButton(onClick=onClose){Text("关闭")}},dismissButton={TextButton(onClick={
  try{context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://hg.mozilla.org/releases/mozilla-release/rev/e4ce606692cd81e6898547e2edd7c552fefcbb04")))}
  catch(_:Exception){Toast.makeText(context,"未找到可以打开源码链接的浏览器",Toast.LENGTH_SHORT).show()}
 }){Text("官方源码")}})
}

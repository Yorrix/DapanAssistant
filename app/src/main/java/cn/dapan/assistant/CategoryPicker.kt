package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*

@OptIn(ExperimentalFoundationApi::class)
@Composable fun CategoryPill(label:String,selected:Boolean,onClick:()->Unit,onLongClick:(()->Unit)?=null){
 Box(Modifier.heightIn(min=36.dp).clip(CircleShape)
  .background(if(selected)DutBlue.copy(alpha=.12f)else SoftSurface)
  .border(.5.dp,if(selected)DutBlue.copy(alpha=.5f)else Hairline,CircleShape)
  .combinedClickable(onClick=onClick,onLongClick=onLongClick,onLongClickLabel=if(onLongClick!=null)"删除分类"else null)
  .semantics{this.selected=selected}.padding(horizontal=12.dp,vertical=7.dp),contentAlignment=Alignment.Center){
  Text(label,fontSize=13.sp,color=if(selected)DutBlue else Ink)
 }
}
@Composable fun CategoryPicker(store:AppStore,value:String,onSelect:(String)->Unit){
 var adding by remember{mutableStateOf(false)};var name by remember{mutableStateOf("")};var error by remember{mutableStateOf<String?>(null)}
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
  Text("分类",fontSize=13.sp,color=Muted,modifier=Modifier.padding(end=10.dp))
  Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
   CategoryPill("无分类",value.isBlank()||value=="无分类",{onSelect("")})
   (store.categories+listOf(value)).filter{it.isNotBlank()&&it!="无分类"}.distinct().forEach{cat->
    CategoryPill(cat,value==cat,{onSelect(cat)},if(cat=="课程")null else { {runCatching{store.removeCategory(cat);if(value==cat)onSelect("")}.onFailure{error=it.message}} })
   }
   IconButton(onClick={name="";adding=true},modifier=Modifier.size(36.dp).semantics{contentDescription="新建分类"}){LineIcon(AppGlyph.Add,Modifier.size(19.dp),DutBlue)}
  }
 }
 if(adding)AlertDialog(onDismissRequest={adding=false},title={Text("新建分类")},text={Column{
  OutlinedTextField(name,{name=it.take(20);error=null},singleLine=true,label={Text("分类名称")});error?.let{Text(it,color=MaterialTheme.colorScheme.error,fontSize=12.sp)}
 }},confirmButton={TextButton(enabled=name.isNotBlank()&&name.trim()!="无分类",onClick={runCatching{store.addCategory(name);onSelect(name.trim());adding=false}.onFailure{error=it.message}}){Text("添加")}},dismissButton={TextButton(onClick={adding=false}){Text("取消")}})
 if(!adding&&error!=null)AlertDialog(onDismissRequest={error=null},text={Text(error!!)},confirmButton={TextButton(onClick={error=null}){Text("知道了")}})
}

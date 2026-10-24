package cn.dapan.assistant

import org.json.*

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

internal val informationSections=listOf("个人信息","老师信息","校园服务")
internal fun informationSection(note:NoteItem)=when {
 note.group.contains("老师")||note.group.contains("教师")->"老师信息"
 note.group.contains("服务")||note.group.contains("地点")||note.group.contains("开放")->"校园服务"
 else->"个人信息"
}
private val personalLabels=listOf("学号","电话","寝室","邮箱")
private fun personalKey(title:String)=when(title.trim().removePrefix("我的")) {"手机号","手机号码","电话号码"->"电话";"宿舍","宿舍号","寝室号"->"寝室";"电子邮箱","邮箱地址"->"邮箱";else->title.trim().removePrefix("我的")}
private fun informationFields(section:String)=if(section=="老师信息")listOf("职务","办公室","电话")else listOf("开放时间","地点","电话")
/** Store readable key/value lines so existing assistant queries can use the same notes. */
private fun informationValues(detail:String):Map<String,String>{
 val fields=linkedMapOf<String,String>();val extra=mutableListOf<String>()
 detail.lines().forEach{line->val split=line.indexOf('：').takeIf{it>=0}?:line.indexOf(':');val key=if(split>0)line.substring(0,split).trim()else""
  if(split in 1..24&&key.isNotBlank()&&key !in listOf("http","https")){
   val canonical=when(key){"职位"->"职务";"办公地点"->"办公室";"营业时间","服务时间"->"开放时间";"联系方式","电话号码","手机号"->"电话";else->key}
   if(canonical!="来源"){val value=line.substring(split+1).trim();fields[canonical]=listOfNotNull(fields[canonical],value).filter{it.isNotBlank()}.joinToString("\n")}
  }else if(line.isNotBlank())extra+=line
 }
 if(extra.isNotEmpty())fields["备注"]=listOfNotNull(fields["备注"],extra.joinToString("\n")).joinToString("\n")
 return fields
}
@OptIn(ExperimentalMaterial3Api::class,ExperimentalFoundationApi::class)
@Composable fun InformationScreen(store:AppStore,onBack:()->Unit){
 val c=LocalContext.current
 var section by rememberSaveable{mutableStateOf("个人信息")};var query by rememberSaveable{mutableStateOf("")}
 var searching by rememberSaveable{mutableStateOf(false)};var draft by rememberSaveable{mutableStateOf("")}
 var edit by remember{mutableStateOf<NoteItem?>(null)};var editSection by remember{mutableStateOf("个人信息")}
 var viewing by remember{mutableStateOf<NoteItem?>(null)}
 val all=store.notes.filter{it.module=="信息查询"};val records=all.filter{informationSection(it)==section}.sortedBy{it.created}
 fun matches(label:String,detail:String)=(label+detail).contains(query,true)
 fun open(note:NoteItem,kind:String=section){editSection=kind;edit=note}
 fun copy(label:String,value:String){(c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label,value));Toast.makeText(c,"已复制",Toast.LENGTH_SHORT).show()}
 AssistantSurface("信息查询",JSONObject().put("section",section).put("query",query).put("selectedId",edit?.id),listOf(surfaceTool("view","切换资料分组与搜索，section=个人信息/老师信息/校园服务，query可空","section,query",false){a->val kind=a.optString("section",section);require(kind in informationSections);prepared("查看信息资料",a){section=kind;query=a.optString("query","");"已切换资料分组和筛选"}}))
 Column(Modifier.fillMaxSize()){
  Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,onBack);Text("信息查询",Modifier.weight(1f),fontSize=17.sp,lineHeight=21.sp,fontWeight=FontWeight.SemiBold)
   QuietIconButton("搜索资料",AppGlyph.Search,{draft=query;searching=true},color=if(query.isBlank())Muted else DutBlue)
   QuietIconButton("添加$section",AppGlyph.Add,{open(NoteItem(module="信息查询",title="",group=section))})
  }
  Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),horizontalArrangement=Arrangement.spacedBy(4.dp)){
   informationSections.forEach{kind->Text(kind,Modifier.weight(1f).clip(AppShape(7.dp)).background(if(section==kind)AccentSurface else androidx.compose.ui.graphics.Color.Transparent).clickable{section=kind}.padding(vertical=8.dp),fontSize=13.sp,lineHeight=18.sp,fontWeight=if(section==kind)FontWeight.SemiBold else FontWeight.Normal,color=if(section==kind)DutBlue else Muted,textAlign=androidx.compose.ui.text.style.TextAlign.Center)}
  }
  if(query.isNotBlank())Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically){Text("搜索：$query",Modifier.weight(1f),fontSize=12.sp,color=Muted);TextButton(onClick={query=""}){Text("清除",fontSize=12.sp)}}
  LazyColumn(Modifier.fillMaxSize().padding(horizontal=14.dp),contentPadding=PaddingValues(top=4.dp,bottom=8.dp+LocalFloatingAiHeight.current)){
   if(section=="个人信息"){
    val pinned=personalLabels.associateWith{label->records.firstOrNull{personalKey(it.title)==label}}
    items(personalLabels.filter{matches(it,pinned[it]?.detail.orEmpty())},key={"slot-$it"}){label->val note=pinned[label]
     InformationValue(label,note?.detail.orEmpty(),onEdit={open(note?:NoteItem(module="信息查询",title=label,group=section))},onCopy={copy(label,it)})
    }
    val extra=records.filter{n->pinned.values.none{it?.id==n.id}&&matches(n.title,n.detail)}
    items(extra,key={it.id}){n->InformationValue(n.title,n.detail,onEdit={open(n)},onCopy={copy(n.title,it)})}
    if(query.isBlank())item{TextButton(onClick={open(NoteItem(module="信息查询",title="",group=section))},contentPadding=PaddingValues(0.dp)){Text("＋ 添加信息项",fontSize=13.sp)}}
    if(query.isNotBlank()&&personalLabels.none{matches(it,pinned[it]?.detail.orEmpty())}&&extra.isEmpty())item{Text("没有匹配的资料",fontSize=13.sp,color=Muted)}
   }else{
    val shown=records.filter{matches(it.title,it.detail)}
    if(records.isEmpty()&&query.isBlank())item{
     Text(if(section=="老师信息")"暂无老师信息，点右上角添加。"else"暂无校园服务，点右上角添加。",Modifier.padding(vertical=12.dp),fontSize=13.sp,lineHeight=18.sp,color=Muted)
    }
    if(records.isNotEmpty()&&shown.isEmpty()||records.isEmpty()&&query.isNotBlank())item{Text("没有匹配的资料",fontSize=13.sp,color=Muted)}
    items(shown,key={it.id}){n->val values=informationValues(n.detail)
     Column(Modifier.fillMaxWidth().combinedClickable(onClick={viewing=n},onLongClick={open(n)}).padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
      Row(verticalAlignment=Alignment.CenterVertically){
       Text(n.title,Modifier.weight(1f).padding(end=6.dp),fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
       val place=values[if(section=="老师信息")"办公室"else"地点"].orEmpty()
       if(place.isNotBlank())Text(place,Modifier.widthIn(max=108.dp).clip(AppShape(5.dp)).background(SoftSurface).padding(horizontal=6.dp,vertical=2.dp),fontSize=11.sp,lineHeight=15.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
      }
      val hours=values[if(section=="老师信息")"职务"else"开放时间"].orEmpty()
      if(hours.isNotBlank())Text(hours,fontSize=12.sp,lineHeight=17.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
      val phone=values["电话"].orEmpty()
      if(phone.isNotBlank())Text(phone,Modifier.fillMaxWidth().clickable{copy("电话",phone)},fontSize=12.sp,lineHeight=17.sp,color=DutBlue,maxLines=1,overflow=TextOverflow.Ellipsis)
     }
     HorizontalDivider(color=Hairline,thickness=.5.dp)
    }
   }
  }
 }
 viewing?.let{n->val values=informationValues(n.detail)
  ModalBottomSheet(onDismissRequest={viewing=null},containerColor=CardSurface,dragHandle=null){
   Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal=16.dp).padding(bottom=16.dp)){
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),verticalAlignment=Alignment.CenterVertically){
     Text(n.title,Modifier.weight(1f),fontSize=17.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold)
     TextButton(onClick={viewing=null;open(n,informationSection(n))}){Text("编辑",fontSize=12.sp)}
     QuietIconButton("关闭",AppGlyph.Close,{viewing=null})
    }
    Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
     values.filterValues{it.isNotBlank()}.forEach{(label,value)->InformationFieldLine(label,value){copy(label,it)}}
    }
   }
  }
 }
 edit?.let{n->InformationRecordEditor(n,editSection,onDismiss={edit=null},onSave={value->
  require(editSection!="个人信息"||all.none{it.id!=value.id&&informationSection(it)=="个人信息"&&personalKey(it.title)==personalKey(value.title)}){"已有同名信息项，请编辑已有条目"}
  store.put(value);edit=null
 },onDelete=if(all.any{it.id==n.id})({store.remove(n);edit=null})else null)}
 if(searching)AlertDialog(onDismissRequest={searching=false},title={Text("搜索$section",fontSize=18.sp)},text={OutlinedTextField(draft,{draft=it},label={Text("名称或内容")},singleLine=true)},confirmButton={TextButton(onClick={query=draft.trim();searching=false}){Text("搜索")}},dismissButton={TextButton(onClick={searching=false}){Text("取消")}})
}
@Composable private fun InformationValue(label:String,value:String,onEdit:()->Unit,onCopy:(String)->Unit){
 Column(Modifier.fillMaxWidth().clickable(onClick=onEdit)){
  Row(Modifier.fillMaxWidth().padding(vertical=9.dp),verticalAlignment=Alignment.CenterVertically){
   Text(label,Modifier.widthIn(min=56.dp,max=100.dp).padding(end=10.dp),fontSize=13.sp,color=Muted)
   Text(value.ifBlank{"未填写"},Modifier.weight(1f),fontSize=14.sp,lineHeight=20.sp,color=if(value.isBlank())Muted else Ink,maxLines=2,overflow=TextOverflow.Ellipsis)
   if(value.isNotBlank())Text("复制",Modifier.clickable{onCopy(value)}.padding(start=12.dp,top=6.dp,bottom=6.dp),fontSize=11.sp,color=DutBlue)
  };HorizontalDivider(color=Hairline,thickness=.5.dp)
 }
}
@Composable private fun InformationFieldLine(label:String,value:String,onCopy:((String)->Unit)?=null){
 Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
  Text(label,Modifier.width(68.dp),fontSize=12.sp,color=Muted)
  Text(value.ifBlank{"未填写"},Modifier.weight(1f),fontSize=13.sp,lineHeight=18.sp,color=if(value.isBlank())Muted else Ink)
  if(value.isNotBlank()&&onCopy!=null)Text("复制",Modifier.clickable{onCopy(value)}.padding(start=10.dp,top=5.dp,bottom=5.dp),fontSize=10.sp,color=DutBlue)
 }
}
@Composable internal fun InformationRecordEditor(note:NoteItem,section:String=informationSection(note),onDismiss:()->Unit,onSave:(NoteItem)->Unit,onDelete:(()->Unit)?=null){
 var title by remember(note){mutableStateOf(note.title)};var content by remember(note){mutableStateOf(note.detail)}
 val parsed=remember(note){informationValues(note.detail)};val keys=remember(note,section){(informationFields(section)+parsed.keys).distinct()}
 var fields by remember(note){mutableStateOf(parsed)};var error by remember{mutableStateOf<String?>(null)};var deleting by remember{mutableStateOf(false)}
 AlertDialog(onDismissRequest=onDismiss,title={Text(section,fontSize=18.sp)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(6.dp)){
  OutlinedTextField(title,{title=it},label={Text(when(section){"个人信息"->"名称";"老师信息"->"姓名";else->"服务名称"})},modifier=Modifier.fillMaxWidth(),singleLine=true)
  if(section=="个人信息")OutlinedTextField(content,{content=it},label={Text("内容")},modifier=Modifier.fillMaxWidth(),minLines=1,maxLines=5)
  else keys.forEach{key->OutlinedTextField(fields[key].orEmpty(),{fields=fields+(key to it)},label={Text(key)},modifier=Modifier.fillMaxWidth(),maxLines=4)}
  if(onDelete!=null)TextButton(onClick={deleting=true},contentPadding=PaddingValues(0.dp)){Text("删除这条资料",fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
  error?.let{Text(it,fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
 }},confirmButton={TextButton(onClick={if(title.isBlank())error="请填写名称"else try{onSave(note.copy(title=title.trim(),group=section,detail=if(section=="个人信息")content.trim()else keys.filter{fields[it].orEmpty().isNotBlank()}.joinToString("\n"){key->"$key：${fields[key]!!.trim().replace("\n","；")}"}))}catch(e:Exception){error=e.message?:"保存失败"}}){Text("保存")}},dismissButton={TextButton(onClick=onDismiss){Text("取消")}})
 if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("删除“${note.title}”？",fontSize=18.sp)},confirmButton={TextButton(onClick={try{onDelete?.invoke()}catch(e:Exception){error=e.message};deleting=false}){Text("删除")}},dismissButton={TextButton(onClick={deleting=false}){Text("取消")}})
}

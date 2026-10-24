package cn.dapan.assistant

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.*
import org.json.*
import java.net.URLEncoder

private fun statusName(value:String)=when(value){"sold"->"已完成";"withdrawn"->"已下架";"expired"->"已过期";else->"发布中"}
private fun imageIds(item:JSONObject?)=item?.optJSONArray("images")?.let{a->(0 until a.length()).map(a::getString)}?:emptyList()
private fun storageText(bytes:Long)=String.format(java.util.Locale.ROOT,"%.2f MB",bytes/1048576.0)

@Composable private fun MarketType(kind:String){
 val color=if(kind=="buy")Pine else Coral
 Surface(color=color.copy(alpha=.11f),shape=AppShape(7.dp)){
  Text(if(kind=="buy")"收"else"出",Modifier.padding(horizontal=8.dp,vertical=3.dp),color=color,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
 }
}
@Composable private fun MarketHeader(title:String,enabled:Boolean=true,onBack:()->Unit,actions:@Composable RowScope.()->Unit={}){
 Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
  QuietIconButton("返回",AppGlyph.Back,onBack,enabled)
  Text(title,Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold);actions()
 }
}

@Composable fun CommunityScreen(initialPage:String="browse",onBack:()->Unit={}) {
 val c=LocalContext.current;val api=remember{CommunityClient(c)};val scope=rememberCoroutineScope()
 var page by remember{mutableStateOf(initialPage)};var loggedIn by remember{mutableStateOf(api.loggedIn)}
 var listings by remember{mutableStateOf(emptyList<JSONObject>())};var selected by remember{mutableStateOf<JSONObject?>(null)}
 var aiDraft by remember{mutableStateOf<JSONObject?>(null)}
 var editing by remember{mutableStateOf<JSONObject?>(null)};var query by remember{mutableStateOf("")};var kind by remember{mutableStateOf("")}
 var mine by remember{mutableStateOf(false)};var busy by remember{mutableStateOf(false)};var demo by remember{mutableStateOf(false)}
 var hasMore by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)};var loadError by remember{mutableStateOf<String?>(null)}
 var pending by remember{mutableStateOf<PendingChange?>(null)};var reporting by remember{mutableStateOf(false)};var reportText by remember{mutableStateOf("")}
 var used by remember{mutableStateOf<Long?>(null)};var photo by remember{mutableStateOf<String?>(null)}
 fun task(work:suspend ()->Unit){if(busy)return;busy=true;scope.launch{try{work()}catch(e:CancellationException){throw e}catch(e:Exception){message=if(e is java.io.IOException)"服务暂时连接不上，请检查网络后重试"else e.message;loggedIn=api.loggedIn}finally{busy=false}}}
 suspend fun reload(append:Boolean=false){
  val result=api.request("/listings?q=${URLEncoder.encode(query,"UTF-8")}&kind=$kind&mine=$mine&offset=${if(append)listings.size else 0}")
  listings=if(append)listings+result.optJSONArray("items").objects()else result.optJSONArray("items").objects();hasMore=result.optBoolean("hasMore");demo=result.optBoolean("demo");loadError=null
  if(mine&&loggedIn)used=api.request("/me").getJSONObject("images").getLong("usedBytes")
 }
 fun detail(id:String){task{selected=api.request("/listings/$id");page="detail"}}
 fun back(){if(!busy){if(page=="browse")onBack()else page="browse"}}
 AssistantSurface("二手信息",JSONObject().put("page",page).put("busy",busy).put("loggedIn",loggedIn).put("query",query).put("kind",kind).put("mine",mine).put("selected",selected).put("visible",JSONArray(listings.take(20))),listOf(
  surfaceTool("search","搜索二手信息，query关键词，kind=buy/sell/空，mine是否只看自己","query,kind,mine",false){a->val k=a.optString("kind",kind);require(k in listOf("","buy","sell"));val m=a.optBoolean("mine",mine);require(!m||api.loggedIn){"请先手动登录"};require(!busy);prepared("搜索二手信息",a){query=a.optString("query","");kind=k;mine=m;page="browse";reload();"已加载 ${listings.size} 条信息；${if(hasMore)"还有更多"else"当前结果已加载"}"}},
  surfaceTool("detail","打开真实信息id，查看描述与公开联系方式","id",false){a->val id=a.text("id",100);require(id.matches(Regex("[a-zA-Z0-9-]+")));prepared("查看二手信息",a){selected=api.request("/listings/$id");page="detail";"已打开信息详情"}},
  surfaceTool("editor","新建发布草稿或编辑自己的id；未登录时打开登录页","id",false){a->val id=a.optString("id");require(id.isBlank()||id.matches(Regex("[a-zA-Z0-9-]+")));prepared("打开发布编辑",a){if(!api.loggedIn){page="login";"请先手动登录，不要向AI发送密码或验证码"}else{val item=if(id.isBlank())null else api.request("/listings/$id");require(item==null||item.optBoolean("mine")){"不能编辑他人信息"};editing=item;aiDraft=null;page="editor";"已打开草稿，尚未发布"}}},
  surfaceTool("compose","从任意页面新建二手信息；kind=buy/sell，title，description可空，contacts={QQ/微信/手机:值}；publish默认false，发布需明确授权且consent=true","kind,title,description,contacts,publish,consent"){a->
   require(api.loggedIn&&!busy){"请先手动登录，并等待加载完成"};val k=a.onlyChoice("kind",listOf("buy","sell"));val t=a.text("title",60);val d=a.optString("description","");require(d.length<=3000)
   val cs=a.optJSONObject("contacts")?:JSONObject();require(cs.keys().asSequence().all{it in listOf("QQ","微信","手机")&&cs.getString(it).isNotBlank()&&cs.getString(it).length<=60})
   val publish=a.optBoolean("publish");if(publish)require(cs.length()>0&&a.optBoolean("consent")){"发布前需提供联系方式并同意公开"}
   val body=JSONObject().put("kind",k).put("title",t).put("description",d).put("contacts",cs).put("images",JSONArray())
   prepared(if(publish)"发布二手信息（公开所填联系方式）"else"新建二手信息草稿",a){if(publish){val result=api.request("/listings","POST",body);page="browse";val refreshed=runCatching{reload()}.isSuccess;"服务器已接收发布（${result.optString("id")}），所填联系方式已公开"+(if(refreshed)""else"；列表暂未刷新，请勿重复发布")}else{editing=null;aiDraft=body;page="editor";"草稿已填写，尚未发布"}}
  },
  surfaceTool("status","修改自己的id，status=sold/withdrawn，已完成/下架","id,status"){a->val id=a.text("id",100);require(id.matches(Regex("[a-zA-Z0-9-]+")));val status=a.onlyChoice("status",listOf("sold","withdrawn"));require(api.loggedIn&&!busy){"请先登录并等待加载结束"};prepared("${if(status=="sold")"完成"else"下架"}信息 $id",a){api.request("/listings/$id","PATCH",JSONObject().put("status",status));page="browse";runCatching{reload()};"服务器已更新信息状态"}},
  surfaceTool("delete","永久删除自己发布的信息与无引用图片","id"){a->val id=a.text("id",100);require(id.matches(Regex("[a-zA-Z0-9-]+"))&&api.loggedIn&&!busy);prepared("删除发布 $id",a){api.request("/listings/$id","DELETE");page="browse";runCatching{reload()};"服务器已删除这条信息"}}
 ))
 BackHandler(enabled=page!="editor"){back()}
 LaunchedEffect(kind,mine){
  if(api.endpoint.isNotBlank()){busy=true;try{reload();if(initialPage=="detail"&&selected==null&&listings.isNotEmpty())selected=api.request("/listings/${listings.first().getString("id")}")}
  catch(e:CancellationException){throw e}catch(e:Exception){loadError=if(e is java.io.IOException)"服务暂时连接不上，请稍后重试"else e.message;loggedIn=api.loggedIn}finally{busy=false}}
 }
 Column(Modifier.fillMaxSize()) {
  if(page!="editor")MarketHeader(when(page){"browse"->"二手信息";"detail"->"信息详情";"register"->"注册";"reset"->"重设密码";else->"登录"},!busy,{back()}){
   if(page=="browse"){
    TextButton(onClick={if(loggedIn)mine=!mine else page="login"},enabled=!busy){Text(if(mine)"全部信息"else"我的")}
    QuietIconButton("发布信息",AppGlyph.Add,{if(loggedIn){editing=null;aiDraft=null;page="editor"}else page="login"},!busy,color=Pine)
   }
  }
  if(demo)Text("演示数据 · 非真实交易信息",Modifier.padding(horizontal=12.dp),fontSize=11.sp,color=Coral)
  if(busy)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp),color=Pine)
  when(page){
   "login","register","reset" -> AccountForm(api,page,busy,{page=it},{task(it)},{loggedIn=true;page="browse"},{message=it})
   "editor" -> ListingEditor(api,editing,busy,{page=if(editing==null)"browse"else"detail"},{task(it)},{page="browse";editing=null;aiDraft=null;reload()},seed=aiDraft)
   "detail" -> {
    val item=selected
    if(item!=null)LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=LocalFloatingAiHeight.current+8.dp)){
     item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
      MarketType(item.optString("kind","sell"));Text(item.getString("title"),Modifier.weight(1f),fontSize=18.sp,fontWeight=FontWeight.SemiBold);Text(statusName(item.optString("status")),fontSize=11.sp,color=Muted)
     }}
     if(item.optString("description").isNotBlank())item{Text(item.getString("description"),fontSize=14.sp,lineHeight=22.sp)}
     if(imageIds(item).isNotEmpty())item{LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp)){items(imageIds(item)){id->MarketPicture(api,id,Modifier.size(140.dp).clip(AppShape(10.dp)).clickable{photo=id})}}}
     item{Text("${item.optString("seller")} · ${java.time.Instant.ofEpochSecond(item.optLong("created")).atZone(java.time.ZoneId.of("Asia/Shanghai")).toLocalDate()}",fontSize=11.sp,color=Muted);HorizontalDivider(Modifier.padding(top=10.dp),color=Muted.copy(alpha=.15f))}
     item{Text("联系方式",fontSize=13.sp,fontWeight=FontWeight.SemiBold)}
     val contacts=item.optJSONObject("contacts")?:JSONObject()
     if(contacts.length()==0)item{Text("此信息已结束，联系方式已隐藏。",fontSize=12.sp,color=Muted)}
     items(contacts.keys().asSequence().toList()){key->Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
      Text(key,Modifier.width(44.dp),fontSize=12.sp,color=Muted);Text(contacts.getString(key),Modifier.weight(1f),fontSize=14.sp)
      TextButton(onClick={(c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(key,contacts.getString(key)))}){Text("复制")}
     }}
     item{Text("发布者选择公开以上联系方式，交易方式由双方商定。",fontSize=11.sp,color=Muted)}
     item{if(item.optBoolean("mine"))Row(horizontalArrangement=Arrangement.spacedBy(2.dp)){
      if(item.optString("status")=="active"){
       TextButton(onClick={editing=item;aiDraft=null;page="editor"},enabled=!busy){Text("编辑")}
       TextButton(onClick={pending=PendingChange("标记为已完成，并停止公开展示？"){task{api.request("/listings/${item.getString("id")}","PATCH",JSONObject().put("status","sold"));page="browse";reload()}}},enabled=!busy){Text("已完成")}
       TextButton(onClick={pending=PendingChange("下架这条信息？图片仍计入容量，可在删除信息后释放。") {task{api.request("/listings/${item.getString("id")}","PATCH",JSONObject().put("status","withdrawn"));page="browse";reload()}}},enabled=!busy){Text("下架")}
      }
      TextButton(onClick={pending=PendingChange("删除这条信息及不再使用的图片？此操作不能撤销。") {task{api.request("/listings/${item.getString("id")}","DELETE");page="browse";reload()}}},enabled=!busy){Text("删除",color=Coral)}
     }else TextButton(onClick={if(loggedIn)reporting=true else page="login"},enabled=!busy){Text("举报",color=Muted)}}
    }
   }
   else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(5.dp),contentPadding=PaddingValues(bottom=LocalFloatingAiHeight.current+8.dp)){
    item{Row(verticalAlignment=Alignment.CenterVertically){CompactSearchField(query,{query=it.take(100)},"搜索主题或详情",Modifier.weight(1f),onSearch={task{reload()}});QuietIconButton("搜索",AppGlyph.Search,{task{reload()}},!busy)}}
    item{Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
     listOf("" to "全部","buy" to "收","sell" to "出").forEach{(value,label)->FilterChip(kind==value,{kind=value},enabled=!busy,label={Text(label,fontSize=12.sp)})}
     Spacer(Modifier.weight(1f));Text(if(mine)"我的发布"else"最新发布",fontSize=11.sp,color=Muted)
    }}
    if(mine)item{Column{
     Row(verticalAlignment=Alignment.CenterVertically){Text("图片 ${used?.let(::storageText)?:"—"} / 10 MB",Modifier.weight(1f),fontSize=11.sp,color=Muted)
      TextButton(onClick={pending=PendingChange("清理尚未被任何信息使用的图片？包括未完成发布时上传的图片；已发布的图片不受影响。") {task{used=api.request("/images/unused","DELETE").getLong("usedBytes")}}},enabled=!busy){Text("清理未发布",fontSize=11.sp)}}
     TextButton(onClick={pending=PendingChange("退出当前账号？"){task{try{api.request("/auth/logout","POST")}finally{api.clearSession();loggedIn=false;mine=false}}}},enabled=!busy){Text("退出登录",fontSize=11.sp,color=Muted)}
    }}
    loadError?.let{error->item{Text(error,fontSize=12.sp,color=Coral);TextButton(onClick={task{reload()}},enabled=!busy){Text("重新加载")}}}
    if(listings.isEmpty()&&!busy&&loadError==null)item{Text(if(mine)"还没有发布信息，点右上角 ＋ 新建。"else"暂无信息，试试其他关键词或发布一条。",Modifier.padding(vertical=12.dp),fontSize=13.sp,color=Muted)}
    items(listings,key={it.getString("id")}){item->
     Surface(Modifier.fillMaxWidth().clickable(enabled=!busy){detail(item.getString("id"))},color=CardSurface,shape=AppShape(12.dp)){
      Row(Modifier.padding(9.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
       Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)){
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){MarketType(item.optString("kind","sell"));Text(item.getString("title"),fontSize=14.sp,lineHeight=19.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))}
        if(item.optString("description").isNotBlank())Text(item.getString("description"),fontSize=12.sp,lineHeight=18.sp,color=Muted,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(if(mine)statusName(item.optString("status"))else item.optString("seller"),fontSize=10.sp,color=Muted)
       }
       imageIds(item).firstOrNull()?.let{id->Box{MarketPicture(api,id,Modifier.size(64.dp).clip(AppShape(8.dp)));if(imageIds(item).size>1)Surface(Modifier.align(Alignment.BottomEnd),color=Ink.copy(alpha=.65f),shape=AppShape(4.dp)){Text("${imageIds(item).size} 图",Modifier.padding(3.dp),fontSize=9.sp,color=Paper)}}}
      }
     }
    }
    if(hasMore)item{TextButton(onClick={task{reload(true)}},enabled=!busy){Text("加载更多")}}
    item{Text("仅提供信息与联系方式。发布后展示 30 天。",Modifier.padding(vertical=6.dp),fontSize=10.sp,color=Muted)}
   }
  }
 }
 pending?.let{plan->AlertDialog(onDismissRequest={pending=null},text={Text(plan.description)},confirmButton={TextButton(onClick={pending=null;plan.apply()},enabled=!busy){Text("确认")}},dismissButton={TextButton(onClick={pending=null}){Text("取消")}})}
 if(reporting)AlertDialog(onDismissRequest={if(!busy)reporting=false},title={Text("举报信息")},text={OutlinedTextField(reportText,{reportText=it.take(500)},label={Text("请描述问题")},minLines=2)},confirmButton={TextButton(onClick={task{api.request("/listings/${selected!!.getString("id")}/report","POST",JSONObject().put("reason",reportText));reporting=false;reportText="";message="已记录举报，等待管理员核查"}},enabled=!busy&&reportText.trim().length>=2){Text("提交")}},dismissButton={TextButton(onClick={reporting=false},enabled=!busy){Text("取消")}})
 message?.let{AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}})}
 photo?.let{id->Dialog(onDismissRequest={photo=null}){Column{MarketPicture(api,id,Modifier.fillMaxWidth().height(420.dp),fit=true);TextButton(onClick={photo=null}){Text("关闭")}}}}
}

@Composable private fun ListingEditor(api:CommunityClient,initial:JSONObject?,busy:Boolean,onCancel:()->Unit,task:(suspend ()->Unit)->Unit,onSaved:suspend ()->Unit,seed:JSONObject?=null){
 val c=LocalContext.current
 var title by remember(initial,seed){mutableStateOf((initial?:seed)?.optString("title")?:"")};var description by remember(initial,seed){mutableStateOf((initial?:seed)?.optString("description")?:"")}
 var kind by remember(initial,seed){mutableStateOf((initial?:seed)?.optString("kind","sell")?:"sell")}
 var contacts by remember(initial,seed){mutableStateOf((initial?:seed)?.optJSONObject("contacts")?.let{o->o.keys().asSequence().associateWith{o.getString(it)}}?:emptyMap<String,String>())}
 val original=remember(initial){imageIds(initial)};var images by remember(initial){mutableStateOf(original)}
 var uploaded by remember{mutableStateOf(emptySet<String>())};var consent by remember{mutableStateOf(initial!=null)}
 var used by remember{mutableStateOf<Long?>(null)};var quotaError by remember{mutableStateOf(false)}
 LaunchedEffect(Unit){try{used=api.request("/me").getJSONObject("images").getLong("usedBytes")}catch(e:CancellationException){throw e}catch(e:Exception){quotaError=true}}
 fun cancel(){if(!busy)task{try{for(id in uploaded)api.request("/images/$id","DELETE")}finally{onCancel()}}}
 BackHandler{cancel()}
 val picker=rememberLauncherForActivityResult(ActivityResultContracts.GetContent()){uri->if(uri!=null)task{
  val encoded=withContext(Dispatchers.IO){val bitmap=ImageFiles.load(c,uri,1600);try{var quality=85;var bytes:ByteArray
   do{val out=java.io.ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,quality,out);bytes=out.toByteArray();quality-=10}while(bytes.size>500*1024&&quality>=45)
   Base64.encodeToString(bytes,Base64.NO_WRAP)
  }finally{bitmap.recycle()}}
  val result=api.request("/images","POST",JSONObject().put("data",encoded));val id=result.getString("id");uploaded=uploaded+id;images=images+id;used=result.getLong("usedBytes");quotaError=false
 }}
 AssistantSurface("二手信息",JSONObject().put("page","editor").put("draft",JSONObject().put("id",initial?.optString("id")).put("title",title).put("description",description).put("kind",kind).put("contacts",JSONObject(contacts)).put("images",JSONArray(images)).put("consent",consent)),listOf(
  surfaceTool("draft","填写当前草稿；kind=buy/sell，title，description可空，contacts={QQ/微信/手机:值}，仅填写用户提供的联系方式","kind,title,description,contacts"){a->val k=a.optString("kind",kind);require(k in listOf("buy","sell"));val t=a.optString("title",title);val d=a.optString("description",description);require(t.length<=60&&d.length<=3000);val cs=a.optJSONObject("contacts")?.let{o->o.keys().asSequence().associateWith{o.getString(it)}}?:contacts;require(cs.keys.all{it in listOf("QQ","微信","手机")}&&cs.values.all{it.length<=60});prepared("填写二手信息草稿",a){kind=k;title=t;description=d;contacts=cs;"草稿已填写，尚未发布"}},
  surfaceTool("choose_image","打开图片选择器，选择后压缩上传；需用户选择图片","",true){a->require(images.size<4&&!busy);prepared("选择并上传信息图片",a){picker.launch("image/*");"已打开图片选择器，等待选择后压缩上传"}},
  surfaceTool("publish","发布/保存当前草稿到服务器并公开联系方式，consent=true仅在用户明确同意公开时提供","consent",true){a->require(!busy&&title.isNotBlank()&&contacts.isNotEmpty()&&contacts.values.all{it.isNotBlank()}){"请补齐主题和至少一种联系方式"};require(consent||a.optBoolean("consent")){"请先确认同意公开所填联系方式"};val body=JSONObject().put("kind",kind).put("title",title.trim()).put("description",description.trim()).put("contacts",JSONObject(contacts.mapValues{it.value.trim()})).put("images",JSONArray(images));PreparedTool("${if(initial==null)"发布"else"保存"}二手信息",AssistantSurfacePreview.describe(body),{api.request(if(initial==null)"/listings"else"/listings/${initial.getString("id")}",if(initial==null)"POST"else"PUT",body);uploaded=emptySet();val refreshed=runCatching{onSaved()}.isSuccess;"服务器已保存，所列联系方式已公开"+(if(refreshed)""else"；页面暂未刷新，请勿重复发布")})}
 ),priority=10)
 Column(Modifier.fillMaxSize()){
  MarketHeader(if(initial==null)"发布信息"else"编辑信息",!busy,{cancel()}){Text("30 天有效",Modifier.padding(end=10.dp),fontSize=11.sp,color=Muted)}
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=12.dp).padding(bottom=LocalFloatingAiHeight.current+8.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
    Text("类型",fontSize=12.sp,color=Muted);listOf("buy" to "收","sell" to "出").forEach{(value,label)->FilterChip(kind==value,{kind=value},enabled=!busy,label={Text(label)},modifier=Modifier.weight(1f))}
   }
   OutlinedTextField(title,{title=it.take(60)},label={Text("主题")},placeholder={Text(if(kind=="buy")"想收什么？"else"想出什么？")},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth(),shape=AppShape(12.dp))
   OutlinedTextField(description,{description=it.take(3000)},label={Text("详情（选填）")},placeholder={Text("描述物品、价格或预算、交易要求等")},minLines=3,maxLines=6,enabled=!busy,modifier=Modifier.fillMaxWidth(),shape=AppShape(12.dp))
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)){
    Text("联系方式",fontSize=12.sp,fontWeight=FontWeight.SemiBold)
    listOf("QQ","微信","手机").forEach{key->FilterChip(key in contacts,{contacts=if(key in contacts)contacts-key else contacts+(key to "")},enabled=!busy,label={Text(key,fontSize=12.sp)})}
   }
   contacts.forEach{(key,value)->OutlinedTextField(value,{contacts=contacts+(key to it.take(60))},label={Text(key)},singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth(),shape=AppShape(12.dp),keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=if(key=="微信")KeyboardType.Text else KeyboardType.Number))}
   if(contacts.isEmpty())Text("至少选择一种；只公开你填写的联系方式。",fontSize=11.sp,color=Muted)
   Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("图片 · 可选 ${images.size}/4",Modifier.weight(1f),fontSize=12.sp,fontWeight=FontWeight.SemiBold);Text("${used?.let(::storageText)?:"—"} / 10 MB",fontSize=11.sp,color=Muted)}
   LazyRow(horizontalArrangement=Arrangement.spacedBy(7.dp)){
    items(images,key={it}){id->Column{
     MarketPicture(api,id,Modifier.size(78.dp).clip(AppShape(8.dp)))
     TextButton(onClick={if(id in uploaded)task{val result=api.request("/images/$id","DELETE");images=images-id;uploaded=uploaded-id;used=result.getLong("usedBytes")}else images=images-id},enabled=!busy,contentPadding=PaddingValues(0.dp),modifier=Modifier.height(32.dp)){Text("移除",fontSize=11.sp)}
    }}
    if(images.size<4)item{OutlinedButton(onClick={picker.launch("image/*")},enabled=!busy,modifier=Modifier.size(78.dp),shape=AppShape(8.dp),contentPadding=PaddingValues(0.dp)){Text("＋ 图片",fontSize=12.sp)}}
   }
   Text(if(quotaError)"容量暂未读取，上传时仍由服务器校验。"else"上传自动压缩，按账号累计；移除已有图片将在保存后释放空间。",fontSize=10.sp,color=Muted,lineHeight=15.sp)
   Row(verticalAlignment=Alignment.CenterVertically){Checkbox(consent,{consent=it},enabled=!busy);Text("我同意公开以上联系方式",fontSize=12.sp,color=Muted)}
   Button(onClick={task{
    val body=JSONObject().put("kind",kind).put("title",title.trim()).put("description",description.trim()).put("contacts",JSONObject(contacts.mapValues{it.value.trim()})).put("images",JSONArray(images))
    api.request(if(initial==null)"/listings"else"/listings/${initial.getString("id")}",if(initial==null)"POST"else"PUT",body);uploaded=emptySet();onSaved()
   }},enabled=!busy&&consent&&title.isNotBlank()&&contacts.isNotEmpty()&&contacts.values.all{it.isNotBlank()},modifier=Modifier.fillMaxWidth().height(44.dp),shape=AppShape(12.dp),colors=ButtonDefaults.buttonColors(containerColor=Pine)){Text(if(busy)"处理中…"else if(initial==null)"发布"else"保存")}
   Spacer(Modifier.height(6.dp))
  }
 }
}

@Composable private fun MarketPicture(api:CommunityClient,id:String,modifier:Modifier=Modifier,fit:Boolean=false){
 var failed by remember(id){mutableStateOf(false)}
 val bitmap by produceState<android.graphics.Bitmap?>(null,id,api.endpoint){value=api.image(id);failed=value==null}
 Box(modifier.background(Muted.copy(alpha=.08f)),contentAlignment=Alignment.Center){
  if(bitmap!=null)Image(bitmap!!.asImageBitmap(),contentDescription="信息图片",modifier=Modifier.fillMaxSize(),contentScale=if(fit)androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop)
  else Text(if(failed)"图片未载入"else"加载中",fontSize=10.sp,color=Muted)
 }
}

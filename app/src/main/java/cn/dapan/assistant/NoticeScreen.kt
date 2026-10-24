package cn.dapan.assistant

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.*
import java.io.File

@Composable fun SmallTag(text:String,color:androidx.compose.ui.graphics.Color=Pine){Surface(color=color.copy(alpha=.09f),shape=AppShape(7.dp)){Text(text,Modifier.padding(horizontal=8.dp,vertical=4.dp),fontSize=11.sp,color=color)}}
private data class NoticeWebsite(val id:String,val name:String)
private fun website(url:String)=Uri.parse(url).host.orEmpty().lowercase()
private fun NoticeWebsite.shortName()=when(id){
 "panjin.dlut.edu.cn"->"盘锦官网";"pjteach.dlut.edu.cn"->"教学科研";"xsgzb.dlut.edu.cn"->"学生事务";"pjxqtw.dlut.edu.cn"->"盘锦团委"
 "pjcareer.dlut.edu.cn"->"盘锦就业";"pjlib.dlut.edu.cn"->"盘锦图书馆";"pjxqzwb.dlut.edu.cn"->"盘锦总务";"hyxy.dlut.edu.cn"->"化工海洋"
 "business.dlut.edu.cn"->"商学院";"fldpj.dlut.edu.cn"->"公共基础";"dqise.dlut.edu.cn"->"昆士兰学院";"dli.dlut.edu.cn"->"莱斯特学院"
 "teach.dlut.edu.cn"->"教务处";"dutdice.dlut.edu.cn"->"国际交流";"ssc.dlut.edu.cn"->"学生服务";"gs.dlut.edu.cn"->"研究生院"
 "lib.dlut.edu.cn"->"图书馆";"chuangxin.dlut.edu.cn"->"创新创业";"xsc.dlut.edu.cn"->"学生工作";"gjzgb.dlut.edu.cn"->"高教综改"
 else->name
}
@OptIn(ExperimentalFoundationApi::class)
@Composable fun NoticeScreen(onBack:()->Unit,onDiscuss:(String)->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope();val api=remember{CommunityClient(c)}
 val file=remember{File(c.filesDir,"notice-cache.json")}
 var data by rememberPageSnapshot<JSONObject?>("notices"){null};var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf<String?>(null)}
 val presentation=remember{c.getSharedPreferences("notice-presentation",0)}
 var excluded by rememberSaveable{mutableStateOf(arrayListOf<String>())}
 var expanded by rememberSaveable{mutableStateOf(presentation.getBoolean("sources-expanded",true))}
 var showSummary by rememberSaveable{mutableStateOf(presentation.getBoolean("show-summary",true))}
 var query by rememberSaveable{mutableStateOf("")};var search by rememberSaveable{mutableStateOf(false)};var draft by rememberSaveable{mutableStateOf("")}
 var selected by remember{mutableStateOf<JSONObject?>(null)}
 var followed by remember{mutableStateOf(NoticeSubscriptions.followed(c))}
 val permission=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()){granted->
  if(!granted)error="已保存关注；开启系统通知权限后才能提醒"
 }
 fun follow(id:String,enabled:Boolean){
  NoticeSubscriptions.set(c,id,enabled);followed=NoticeSubscriptions.followed(c)
  if(enabled&&android.os.Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
 }
 suspend fun sync(){if(busy)return;busy=true;try{val fresh=api.request("/notices");withContext(Dispatchers.IO){val temp=File(file.parentFile,"notice-cache.tmp");temp.writeText(fresh.toString());check(temp.renameTo(file))};data=fresh;error=null}catch(e:CancellationException){throw e}catch(e:Exception){error="同步未完成，正在显示本机缓存"}finally{busy=false}}
 LaunchedEffect(Unit){data=withContext(Dispatchers.IO){runCatching{JSONObject(if(file.exists())file.readText()else c.assets.open("notices.json").bufferedReader().use{it.readText()})}.getOrNull()};sync()}
 val sites=data?.optJSONArray("sources").objects().groupBy{website(it.optString("url"))}.map{(id,channels)->NoticeWebsite(id,channels.first().optString("name"))}
 val chosen=sites.map{it.id}.filter{it !in excluded}
 val allSelected=sites.isNotEmpty()&&chosen.size==sites.size
 val articles=data?.optJSONArray("items").objects().filter{n->(website(n.optString("url")) in chosen)&&(query.isBlank()||(n.optString("title")+n.optString("summary")+n.optString("headline")).contains(query,true))}.sortedByDescending{it.optString("date")}
 fun open(url:String){runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))}.onFailure{error="无法打开官网，请检查浏览器"}}
 AssistantSurface("官网通知",JSONObject().put("ready",data!=null).put("query",query).put("selected",selected).put("sources",JSONArray(sites.map{JSONObject().put("id",it.id).put("name",it.name)})).put("chosen",JSONArray(chosen)).put("summary",showSummary),listOf(
  surfaceTool("filter","query关键词；sources网站域名数组（空数组=不选来源）；expanded控制来源选择区：false折叠、true展开；summary控制通知正文：false仅标题、true标题加摘要，两者独立，只改用户指定项","query,sources,expanded,summary",false){a->val ids=a.optJSONArray("sources")?.objectsAsText();require(ids==null||ids.all{id->sites.any{it.id==id}}){"来源不存在，请先查询来源列表"};prepared("筛选官网通知",a){if(a.has("query"))query=a.optString("query");if(ids!=null)excluded=ArrayList(sites.map{it.id}.filter{it !in ids});if(a.has("expanded"))expanded=a.getBoolean("expanded");if(a.has("summary"))showSummary=a.getBoolean("summary");"已调整通知筛选"}},
  surfaceTool("select","选择具体通知以便进一步提问","id",false){a->val n=data?.optJSONArray("items").objects().singleOrNull{it.optString("id")==a.text("id")}?:error("通知不在本机缓存，请刷新");prepared("查看 ${n.optString("title")}",a){selected=n;"已打开通知详情"}},
  surfaceTool("refresh","同步官网通知","",false){a->require(!busy);prepared("同步通知",a){sync();check(error==null){error.orEmpty()};"通知已同步"}},
  surfaceTool("follow","关注或取消关注通知来源；source为sources列表中的网站域名，enabled布尔；新通知由系统后台定期检查","source,enabled"){a->val id=a.text("source");require(sites.any{it.id==id}){"请使用来源列表中的网站域名"};val enabled=a.getBoolean("enabled");prepared(if(enabled)"关注 $id"else"取消关注 $id",a){follow(id,enabled);"已${if(enabled)"关注"else"取消关注"}该来源；后台检查时间由系统调度"}},
  surfaceTool("open","打开指定通知的官方原文链接","id",false){a->val n=data?.optJSONArray("items").objects().singleOrNull{it.optString("id")==a.text("id")}?:error("通知不存在");prepared("打开官网原文",a){c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(n.getString("url"))));"已请求浏览器打开原文"}}
 ))
 Column(Modifier.fillMaxSize()){
  Row(Modifier.fillMaxWidth().height(44.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,onBack)
   Text("官网通知",Modifier.weight(1f),fontSize=17.sp,lineHeight=22.sp,fontWeight=FontWeight.SemiBold)
   QuietIconButton("刷新通知",AppGlyph.Refresh,{scope.launch{sync()}},enabled=!busy)
   QuietIconButton(if(query.isBlank())"搜索通知"else"修改搜索：$query",AppGlyph.Search,{draft=query;search=true},color=if(query.isBlank())Muted else DutBlue)
  }
  Column(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
   Row(Modifier.fillMaxWidth().height(30.dp),verticalAlignment=Alignment.CenterVertically){
    Text("来源 ${chosen.size}/${sites.size} ${if(expanded)"▴"else"▾"}",Modifier.weight(1f).clip(AppShape(7.dp)).clickable{expanded=!expanded;presentation.edit().putBoolean("sources-expanded",expanded).apply()}.semantics{contentDescription=if(expanded)"折叠来源选择"else"展开来源选择"}.padding(vertical=6.dp),fontSize=12.sp,color=Ink)
    Text(if(allSelected)"全不选"else"全选",Modifier.clip(AppShape(7.dp)).clickable(enabled=sites.isNotEmpty()){excluded=if(allSelected)ArrayList(sites.map{it.id})else arrayListOf()}.padding(horizontal=10.dp,vertical=5.dp),fontSize=11.sp,color=DutBlue)
    Text(if(showSummary)"标题＋概要"else"仅标题",Modifier.clip(AppShape(7.dp)).background(SoftSurface).toggleable(value=showSummary,role=Role.Switch,onValueChange={showSummary=it;presentation.edit().putBoolean("show-summary",it).apply()}).semantics{contentDescription="显示概要";stateDescription=if(showSummary)"标题和概要"else"仅标题"}.padding(horizontal=9.dp,vertical=5.dp),fontSize=11.sp,color=DutBlue)
   }
   AnimatedVisibility(expanded,enter=expandVertically(),exit=shrinkVertically()){
   Column(verticalArrangement=Arrangement.spacedBy(4.dp)){
    sites.chunked(4).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(5.dp)){
     row.forEach{s->val active=s.id in chosen
      Box(Modifier.weight(1f).height(30.dp).clip(AppShape(8.dp)).background(if(active)DutBlue else SoftSurface).combinedClickable(onClick={excluded=ArrayList(if(active)excluded+s.id else excluded-s.id)},onLongClick={runCatching{follow(s.id,s.id !in followed)}.onFailure{error=it.message}}).semantics{contentDescription=s.name+if(s.id in followed)"，已关注"else"，长按关注";this.selected=active;role=Role.Checkbox},contentAlignment=Alignment.Center){Text((if(s.id in followed)"★ "else"")+s.shortName(),Modifier.padding(horizontal=3.dp),fontSize=11.sp,lineHeight=13.sp,textAlign=TextAlign.Center,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(active)OnAccent else Ink,fontWeight=if(active)FontWeight.SemiBold else FontWeight.Normal)}
     }
     repeat(4-row.size){Spacer(Modifier.weight(1f))}
    }}
    Text("长按关注 / 取消 · 已关注 ${followed.size} 个来源 · 后台定期提醒",fontSize=10.sp,color=Muted)
   }
   }
   Row(Modifier.fillMaxWidth().heightIn(min=24.dp),verticalAlignment=Alignment.CenterVertically){
    Text("${articles.size} 条通知",fontSize=10.sp,color=Muted)
    if(query.isNotBlank()){
     Text(" · $query",Modifier.weight(1f).padding(start=4.dp),fontSize=11.sp,color=DutBlue,maxLines=1,overflow=TextOverflow.Ellipsis)
     Text("清除",Modifier.clickable{query=""}.padding(horizontal=8.dp,vertical=4.dp),fontSize=11.sp,color=Muted)
    }else{Spacer(Modifier.weight(1f));Text(if(busy)"同步中…"else"按发布时间",fontSize=10.sp,color=Muted)}
   }
   if(busy)LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
   error?.let{Text(it,fontSize=11.sp,color=Muted)}
   HorizontalDivider(color=Hairline,thickness=.5.dp)
   val list=rememberLazyListState()
   val filterKey=excluded.sorted().joinToString()+"|"+query
   var previousFilter by rememberSaveable{mutableStateOf(filterKey)}
   LaunchedEffect(filterKey){if(previousFilter!=filterKey){list.scrollToItem(0);previousFilter=filterKey}}
   LazyColumn(Modifier.weight(1f),state=list,contentPadding=PaddingValues(bottom=8.dp+LocalFloatingAiHeight.current)){
    if(articles.isEmpty())item{Text(if(data==null)"正在读取通知…"else if(chosen.isEmpty())"尚未选择来源，点“全选”或展开选择"else"没有符合条件的通知",Modifier.padding(vertical=16.dp),fontSize=13.sp,color=Muted)}
    items(articles,key={it.getString("id")}){n->val ready=n.optString("summaryState")=="ready"
     Column(Modifier.fillMaxWidth().clickable{selected=n}.padding(vertical=9.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
      if(ready){
       Text(n.optString("headline").ifBlank{n.optString("title").ifBlank{"通知"}},fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,color=Ink,maxLines=2,overflow=TextOverflow.Ellipsis)
       if(showSummary&&n.optString("summary").isNotBlank())Row(horizontalArrangement=Arrangement.spacedBy(7.dp)){
        Box(Modifier.width(2.dp).height(16.dp).background(DutBlue.copy(alpha=.3f),AppShape(1.dp)))
        Text(n.optString("summary"),fontSize=13.sp,lineHeight=18.sp,color=Muted,maxLines=3,overflow=TextOverflow.Ellipsis)
       }
      }else Text(n.optString("title").ifBlank{"通知"},fontSize=15.sp,lineHeight=20.sp,fontWeight=FontWeight.SemiBold,color=Ink,maxLines=2,overflow=TextOverflow.Ellipsis)
      Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
       val publisher=sites.firstOrNull{it.id==website(n.optString("url"))}?.shortName()?:n.optString("source")
       Text("$publisher · ${n.optString("date").ifBlank{"日期待核"}}${if(showSummary&&!ready)" · 摘要待生成"else""}",Modifier.weight(1f),fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
       Text("原文 ↗",Modifier.clickable{open(n.getString("url"))}.padding(start=8.dp),fontSize=11.sp,color=DutBlue)
      }
     };HorizontalDivider(color=Hairline,thickness=.5.dp)
    }
   }
  }
 }
 if(search){
  val focus=remember{FocusRequester()}
  fun applySearch(){query=draft.trim();search=false}
  AlertDialog(onDismissRequest={search=false},title={Text("搜索通知",fontSize=18.sp)},text={OutlinedTextField(draft,{draft=it},Modifier.fillMaxWidth().focusRequester(focus),placeholder={Text("标题或摘要关键词",fontSize=14.sp)},singleLine=true,keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={applySearch()}))},confirmButton={TextButton(onClick={applySearch()}){Text("搜索")}},dismissButton={TextButton(onClick={search=false}){Text("取消")}})
  LaunchedEffect(Unit){focus.requestFocus()}
 }
 selected?.let{n->AlertDialog(onDismissRequest={selected=null},title={Text(n.optString("headline").ifBlank{n.optString("title").ifBlank{"通知摘要"}},fontSize=19.sp,fontWeight=FontWeight.Bold)},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
  Text("${n.optString("source")} · ${n.optString("date")}",fontSize=11.sp,color=Muted)
  Text(if(n.optString("summaryState")=="ready")n.optString("summary")else"摘要待生成，请查看官网原文。",fontSize=14.sp,lineHeight=22.sp)
  Text("AI 摘要供快速浏览，具体要求以官网原文为准。",fontSize=11.sp,color=Muted)
  TextButton(onClick={onDiscuss("请帮我理解这条通知。以下只是AI摘要，信息不全时提醒我查看官网。\n${n.optString("summary")}\n${n.getString("url")}");selected=null}){Text("问助手")}
 }},confirmButton={TextButton(onClick={open(n.getString("url"))}){Text("官网原文 ↗")}},dismissButton={TextButton(onClick={selected=null}){Text("关闭")}})}
}

package cn.dapan.assistant

import androidx.compose.foundation.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlinx.coroutines.launch

internal data class NoteRow(val id:String=UUID.randomUUID().toString(), val parent:String="",
    val title:String="", val body:String="", val day:String="", val last:String="",
    val start:String="", val finish:String="", val important:Boolean=false,
    val crossed:Boolean=false, val folded:Boolean=false) {
    fun encode()=JSONObject().put("id",id).put("parent",parent).put("title",title).put("body",body)
        .put("day",day).put("last",last).put("start",start).put("finish",finish)
        .put("important",important).put("crossed",crossed).put("folded",folded)
}

internal fun decode(o:JSONObject)=NoteRow(o.getString("id"),o.optString("parent"),o.getString("title"),
    o.optString("body"),o.optString("day"),o.optString("last"),o.optString("start"),o.optString("finish"),
    o.optBoolean("important"),o.optBoolean("crossed",o.optString("status") in setOf("已结束","未参与")),o.optBoolean("folded"))

internal fun outline(rows:List<NoteRow>, parent:String="", includeFolded:Boolean=false):List<Pair<NoteRow,Int>> {
    val children=rows.groupBy { it.parent }
    val pending=ArrayDeque<Pair<NoteRow,Int>>()
    fun push(id:String,depth:Int) { children[id].orEmpty().asReversed().forEach { pending.addLast(it to depth) } }
    push(parent,0)
    return buildList {
        while(pending.isNotEmpty()) {
            val (row,depth)=pending.removeLast()
            add(row to depth)
            if(includeFolded || !row.folded) push(row.id,depth+1)
        }
    }
}

private fun dateLabel(row:NoteRow):String {
    if(row.day.isBlank()) return "待补日期"
    val first=LocalDate.parse(row.day)
    val end=row.last.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
    val showYear=first.year!=LocalDate.now().year || (end!=null && first.year!=end.year)
    fun label(d:LocalDate)=(if(showYear) "${d.year}年" else "")+"${d.monthValue}月${d.dayOfMonth}日"
    return label(first)+(if(end!=null && end!=first) "–"+label(end) else "")
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable internal fun PlannerNotes(screen:String,onBack:()->Unit) {
    val tree=screen=="大纲"
    val ctx=LocalContext.current
    val storage=remember(ctx,screen){PlannerStorage(ctx,screen)}
    var rows by remember(screen){mutableStateOf(storage.read())}
    fun save(next:List<NoteRow>){storage.save(next);rows=next}
    var draft by remember { mutableStateOf<NoteRow?>(null) }
    var deleting by remember { mutableStateOf<NoteRow?>(null) }
    DisposableEffect(storage){
        var previous=storage.read().associateBy{it.id}
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener { _,name->
            if(name==screen){
                rows=storage.read()
                val current=rows.associateBy{it.id}
                draft?.let{if(previous[it.id]!=current[it.id] || it.parent.isNotBlank() && it.parent !in current) draft=null}
                deleting?.let{if(previous[it.id]!=current[it.id]) deleting=null}
                previous=current
            }
        }
        storage.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose{storage.prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    val visible=remember(rows,tree){ if(tree) outline(rows) else rows.sortedWith(
        compareBy<NoteRow>{it.day.isBlank()}.thenBy{it.day}.thenBy{it.start}).map{it to 0} }
    var quick by remember { mutableStateOf<String?>(null) }
    AssistantSurface(screen,JSONObject().put("selectedId",quick ?: draft?.id.orEmpty()).put("count",rows.size))
    val parents=remember(rows){rows.mapTo(mutableSetOf()){it.parent}}
    val today=LocalDate.now().toString()
    val anchor=visible.indexOfFirst { (r,_)->!r.crossed && (r.day.isBlank() || r.last.ifBlank{r.day}>=today) }
    val list=remember(screen){androidx.compose.foundation.lazy.LazyListState(if(tree) 0 else if(anchor<0) visible.size else anchor)}
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp)) {
        val scope=rememberCoroutineScope()
        val todayIndex=visible.indexOfFirst{it.first.day.isBlank() || it.first.day>=today}.let{if(it<0) visible.size else it}
        if(tree) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
            TextButton(onClick={draft=NoteRow()}){Text("＋ 顶层条目")}
        } else Row(Modifier.fillMaxWidth().height(48.dp),verticalAlignment=Alignment.CenterVertically){
            QuietIconButton("返回",AppGlyph.Back,onBack)
            Text("TTB",Modifier.weight(1f),fontSize=19.sp,fontWeight=FontWeight.SemiBold,color=Ink)
            QuietIconButton("滚动到今天",AppGlyph.Calendar,{scope.launch{list.animateScrollToItem(todayIndex)}})
            QuietIconButton("新建事项",AppGlyph.Add,{draft=NoteRow(day=LocalDate.now().toString())},color=DutBlue)
        }
        if(!tree) GridRow(header=true,left={Text("日期 / 时间",fontSize=13.sp,color=Muted)},right={Text("事项",fontSize=13.sp,color=Muted)})
        if(rows.isEmpty() && tree) Text("点「顶层条目」开始。",Modifier.padding(12.dp),color=Muted)
        BoxWithConstraints(Modifier.fillMaxSize()){
            val tail=if(tree) LocalFloatingAiHeight.current+12.dp else maxHeight
            LazyColumn(state=list,contentPadding=PaddingValues(bottom=tail)) {
                items(visible,key={it.first.id}) { (r,depth)->
                    if(tree){
                        val branch=r.id in parents
                        val angle by animateFloatAsState(if(r.folded) 0f else 90f,label="outline")
                        Row(Modifier.fillMaxWidth().combinedClickable(
                            onClick={if(branch) save(rows.map{if(it.id==r.id) it.copy(folded=!it.folded) else it})},
                            onLongClickLabel="编辑条目",onLongClick={draft=r}
                        ).padding(start=(depth.coerceAtMost(12)*12).dp),verticalAlignment=Alignment.CenterVertically){
                            Box(Modifier.width(20.dp),contentAlignment=Alignment.Center){
                                if(branch) LineIcon(AppGlyph.Next,Modifier.size(12.dp).rotate(angle),Muted)
                                else Text("•",color=Muted,fontSize=12.sp)
                            }
                            Column(Modifier.weight(1f).padding(vertical=7.dp)){
                                Text(r.title,color=Ink,fontWeight=if(depth==0) FontWeight.SemiBold else FontWeight.Normal)

                            }
                            QuietIconButton("添加子项",AppGlyph.Add,{draft=NoteRow(parent=r.id)})
                        }
                    } else {
                        val done=r.crossed
                        val line=if(done) TextDecoration.LineThrough else null
                        val color=if(done) Muted else if(r.important) DutBlue else Ink
                        if(visible.getOrNull(todayIndex)?.first?.id==r.id) TodayBoundary(today)
                        GridRow(highlight=r.important && !done,modifier=Modifier.combinedClickable(onClickLabel="设置重点和划掉",onClick={quick=r.id},onLongClickLabel="编辑事项",onLongClick={draft=r}),
                            left={
                                Text(dateLabel(r),color=color,fontSize=14.sp,fontWeight=FontWeight.Medium,textDecoration=line)
                                if(r.start.isNotBlank()) Text(listOf(r.start,r.finish).filter{it.isNotBlank()}.joinToString("–"),color=Muted,fontSize=12.sp,textDecoration=line)
                            },right={
                                Text(r.title,color=color,fontWeight=if(r.important) FontWeight.Bold else FontWeight.Normal,textDecoration=line)

                            })
                    }
                }
                if(!tree && todayIndex==visible.size) item{TodayBoundary(today);Text("今天起暂无事项",Modifier.padding(12.dp),color=Muted,fontSize=13.sp)}
                else if(!tree && anchor<0) item{Text("暂无待进行事项",Modifier.padding(12.dp),color=Muted,fontSize=13.sp)}
            }
        }
    }
    rows.find{it.id==quick}?.let { r->
        ModalBottomSheet(onDismissRequest={quick=null},containerColor=CardSurface){
            Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=8.dp)){
                Text(r.title,color=Ink,fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                fun update(next:NoteRow){save(rows.map{if(it.id==r.id) next else it})}
                Row(Modifier.fillMaxWidth().clickable{update(r.copy(important=!r.important))}.height(46.dp),verticalAlignment=Alignment.CenterVertically){
                    Checkbox(r.important,null);Spacer(Modifier.width(12.dp));Text("重点事项")
                }
                HorizontalDivider(color=Hairline)
                CrossedOption(r.crossed){update(r.copy(crossed=it));quick=null}
            }
        }
    }
    draft?.let { initial-> key(initial.id) {
        val existing=rows.any{it.id==initial.id}
        NoteEditor(initial,tree,existing,{draft=null},{deleting=initial},{row->
            val next=rows.map { when(it.id){row.id->row;row.parent->it.copy(folded=false);else->it} }
            save(if(existing) next else next+row)
            draft=null
        })
    } }
    deleting?.let { root->
        val ids=outline(rows,root.id,true).map{it.first.id}.toSet()+root.id
        AlertDialog(onDismissRequest={deleting=null},title={Text("删除「${root.title}」？")},
            text=if(ids.size>1) {{Text("其下 ${ids.size-1} 条子项也将删除。")}} else null,
            confirmButton={TextButton(onClick={save(rows.filterNot{it.id in ids});draft=null;deleting=null}){Text("删除")}},
            dismissButton={TextButton(onClick={deleting=null}){Text("取消")}})
    }
}

@Composable private fun NoteEditor(initial:NoteRow,tree:Boolean,existing:Boolean,close:()->Unit,remove:()->Unit,accept:(NoteRow)->Unit) {
    var r by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest=close,title={Text(if(tree) "大纲条目" else "TTB 事项")}, text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(5.dp)) {
            OutlinedTextField(r.title,{r=r.copy(title=it)},label={Text(if(tree)"标题" else "事项")})
            if(!tree) {
                PlannerDateFields(r){r=it}
                Row(verticalAlignment=Alignment.CenterVertically){Text("重点事项",Modifier.weight(1f));Switch(r.important,{r=r.copy(important=it)})}
                CrossedOption(r.crossed){r=r.copy(crossed=it)}
            }
            if(error.isNotEmpty()) Text(error,color=MaterialTheme.colorScheme.error)
            if(existing) TextButton(onClick=remove){Text("删除条目")}
        }
    },dismissButton={TextButton(onClick=close){Text("取消")}},confirmButton={TextButton(onClick={
        try {
            require(r.title.isNotBlank()){"请填写${if(tree)"标题" else "事项"}"}
            fun date(raw:String)=raw.trim().takeIf{it.isNotEmpty()}?.let{
                requireNotNull(runCatching{LocalDate.parse(it)}.getOrNull()){"日期请按 2026-11-07 填写"}
            }
            fun time(raw:String)=raw.trim().takeIf{it.isNotEmpty()}?.let{
                requireNotNull(runCatching{LocalTime.parse(it.padStart(5,'0'))}.getOrNull()?.takeIf{t->t.second==0 && t.nano==0}){"时间请按 09:00 填写"}
            }
            val day=date(r.day);val last=date(r.last);val start=time(r.start);val end=time(r.finish)
            require(tree || day!=null){"请选择日期"}
            require(last==null || !last.isBefore(day)){"结束日期不能早于开始日期"}
            require(end==null || start!=null){"请先填写开始时间"}
            require(end==null || (last!=null && last!=day) || end>start){"结束时间应晚于开始时间"}
            accept(r.copy(title=r.title.trim(),day=day?.toString().orEmpty(),last=last?.toString().orEmpty(),
                start=start?.toString().orEmpty(),finish=end?.toString().orEmpty()))
        } catch(e:IllegalArgumentException){error=e.message.orEmpty()}
    }){Text("保存")}})
}

@Composable private fun GridRow(modifier:Modifier=Modifier,header:Boolean=false,highlight:Boolean=false,left:@Composable ColumnScope.()->Unit,right:@Composable ColumnScope.()->Unit){
    Column(Modifier.fillMaxWidth().background(when{header->SoftSurface;highlight->AccentSurface;else->CardSurface})){
        Row(modifier.fillMaxWidth().height(IntrinsicSize.Min),verticalAlignment=Alignment.CenterVertically){
            Column(Modifier.weight(.34f).padding(horizontal=8.dp,vertical=7.dp),content=left)
            VerticalDivider(thickness=.5.dp,color=Hairline)
            Column(Modifier.weight(.66f).padding(horizontal=10.dp,vertical=7.dp),content=right)
        }
        HorizontalDivider(thickness=.5.dp,color=Hairline)
    }
}

@Composable private fun PlannerDateFields(row:NoteRow,change:(NoteRow)->Unit){
    val ctx=LocalContext.current
    fun date(value:String,apply:(String)->Unit){
        val d=value.takeIf{it.isNotEmpty()}?.let(LocalDate::parse)?:LocalDate.now()
        android.app.DatePickerDialog(ctx,platformDialogTheme,{_,y,m,n->apply(LocalDate.of(y,m+1,n).toString())},d.year,d.monthValue-1,d.dayOfMonth).show()
    }
    fun time(value:String,apply:(String)->Unit){
        val t=value.takeIf{it.isNotEmpty()}?.let(LocalTime::parse)?:LocalTime.now()
        android.app.TimePickerDialog(ctx,platformDialogTheme,{_,h,m->apply(LocalTime.of(h,m).toString())},t.hour,t.minute,true).show()
    }
    run {
        ChoiceLine("开始日期",row.day.ifEmpty{"选择日期"},{date(row.day){change(row.copy(day=it,last=if(row.last.isNotEmpty() && row.last<it) it else row.last))}})
        ChoiceLine("结束日期",row.last.ifEmpty{"同一天"},{date(row.last.ifEmpty{row.day}){change(row.copy(last=it))}},if(row.last.isBlank()) null else ({change(row.copy(last=""))}))
        ChoiceLine("开始时间",row.start.ifEmpty{"不指定"},{time(row.start){change(row.copy(start=it))}},if(row.start.isBlank()) null else ({change(row.copy(start="",finish=""))}))
        if(row.start.isNotEmpty()) ChoiceLine("结束时间",row.finish.ifEmpty{"不指定"},{time(row.finish.ifEmpty{row.start}){change(row.copy(finish=it))}},if(row.finish.isBlank()) null else ({change(row.copy(finish=""))}))
    }
}
@Composable private fun ChoiceLine(label:String,value:String,pick:()->Unit,clear:(()->Unit)?=null){
    Row(verticalAlignment=Alignment.CenterVertically){
        Row(Modifier.weight(1f).heightIn(min=42.dp).clickable(onClick=pick),verticalAlignment=Alignment.CenterVertically){
            Text(label,Modifier.weight(1f),color=Muted,fontSize=14.sp)
            Text(value,color=DutBlue,fontSize=15.sp)
            LineIcon(AppGlyph.Next,Modifier.size(14.dp),Muted)
        }
        if(clear!=null) QuietIconButton("清除"+label,AppGlyph.Close,clear)
    }
}
@Composable private fun CrossedOption(value:Boolean,change:(Boolean)->Unit){
    Row(Modifier.fillMaxWidth().clickable{change(!value)}.height(46.dp),verticalAlignment=Alignment.CenterVertically){
        Checkbox(value,null)
        Spacer(Modifier.width(12.dp))
        Text("划掉")
    }
}

@Composable private fun TodayBoundary(today:String){
    Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
        Text("今天 · "+today.substring(5).replace("-","."),color=DutBlue,fontSize=12.sp,fontWeight=FontWeight.SemiBold)
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(color=DutBlue.copy(alpha=.35f))
    }
}

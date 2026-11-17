package cn.dapan.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

internal class PlannerStorage(context:Context,private val module:String){
    val prefs=context.getSharedPreferences("planner_notes",Context.MODE_PRIVATE)
    fun read():List<NoteRow>{
        val data=JSONArray(prefs.getString(module,"[]"))
        return List(data.length()){decode(data.getJSONObject(it))}
    }
    fun save(rows:List<NoteRow>){
        val data=JSONArray()
        rows.forEach{data.put(it.encode())}
        check(prefs.edit().putString(module,data.toString()).commit()){"保存失败"}
    }
}

private fun path(row:NoteRow,byId:Map<String,NoteRow>):String{
    val titles=mutableListOf<String>()
    val seen=mutableSetOf<String>()
    var current:NoteRow?=row
    while(current!=null && seen.add(current.id)){
        titles+=current.title
        current=byId[current.parent]
    }
    return titles.asReversed().joinToString(" / ")
}

internal fun plannerLookup(rows:List<NoteRow>,module:String,query:String,id:String,offset:Int,root:JSONObject):JSONObject{
    val byId=rows.associateBy{it.id}
    val records=if(module=="大纲") outline(rows,includeFolded=true).map{it.first} else rows.sortedWith(compareBy({it.day.isEmpty()},{it.day},{it.start}))
    val terms=query.trim().split(Regex("\\s+")).filter{it.isNotEmpty()}
    val hits=records.mapNotNull{row->
        if(id.isNotBlank() && row.id!=id) return@mapNotNull null
        val item=JSONObject().put("id",row.id).put("title",row.title)
        val searchable=if(module=="大纲"){
            val name=path(row,byId)
            item.put("path",name).put("parentId",row.parent).put("folded",row.folded)
            name
        }else{
            item.put("day",row.day).put("last",row.last).put("start",row.start).put("finish",row.finish)
                .put("crossed",row.crossed).put("important",row.important)
            listOf(row.title,row.day,row.last,row.start,row.finish,if(row.crossed)"已划掉" else "未划掉",if(row.important)"重点" else "一般").joinToString(" ")
        }
        item.takeIf{terms.all{term->searchable.contains(term,true)}}
    }
    val page=hits.drop(offset).take(50)
    return root.put("totalMatched",hits.size).put("items",JSONArray(page)).put("truncated",offset+page.size<hits.size)
        .put("nextOffset",offset+page.size)
}

internal fun plannerTools(c:Context,module:String):List<SurfaceTool>{
    val tree=module=="大纲"
    val fields=if(tree)"title,parentId,folded" else "title,day,last,start,finish,important,crossed"
    val hints=if(tree)"parentId 为查到的父项ID，空为顶层；folded 控制折叠" else
        "day 日期 YYYY-MM-DD；last 可选结束日期；start/finish 可选 HH:mm；important 标重点，crossed 划掉。更新不提供的字段保持不变，空字符串可清除可选日期/时间"
    fun tool(op:String,description:String,keys:String)=surfaceTool(op,description,keys){args->
        val storage=PlannerStorage(c,module)
        preparePlanner(module,op,args,storage::read,storage::save)
    }
    return listOf(tool("add","新建：title必填；"+(if(tree) "" else "day必填；")+hints,fields),
        tool("edit","修改真实id的条目；"+hints,"id,$fields"),
        tool("delete",if(tree)"删除真实id及其子树" else "删除真实id的事项；划掉请用edit","id")) + if(tree) emptyList() else listOf(
        surfaceTool("import_rows","一次导入整个TTB表格。rows为1到100个对象，字段同add；单端时间不补结束，跨日用last。重复行会跳过。","rows"){args->
            val storage=PlannerStorage(c,module);prepareTtbImport(args,storage::read,storage::save)
        })
}

internal fun preparePlanner(module:String,op:String,a:JSONObject,read:()->List<NoteRow>,save:(List<NoteRow>)->Unit):PreparedTool{
    val rows=read()
    val byId=rows.associateBy{it.id}
    val tree=module=="大纲"
    val old=if(op=="add") null else byId[a.text("id")] ?: error("条目不存在，请重新查询")
    val base=old?:NoteRow()
    val title=if(a.has("title")) a.text("title",500) else base.title
    require(title.isNotBlank()){"请提供条目标题"}
    val branch=if(old!=null && tree) outline(rows,old.id,true).map{it.first} else emptyList()
    val parent=a.optString("parentId",base.parent)
    require(parent.isEmpty() || parent in byId){"父项不存在，请查询后选择"}
    require(parent!=base.id && branch.none{it.id==parent}){"不能移入自身或下级条目"}
    val next=base.copy(title=title,parent=parent,folded=a.optBoolean("folded",base.folded),
        important=a.optBoolean("important",base.important),crossed=a.optBoolean("crossed",base.crossed),
        day=a.optString("day",base.day),last=a.optString("last",base.last),
        start=a.optString("start",base.start),finish=a.optString("finish",base.finish))
    if(!tree && op!="delete") validateTtb(next,old!=null && !a.has("day") && old.day.isBlank())
    val verb=when(op){"add"->"新增";"edit"->"修改";"delete"->"删除";else->error("不支持的操作")}
    val ids=(branch.map{it.id}+base.id).toSet()
    fun describe(row:NoteRow)=if(tree) path(row,byId)+(if(row.folded)"（收起）" else "（展开）") else
        listOf(row.title,row.day.ifBlank{"待补日期"},row.last,row.start,row.finish,if(row.important)"重点" else "一般",if(row.crossed)"已划掉" else "未划掉").filter{it.isNotBlank()}.joinToString(" · ")
    val detail=when(op){
        "delete"->describe(base)+if(branch.isEmpty()) "" else "\n同时删除 ${branch.size} 条子项"
        "edit"->describe(base)+"\n→ "+describe(next)
        else->describe(next)
    }
    val guard={
        check(read()==rows){"条目已变化，请重新查询"}
    }
    return PreparedTool("$verb$module · $title",detail,run={
        guard()
        val result=when(op){
            "delete"->rows.filterNot{it.id in ids}
            else->rows.map{when{
                it.id==base.id->next
                tree && it.id==parent && (old==null || old.parent!=parent)->it.copy(folded=false)
                else->it
            }}+if(old==null) listOf(next) else emptyList()
        }
        save(result)
        "已$verb：$detail"
    },guard=guard,evidence={
        JSONObject().put("module",module).put("operation",op).put("id",base.id).put("title",title).put("details",detail).toString()
    })
}

internal fun prepareTtbImport(args:JSONObject,read:()->List<NoteRow>,save:(List<NoteRow>)->Unit):PreparedTool {
    val input=args.getJSONArray("rows")
    require(input.length() in 1..100){"每批可导入 1–100 条"}
    val before=read()
    fun key(r:NoteRow)=listOf(r.title.trim(),r.day,r.last.ifEmpty{r.day},r.start,r.finish)
    val seen=before.map(::key).toMutableSet()
    val additions=mutableListOf<NoteRow>()
    val fields=setOf("title","day","last","start","finish","important","crossed")
    for(i in 0 until input.length()){
        try{
            val item=input.getJSONObject(i)
            require(item.keys().asSequence().all{it in fields}){"不支持的字段"}
            AssistantToolArguments.validate("TTB","add",item)
            val row=NoteRow(title=item.text("title",500),day=item.getString("day"),
                last=item.optString("last"),start=item.optString("start"),finish=item.optString("finish"),
                important=item.optBoolean("important"),crossed=item.optBoolean("crossed"))
            validateTtb(row,false)
            if(seen.add(key(row))) additions+=row
        }catch(e:Exception){throw IllegalArgumentException("第 ${i+1} 行：${e.message}；整批尚未写入",e)}
    }
    val summary="新增 ${additions.size} 条，跳过 ${input.length()-additions.size} 条重复事项"
    val details=(listOf(summary)+additions.map{r->listOf(r.day,r.last,r.start,r.finish,r.title,
        if(r.crossed)"划掉" else "",if(r.important)"重点" else "").filter{it.isNotBlank()}.joinToString(" · ")}).joinToString("\n")
    val guard={check(read()==before){"TTB 已变化，请重新核对清单"}}
    return PreparedTool("导入 TTB",details,{
        guard()
        if(additions.isNotEmpty()) save(before+additions)
        summary
    },guard,evidence={JSONObject().put("module","TTB").put("operation","import_rows").put("summary",summary)
        .put("records",JSONArray(additions.map{JSONObject().put("id",it.id).put("title",it.title).put("day",it.day)})).toString()})
}

private fun validateTtb(next:NoteRow,allowUndated:Boolean){
        require(next.day.isNotBlank() || allowUndated){"TTB 事项需要日期"}
        fun day(s:String)=s.takeIf{it.isNotBlank()}?.let{LocalDate.parse(it)}
        fun time(s:String)=s.takeIf{it.isNotBlank()}?.let{
            require(it.matches(Regex("[0-2][0-9]:[0-5][0-9]"))){"时间请用 HH:mm"}
            LocalTime.parse(it)
        }
        val first=day(next.day);val last=day(next.last);val start=time(next.start);val end=time(next.finish)
        require(last==null || first!=null && last>=first){"结束日期不能早于开始日期"}
        require(end==null || start!=null && (last!=null && last!=first || end>start)){"结束时间需要晚于开始时间"}
    }

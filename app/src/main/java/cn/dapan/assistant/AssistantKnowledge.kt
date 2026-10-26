package cn.dapan.assistant

import android.content.Context
import org.json.*
import java.io.File

object AssistantKnowledge{
 suspend fun read(c:Context,store:AppStore,module:String,query:String,id:String="",offset:Int=0,limit:Int=10000,history:List<ChatLine> = emptyList()):String{
  val name=AssistantSurfaces.canonical(module);val root=JSONObject().put("module",module).put("query",query).put("deviceTime",java.time.ZonedDateTime.now().toString())
  if(name=="操作记录")return AssistantOperationMemory.lookup(history,id,query,offset,limit)
  if(name=="资料库")return LibraryStore(c).use{lib->
   lib.migrate(allowRemoteIndex=false)
   val result=if(id.isNotBlank())lib.find(id)?.let{LibraryRetrieval.document(it,query,offset,limit)}
    ?:JSONObject().put("module",module).put("error","文件不存在，请重新检索真实 id")
   else LibraryRetrieval.search(lib.searchResults(query,(offset+13).coerceIn(13,500)),query,offset)
   result.put("deviceTime",java.time.ZonedDateTime.now().toString()).toString()
  }
  fun match(text:String)=query.isBlank()||text.contains(query,true)
  val rows:List<JSONObject> = when(name){
   "日历","待办"->store.calendarEntries.filter{(name!="待办"||it.kind==EventKind.TODO)&&(id.isBlank()||it.id==id)&&AssistantRecordSearch.matches(it.title+it.notes+it.location+it.start.toLocalDate(),query)}.map(::entryToJson)
   "信息查询","账单","快递"->store.notes.filter{it.module==name&&(id.isBlank()||it.id==id)&&match(it.title+it.detail+it.group)}.map{JSONObject().put("id",it.id).put("title",it.title).put("detail",it.detail).put("group",it.group).put("created",it.created)}
   "晨签"->{root.put("start",store.morningStart).put("end",store.morningEnd).put("time",store.morningTime.toString()).put("notify",store.morningNotify).put("lastCall",store.morningLastCall).put("lastCallTime",store.morningTime.minusMinutes(1).toString()).put("lastCallIssue",MorningLastCall.issue(c));store.checks.map{JSONObject().put("date",it.date.toString()).put("required",it.required).put("passed",it.passed)}}
   "会议纪要","录音"->{
    val lib=MeetingLibrary(c);val clips=Recordings.clips(c)
    lib.folders(clips).filter{f->
     val selected=clips.filter{it.session==f.id&&(id.isBlank()||id==f.id||id==it.id)}
     (id.isBlank()||f.id==id||selected.isNotEmpty())&&AssistantRecordingSearch.matches(f.title,selected.flatMap{it.lines}.map{it.text},query)
    }.map{f->JSONObject().put("id",f.id).put("title",f.title).put("kind",f.kind).put("summary",lib.summary(f.id)?.text?.take(16000))
     .put("clips",JSONArray(lib.order(f.id,clips).filter{id.isBlank()||id==f.id||it.id==id}.map{clip->
      val lines=clip.lines.filter{AssistantRecordingSearch.includeLine(f.title,it.text,query)}
      JSONObject().put("id",clip.id).put("name",clip.name).put("durationMs",clip.durationMs())
       .put("lines",JSONArray(lines.take(60).map{JSONObject().put("text",it.text).put("start",it.start)})).put("linesTruncated",lines.size>60)
     }))}
   }
   "官网通知"->{val f=File(c.filesDir,"notice-cache.json");val data=JSONObject(if(f.exists())f.readText()else c.assets.open("notices.json").bufferedReader().use{it.readText()});root.put("followed",JSONArray(NoticeSubscriptions.followed(c).toList())).put("sources",data.optJSONArray("sources")).put("cacheModified",f.lastModified());data.optJSONArray("items").objects().filter{(id.isBlank()||it.optString("id")==id)&&match(it.optString("title")+it.optString("headline")+it.optString("summary"))}.sortedByDescending{it.optString("date")}}
   "tibo雷达"->{root.put("cached",ResetRadar.cached(c)).put("notification",ResetRadar.enabled(c));emptyList()}
   "课堂助手"->{ClassroomSession.initialize(c);check(ClassroomSession.ready){"正在读取课堂记录，请稍后重试"};ClassroomSession.lessons.filter{(id.isBlank()||it.id==id)&&match(it.topic+it.raw())}.map{item->JSONObject().put("id",item.id).put("topic",item.topic).put("transcript",item.raw().take(limit)).put("truncated",item.raw().length>limit).put("draftsAreNotSpoken",true).put("mode",ClassroomSession.mode.label).put("prompt",ClassroomSession.prompt).put("automatic",ClassroomSession.automatic).put("drafts",JSONArray(item.drafts.takeLast(3).map{it.text}))}}
   "天气"->{root.put("notifications",JSONArray(WeatherNotice.entries.map{JSONObject().put("period",it.label).put("enabled",WeatherMorning.enabled(c,it)).put("time",WeatherMorning.time(c,it).toString())}));root.put("cachedAt",c.getSharedPreferences("weather-cache",0).getLong("fetched",0)).put("forecast",WeatherData.cached(c));emptyList()}
   "抽签"->{val s=WheelStore(c);root.put("selected",s.selected);s.wheels.filter{(id.isBlank()||it.id==id)&&match(it.name)}.map{JSONObject().put("id",it.id).put("name",it.name).put("options",JSONArray(it.options.map{o->JSONObject().put("id",o.id).put("name",o.name).put("weight",o.weight)}))}}
   "课堂生词"->{root.put("level",VocabularyPreferences(c).level.label).put("listening",VocabularyState.running);VocabularyStore(c).use{it.list(VocabularyPreferences(c).level,query,51)}.map{it.json()}}
   "实时翻译"->TranslationHistory(c).read().filter{(id.isBlank()||it.id==id)&&match(it.text+it.translated)}.takeLast(50).map{JSONObject().put("id",it.id).put("from",it.from).put("to",it.to).put("text",it.text).put("translated",it.translated).put("pending",it.pending).put("error",it.error)}
   "手持弹幕"->{root.put("options",JSONObject(BannerOptions.load(c).json()));emptyList()}
   "专注"->{root.put("minutes",FocusControl.minutes(c)).put("end",store.focusEnd).put("notifications",FocusControl.allowNotifications(c)).put("calls",FocusControl.allowCalls(c));emptyList()}
   "地图"->{root.put("heatRunning",HeatState.running).put("heatStatus",HeatState.status).put("places",JSONArray(CampusData.cached(c).places.keys.toList()));emptyList()}
   "二手信息"->{val api=CommunityClient(c);if(id.isNotBlank()){require(id.matches(Regex("[a-zA-Z0-9-]+")));listOf(api.request("/listings/$id"))}else{val result=api.request("/listings?q="+java.net.URLEncoder.encode(query,"UTF-8"));root.put("hasMore",result.optBoolean("hasMore"));result.optJSONArray("items").objects()}}
   "今天"->{val home=HomeLayout(c);root.put("widgetOrder",JSONArray(home.order.map{it.title})).put("enabled",JSONArray(home.order.filter{it in home.enabled}.map{it.title})).put("noticeScope",home.notices.name);emptyList()}
   "设置","工具","扫描文件"->emptyList()
   else->error("没有这个资料来源")
  }
  root.put("totalMatched",rows.size).put("truncated",rows.size>50).put("items",JSONArray(rows.take(50)))
  val raw=root.toString();return if(raw.length<=60000)raw else JSONObject().put("module",module).put("truncated",true).put("hint","内容较多，请使用更具体的query或id").put("items",JSONArray(rows.take(5).map{JSONObject(it.toString()).also{row->if(row.toString().length>10000){row.remove("clips");row.remove("text");row.remove("summary");row.put("contentTruncated",true)}}})).toString()
 }
}


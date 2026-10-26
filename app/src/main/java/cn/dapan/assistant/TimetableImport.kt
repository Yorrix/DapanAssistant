package cn.dapan.assistant

import org.json.JSONObject
import java.net.URI
import java.time.*
import java.util.UUID

data class TeachingSlot(val start:LocalTime,val end:LocalTime)
data class TeachingTerm(val id:String,val name:String,val monday:LocalDate,val slots:List<TeachingSlot>)
data class TeachingCourse(val code:String,val title:String,val schedule:String)
data class TeachingImportIssue(val index:Int,val code:String,val title:String,val reason:String)
data class TeachingImport(val term:TeachingTerm,val courses:List<TeachingCourse>,val entries:List<CalendarEntry>,val unscheduled:List<String>,val issues:List<TeachingImportIssue> = emptyList())
data class TeachingMerge(val fresh:List<CalendarEntry>,val unchanged:Int,val changed:List<CalendarEntry>)

/** Reads the student's rendered teaching-system pages, never a fixed account or hidden API. */
object TimetableImport {
 const val ENTRY="http://jxgl.dlut.edu.cn/student/ucas-sso/login"
 const val PREFIX="teaching:"
 fun allowedUrl(url:String):Boolean = runCatching {
  val u=URI(url);val host=u.host?.lowercase()
  u.rawUserInfo==null && u.port in listOf(-1,if(u.scheme=="https")443 else 80) &&
   ((u.scheme=="https"&&host in setOf("sso.dlut.edu.cn","jxgl.dlut.edu.cn")) || (u.scheme=="http"&&host=="jxgl.dlut.edu.cn"))
 }.getOrDefault(false)
 fun courseUrl(url:String):Boolean=allowedUrl(url)&&runCatching{val u=URI(url);u.host=="jxgl.dlut.edu.cn"&&u.path.startsWith("/student/for-std/course-table")}.getOrDefault(false)
 private fun clock(s:String):LocalTime {require(s.matches(Regex("\\d{3,4}")));val n=s.toInt();return LocalTime.of(n/100,n%100)}
 fun term(o:JSONObject):TeachingTerm {
  require(o.optString("kind")=="grid"){"请先进入‘我的课表’，等待课表加载完成"}
  val id=o.getString("semester");require(id.matches(Regex("\\d{1,12}")))
  val date=LocalDate.parse(o.getString("startDate"));require(date.dayOfWeek==DayOfWeek.MONDAY){"校历起始日期不是周一，请核对学校课表"}
  val slots=o.getJSONArray("slots").objects().map{TeachingSlot(clock(it.getString("start")),clock(it.getString("end")))}
  require(slots.size in 1..24 && slots.all{it.start<it.end} && slots.zipWithNext().all{(a,b)->a.end<=b.start}){"学校节次时间未完整加载"}
  val name=o.getString("semesterName");require(name.isNotBlank()&&name.length<=100)
  return TeachingTerm(id,name,date,slots)
 }
 fun weeks(text:String):List<Int> {
  val s=text.replace("，",",").replace("（","(").replace("）",")").replace("～","~").replace("－","-").replace("周","").replace(Regex("\\s+"),"")
  require(s.isNotEmpty()){"缺少周次"}
  return s.split(',').flatMap{part->
   val m=Regex("^(\\d{1,2})(?:[~-](\\d{1,2}))?(?:\\(([单双])\\))?$").matchEntire(part)?:error("无法识别周次：$text")
   val a=m.groupValues[1].toInt();val b=m.groupValues[2].ifBlank{m.groupValues[1]}.toInt()
   require(a in 1..60 && b in a..60){"周次超出范围：$text"}
   (a..b).filter{when(m.groupValues[3]){"单"->it%2==1;"双"->it%2==0;else->true}}
  }.distinct().sorted().also{require(it.isNotEmpty()){"周次为空：$text"}}
 }
 private fun unit(s:String):Int {
  s.toIntOrNull()?.let{return it}
  val digits=mapOf('一' to 1,'二' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
  if(s=="十")return 10
  if(s.length==1)return digits[s.single()]?:error("未知节次：$s")
  if(s.startsWith("十")&&s.length==2)return 10+(digits[s.last()]?:error("未知节次：$s"))
  if(s.length in 2..3&&s[1]=='十')return (digits[s[0]]?:error("未知节次：$s"))*10+if(s.length==3)(digits[s[2]]?:error("未知节次：$s"))else 0
  error("未知节次：$s")
 }
 fun read(term:TeachingTerm,o:JSONObject):TeachingImport {
  require(o.optString("kind")=="courses"&&o.optString("semester")==term.id){"学期已改变，请返回课表重新读取"}
  val rows=o.optJSONArray("courses")?:error("学校未返回课程列表，请等待页面加载后重试")
  require(rows.length() in 1..150){"课程列表为空或数量异常，请检查学校页面"}
  val courses=(0 until rows.length()).map{i->val row=rows.optJSONObject(i);TeachingCourse(row?.optString("code").orEmpty(),row?.optString("title").orEmpty(),row?.optString("schedule").orEmpty())}
  val duplicates=courses.groupingBy{it.code}.eachCount().filterValues{it>1}.keys
  val pending=mutableListOf<String>();val result=mutableListOf<CalendarEntry>();val issues=mutableListOf<TeachingImportIssue>()
  courses.forEachIndexed{index,c->
   val reason=when{
    c.code in duplicates->"学校返回了重复的课程编号，请核对后再导入这门课"
    c.schedule.trim()=="不排课"->{pending+=c.title;"学校标记不排课，暂无可导入的时间"}
    c.schedule.isBlank()||c.schedule.trim() in setOf("待安排","未排课","暂无","无","--","-","未安排","待定")->"学校尚未安排上课时间"
    else->null
   }
   if(reason!=null)issues+=TeachingImportIssue(index,c.code,c.title.ifBlank{"第 ${index+1} 门课程"},reason)
   else runCatching{readCourse(term,c)}.onSuccess{result+=it}.onFailure{issues+=TeachingImportIssue(index,c.code,c.title.ifBlank{"第 ${index+1} 门课程"},it.message?.take(180)?:"课程信息不完整")}
  }
  require(result.size<=5000){"课次数量异常，暂不导入"}
  return TeachingImport(term,courses,result.sortedBy{it.start},pending,issues)
 }
 private fun readCourse(term:TeachingTerm,c:TeachingCourse):List<CalendarEntry>{
   val result=mutableListOf<CalendarEntry>()
   require(c.code.matches(Regex("[A-Za-z0-9._-]{1,80}"))&&c.title.isNotBlank()&&c.title.length<=200&&c.schedule.length<=24000){"课程格式异常"}
   val lines=c.schedule.split(Regex("[;；\\n]+")).map{it.trim()}.filter{it.isNotEmpty()}
   require(lines.isNotEmpty()){"${c.title} 缺少排课信息"}
   lines.forEach{line->
    val m=Regex("^(.+?)周\\s+周([一二三四五六日天])\\s+第([一二三四五六七八九十0-9]+)节(?:[~～-]第([一二三四五六七八九十0-9]+)节)?\\s+(.+)$").matchEntire(line)
     ?:error("${c.title} 的排课暂不支持：$line")
    val ws=weeks(m.groupValues[1]);val weekday=if(m.groupValues[2]=="天")7 else "一二三四五六日".indexOf(m.groupValues[2])+1
    val first=unit(m.groupValues[3]);val last=unit(m.groupValues[4].ifBlank{m.groupValues[3]})
    require(first in 1..term.slots.size&&last in first..term.slots.size){"${c.title} 的节次未找到对应时间"}
    // Keep the complete school-provided place/teacher text in notes; do not guess a room from merged grid cards.
    val tail=m.groupValues[5].trim();val location=Regex("^\\S*校区\\s+(\\S+)(?:\\s+.*)?$").matchEntire(tail)?.groupValues?.get(1)?:tail
    ws.forEach{week->
     val date=term.monday.plusWeeks((week-1).toLong()).plusDays((weekday-1).toLong())
     val key="${term.id}|${term.monday}|${c.code}|$date|$first|$last"
     result+=CalendarEntry(id=PREFIX+UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)),title=c.title,
      start=date.atTime(term.slots[first-1].start),end=date.atTime(term.slots[last-1].end),travelMinutes=20,
      reminderMode=ReminderMode.DEPARTURE,category="课程",location=location,
      notes="教务课表 · ${term.name}\n课程编号：${c.code}\n第${week}周 · 第${first}–${last}节\n$tail")
    }
   }
  val grouped=result.groupBy{it.id}
  require(grouped.values.all{it.distinct().size==1}){"同一课程同一时段出现不同教室或教师，请在学校页面核对分组后再导入"}
  return grouped.values.map{it.first()}
 }
 fun merge(values:List<CalendarEntry>,existing:List<CalendarEntry>):TeachingMerge {
  val byId=existing.associateBy{it.id};val fresh=mutableListOf<CalendarEntry>();val changed=mutableListOf<CalendarEntry>();var unchanged=0
  values.forEach{e->val old=byId[e.id]
   if(old!=null){if(old.title==e.title&&old.start==e.start&&old.end==e.end&&old.location==e.location&&old.notes==e.notes)unchanged++ else changed+=e}
   else if(existing.any{it.kind==EventKind.TIMED&&it.title==e.title&&it.start==e.start&&it.end==e.end&&it.location==e.location})unchanged++
   else fresh+=e
  }
  return TeachingMerge(fresh,unchanged,changed)
 }
}

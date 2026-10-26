package cn.dapan.assistant

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/** Keep the current clarification thread; never treat an unrelated old task as consent. */
internal object AssistantConversationContext {
 private val fresh=Regex("^(?:另外|顺便|换个|换一个|再帮我|现在帮我)|记一笔|加个|添加|新建|创建|安排|帮我记|导航|查一下")
 private val follow=Regex("^(?:对|好|可以|行|没问题|确认|就|按|选|用|不是|不对|改成|是的|一[。！.!]?\\s*$|二[。！.!]?\\s*$|三[。！.!]?\\s*$|[123][。！.!]?\\s*$)")
 private val abandoned=Regex("(?:不用|不要|别|先不|先别|不再)(?:再)?管.{0,12}(?:了|那个|这个)|(?:先)?(?:取消|放弃)(?:刚才|之前|上面|那个|这个|这次)|(?:别|不|不要)说这个了|(?:先不|不再)(?:做|安排|处理|继续)(?:这个|那个|刚才|之前)")
 private fun startsNewTask(text:String)=abandoned.containsMatchIn(text)||(fresh.containsMatchIn(text)&&!follow.containsMatchIn(text))
 private fun question(line:ChatLine?)=line?.operation==null&&line?.text?.let{
  it.contains('？')||it.contains('?')||Regex("请(?:告诉|提供|补充|选择)|几点|在哪(?:里|儿)?|哪个|哪一种|哪种|是否|可以吗|好吗").containsMatchIn(it)
 }==true
 private fun threadStart(text:String,history:List<ChatLine>):Int {
  val latest=history.lastOrNull{it.role=="assistant"&&it.includeInModel}
  if(latest?.operation!=null||startsNewTask(text)||(!question(latest)&&!follow.containsMatchIn(text)))return history.size
  var start=history.size;var users=0
  for(index in history.lastIndex downTo (history.size-18).coerceAtLeast(0)){
   val line=history[index]
   if(line.operation!=null)break
   if(line.role!="user")continue
   start=index;users++
   val asked=history.take(index).lastOrNull{it.role=="assistant"&&it.includeInModel}
   if(startsNewTask(line.text)||(!follow.containsMatchIn(line.text)&&!question(asked))||users>=6)break
  }
  return start
 }
 fun userRequest(text:String,history:List<ChatLine>):String {
  return (history.drop(threadStart(text,history)).filter{it.role=="user"}.map{it.text}+text).joinToString("\n")
 }

 /** Only the latest proposal may be approved, and a numbered answer selects one option. */
 fun approvedProposal(text:String,history:List<ChatLine>):String {
  val last=history.lastOrNull{it.role=="assistant"&&it.includeInModel}?:return ""
  if(last.operation!=null||startsNewTask(text))return ""
  return selectedProposal(text,last.text)
 }
 private fun selectedProposal(text:String,proposal:String):String {
  val answer=text.replace(Regex("[\\s，。！？,.!?]"),"")
  val choice=when(answer){"一","1","选一","选1","第一种","第一个","方案一","方案1"->0;"二","2","选二","选2","第二种","第二个","方案二","方案2"->1;"三","3","选三","选3","第三种","第三个","方案三","方案3"->2;else->null}
  val options=Regex("[①②③]|(?:^|[；;\\n])\\s*[123][、.)）]").findAll(proposal).toList()
  if(choice!=null){
   val start=options.getOrNull(choice)?:return ""
   val preamble=proposal.substring(0,options.first().range.first)
   // Keep a shared date such as “明天：①…②…”, but never another option's clock.
   val datePrefix=dateToken.findAll(preamble).lastOrNull()?.value.orEmpty()
   return datePrefix+proposal.substring(start.range.last+1,options.getOrNull(choice+1)?.range?.first?:proposal.length)
  }
  if(OperationReply.parse(text)!=OperationReply.APPROVE||options.size>1)return ""
  return proposal
 }

 private val clockToken=Regex("(?<!\\d)([01]?\\d|2[0-3])[:：]([0-5]\\d)(?!\\d)")
 private val dateToken=Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}月\\d{1,2}[日号]?|大后天|后天|明天|明日|今天|今日|昨天|昨日|(?:下下|下|本|这|上)?(?:周|星期|礼拜)[一二三四五六日天]")
 private fun dateOf(token:String,today:LocalDate):LocalDate? = runCatching{
  when(token){
   "大后天"->today.plusDays(3);"后天"->today.plusDays(2);"明天","明日"->today.plusDays(1);"今天","今日"->today;"昨天","昨日"->today.minusDays(1)
   else->{
    val digits=Regex("\\d+").findAll(token).map{it.value.toInt()}.toList()
    if(digits.size==3)LocalDate.of(digits[0],digits[1],digits[2])
    else if(digits.size==2)LocalDate.of(today.year,digits[0],digits[1])
    else {val weekday="一二三四五六日天".indexOf(token.last()).let{if(it==7)7 else it+1};require(weekday in 1..7)
     val week=when{token.startsWith("下下")->2;token.startsWith("下")->1;token.startsWith("上")->-1;else->0}
     val date=today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(week.toLong()).plusDays(weekday-1L)
     if(week==0&&!token.startsWith("本")&&!token.startsWith("这")&&date<today)date.plusWeeks(1)else date
    }
   }
  }
 }.getOrNull()
 private fun proposedStarts(proposal:String,request:String,today:LocalDate):Set<LocalDateTime> {
  val clocks=clockToken.findAll(proposal).toList()
  data class Candidate(val match:MatchResult,val priority:Int)
  val candidates=clocks.mapIndexedNotNull{index,match->
   val tail=proposal.substring(match.range.last+1,clocks.getOrNull(index+1)?.range?.first?:proposal.length).trimStart()
   val head=proposal.substring(0,match.range.first).substringAfterLast('，').substringAfterLast('。').substringAfterLast('；').substringAfterLast('\n').trimEnd()
   val start=Regex("^(?:开始|起(?:[，。；,;]|$))").containsMatchIn(tail)||Regex("(?:开始(?:时间)?|起始(?:时间)?)(?:为|是|在|设为|定为)?[：:]?\\s*$").containsMatchIn(head)
   val ending=Regex("^(?:结束|下课|聊完|截止)").containsMatchIn(tail)||Regex("(?:结束|下课|截止|到|至|[-～~—])(?:时间)?(?:为|是|在)?[：:]?\\s*$").containsMatchIn(head)
   // A course's start/end can appear in the explanation before the proposed reminder.
   // Prefer the actual proposal (“提醒设在9:45可以吗”) over that reference clock.
   val reminder=Regex("(?:提醒|通知|叫你|叫我)(?:时间)?(?:设在|定在|放在|安排在|设为|定为|在|是|为)?[：:]?\\s*$").containsMatchIn(head)||
    Regex("^\\s*(?:可以吗|行吗|好吗|提醒|通知|叫你|叫我)").containsMatchIn(tail)
   if(ending&&!start&&!reminder)null else Candidate(match,if(reminder)2 else if(start)1 else 0)
  }.let{rows->val priority=rows.maxOfOrNull{it.priority};rows.filter{it.priority==priority}}
  return candidates.mapNotNull{candidate->
   val match=candidate.match
   val token=dateToken.findAll(proposal.substring(0,match.range.first)).lastOrNull()?.value
    ?:dateToken.findAll(request).lastOrNull()?.value
   val day=token?.let{dateOf(it,today)}?:today
   runCatching{day.atTime(match.groupValues[1].toInt(),match.groupValues[2].toInt())}.getOrNull()
  }.toSet()
 }
 /** Carry only actually approved options through subsequent location/notes questions. */
 fun approvedStartTimes(text:String,history:List<ChatLine>,today:LocalDate):Set<LocalDateTime> {
  val begin=threadStart(text,history)
  val request=userRequest(text,history)
  val direct=approvedProposal(text,history)
  val directTimes=proposedStarts(direct,request,today)
  if(directTimes.isNotEmpty())return directTimes
  if(begin==history.size)return emptySet()
  // A new clock or a rejected time is a correction, not an approval of the earlier time.
  if(clockToken.containsMatchIn(text)||Regex("[零一二两三四五六七八九十\\d]+点|(?:不|别|改|换).{0,6}(?:时间|时刻)").containsMatchIn(text))return emptySet()
  for(index in history.lastIndex downTo begin){
   val line=history[index]
   if(line.role!="user")continue
   val proposal=history.subList(begin,index).lastOrNull{it.role=="assistant"&&it.includeInModel}?:continue
   val selected=selectedProposal(line.text,proposal.text)
   val times=proposedStarts(selected,request,today)
   if(times.isNotEmpty())return times
  }
  return emptySet()
 }
 fun approvedTime(text:String,history:List<ChatLine>,time:LocalDateTime,today:LocalDate=LocalDate.now(Billing.zone)):Boolean {
  return time in approvedStartTimes(text,history,today)
 }

 /** A relative time must resolve to one actual event, not a plausible invented clock. */
 private data class Reference(val title:String,val start:Boolean,val position:Int,val end:Int)
 private fun cleanReference(text:String):String {
  var words=dateToken.replace(text,"").trim()
  val prefix=Regex("^(?:就是|等会儿?|待会儿?|请|帮我|提醒我|等到|等|在|当|跟|和|去|那个|这个|第一节|这节|的|就)\\s*")
  while(true){val next=words.replaceFirst(prefix,"");if(next==words)break;words=next}
  return words.removeSuffix("的时候").removeSuffix("的").removeSuffix("课").trim()
 }
 private fun references(request:String):List<Reference> {
  val suffix=Regex("([^，。！？\\n]{1,48}?)(聊完|开完|上完|结束|下课|开始)").findAll(request).mapNotNull{m->
   cleanReference(m.groupValues[1]).takeIf{it.length>=2}?.let{Reference(it,m.groupValues[2]=="开始",m.range.first,m.range.last+1)}
  }
  val prefix=Regex("(?:上完|开完|聊完)([^，。！？\\n]{2,24}?)(以后|之后|后)").findAll(request).mapNotNull{m->
   cleanReference(m.groupValues[1]).takeIf{it.length>=2}?.let{Reference(it,false,m.range.first,m.range.last+1-m.groupValues[2].length)}
  }
  return (suffix+prefix).toList().sortedBy{it.position}
 }
 fun referenceTitle(request:String):String?=references(request).lastOrNull()?.title
 data class TimeReference(val source:CalendarEntry,val time:LocalDateTime)
 fun minuteNumber(text:String):Long? {
  text.toLongOrNull()?.let{return it}
  val digits=mapOf('零' to 0,'〇' to 0,'一' to 1,'二' to 2,'两' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
  if(text.length==1)return if(text=="十")10 else digits[text.single()]?.toLong()
  if(text.count{it=='十'}!=1)return null
  val parts=text.split('十');val tens=if(parts[0].isEmpty())1 else parts[0].singleOrNull()?.let{digits[it]}?:return null
  val ones=if(parts[1].isEmpty())0 else parts[1].singleOrNull()?.let{digits[it]}?:return null
  return (tens*10+ones).toLong()
 }
 /** Resolve against real records, shared by the model context and the local validator. */
 fun timeReferences(request:String,entries:List<CalendarEntry>,today:LocalDate):List<TimeReference> = references(request).flatMap{ref->
  val token=dateToken.findAll(request.substring(0,ref.end)).lastOrNull()?.value
  val day=token?.let{dateOf(it,today)}?:today
  val words=ref.title.removeSuffix("课")
  val matches=entries.filter{it.kind==EventKind.TIMED&&it.start.toLocalDate()==day&&it.title.removeSuffix("课").contains(words,true)}
  // Exact course names take precedence over broader names (e.g. 数学 / 数学辅导).
  val exact=matches.filter{it.title.removeSuffix("课").equals(words,true)}
  val candidates=exact.ifEmpty{matches}
  val tail=request.substring(ref.end).trimStart()
  val number="[0-9零〇一二两三四五六七八九十]+"
  val offset=Regex("^(?:(?:以后|之后|后|前|之前|提前|再过|再等)\\s*($number)\\s*分钟|($number)\\s*分钟(前|后))").find(tail)
  val minutes=if(offset==null)0L else minuteNumber(offset.groupValues[1].ifBlank{offset.groupValues[2]})?:return@flatMap emptyList()
  val before=offset?.value?.let{it.startsWith("前")||it.startsWith("之前")||it.startsWith("提前")||it.endsWith("前")}==true
  candidates.mapNotNull{source->val base=if(ref.start)source.start else source.end;runCatching{TimeReference(source,base.plusMinutes(if(before)-minutes else minutes))}.getOrNull()}
 }.distinctBy{it.source.id to it.time}
 fun anchoredTime(request:String,time:LocalDateTime,entries:List<CalendarEntry>,today:LocalDate):Boolean =
  timeReferences(request,entries,today).singleOrNull()?.time==time
}

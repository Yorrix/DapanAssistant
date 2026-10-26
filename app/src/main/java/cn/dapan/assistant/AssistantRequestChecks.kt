package cn.dapan.assistant

import org.json.JSONObject
import java.time.LocalDate

/** High confidence omissions are rejected, not silently filled from unrelated stored records. */
internal object AssistantRequestChecks {
 private val clock=Regex("[0-9零〇一二两三四五六七八九十半]+\\s*(?:[:：点时]|个?小时后|分钟后)|现在|马上|立即|半小时后|一刻钟后|同一时间|时间不变|跟刚才一样",RegexOption.IGNORE_CASE)
 private val money=Regex("[0-9零〇一二两三四五六七八九十百千万]+(?:[.点][0-9零一二三四五六七八九]+)?\\s*(?:元|块|圆)|[¥￥]\\s*\\d|\\d+(?:\\.\\d+)?")
 fun validate(action:AssistantAction,text:String,history:List<ChatLine>,context:JSONObject,access:String,today:LocalDate=LocalDate.now(Billing.zone),records:AssistantSnapshot?=null){
  val actions=if(action is AssistantAction.Batch)action.actions else listOf(action)
  val request=AssistantConversationContext.userRequest(text,history)
  AssistantNoteAppend.validate(action,request,records)
  MorningRequestChecks.validate(actions,request,today)
  val replacesBill=actions.any{it is AssistantAction.Change&&it.module=="账单"&&it.operation=="delete"}&&actions.any{it is AssistantAction.Note&&it.value.module=="账单"}
  val explicitReplacement=Regex("删|清除|清空|移除|重建|重新建").containsMatchIn(request)&&!Regex("(?:不|别|勿|无需|不用).{0,5}(?:删|清除|清空|移除|重建|重新建)").containsMatchIn(request)
  require(!replacesBill||explicitReplacement){
   "用户没有要求删除账单，不可先删旧账再记一笔。‘不是收入，是午饭花了十八块’可以是本句话的口语纠正：没有明确指向旧账时只新增；明确修改旧账时用update保留记录ID和日期。"
  }
  actions.forEach{a->
   if(a is AssistantAction.Change&&a.operation=="delete"&&a.module=="日历"&&a.targetId.isNotBlank()){
    val selected=records?.calendar?.singleOrNull{it.id==a.targetId}
    if(selected!=null&&records.calendar.count{it.title==selected.title}>1){
     val bareName=Regex("^(?:(?:请|帮我|给我)\\s*)*(?:删掉|删除|取消)\\s*${Regex.escape(selected.title)}(?:那节课|那一节|那条|这节课|这条|课程|日程|事项|课)?[。！!\\s]*$").matches(request)
     val pageId=context.optJSONObject("current")?.optJSONObject("state")?.optString("selectedId").orEmpty()
     val lastOperation=history.lastOrNull{it.role=="assistant"&&it.includeInModel}?.takeIf{it.operation!=null}
     val recentIds=lastOperation?.let{AssistantOperationMemory.receipt(it,1000,0).optJSONArray("records").objects().map{row->row.optString("id")}}.orEmpty()
     require(!bareName||pageId==a.targetId||recentIds.singleOrNull()==a.targetId){"有多条同名的${selected.title}，用户没有指定日期或选中其中一条。不能随意挑选targetId删除；只问要删哪一天的那节课。"}
    }
   }
   if(a is AssistantAction.Tool&&AssistantSurfaces.canonical(a.module)=="资料库"&&a.operation=="save_attachments"){
    val saves=Regex("保存|存进|存到|存一下|存一份|存起来|存下|存好|存这|收藏|收进|放进|放到|放入|归档|加入资料库|收下|收好|收起来|留着")
    val authorized=saves.findAll(request).any{match->
     val prefix=request.substring(0,match.range.first).takeLast(12).substringAfterLast('，').substringAfterLast('。')
     !Regex("(?:不|别|无需|不用|不要|先不).{0,4}$").containsMatchIn(prefix)
    }
    val reading=Regex("读取|阅读|查证|查阅|原文|正文|命名|截止时间|(?:查|找).{0,8}(?:里面|文件|资料|文档)").containsMatchIn(request)
    val declined=Regex("(?:不用|不要|别|先不).{0,3}(?:保存|存进|存到|存下|存了|归档)").containsMatchIn(request)
    require(!declined&&(!reading||authorized)){"这次是查阅资料，不是保存附件；有附件或上次保存过不代表允许再次保存。请lookup资料库并回答当前问题，不重复save_attachments。"}
   }
   if(a is AssistantAction.Change&&a.operation=="complete"&&a.module=="快递"&&
    Regex("删除|删掉|删了|移除").containsMatchIn(request)&&!Regex("(?:不要|不用|别|不).{0,3}(?:删除|删掉|删了|移除)").containsMatchIn(request))
    throw IllegalArgumentException("用户要删除取件码，请用delete；complete只改状态，不能代替删除。")
   if(a is AssistantAction.Reply&&access!="只读"&&AssistantConversationContext.approvedProposal(text,history).isNotBlank())
    require(!Regex("(?:要我|是否|可以).{0,24}(?:建|执行|提交|安排|修改|删除).{0,18}[？?]").containsMatchIn(a.text)){
     "用户已经确认最近方案，请直接生成对应操作；不再问是否执行。若发生新冲突，只说明该冲突。"
    }
   val scopeRequest=request+"\n"+AssistantConversationContext.approvedProposal(text,history)
   if(a is AssistantAction.CalendarBulk)validateCalendarScope(a.from,a.through,scopeRequest,today,a.weekdays)
   if(a is AssistantAction.RenameEvents)validateCalendarScope(a.from,a.through,scopeRequest,today)
   if(a is AssistantAction.Reply&&access!="只读"&&request.contains("晨签")&&Regex("统计|结算|时间").containsMatchIn(request)&&Regex("改|设").containsMatchIn(request)&&clock.containsMatchIn(request)&&!Regex("起止|起点|终点|日期|哪天|多久|怎么").containsMatchIn(request))
    require(!Regex("需要|请告知|请提供").containsMatchIn(a.text)||!Regex("起止|开始日期|结束日期").containsMatchIn(a.text)){
     "只改晨签时间无需起止日期。请用晨签configure仅传time=HH:mm；start/end/notify/lastCall省略后保留原设置，不能追问未要求修改的日期。"
    }
   if(a is AssistantAction.Tool&&AssistantSurfaces.canonical(a.module)=="官网通知"&&a.operation=="filter"&&request.contains("来源")&&!Regex("(?:不|别).{0,3}(?:折叠|收起|展开)").containsMatchIn(request)){
    val expanded=when{Regex("折叠|收起").containsMatchIn(request)->false;request.contains("展开")->true;else->null}
    if(expanded!=null){
     require(a.args.has("expanded")&&a.args.optBoolean("expanded")==expanded){"用户要${if(expanded)"展开"else"折叠"}来源区域，应设expanded=$expanded；summary只控制是否显示通知摘要，不能代替expanded。"}
     require(!a.args.has("summary")||Regex("摘要|概要|标题|正文").containsMatchIn(request)){"用户仅调整来源区域，不要同时改变通知摘要显示。"}
    }
   }
   val writes=when(a){is AssistantAction.Reply,is AssistantAction.Search,is AssistantAction.Lookup,is AssistantAction.Route->false
    is AssistantAction.Navigate->a.module=="扫描文件"
    is AssistantAction.Tool->AssistantToolContract.writes(a,context)
    else->true}
   require(access!="只读"||!writes){"当前为只读，不能生成写入操作。请简短说明不能执行，用户可自行在设置改权限。"}
   if(a is AssistantAction.Event&&a.value.kind==EventKind.TIMED){
    val approved=AssistantConversationContext.approvedStartTimes(text,history,today)
    val referenced=a.timeReference?.let{ref->ref.validate(request,records?.calendar.orEmpty(),today);ref.time(records?.calendar.orEmpty())==a.value.start}==true
    require(if(approved.isNotEmpty())a.value.start in approved else clock.containsMatchIn(request)||
     referenced||AssistantConversationContext.anchoredTime(request,a.value.start,records?.calendar.orEmpty(),today)){
     "开始时间尚未定位：用户若说某个日程结束后，先用真实日历定位该日程并采用其结束时间；已确认的最近方案可直接执行。仅在没有时刻、没有唯一关联日程、也没有已确认方案时，简短询问缺失信息。不要让用户重复已确认的时间。"
    }
    if(a.value.category=="课程"&&!Regex("路程|路上|走路|步行|交通|出发|就地|线上|网课").containsMatchIn(request))
     require(a.value.travelMinutes==20){"课程默认路程20分钟；用户没修改路程。不要因不提醒而删除路程。"}
    if(a.value.category=="课程"&&!Regex("提醒|通知|闹钟|叫我").containsMatchIn(request))
     require(a.value.reminderMode==ReminderMode.DEPARTURE){"课程默认出发提醒；用户未要求取消，须使用DEPARTURE。"}
   }
   if(a is AssistantAction.Note&&a.value.module=="账单"){
    val withoutDates=request.replace(Regex("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|[0-9一二三四五六七八九十]+月[0-9一二三四五六七八九十]+[日号]?|\\d{1,2}[:：]\\d{2}|\\d+[年月日号点时]"),"")
    val contextualAmount=Regex("(?:花了?|花费|付了?|收入|到账|收到|赚了?|工资|补贴|饭钱|餐费|午饭|晚饭|早饭|金额)\\s*[零〇一二两三四五六七八九十百千万]+(?:[，。！？、,\\s]|$)")
    require(money.containsMatchIn(withoutDates)||contextualAmount.containsMatchIn(withoutDates)){"本次记账没有金额，请询问花了/收入多少钱。已有账单的金额不能当作这笔的金额。"}
    val relative=Regex("大前天|前天|昨天|昨日|今天|今日").findAll(request).map{it.value}.distinct().toList().singleOrNull()
    val day=when(relative){"大前天"->today.minusDays(3);"前天"->today.minusDays(2);"昨天","昨日"->today.minusDays(1);"今天","今日"->today;else->null}
    require(day==null||Billing.day(a.value)==day){"这笔账的日期应为 $day，请在note里传date，不能遗漏用户指定的日期或回退成今天。"}
   }
   if(a is AssistantAction.Morning)require(!a.day.date.isAfter(today)){"未来日期不能记录晨签，请解释限制，不要改成今天。"}
   val focusMinutes=when{
    a is AssistantAction.Focus->a.minutes
    a is AssistantAction.Tool&&AssistantSurfaces.canonical(a.module)=="专注"&&a.operation=="configure"&&a.args.has("minutes")->a.args.optInt("minutes")
    else->null
   }
   if(focusMinutes!=null){
    val duration=Regex("(\\d+)\\s*(?:分钟|分鍾|min\\b)",RegexOption.IGNORE_CASE).findAll(request).map{it.groupValues[1].toIntOrNull()}.toList().singleOrNull()
    require(duration==null||focusMinutes==duration){"用户指定专注 $duration 分钟，不可擅自缩短为 $focusMinutes 分钟。支持1～480分钟，超出时说明范围并询问合适时长。"}
   }
   if(a is AssistantAction.Focus&&Regex("来电|电话|通知|勿扰").containsMatchIn(request))
    throw IllegalArgumentException("focus仅支持开始计时，不能承载电话或通知设置。‘专注设40分钟，允许来电但不要其它通知’是配置请求，直接用专注configure(minutes=40,calls=true,notifications=false)，不必询问是否开始；只有明确同时要求开始才说明需分步执行，不能漏掉设置。")
   if(a is AssistantAction.Tool&&AssistantSurfaces.canonical(a.module)=="会议纪要"&&a.operation=="create"&&Regex("开始录音|开始录制|录下来").containsMatchIn(text)&&!Regex("不.{0,3}(开始录音|开始录制)|别.{0,3}(开始录音|开始录制)").containsMatchIn(text))
    throw IllegalArgumentException("用户要求新建并开始录音，create只建记录，会漏掉录音。请使用record_start并提供title和kind，一次完成。")
  }
 }
 private fun validateCalendarScope(from:LocalDate?,through:LocalDate?,request:String,today:LocalDate,weekdays:Set<Int> = emptySet()){
  val universal=Regex("所有|全部|每个|每节|整.*学期|统一|统统|通通|都|全(?=改|删|挪|移|提前|推迟|换|设)")
  val negated=Regex("(?:不要|不用|不必|无需|别|不是).{0,8}(?:所有|全部|整个学期|整学期)")
  val limited=Regex("今天|明天|后天|昨天|前天|下下周|下周|本周|这周|上周|下个星期|这个星期|本星期|下星期|下个月|这个月|本月|下月|上月|\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|[一二三四五六七八九十\\d]{1,3}月|[一二三四五六七八九十\\d]{1,3}[日号]")
  val boundary=limited.findAll(request).lastOrNull{match->
   !Regex("(?:不是|不限于|不只是|不只|不仅)(?:在)?$").containsMatchIn(request.substring(0,match.range.first))
  }
  if(from==null)require(universal.containsMatchIn(request)&&!negated.containsMatchIn(request)&&boundary==null){
   "范围与用户请求不符：只改某次用update；用户已说下周、某天或某月时，用对应date_range，不能用all扩大到整学期。按已给范围修正操作，不要再让用户重复确认。"
  }
  val week=Regex("下下周|下周|下个星期|下星期|本周|这周|这个星期|本星期|上周").findAll(request).lastOrNull()
  if(from==null){
   val mentionedDays=Regex("(?:周|星期|礼拜)([一二三四五六日天])").findAll(request).map{m->"一二三四五六日天".indexOf(m.groupValues[1]).let{if(it==7)7 else it+1}}.toSet()
   require(mentionedDays.isEmpty()||weekdays==mentionedDays){"用户只指定这些星期：$mentionedDays，不能扩大到其他星期。使用calendar_bulk的weekdays限定；仅一次则使用日期范围。"}
  }
  if(from!=null&&week!=null&&boundary!=null){
   val offset=when(week.value){"下下周"->2L;"下周","下个星期","下星期"->1L;"上周"->-1L;else->0L}
   val start=today.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).plusWeeks(offset)
   require(from>=start&&through!=null&&through<=start.plusDays(6)){
    "用户指定${week.value}，日期范围必须在 $start 至 ${start.plusDays(6)} 内，不能包含其他周。"
   }
  }
 }
}

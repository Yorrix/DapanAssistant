package cn.dapan.assistant

import org.json.JSONObject
import java.time.LocalDate

/** High confidence omissions are rejected, not silently filled from unrelated stored records. */
internal object AssistantRequestChecks {
 private val clock=Regex("[0-9零〇一二两三四五六七八九十半]+\\s*(?:[:：点时]|个?小时后|分钟后)|现在|马上|立即|半小时后|一刻钟后|同一时间|时间不变|跟刚才一样",RegexOption.IGNORE_CASE)
 private val money=Regex("[0-9零〇一二两三四五六七八九十百千万]+(?:[.点][0-9零一二三四五六七八九]+)?\\s*(?:元|块|圆)|[¥￥]\\s*\\d|\\d+(?:\\.\\d+)?")
 fun validate(action:AssistantAction,text:String,history:List<ChatLine>,context:JSONObject,access:String,today:LocalDate=LocalDate.now(Billing.zone)){
  val actions=if(action is AssistantAction.Batch)action.actions else listOf(action)
  val latestAssistant=history.lastOrNull{it.includeInModel&&it.role=="assistant"}
  val previousQuestion=latestAssistant?.takeIf{it.operation==null}?.text.orEmpty()
  // A short answer to a question can carry its previous request forward; old receipts cannot.
  val newRequest=Regex("^(?:另外|顺便|再帮|现在帮)|记一笔|加个|添加|新建|创建|安排|帮我记|导航|查一下").containsMatchIn(text)
  val clarification=(previousQuestion.contains('？')||previousQuestion.contains('?'))&&!newRequest
  val prior=if(clarification)history.lastOrNull{it.includeInModel&&it.role=="user"}?.text.orEmpty()else ""
  val request=text+"\n"+prior
  MorningRequestChecks.validate(actions,request,today)
  val replacesBill=actions.any{it is AssistantAction.Change&&it.module=="账单"&&it.operation=="delete"}&&actions.any{it is AssistantAction.Note&&it.value.module=="账单"}
  val explicitReplacement=Regex("删|清除|清空|移除|重建|重新建").containsMatchIn(request)&&!Regex("(?:不|别|勿|无需|不用).{0,5}(?:删|清除|清空|移除|重建|重新建)").containsMatchIn(request)
  require(!replacesBill||explicitReplacement){
   "用户没有要求删除账单，不可先删旧账再记一笔。‘不是收入，是午饭花了十八块’可以是本句话的口语纠正：没有明确指向旧账时只新增；明确修改旧账时用update保留记录ID和日期。"
  }
  actions.forEach{a->
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
    require(clock.containsMatchIn(request)){"用户没有提供开始时刻。日期/上午/下午不是明确开始时间，也不能从已有记录猜测。请只询问几点开始。"}
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
    throw IllegalArgumentException("focus仅支持开始计时，不能承载电话或通知设置。用户是在调整设置时用专注 configure，保留minutes/notifications/calls要求；明确同时开始时询问先设置还是开始，不能漏掉设置。")
   if(a is AssistantAction.Tool&&AssistantSurfaces.canonical(a.module)=="会议纪要"&&a.operation=="create"&&Regex("开始录音|开始录制|录下来").containsMatchIn(text)&&!Regex("不.{0,3}(开始录音|开始录制)|别.{0,3}(开始录音|开始录制)").containsMatchIn(text))
    throw IllegalArgumentException("用户要求新建并开始录音，create只建记录，会漏掉录音。请使用record_start并提供title和kind，一次完成。")
  }
 }
}

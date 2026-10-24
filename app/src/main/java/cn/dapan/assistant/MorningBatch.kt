package cn.dapan.assistant

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Expand a semantic date range locally, never trust a model's enumerated workdays. */
internal object MorningBatch {
 fun plan(state:AssistantSnapshot,a:AssistantAction.MorningRange,today:LocalDate):AssistantPlan {
  require(a.through>=a.from){"晨签结束日期不能早于开始日期"}
  require(a.through<=today){"未来日期不能记录晨签"}
  require(ChronoUnit.DAYS.between(a.from,a.through)<3660){"一次最多调整十年的晨签记录"}
  require(a.dayScope in setOf("workdays","all","required")){"晨签日期规则无效"}
  if(a.dayScope!="all")require((a.from.year..a.through.year).all{year->Holidays.days.keys.any{it.year==year}}){"该年份还没有核验过国家放假及调休安排，不能把普通周一到周五冒充法定工作日"}
  require(!a.setStart||state.morningEnd==null||state.morningEnd>=a.from){"新的统计起点晚于现有终点，请先说明统计终点"}
  val old=state.checks.associateBy{it.date}
  val dates=generateSequence(a.from){it.plusDays(1)}.takeWhile{it<=a.through}.toList()
  val changes=dates.mapNotNull{day->
   val required=when(a.dayScope){"all"->true;"workdays"->Holidays.required(day);else->old[day]?.required?:Holidays.required(day)}
   // A request explicitly excluding rest days also clears erroneous holiday marks.
   if(a.dayScope=="required"&&!required)return@mapNotNull null
   CheckDay(day,required,required&&a.passed)
  }
  val changed=changes.filter{it!=old[it.date]}
  val changedDates=changed.mapTo(hashSetOf()){it.date}
  val newStart=if(a.setStart)a.from else state.morningStart
  val after=state.copy(checks=(state.checks.filterNot{it.date in changedDates}+changed).sortedBy{it.date},morningStart=newStart)
  val rows=mutableListOf<OperationPreview>()
  if(newStart!=state.morningStart)rows+=OperationPreview("修改","晨签统计起点",before=mapOf("起点" to (state.morningStart?.toString()?:"未设置")),after=mapOf("起点" to newStart.toString()),context="统计终点和提醒设置保持不变")
  if(changed.isNotEmpty())rows+=OperationPreview("记录","${a.from} ～ ${a.through}",after=linkedMapOf(
   "日期规则" to when(a.dayScope){"workdays"->"国家工作日（含调休），休息日不计入";"all"->"全部日期";else->"保留逐日是否需要晨签的设置"},
   "结果" to "${changes.count{it.required==true}} 天${if(a.passed)"合格"else"不合格"}"+
    if(a.dayScope=="workdays")"，${changes.count{it.required==false}} 天不计入"else""),context="整段日期由本机计算，一次保存；可撤销恢复原记录",count=changed.size)
  return AssistantPlan(state,after,rows,writes=after!=state,unchanged=changes.size-changed.size)
 }
}

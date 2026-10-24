package cn.dapan.assistant

import java.time.LocalDate

/** Reject incomplete proposals so the model repairs them before a single write occurs. */
internal object MorningRequestChecks {
 private const val number="[0-9零〇一二两三四五六七八九十]+"
 private const val date="(?:(?:[0-9]{4})年)?${number}月${number}[日号]?|[0-9]{4}-[0-9]{2}-[0-9]{2}"
 private fun integer(s:String):Int?=s.toIntOrNull()?:run {
  val digits="零一二三四五六七八九"
  val value=s.replace('〇','零').replace('两','二')
  if('十' in value){val p=value.split('十');(if(p[0].isEmpty())1 else digits.indexOf(p[0].singleOrNull()?:'?').takeIf{it>=0}?:return@run null)*10+(if(p[1].isEmpty())0 else digits.indexOf(p[1].singleOrNull()?:'?').takeIf{it>=0}?:return@run null)}
  else digits.indexOf(value.singleOrNull()?:'?').takeIf{it>=0}
 }
 private fun parse(value:String,today:LocalDate):LocalDate?=runCatching{
  if('-' in value)LocalDate.parse(value)else{
   val m=Regex("(?:([0-9]{4})年)?($number)月($number)[日号]?").matchEntire(value)?:return@runCatching null
   LocalDate.of(m.groupValues[1].toIntOrNull()?:today.year,integer(m.groupValues[2])?:return@runCatching null,integer(m.groupValues[3])?:return@runCatching null)
  }
 }.getOrNull()
 fun validate(actions:List<AssistantAction>,request:String,today:LocalDate){
  if(!request.contains("晨签")&&actions.none{it is AssistantAction.MorningRange||it is AssistantAction.Morning})return
  if(actions.all{it is AssistantAction.Reply||it is AssistantAction.Lookup||it is AssistantAction.Search})return
  val range=Regex("(?:从)?($date)\\s*(?:到|至|—|~|～)\\s*(今天|今日|昨天|昨日|$date)").find(request)
  val bulk=range!=null&&Regex("合格|补签|补上").containsMatchIn(request)&&Regex("都|全部|所有|批量").containsMatchIn(request)
  val proposal=actions.filterIsInstance<AssistantAction.MorningRange>().singleOrNull()
  if(bulk){
   require(proposal!=null){"整段晨签必须使用 morning_range，由本机展开全部日期；不可逐日漏记或只修改统计起点"}
   val from=parse(range!!.groupValues[1],today)
   val through=when(val end=range.groupValues[2]){"今天","今日"->today;"昨天","昨日"->today.minusDays(1);else->parse(end,today)}
   require(from==null||proposal.from==from){"晨签开始日期应为 $from，不可改变范围"}
   require(through==null||proposal.through==through){"晨签结束日期应为 $through，不可截断范围"}
  }
  if(proposal!=null){
   if(Regex("不包(?:含|括)|排除|不算|跳过|除了").containsMatchIn(request)&&Regex("休息日|节假日|假期").containsMatchIn(request))
    require(proposal.dayScope=="workdays"){"用户排除休息日，应使用 dayScope=workdays，按国家假期与调休计算，不能保留旧的错误是否计入设置"}
   val start=Regex("(?:晨签|统计).{0,12}?(?:从)?($date)\\s*(?:开始|起)|(?:统计起点|起始日期).{0,6}?($date)").find(request)
   if(start!=null){
    val expected=parse(start.groupValues.drop(1).first{it.isNotBlank()},today)
    require(proposal.setStart&&(expected==null||proposal.from==expected)){"用户明确要求晨签从 $expected 开始；必须同时 setStart=true 修改统计起点，不能只补记录"}
   }
  }
 }
}

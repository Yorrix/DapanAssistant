package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class AssistantRequestChecksTest {
 private val today=LocalDate.of(2026,10,23)
 private fun check(a:AssistantAction,text:String,history:List<ChatLine> = emptyList(),access:String="完全控制")=
  AssistantRequestChecks.validate(a,text,history,JSONObject().put("pageOperations",AssistantCatalogue.json()),access,today)
 private fun bill(date:LocalDate= today)=AssistantAction.Note(NoteItem(module="账单",title="午饭",detail="18",created=date.atTime(12,0).atZone(Billing.zone).toInstant().toEpochMilli()))
 @Test fun omittedYesterdayMustBeRepaired(){
  assertThrows(IllegalArgumentException::class.java){check(bill(),"昨天午饭18元")}
  check(bill(today.minusDays(1)),"昨天午饭18元")
 }
 @Test fun oldQuestionCannotLeakIntoANewRequest(){
  val history=listOf(ChatLine("user","明天8点开会"),ChatLine("assistant","地点在哪里？"))
  val a=AssistantAction.Event(CalendarEntry(title="自习",start=today.plusDays(1).atTime(9,0)))
  assertThrows(IllegalArgumentException::class.java){check(a,"另外明天加个自习",history)}
 }
 @Test fun shortClarificationKeepsAmountAndMealContext(){
  check(bill(),"十八块",listOf(ChatLine("user","记一笔午饭"),ChatLine("assistant","午饭花了多少钱？")))
  check(bill(),"兼职工资到账二百，记成收入")
 }
 @Test fun dateIsNotAMoneyAmount(){
  assertThrows(IllegalArgumentException::class.java){check(bill(),"记10月23日午饭的钱")}
 }
 @Test fun spokenCorrectionMustNotDeleteAnOlderBill(){
  val replacement=AssistantAction.Batch(listOf(AssistantAction.Change("delete","账单","午饭",JSONObject(),"lunch"),bill()))
  assertThrows(IllegalArgumentException::class.java){check(replacement,"不是收入，是午饭花了十八块")}
  assertThrows(IllegalArgumentException::class.java){check(replacement,"不要删除旧的午饭，再记一笔18元")}
  check(bill(),"不是收入，是午饭花了十八块")
  check(replacement,"删除午饭这笔旧账，再记一笔午饭18元")
  check(AssistantAction.Change("update","账单","午饭",JSONObject().put("detail","20"),"lunch"),"把午饭这笔账改成20元")
 }
 @Test fun focusDurationCannotBeSilentlyClamped(){
  assertThrows(IllegalArgumentException::class.java){check(AssistantAction.Focus(480),"专注900分钟")}
  assertThrows(IllegalArgumentException::class.java){check(AssistantAction.Tool("专注","configure",JSONObject().put("minutes",480)),"专注900分钟")}
  check(AssistantAction.Focus(45),"专注45分钟")
 }
 @Test fun futureMorningIsRelativeToRequestClock(){
  assertThrows(IllegalArgumentException::class.java){check(AssistantAction.Morning(CheckDay(today.plusDays(1),null,true)),"明天合格")}
 }
 @Test fun startingARecordingCannotBecomeOnlyAnEmptyFolder(){
  val a=AssistantAction.Tool("会议纪要","create",JSONObject().put("title","高数").put("kind","课程"))
  assertThrows(IllegalArgumentException::class.java){check(a,"新建高数并开始录音")}
  check(a,"新建高数，别开始录音")
 }
 @Test fun independentReadOnlyNavigationIsAllowedButWritesAreNot(){
  check(AssistantAction.Route("我的位置","F02"),"导航去F02",access="只读")
  assertThrows(IllegalArgumentException::class.java){check(bill(),"记饭钱18",access="只读")}
 }
 @Test fun sourceCollapseCannotBeReplacedWithHidingSummaries(){
  fun filter(args:JSONObject)=AssistantAction.Tool("官网通知","filter",args)
  assertThrows(IllegalArgumentException::class.java){check(filter(JSONObject().put("summary",false)),"把官网通知的来源折叠起来")}
  assertThrows(IllegalArgumentException::class.java){check(filter(JSONObject().put("summary",false).put("expanded",false)),"来源收起")}
  check(filter(JSONObject().put("expanded",false)),"把官网通知的来源折叠起来")
  check(filter(JSONObject().put("expanded",true)),"展开官网通知来源")
  check(filter(JSONObject().put("expanded",false).put("summary",false)),"折叠来源，通知只显示标题")
 }
 @Test fun changingMorningTimeDoesNotRequireUnchangedDateRange(){
  assertThrows(IllegalArgumentException::class.java){check(AssistantAction.Reply("请提供统计起止日期。"),"晨签统计时间改到7点40")}
  check(AssistantAction.Tool("晨签","configure",JSONObject().put("time","07:40")),"晨签统计时间改到7点40")
  check(AssistantAction.Reply("你需要统计的起止日期是什么？"),"晨签起止日期怎么设置")
 }
}

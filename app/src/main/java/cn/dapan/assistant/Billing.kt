package cn.dapan.assistant

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*

/** Retains the original note representation: positive expense, negative income. */
object Billing {
 val zone:ZoneId=ZoneId.of("Asia/Shanghai")
 val expenseCategories=listOf("吃饭","其他")
 val incomeCategories=listOf("生活费","兼职","奖学金","其他")
 fun amount(detail:String):BigDecimal?=runCatching{
  val raw=detail.substringBefore('\n').trim()
  require(raw.length in 1..40)
  val value=raw.toBigDecimal()
  require(value.abs()<=BigDecimal("999999999.99"))
  value.setScale(2,RoundingMode.UNNECESSARY)
 }.getOrNull()
 fun day(note:NoteItem):LocalDate=Instant.ofEpochMilli(note.created).atZone(zone).toLocalDate()
 fun expenseCategory(value:String)=if(value.trim() in setOf("吃饭","餐饮","饮食","早餐","午餐","晚餐","早饭","午饭","晚饭","夜宵","食堂","外卖","零食","饮料","水果"))"吃饭"else"其他"
 fun category(note:NoteItem)=if(amount(note.detail)?.signum()==-1)note.group.takeUnless{it.isBlank()||it=="一般"}?:"收入"else expenseCategory(note.group)
 fun memo(note:NoteItem)=note.detail.substringAfter('\n',"")
 fun money(value:BigDecimal)=value.setScale(2,RoundingMode.UNNECESSARY).toPlainString()
 fun sum(notes:List<NoteItem>,income:Boolean)=notes.mapNotNull{amount(it.detail)}.filter{if(income)it.signum()<0 else it.signum()>=0}.fold(BigDecimal.ZERO){a,b->a+b.abs()}
 fun saved(old:NoteItem,income:Boolean,raw:String,category:String,title:String,memo:String,date:LocalDate):NoteItem{
  val value=amount(raw)?:error("金额最多两位小数，且不超过 999999999.99")
  require(value.signum()>0){"请输入大于 0 的金额"}
  require(category.trim().length in 1..20){"分类需为 1～20 个字"}
  require(title.length<=100&&memo.length<=2000){"名称或备注过长"}
  val signed=if(income)value.negate()else value
  val timestamp=if(day(old)==date)old.created else date.atTime(12,0).atZone(zone).toInstant().toEpochMilli()
  return old.copy(module="账单",title=title.trim().ifBlank{category.trim()},detail=money(signed)+(if(memo.isBlank())""else "\n"+memo.trim()),group=category.trim(),created=timestamp)
 }
 fun csv(notes:List<NoteItem>):String {
  fun cell(s:String):String{val safe=if(s.trimStart().firstOrNull() in listOf('=','+','-','@','\t','\r'))"'"+s else s;return "\""+safe.replace("\"","\"\"")+"\""}
  return "\uFEFF日期,类型,分类,金额（元）,名称,备注\r\n"+notes.joinToString("\r\n"){n->val a=amount(n.detail);listOf(day(n).toString(),if(a==null)"待核对"else if(a.signum()<0)"收入"else"支出",category(n),a?.abs()?.let(::money)?:n.detail.substringBefore('\n'),n.title,memo(n)).joinToString(",",transform=::cell)}
 }
}

package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MorningCalendarDisplayTest {
 private val start=LocalDate.of(2026,10,1)
 private val now=LocalDateTime.of(2026,10,24,12,0)
 private val time=LocalTime.of(7,30)
 private fun display(record:CheckDay?,date:LocalDate=record?.date?:start,rangeStart:LocalDate?=start,rangeEnd:LocalDate?=null,holidays:Map<LocalDate,Boolean> = Holidays.days):MorningStatus {
  val fallback=MorningCalendar.status(date,rangeStart,rangeEnd,now,time,record,holidays)
  return MorningCalendar.displayStatus(record,fallback,holidays)
 }

 @Test fun implicitPassedOnNationalHolidayStaysExcluded(){
  for(day in 1..7){
   val date=LocalDate.of(2026,10,day)
   assertEquals(MorningStatus.EXCLUDED,display(CheckDay(date,null,true)))
   assertEquals(MorningStatus.EXCLUDED,display(CheckDay(date,null,false)))
  }
 }

 @Test fun firstWorkingDayAfterNationalHolidayAndMakeupSaturdayAreIncluded(){
  listOf(LocalDate.of(2026,10,8),LocalDate.of(2026,10,10)).forEach{date->
   assertEquals(MorningStatus.PASSED,display(CheckDay(date,null,true)))
   assertEquals(MorningStatus.FAILED,display(CheckDay(date,null,false)))
  }
 }

 @Test fun missingHolidayEntryFallsBackToWeekday(){
  val monday=LocalDate.of(2026,10,19)
  val sunday=LocalDate.of(2026,10,18)
  assertEquals(MorningStatus.PASSED,display(CheckDay(monday,null,true),holidays=emptyMap()))
  assertEquals(MorningStatus.EXCLUDED,display(CheckDay(sunday,null,true),holidays=emptyMap()))
 }

 @Test fun manualRequiredCanOverrideHolidayAndManualExcludedWinsOverPassed(){
  assertEquals(MorningStatus.PASSED,display(CheckDay(start,true,true)))
  assertEquals(MorningStatus.FAILED,display(CheckDay(start,true,false)))
  assertEquals(MorningStatus.EXCLUDED,display(CheckDay(start,false,true)))
  assertEquals(MorningStatus.EXCLUDED,display(CheckDay(LocalDate.of(2026,10,8),false,true)))
 }

 @Test fun historicalManualMarksOutsideStatisticsRangeStayVisibleButAreNotCounted(){
  val before=CheckDay(start,true,true)
  val after=CheckDay(LocalDate.of(2026,10,20),true,true)
  val rangeStart=LocalDate.of(2026,10,15)
  val rangeEnd=LocalDate.of(2026,10,16)
  assertEquals(MorningStatus.PASSED,display(before,rangeStart=rangeStart,rangeEnd=rangeEnd))
  assertEquals(MorningStatus.PASSED,display(after,rangeStart=rangeStart,rangeEnd=rangeEnd))
  val stats=MorningCalendar.stats(rangeStart,rangeEnd,now,time,listOf(before,after),Holidays.days)
  assertEquals(2,stats.required)
  assertEquals(0,stats.passed)
 }

 @Test fun missingRecordKeepsEffectiveStatusAndFutureDayRemainsUneditable(){
  assertEquals(MorningStatus.EXCLUDED,display(null,start))
  assertEquals(MorningStatus.FAILED,display(null,LocalDate.of(2026,10,8)))
  assertEquals(MorningStatus.EXCLUDED,display(null,start,rangeStart=LocalDate.of(2026,10,15)))
  val tomorrow=now.toLocalDate().plusDays(1)
  assertFalse(MorningCalendar.canMark(tomorrow,now.toLocalDate()))
  assertTrue(MorningCalendar.canMark(now.toLocalDate(),now.toLocalDate()))
 }

 @Test fun implicitRecordsAgreeWithStatisticsAcrossNationalHolidayAndMakeupDay(){
  val end=LocalDate.of(2026,10,11)
  val records=(0L..10L).map{CheckDay(start.plusDays(it),null,true)}
  assertEquals(3,records.count{display(it)==MorningStatus.PASSED})
  val stats=MorningCalendar.stats(start,end,now,time,records,Holidays.days)
  assertEquals(3,stats.required)
  assertEquals(3,stats.passed)
 }
}

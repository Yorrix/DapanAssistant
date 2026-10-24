package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MorningLastCallRulesTest {
 private val zone=ZoneId.of("Asia/Shanghai")
 private val friday=LocalDate.of(2026,10,23)
 private val time=LocalTime.of(7,30)
 private fun now(value:String)=friday.atTime(LocalTime.parse(value)).atZone(zone)
 private fun next(enabled:Boolean=true,start:LocalDate?=friday,end:LocalDate?=null,clock:LocalTime=time,
                  at:ZonedDateTime=now("07:00"),records:List<CheckDay> = emptyList(),holidays:Map<LocalDate,Boolean> = emptyMap(),
                  previous:Long=0,finished:LocalDate?=null)=MorningLastCallRules.next(enabled,start,end,clock,at,records,holidays,previous,finished)

 @Test fun disabledAndNoStatisticsRangeNeverSchedule(){
  assertNull(next(enabled=false));assertNull(next(start=null));assertNull(next(end=friday.minusDays(1)))
 }
 @Test fun warningIsExactlyOneMinuteBeforeSelectedStatisticsTime(){
  val ticket=next()!!
  assertEquals(friday,ticket.date);assertEquals(60_000L,ticket.deadline-ticket.due)
  assertEquals(now("07:29").toInstant().toEpochMilli(),ticket.due)
  val changed=next(clock=LocalTime.of(8,15))!!
  assertEquals(now("08:14").toInstant().toEpochMilli(),changed.due)
 }
 @Test fun weekendAndHolidaySkippedButNationalMakeupDayIncluded(){
  val saturday=friday.plusDays(1)
  assertEquals(friday.plusDays(3),next(at=now("08:00"))!!.date)
  assertEquals(saturday,next(at=now("08:00"),holidays=mapOf(saturday to true))!!.date)
  assertEquals(friday.plusDays(3),next(holidays=mapOf(friday to false))!!.date)
 }
 @Test fun AnyExplicitAnswerOrExclusionSuppressesReminder(){
  listOf(CheckDay(friday,true,true),CheckDay(friday,true,false),CheckDay(friday,false,false)).forEach{record->
   assertEquals(friday.plusDays(3),next(records=listOf(record))!!.date)
  }
 }
 @Test fun endDateIsInclusiveAndFutureStartHonoured(){
  assertEquals(friday,next(end=friday)!!.date)
  assertNull(next(end=friday,at=now("08:00")))
  val monday=friday.plusDays(3)
  assertEquals(monday,next(start=monday,end=monday)!!.date)
 }
 @Test fun freshEnableAfterWarningDoesNotCreateLateCatchup(){
  assertEquals(friday.plusDays(3),next(at=now("07:29:20"))!!.date)
 }
 @Test fun rebootRestoresOnlyWithinExistingFinalMinute(){
  val ticket=next()!!
  assertEquals(ticket,next(at=now("07:29:20"),previous=ticket.due))
  assertEquals(friday.plusDays(3),next(at=now("07:30"),previous=ticket.due)!!.date)
 }
 @Test fun oneDeliveryPerDateEvenWhenClockSettingChanges(){
  assertEquals(friday.plusDays(3),next(at=now("07:29:20"),finished=friday,clock=LocalTime.of(8,0))!!.date)
 }
 @Test fun midnightWarningBelongsToFollowingDay(){
  val monday=friday.plusDays(3)
  val sunday=monday.minusDays(1).atTime(23,58).atZone(zone)
  val ticket=next(start=monday,clock=LocalTime.MIDNIGHT,at=sunday)!!
  assertEquals(monday,ticket.date)
  assertEquals(sunday.plusMinutes(1).toInstant().toEpochMilli(),ticket.due)
 }
 @Test fun receiverRejectsDisabledChangedAnsweredDuplicateAndExpiredCallbacks(){
  val ticket=next()!!
  fun valid(enabled:Boolean=true,clock:LocalTime=time,records:List<CheckDay> = emptyList(),finished:LocalDate?=null,at:Long=ticket.due)=
   MorningLastCallRules.canDeliver(ticket,at,enabled,friday,null,clock,zone,records,emptyMap(),finished)
  assertTrue(valid());assertTrue(valid(at=ticket.deadline-1))
  assertFalse(valid(enabled=false));assertFalse(valid(clock=time.plusMinutes(5)))
  assertFalse(valid(records=listOf(CheckDay(friday,true,true))))
  assertFalse(valid(finished=friday));assertFalse(valid(at=ticket.due-1));assertFalse(valid(at=ticket.deadline))
 }
 @Test fun deletedOrShortenedRangeRejectsPendingDelivery(){
  val ticket=next()!!
  assertFalse(MorningLastCallRules.canDeliver(ticket,ticket.due,true,null,null,time,zone,emptyList(),emptyMap(),null))
  assertFalse(MorningLastCallRules.canDeliver(ticket,ticket.due,true,friday.minusDays(2),friday.minusDays(1),time,zone,emptyList(),emptyMap(),null))
 }
}

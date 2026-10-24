package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ReminderQueueTest {
 private val today=LocalDate.of(2026,10,20)
 private val zone=ZoneId.of("Asia/Shanghai")
 private val time=LocalTime.of(7,30)
 private fun now(time:String)=today.atTime(LocalTime.parse(time)).atZone(zone)
 private fun epoch(time:String)=now(time).toInstant().toEpochMilli()
 private fun task(key:String,due:Long)=ReminderTicket(key,due,due+600000)
 @Test fun oneWakeProcessesCoincidentAndRecentlyDueItems(){
  val tasks=listOf(task("event:a",10000),task("event:b",10000),task("morning",10000),task("weather",10000),task("focus",10500))
  val previous=tasks.associate{it.key to it.due}
  val planned=ReminderQueue.plan(tasks,11000,{previous[it]?:0},{0})
  assertEquals(12000L,ReminderQueue.nextWake(planned))
  assertEquals(tasks,ReminderQueue.due(planned,11000))
 }
 @Test fun wholeSemesterNeedsOnlyNearestSystemWake(){
  val tasks=(1..1500).map{task("event:$it",10000L+it*60000)}
  assertEquals(70000L,ReminderQueue.nextWake(ReminderQueue.plan(tasks,1000,{0},{0})))
 }
 @Test fun successfulBatchMovesToNextReminderWithoutRepeating(){
  val tasks=listOf(task("event:a",10000),task("weather",10000),task("event:b",30000))
  val pending=ReminderQueue.plan(tasks,11000,{10000},{if(it=="event:b")0 else 10000})
  assertTrue(ReminderQueue.due(pending,11000).isEmpty())
  assertEquals(30000L,ReminderQueue.nextWake(pending))
 }
 @Test fun changedCompletedAndExpiredDoNotReappear(){
  val pending=ReminderQueue.plan(listOf(ReminderTicket("expired",10000,11000),task("changed",9000),task("done",10000)),12000,{10000},{if(it=="done")10000 else 0})
  assertTrue(pending.isEmpty())
 }
 @Test fun freshEnableAfterDailyTimeStartsTomorrow(){
  assertEquals(today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli(),ReminderQueue.daily("morning",now("08:00"),time,0,0)!!.due)
 }
 @Test fun backgroundRecoveryKeepsMissedDailyDueUntilMidnight(){
  assertEquals(epoch("07:30"),ReminderQueue.daily("morning",now("08:00"),time,epoch("07:30"),0)!!.due)
 }
 @Test fun sentDailySchedulesTomorrowEvenWithoutOpeningApp(){
  assertEquals(today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli(),ReminderQueue.daily("weather",now("07:30:01"),time,epoch("07:30"),epoch("07:30"))!!.due)
 }
 @Test fun futureStartAndInclusiveEndAreHonoured(){
  val start=today.plusDays(3)
  assertEquals(start.atTime(time).atZone(zone).toInstant().toEpochMilli(),ReminderQueue.daily("morning",now("08:00"),time,0,0,start,start)!!.due)
  assertNull(ReminderQueue.daily("morning",now("08:00"),time,epoch("07:30"),epoch("07:30"),today,today))
 }
 @Test fun calendarMorningLocksAllFutureDaysButKeepsPastAndTodayEditable(){
  assertTrue(MorningCalendar.canMark(today,today))
  assertTrue(MorningCalendar.canMark(today.minusYears(1),today))
  assertFalse(MorningCalendar.canMark(today.plusDays(1),today))
  assertFalse(MorningCalendar.canMark(today.plusMonths(1),today))
 }
 @Test fun historicalFutureMarkStillDoesNotCountBeforeItsDate(){
  val records=listOf(CheckDay(today.plusDays(1),true,true))
  val stats=MorningCalendar.stats(today,today.plusDays(1),today.atTime(8,0),time,records,emptyMap())
  assertEquals(1,stats.required);assertEquals(0,stats.passed)
 }
}

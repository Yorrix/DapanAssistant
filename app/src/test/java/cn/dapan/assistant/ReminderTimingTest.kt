package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class ReminderTimingTest {
 @Test fun resumeAfterDuePreservesUnsentReminder(){assertEquals(12000L,ReminderTiming.target(10000,11000,10000,0,20000))}
 @Test fun postedReminderIsNotRepeatedOnResume(){assertNull(ReminderTiming.target(10000,11000,10000,10000,20000))}
 @Test fun enablingAfterTimeDoesNotInventMissedNotification(){assertNull(ReminderTiming.target(10000,11000,0,0,20000))}
 @Test fun oldReminderIsNotRecoveredAfterExpiry(){assertNull(ReminderTiming.target(10000,20000,10000,0,20000))}
 @Test fun changingTimeDoesNotRecoverPreviousSchedule(){assertNull(ReminderTiming.target(10500,11000,10000,0,20000))}
 @Test fun futureReminderKeepsItsExactDueTime(){assertEquals(15000L,ReminderTiming.target(15000,11000,10000,10000,20000))}
 @Test fun deliveredEarlierTimeDoesNotBlockNewLaterTime(){assertEquals(15000L,ReminderTiming.target(15000,11000,15000,10000,20000))}
}

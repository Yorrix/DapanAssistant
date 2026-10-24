package cn.dapan.assistant

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class EventEditorDraftTest {
    @Test fun switchingFromAllDayOrDeadlineSuppliesValidSameDayDuration() {
        val start = LocalDateTime.of(2026, 10, 23, 0, 0)
        assertEquals(start.plusHours(1), eventEditorTimedEnd(start, start.plusDays(1)))
        assertEquals(start.plusHours(1), eventEditorTimedEnd(start, start))
    }

    @Test fun existingValidDurationIsRetained() {
        val start = LocalDateTime.of(2026, 10, 23, 9, 0)
        assertEquals(start.plusMinutes(95), eventEditorTimedEnd(start, start.plusMinutes(95)))
    }

    @Test fun lateDurationStopsBeforeMidnightWithoutMovingStart() {
        val start = LocalDateTime.of(2026, 10, 23, 23, 30)
        assertEquals(start.withMinute(59), eventEditorTimedEnd(start, start))
        val lastMinute = start.withMinute(59)
        assertEquals(lastMinute, eventEditorTimedEnd(lastMinute, lastMinute.plusDays(1)))
    }
}

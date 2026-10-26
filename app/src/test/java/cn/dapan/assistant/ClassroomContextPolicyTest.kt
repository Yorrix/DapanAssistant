package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
class ClassroomContextTest {
    @Test fun customLimitsPreserveRecentAndCapAllInputs() {
        for (limits in listOf(ClassroomContext.Limits(1, 257), ClassroomContext.Limits(3000, 9000), ClassroomContext.Limits(500, 2000))) {
            val raw = "旧".repeat(20000) + "新".repeat(limits.recent)
            val actual = ClassroomContext.snapshot(raw, "", "摘".repeat(20000), 10000, limits)
            assertTrue(ClassroomContext.count(actual) <= limits.total)
            assertTrue(actual.endsWith("新".repeat(limits.recent)))
            val plan = ClassroomContext.plan(raw, "摘".repeat(20000), 0, limits)!!
            assertTrue(ClassroomContext.count(plan.input) <= limits.total)
            assertTrue(plan.end > plan.start)
            assertTrue(plan.end <= 20000)
        }
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidLimitsAreRejected() { ClassroomContext.Limits(2000, 2000) }

    @Test fun exactly2000NeedsNoCompression() {
        assertNull(ClassroomContext.plan("中".repeat(2000), "", 0))
        assertEquals("中".repeat(2000), ClassroomContext.snapshot("中".repeat(2000), "", "", 0))
    }
    @Test fun onlyOlderCharactersAreCompressed() {
        val p = ClassroomContext.plan("旧".repeat(800)+"新".repeat(2000), "", 0)!!
        assertEquals(800, p.end); assertTrue(p.input.endsWith("旧".repeat(800)))
    }
    @Test fun hugeBacklogNeverExceeds5000AndKeepsRecent2000() {
        val raw = "旧".repeat(30000)+"新".repeat(1900)
        val actual = ClassroomContext.snapshot(raw, "末".repeat(100), "摘".repeat(2800), 10000)
        assertTrue(ClassroomContext.count(actual) <= 5000)
        assertTrue(actual.endsWith("新".repeat(1900)+"末".repeat(100)))
        assertTrue(actual.contains("摘".repeat(2800)))
    }
    @Test fun failedCompressionStillHasHardLimit() {
        val raw = "旧".repeat(20000)+"新".repeat(2000)
        val actual = ClassroomContext.snapshot(raw, "", "", 0)
        assertTrue(ClassroomContext.count(actual) <= 5000); assertTrue(actual.endsWith("新".repeat(2000)))
    }
    @Test fun compressionInputIsAlsoCappedAndCursorAdvances() {
        val raw = "旧".repeat(20000)+"新".repeat(2000)
        val p = ClassroomContext.plan(raw, "摘".repeat(2800), 1000)!!
        assertTrue(ClassroomContext.count(p.input) <= 5000); assertEquals(1000, p.start)
        assertTrue(p.end > p.start); assertTrue(p.end <= 20000)
    }
    @Test fun unicodeCharactersDoNotSplitSurrogatePairs() {
        assertEquals(1, ClassroomContext.count("😀")); assertEquals("😀", ClassroomContext.tail("中😀", 1))
        val actual = ClassroomContext.snapshot("😀".repeat(2200), "", "", 0)
        assertTrue(actual.endsWith("😀".repeat(2000))); assertTrue(ClassroomContext.count(actual) <= 5000)
    }
    @Test fun shortUncompressedInputRetainsEverything() {
        assertEquals("老师提问？", ClassroomContext.snapshot("老师", "提问？", "", 0))
    }
    @Test fun noRepeatedCompressionWhenCaughtUp() {
        assertNull(ClassroomContext.plan("中".repeat(5000), "摘要", 3000))
    }
    @Test fun manualActionRejectsOldDetection() {
        val g = ClassroomIntentGate(); g.toggle(true); val old = g.revision; g.invalidate()
        assertFalse(g.accept(old, "question")); assertTrue(g.accept(g.revision, "new-question"))
    }
    @Test fun disablingAndReenablingRejectsInFlightResult() {
        val g = ClassroomIntentGate(); g.toggle(true); val old = g.revision; g.toggle(false); g.toggle(true)
        assertFalse(g.accept(old, "q"))
    }
    @Test fun duplicateFinalSentenceCannotGenerateTwice() {
        val g = ClassroomIntentGate(); g.toggle(true)
        assertTrue(g.accept(g.revision, "q")); assertFalse(g.accept(g.revision, "q"))
    }
    @Test fun clearInvalidatesOldResultsAndAllowsNewSession() {
        val g = ClassroomIntentGate(); g.toggle(true); val old = g.revision; g.reset()
        assertFalse(g.accept(old, "q")); assertTrue(g.accept(g.revision, "q"))
    }
}


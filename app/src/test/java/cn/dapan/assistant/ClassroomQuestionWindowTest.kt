package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class ClassroomQuestionWindowTest {
    @Test fun joinsQuestionAcrossAsrSegments() {
        val w = ClassroomQuestionWindow()
        w.append("请解释一下"); w.append("这两者有什么区别？")
        assertEquals("请解释一下\n这两者有什么区别？", w.snapshot(100).joinToString("\n") { it.text })
    }
    @Test fun trimsByUnicodeCharactersAndPreservesPosition() {
        val w = ClassroomQuestionWindow(); w.append("旧".repeat(200)); w.append("😀".repeat(30))
        val snapshot = w.snapshot(50)
        assertEquals(50, ClassroomContext.count(snapshot.joinToString("\n") { it.text }))
        assertEquals("😀".repeat(30), snapshot.last().text)
        assertEquals(listOf(1, 2), snapshot.map { it.number })
    }
    @Test fun slidingWindowKeepsStableDedupKey() {
        val w = ClassroomQuestionWindow(); w.append("为什么？")
        val key = ClassroomQuestionWindow.questionKey(w.snapshot(100), 1)
        w.append("谁来说一下。")
        assertEquals(key, ClassroomQuestionWindow.questionKey(w.snapshot(100), 1))
        w.append("为什么？")
        assertNotEquals(key, ClassroomQuestionWindow.questionKey(w.snapshot(100), 3))
        assertNull(ClassroomQuestionWindow.questionKey(w.snapshot(100), 99))
    }
    @Test fun growingSettingUsesRetainedRecentHistory() {
        val w = ClassroomQuestionWindow(); w.append("旧".repeat(10000)); w.append("新".repeat(80))
        assertEquals(100, ClassroomContext.count(w.snapshot(100).joinToString("\n") { it.text }))
        assertEquals(2000, ClassroomContext.count(w.snapshot(2000).joinToString("\n") { it.text }))
        w.clear(); assertTrue(w.snapshot(100).isEmpty()); assertEquals(0, w.latestNumber)
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooSmallWindow() { ClassroomQuestionWindow.validate(19) }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooLargeWindow() { ClassroomQuestionWindow.validate(2001) }
}

package cn.dapan.assistant

class ClassroomQuestionWindow {
    data class Segment(val number: Int, val text: String)
    private val segments = mutableListOf<Segment>()
    private var serial = 0
    val latestNumber get() = serial
    fun append(text: String) {
        if (text.isBlank()) return
        segments.add(Segment(++serial, text.trim()))
        val retained = snapshot(MAX)
        segments.clear(); segments.addAll(retained)
    }
    fun snapshot(chars: Int): List<Segment> {
        validate(chars)
        var remaining = chars
        val result = mutableListOf<Segment>()
        for (segment in segments.asReversed()) {
            if (remaining <= 0) break
            val text = ClassroomContext.tail(segment.text, remaining)
            result.add(segment.copy(text = text))
            remaining -= ClassroomContext.count(text) + 1
        }
        return result.asReversed().toList()
    }
    fun clear() { segments.clear(); serial = 0 }
    companion object {
        const val DEFAULT = 100
        const val MIN = 20
        const val MAX = 2000
        fun validate(chars: Int) {
            require(chars in MIN..MAX) { "提问检测窗口请输入 20～2000 之间的整数" }
        }
        fun questionKey(window: List<Segment>, endSegment: Int): String? =
            window.firstOrNull { it.number == endSegment }?.let { "question-end-${it.number}" }
    }
}

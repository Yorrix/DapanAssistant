package cn.dapan.assistant

object ClassroomContext {
    const val RECENT = 2000
    const val TOTAL = 5000
    const val SUMMARY = 2800
    data class Limits(val recent: Int = RECENT, val total: Int = TOTAL) {
        init {
            require(recent > 0 && total <= 100000 && total.toLong() >= recent.toLong() + 256) {
                "原文字数需大于 0；总字数至少比原文多 256，且不超过 100000"
            }
        }
        val summary get() = total - recent - 200
    }
    fun count(s: String) = s.codePointCount(0, s.length)
    fun head(s: String, n: Int) = s.substring(0, s.offsetByCodePoints(0, n.coerceIn(0, count(s))))
    fun drop(s: String, n: Int) = s.substring(s.offsetByCodePoints(0, n.coerceIn(0, count(s))))
    fun tail(s: String, n: Int) = drop(s, (count(s)-n).coerceAtLeast(0))
    data class Plan(val start: Int, val end: Int, val input: String)
    fun plan(raw: String, summary: String, cursor: Int, limits: Limits = Limits()): Plan? {
        val available = count(raw)-limits.recent-cursor
        if (available <= 0) return null
        val prefix = "[已有摘要]\n${head(summary, limits.summary)}\n[新增历史原文]\n"
        val amount = minOf(available, limits.total-count(prefix))
        return Plan(cursor, cursor+amount, prefix+head(drop(raw, cursor), amount))
    }
    fun snapshot(raw: String, partial: String, summary: String, cursor: Int, limits: Limits = Limits()): String {
        val all = raw + partial
        val recent = tail(all, limits.recent)
        if (count(all) <= limits.recent) return recent
        val oldEnd = count(all)-count(recent)
        val pending = head(drop(all, cursor), (oldEnd-cursor).coerceAtLeast(0))
        val prefix = if (summary.isBlank()) "" else "[历史摘要]\n${head(summary, limits.summary)}\n"
        val suffix = "[最近原文，末尾可能为暂定转写]\n$recent"
        val tag = "[待压缩历史片段]\n"
        val room = (limits.total-count(prefix)-count(suffix)-count(tag)-1).coerceAtLeast(0)
        val middle = if (pending.isNotEmpty() && room > 0) tag+tail(pending, room)+"\n" else ""
        return prefix+middle+suffix
    }
}

class ClassroomIntentGate {
    var enabled = false; private set
    var revision = 0L; private set
    var handledThrough = 0; private set
    private val handled = mutableSetOf<String>()
    fun toggle(value: Boolean, through: Int = handledThrough) { enabled = value; revision++; handledThrough=maxOf(handledThrough,through) }
    fun invalidate() { revision++ }
    fun reset() { revision++; handled.clear(); handledThrough=0 }
    fun consumed(through:Int) { revision++; handledThrough=maxOf(handledThrough,through) }
    fun accept(ticket: Long, id: String, end:Int?=null): Boolean {
        if(!enabled||ticket!=revision||(end!=null&&end<=handledThrough)||!handled.add(id))return false
        if(end!=null)handledThrough=end
        return true
    }
}

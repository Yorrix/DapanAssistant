package cn.dapan.assistant

import org.junit.Test
import org.junit.Assert.*

class ConversationCompressionTest {
    @Test fun boundedHistoryKeepsAttachmentTableForFollowup(){
        val history=listOf(ChatLine("user","[附件：示例.jpg · 图片]"),ChatLine("assistant","|日期|事项|\n|11月3日|学院宣讲|"))+
            (1..40).map{ChatLine("user","第 $it 次操作"+"。".repeat(900))}
        val messages=ConversationCompression.messages(history,"全都导入")
        val content=messages.joinToString{it.getString("content")}
        assertTrue(content.contains("学院宣讲"))
        assertTrue(content.contains("第 40 次"))
        assertTrue(content.length<20000)
    }
    @Test fun manualCompressionKeepsCorrectionsAndSubsequentMessages(){
        val history=listOf(ChatLine("user","考试改为周五下午三点"),ChatLine("assistant",ConversationCompression.MARKER),ChatLine("user","改成四点"))
        val messages=ConversationCompression.messages(history,"好的")
        assertTrue(messages.first().getString("content").contains("周五下午三点"))
        assertEquals("改成四点",messages.last().getString("content"))
    }
}

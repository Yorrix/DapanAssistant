package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
class ClassroomRulesTest {
 @Test fun discussionDoesNotWaitForATeacherQuestion(){val p=ClassroomRules.prompt(ClassroomMode.DISCUSSION,"",4);assertTrue(p.contains("前面同学"));assertTrue(p.contains("不简单复述"));assertFalse(p.contains("找到老师最近提出的问题"))}
 @Test fun customizationIsUsedAndEvidenceBoundaryIsKept(){val p=ClassroomRules.prompt(ClassroomMode.CUSTOM,"用英文解释数学推理",6);assertTrue(p.contains("用英文解释数学推理"));assertTrue(p.contains("不能编造"));assertTrue(p.contains("资料"))}
 @Test fun modesHaveDifferentPrompts(){assertEquals(4,ClassroomMode.entries.map{it.prompt}.distinct().size);assertTrue(ClassroomMode.GENERAL.prompt.contains("不套思政内容"))}
 @Test fun draftsStaySeparateFromRealTranscriptAcrossPersistence(){val row=ClassroomLesson(topic="人工智能与学习",lines=listOf(ClassroomLine(speaker="同学甲",text="先核对来源")),drafts=listOf(ClassroomDraft(text="我想补充验证方法",mode="讨论发言")));val restored=ClassroomLesson.read(row.json());assertEquals(row,restored);assertFalse(restored.raw().contains("我想补充"));assertTrue(ClassroomRules.input(restored,"","",ClassroomContext.Limits()).contains("不代表已经发言"))}
 @Test fun previousDraftsAreLimitedAndDoNotReplaceTheCurrentQuestion(){val row=ClassroomLesson(drafts=(1..8).map{ClassroomDraft(text="草稿-$it",mode="自定义")});val input=ClassroomRules.input(row,"","请解释这个反例",ClassroomContext.Limits());assertTrue(input.contains("请解释这个反例"));assertFalse(input.contains("草稿-1"));assertTrue(input.contains("草稿-8"))}
}

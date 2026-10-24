package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class ComposerRulesTest {
 @Test fun speechInsertsWithoutReplacingExistingDraft(){assertEquals("明天下午去上课" to 4,ComposerRules.insert("明天去上课",2,"下午"))}
 @Test fun absentCaretAppendsAndPreservesEmoji(){val text="记得📚";assertEquals((text+"带书") to (text.length+2),ComposerRules.insert(text,null,"带书"))}
 @Test fun changedDraftClampsStaleCaret(){assertEquals("好呀" to 2,ComposerRules.insert("好",99,"呀"));assertEquals("先好" to 1,ComposerRules.insert("好",-1,"先"))}
}

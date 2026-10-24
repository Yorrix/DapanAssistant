package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class AsrHotwordRulesTest {
 @Test fun defaultsIncludeConfirmedCampusCard(){assertTrue("玉兰卡" in AsrHotwordRules.defaults);assertFalse("预览卡" in AsrHotwordRules.defaults);assertNull(AsrHotwordRules.error(AsrHotwordRules.defaults))}
 @Test fun editorAcceptsNewlinesAndChineseSeparatorsWithoutDuplicatingWords(){assertEquals(listOf("玉兰卡","A01","linear algebra"),AsrHotwordRules.parse("玉兰卡\n A01，a01；玉兰卡、linear   algebra"))}
 @Test fun requestContainsVocabularyAndFeatureContextInTheOfficialField(){
  val input=AsrHints.input("讲授线性代数",listOf("玉兰卡","特征向量"))
  val messages=input.getJSONArray("context");assertEquals(1,messages.length())
  val turn=messages.getJSONObject(0);assertEquals("user",turn.getString("role"))
  val content=turn.getJSONArray("content").getJSONObject(0);assertEquals("input_text",content.getString("type"))
  val text=content.getString("text");assertTrue(text.contains("玉兰卡"));assertTrue(text.contains("特征向量"));assertTrue(text.contains("讲授线性代数"))
  assertFalse(input.has("vocabulary_id"))
 }
 @Test fun totalContextNeverExceeds400CharactersAndKeepsHotwords(){
  val text=AsrHotwordRules.context("讲授术语".repeat(250),AsrHotwordRules.defaults)
  assertEquals(400,text.codePointCount(0,text.length));assertTrue(text.startsWith("常用词：玉兰卡"));assertTrue(text.contains("当前场景：讲授术语"))
 }
 @Test fun emptyOrDisabledWordsLeaveOnlyFeatureContext(){assertEquals("线性代数",AsrHotwordRules.context(" 线性代数 ",emptyList()));assertEquals(0,AsrHints.input("",emptyList()).length())}
 @Test fun oversizedWordsAreRejectedBeforeSaving(){assertNotNull(AsrHotwordRules.error(listOf("词".repeat(41))));assertNotNull(AsrHotwordRules.error((1..30).map{"词".repeat(8)+it})) }
 @Test fun characterLimitDoesNotSplitSurrogatePairs(){
  val source="😀".repeat(500);val text=AsrHotwordRules.context(source,emptyList())
  assertEquals(400,text.codePointCount(0,text.length));assertFalse(Character.isHighSurrogate(text.last()))
 }
}

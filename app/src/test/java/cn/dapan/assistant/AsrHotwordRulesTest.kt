package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class AsrHotwordRulesTest {
 @Test fun defaultsIncludeConfirmedCampusCard(){assertTrue("玉兰卡" in AsrHotwordRules.defaults);assertFalse("预览卡" in AsrHotwordRules.defaults);assertNull(AsrHotwordRules.error(AsrHotwordRules.defaults))}
 @Test fun editorAcceptsNewlinesAndChineseSeparatorsWithoutDuplicatingWords(){assertEquals(listOf("玉兰卡","A01","linear algebra"),AsrHotwordRules.parse("玉兰卡\n A01，a01；玉兰卡、linear   algebra"))}
 @Test fun wordsMustNotBePresentedAsAlreadySpokenContext(){
  val input=AsrHints.input("讲授线性代数",listOf("玉兰卡","特征向量"))
  val messages=input.getJSONArray("context");assertEquals(1,messages.length())
  val turn=messages.getJSONObject(0);assertEquals("user",turn.getString("role"))
  val content=turn.getJSONArray("content").getJSONObject(0);assertEquals("input_text",content.getString("type"))
  val text=content.getString("text");assertFalse(text.contains("玉兰卡"));assertFalse(text.contains("特征向量"));assertEquals("讲授线性代数",text)
  assertFalse(input.has("vocabulary_id"))
 }
 @Test fun featureContextHasItsOwn400CharacterBudget(){
  val text=AsrHotwordRules.context("讲授术语".repeat(250),AsrHotwordRules.defaults)
  assertEquals(400,text.codePointCount(0,text.length));assertTrue(text.startsWith("讲授术语"));assertFalse(text.contains("玉兰卡"))
 }
 @Test fun emptyOrDisabledWordsLeaveOnlyFeatureContext(){assertEquals("线性代数",AsrHotwordRules.context(" 线性代数 ",emptyList()));assertEquals(0,AsrHints.input("",emptyList()).length())}
 @Test fun oversizedWordsAreRejectedBeforeSaving(){assertNotNull(AsrHotwordRules.error(listOf("词".repeat(41))));assertNotNull(AsrHotwordRules.error((1..30).map{"词".repeat(8)+it})) }
 @Test fun characterLimitDoesNotSplitSurrogatePairs(){
  val source="😀".repeat(500);val text=AsrHotwordRules.context(source,emptyList())
  assertEquals(400,text.codePointCount(0,text.length));assertFalse(Character.isHighSurrogate(text.last()))
 }
 @Test fun olderDefaultsUpgradeButCustomEditsAndDeletedWordsRemain(){
  assertEquals(AsrHotwordRules.defaults,AsrHotwordRules.upgrade(AsrHotwordRules.legacyDefaults))
  val edited=listOf("玉兰卡","阿喀琉斯");assertEquals(edited,AsrHotwordRules.upgrade(edited))
  assertEquals(emptyList<String>(),AsrHotwordRules.upgrade(emptyList()))
 }
 @Test fun compiledVocabularyHasModerateWeightsAndNoSpuriousTokens(){
  val entries=AsrVocabulary.entries(listOf("玉兰卡","大工","a01","A01","词".repeat(16)))
  assertEquals(3,entries.length());assertEquals(4,entries.getJSONObject(0).getInt("weight"));assertEquals(3,entries.getJSONObject(1).getInt("weight"))
 }
 @Test fun publicVocabularyUsesRelayFieldNotPrivateAccountIds(){
  val payload=org.json.JSONObject().put("parameters",org.json.JSONObject()).put("input",AsrHints.input("",listOf("玉兰卡")))
  AsrVocabulary.attach(payload,listOf("玉兰卡"),true,"vocab-other-account")
  assertEquals("玉兰卡",payload.getJSONArray("dapan_hotwords").getString(0))
  assertFalse(payload.getJSONObject("parameters").has("vocabulary_id"));assertEquals(0,payload.getJSONObject("input").length())
 }
 @Test fun failedCompilationStillProducesAnOrdinaryRecognitionRequest(){
  val payload=org.json.JSONObject().put("parameters",org.json.JSONObject())
  AsrVocabulary.attach(payload,listOf("玉兰卡"),false,null)
  assertFalse(payload.has("dapan_hotwords"));assertFalse(payload.getJSONObject("parameters").has("vocabulary_id"))
  AsrVocabulary.attach(payload,listOf("玉兰卡"),false,"vocab-dapan-123")
  assertEquals("vocab-dapan-123",payload.getJSONObject("parameters").getString("vocabulary_id"))
 }
}

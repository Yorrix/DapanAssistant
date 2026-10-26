package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ClassroomAutomationTest {
 private val segments=listOf(ClassroomQuestionWindow.Segment(8,"刚才介绍了三种方法"),ClassroomQuestionWindow.Segment(9,"请下一位同学谈谈看法"))
 private fun reply(end:Any=9,question:String="请谈谈你对三种方法的看法")=JSONObject().put("is_question",true).put("question",question).put("end_segment",end).toString()

 @Test fun editingEveryPresetImmediatelyBecomesCustomAndPreservesTheOriginal(){
  ClassroomMode.entries.filter{it!=ClassroomMode.CUSTOM}.forEach{preset->
   val initial=ClassroomPromptEditor(ClassroomPromptChoice.preset(preset),"我的旧提示词")
   val edited=initial.edit("用英文补充一个不同的角度")
   assertEquals(ClassroomMode.CUSTOM,edited.choice.mode)
   assertEquals("用英文补充一个不同的角度",edited.choice.text)
   val restored=edited.select(preset)
   assertEquals(preset.prompt,restored.choice.text)
   assertEquals(edited.choice,restored.select(ClassroomMode.CUSTOM).choice)
  }
 }
 @Test fun legacyEditedPresetIsRestoredAsCustomWithoutLosingText(){
  val migrated=ClassroomPromptChoice(ClassroomMode.POLITICS,"结合我选定的新闻举例").normalized()
  assertEquals(ClassroomMode.CUSTOM,migrated.mode)
  assertEquals("结合我选定的新闻举例",migrated.text)
  assertEquals(ClassroomMode.POLITICS,ClassroomPromptChoice.preset(ClassroomMode.POLITICS).normalized().mode)
 }
 @Test fun samePromptUsesTheSameGenerationRulesInEveryMode(){
  val outputs=ClassroomMode.entries.map{ClassroomRules.prompt(it,"用英文解释数学",4)}
  assertEquals(1,outputs.distinct().size)
 }
 @Test fun automaticStartsOffAndDoesNotReplaySpeechFromBeforeEnabling(){
  val gate=ClassroomIntentGate()
  assertFalse(gate.accept(gate.revision,"old",8))
  gate.toggle(true,8)
  assertFalse(gate.accept(gate.revision,"old",8))
  assertTrue(gate.accept(gate.revision,"new",9))
 }
 @Test fun disablingAndRestartingCannotAcceptALateDetection(){
  val gate=ClassroomIntentGate();gate.toggle(true);val ticket=gate.revision
  gate.toggle(false,9);assertFalse(gate.accept(ticket,"question",9))
  gate.toggle(true,9);assertFalse(gate.accept(ticket,"question",10))
  assertTrue(gate.accept(gate.revision,"question",10))
 }
 @Test fun manualGenerationConsumesOldQuestionsButNotLaterSpeech(){
  val gate=ClassroomIntentGate();gate.toggle(true);val ticket=gate.revision
  gate.consumed(9)
  assertFalse(gate.accept(ticket,"old",9))
  assertFalse(gate.accept(gate.revision,"old",9))
  assertTrue(gate.accept(gate.revision,"new",10))
 }
 @Test fun newQuestionArrivingDuringAnEarlierRequestIsNotConsumedWithIt(){
  val gate=ClassroomIntentGate();gate.toggle(true)
  assertTrue(gate.accept(gate.revision,"first",9));gate.consumed(9)
  assertFalse(gate.accept(gate.revision,"same-but-different-id",9))
  assertTrue(gate.accept(gate.revision,"arrived-later",10))
 }
 @Test fun startingAnotherRecordingInvalidatesOldTicketsAndResetsSegmentNumbers(){
  val gate=ClassroomIntentGate();gate.toggle(true);gate.consumed(99);val ticket=gate.revision;gate.reset()
  assertFalse(gate.accept(ticket,"old",100))
  assertTrue(gate.accept(gate.revision,"first-in-new-recording",1))
 }
 @Test fun invitationCanCarryTheQuestionWithoutNeedingAQuestionMark(){
  val parsed=ClassroomDetection.parse(reply(),segments,8)!!
  assertEquals(9,parsed.end);assertTrue(parsed.question.contains("看法"))
 }
 @Test fun negativeDetectionRequiresNoInventedQuestion(){assertNull(ClassroomDetection.parse("""{"is_question":false,"end_segment":null}""",segments,8))}
 @Test fun consumedOrInventedSegmentIsNeverExecuted(){
  assertNull(ClassroomDetection.parse(reply(8),segments,8))
  assertNull(ClassroomDetection.parse(reply(10),segments,0))
 }
 @Test fun fencedJsonIsAccepted(){assertEquals(9,ClassroomDetection.parse("```json\n${reply()}\n```",segments,0)?.end)}
 @Test fun brokenOrAmbiguousDetectionCannotTriggerGeneration(){
  val broken=listOf("", "{", reply("9"),reply(9.5),reply(1e30),reply(question=""),reply(question="问".repeat(2001)),"""{"is_question":"true","question":"问题","end_segment":9}""")
  broken.forEach{raw->assertTrue("Accepted broken detection: ${raw.take(80)}",runCatching{ClassroomDetection.parse(raw,segments,0)}.isFailure)}
 }
 @Test fun detectionContextKeepsPromptAndTranscriptSeparate(){
  val value=JSONObject(ClassroomDetection.input("课堂主题","只生成一个新角度",segments,8))
  assertEquals("只生成一个新角度",value.getString("responsePrompt"))
  assertEquals("请下一位同学谈谈看法",value.getJSONArray("segments").getJSONObject(1).getString("text"))
  assertEquals(8,value.getInt("handledThrough"))
 }
 @Test fun assistantCanToggleAutomaticAloneAndOnlyWithBooleanValues(){
  listOf(true,false).forEach{AssistantToolArguments.validate("课堂助手","configure",JSONObject().put("automatic",it))}
  listOf("开启",1,JSONObject.NULL).forEach{value->assertTrue(runCatching{AssistantToolArguments.validate("课堂助手","configure",JSONObject().put("automatic",value))}.isFailure)}
  val tool=AssistantCatalogue.json().getJSONArray("课堂助手").getJSONObject(0)
  assertTrue(tool.getJSONArray("fields").toString().contains("automatic"))
 }
}

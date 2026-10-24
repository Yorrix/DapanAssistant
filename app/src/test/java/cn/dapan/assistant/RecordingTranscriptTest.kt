package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class RecordingTranscriptTest {
 private fun clip(id:String,vararg texts:String)=Clip(id,"session",id,0,texts.mapIndexed{i,text->TranscriptLine(text,i*1000L,(i+1)*1000L)},File("unused-$id.wav"))
 @Test fun singleSelectionNeverSearchesAnotherRecording(){
  val clips=listOf(clip("a","无关内容"),clip("b","极限"))
  assertTrue(RecordingTranscript.sections(clips,false,"a","极限").isEmpty())
  assertEquals("b",RecordingTranscript.sections(clips,true,null,"极限").single().clip.id)
 }
 @Test fun originalIndicesAndRecordingNumbersSurviveFiltering(){
  val clips=listOf(clip("a","普通"),clip("b","不匹配","极限例题","其他","极限证明"))
  val section=RecordingTranscript.sections(clips,false,"b","极限").single()
  assertEquals(2,section.number)
  assertEquals(listOf(1,3),section.lines.map{it.index})
  assertEquals(listOf(1000L,3000L),section.lines.map{it.value.start})
 }
 @Test fun allKeepsUserOrderAndSeparateZeroTimestamps(){
  val clips=listOf(clip("b","开场"),clip("a","开场"))
  val sections=RecordingTranscript.sections(clips,true,null,"")
  assertEquals(listOf("b","a"),sections.map{it.clip.id})
  assertEquals(listOf(1,2),sections.map{it.number})
  assertEquals(listOf(0L,0L),sections.map{it.lines.single().value.start})
 }
 @Test fun emptyRecordingsStayVisibleUntilSearch(){
  val clips=listOf(clip("empty"),clip("filled","hello"))
  assertEquals(2,RecordingTranscript.sections(clips,true,null,"").size)
  assertEquals(2,RecordingTranscript.sections(clips,true,null," HELLO ").single().number)
 }
 @Test fun deletedSelectionDoesNotFallBackToAll(){
  assertTrue(RecordingTranscript.sections(listOf(clip("a","hello")),false,"missing","").isEmpty())
 }
 @Test fun exportLabelsEveryRecording(){
  val text=RecordingTranscript.export(listOf(clip("a","第一段"),clip("b","第二段")))
  assertTrue(text.contains("第 1 次录音 · a"));assertTrue(text.contains("第 2 次录音 · b"))
 }
 @Test fun newAndLegacyNamesResolveToSameTool(){
  listOf("录音摘要","会议摘要").forEach{name->
   assertEquals("会议纪要",AssistantSurfaces.canonical(name))
   val action=AssistantActions.decode(JSONObject().put("action","navigate").put("module",name))
   assertEquals(AssistantAction.Navigate("会议纪要"),action)
  }
 }
}

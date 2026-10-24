package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test

class AssistantRecordingSearchTest {
 @Test fun titleAndTranscriptTermsFindTheSameRecording(){
  assertTrue(AssistantRecordingSearch.matches("高数第一课",listOf("今天讲极限的定义"),"高数 第一课 极限"))
  assertTrue(AssistantRecordingSearch.includeLine("高数第一课","今天讲极限的定义","高数 第一课 极限"))
  assertFalse(AssistantRecordingSearch.includeLine("高数第一课","下周提交作业","高数 第一课 极限"))
 }
 @Test fun folderNameAloneAllowsReadingItsContent(){
  assertTrue(AssistantRecordingSearch.includeLine("高数第一课","今天讲极限","高数 第一课"))
  assertFalse(AssistantRecordingSearch.matches("高数第一课",listOf("今天讲极限"),"英语 第一课"))
 }
 @Test fun emptyQueryReadsSelectedRecording(){
  assertTrue(AssistantRecordingSearch.matches("高数",listOf("正文"),""))
  assertTrue(AssistantRecordingSearch.includeLine("高数","正文",""))
 }
}

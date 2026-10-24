package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
class AudioHintsTest {
 @Test fun blankContextIsOmitted(){assertEquals(0,AsrHints.input(" ").length())}
 @Test fun contextUsesOfficialInputTextShapeAndLengthLimit(){
  val input=AsrHints.input("极限".repeat(250));val turn=input.getJSONArray("context").getJSONObject(0)
  assertEquals("user",turn.getString("role"));val content=turn.getJSONArray("content").getJSONObject(0)
  assertEquals("input_text",content.getString("type"));assertEquals(400,content.getString("text").length)
 }
 @Test fun changingSummaryHintInvalidatesSignature(){
  assertNotEquals(meetingSignature(emptyList(),"课程"),meetingSignature(emptyList(),"课程","只列考试重点"))
  assertEquals(meetingSignature(emptyList(),"课程"),meetingSignature(emptyList(),"课程",""))
 }
}

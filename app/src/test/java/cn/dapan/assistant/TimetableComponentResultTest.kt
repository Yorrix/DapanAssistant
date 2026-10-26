package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class TimetableComponentResultTest {
 private val base="content://cn.dapan.assistant.timetable.results/timetable/12345678-1234-1234-1234-123456789abc.json"
 @Test fun onlyAcceptsLocalResultProvider(){
  assertTrue(TimetableComponentResult.allowedUri(base))
  for(uri in listOf(base.replace("content:","file:"),base.replace(".results",".results.evil"),"$base?x=1",base.replace("/timetable/","/documents/"),base.replace("12345678-1234-1234-1234-123456789abc","../secret")))assertFalse(uri,TimetableComponentResult.allowedUri(uri))
 }
 @Test fun validatesSchemaAndBounds(){
  val p=TimetableComponentResult.read("""{"schema":1,"grid":{"kind":"grid"},"courses":{"kind":"courses"}}""".byteInputStream())
  assertEquals(1,p.getInt("schema"))
  for(text in listOf("", "{}", """{"schema":2,"grid":{},"courses":{}}""", "x".repeat(TimetableComponentResult.MAX_BYTES+1)))assertTrue(runCatching{TimetableComponentResult.read(text.byteInputStream())}.isFailure)
 }
}

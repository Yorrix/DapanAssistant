package cn.dapan.assistant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NoticeDeltaTest {
 private fun row(id:String)=JSONObject().put("id",id).put("url","https://panjin.dlut.edu.cn/info/$id.htm")
 @Test fun firstSyncIsBaselineAndDoesNotNotifyOldArticles(){assertTrue(NoticeDelta.fresh(listOf(row("old")),null).isEmpty())}
 @Test fun repeatedSyncAndSummaryUpdatesDoNotNotifyAgain(){
  val old=row("old");val updated=row("old").put("summary","新摘要")
  val fresh=NoticeDelta.fresh(listOf(updated,row("new"),row("new")),NoticeDelta.ids(listOf(old)))
  assertEquals(listOf("new"),fresh.map{it.getString("id")})
  assertTrue(NoticeDelta.fresh(listOf(updated,row("new")),setOf("old","new")).isEmpty())
 }
 @Test fun sourceUsesExactHost(){assertEquals("panjin.dlut.edu.cn",NoticeDelta.host(row("a")))}
}

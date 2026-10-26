package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedAiStatusTest {
 @Test fun readsAllSixServerLimitsInsteadOfAssumingDefaultQuotas(){
  val raw=JSONObject().put("available",true).put("speechAvailable",true)
  val limits=listOf("day","week","month","siteDay","siteWeek","siteMonth")
  limits.forEachIndexed{i,k->raw.put(k,JSONObject().put("used",i*100L).put("held",50L)
   .put("limit",(i+1)*2_000_000L).put("remaining",(i+1)*2_000_000L-i*100L-50L).put("resetsAt","2026-11-01T00:00:00+08:00"))}
  val status=SharedAi.parseStatus(raw)
  listOf(status.day,status.week,status.month,status.siteDay,status.siteWeek,status.siteMonth).forEachIndexed{i,q->
   assertNotNull(q);assertEquals((i+1)*2_000_000L,q!!.limit);assertEquals((i+1)*2_000_000L-i*100L-50L,q.remaining)
  }
  assertTrue(status.speechAvailable)
 }
 @Test fun missingQuotaIsUnavailableNotZeroOrAnInventedDefault(){
  val status=SharedAi.parseStatus(JSONObject().put("available",false).put("reason","login_required"))
  assertNull(status.day);assertNull(status.month);assertNull(status.siteDay);assertNull(status.siteWeek)
 }
}

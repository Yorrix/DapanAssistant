package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HomeNoticeItemsTest {
 private fun notice(id:String,host:String,date:String)=JSONObject()
  .put("id",id).put("url","https://$host/info/$id.htm").put("date",date)

 @Test fun existingInstallsAndUnknownSettingsDefaultToAll(){
  assertEquals(HomeNoticeScope.ALL,HomeNoticeScope.fromStored(null))
  assertEquals(HomeNoticeScope.ALL,HomeNoticeScope.fromStored("unknown"))
  assertEquals(HomeNoticeScope.FOLLOWED,HomeNoticeScope.fromStored("FOLLOWED"))
 }

 @Test fun allScopeSelectsLatestThreeAcrossSources(){
  val items=listOf(
   notice("1","panjin.dlut.edu.cn","2026-10-20"),
   notice("4","teach.dlut.edu.cn","2026-10-23"),
   notice("2","panjin.dlut.edu.cn","2026-10-21"),
   notice("3","mail.dlut.edu.cn","2026-10-22")
  )
  assertEquals(listOf("4","3","2"),HomeNoticeItems.select(items,HomeNoticeScope.ALL,emptySet()).map{it.getString("id")})
 }

 @Test fun followedScopeFiltersBeforeApplyingLimitAndMatchesExactSourceHost(){
  val items=(1..4).map{notice("other$it","teach.dlut.edu.cn","2026-10-24")}+listOf(
   notice("wanted","PANJIN.DLUT.EDU.CN","2026-10-21"),
   notice("nested","fake.panjin.dlut.edu.cn","2026-10-23"),
   notice("suffix","panjin.dlut.edu.cn.fake.example","2026-10-23"),
   JSONObject().put("id","invalid").put("url","not a URL").put("date","2026-10-25")
  )
  assertEquals(listOf("wanted"),HomeNoticeItems.select(items,HomeNoticeScope.FOLLOWED,setOf("panjin.dlut.edu.cn")).map{it.getString("id")})
 }

 @Test fun noFollowedSourcesOrNoMatchingArticlesNeverFallsBackToAll(){
  val items=listOf(notice("other","teach.dlut.edu.cn","2026-10-23"))
  assertTrue(HomeNoticeItems.select(items,HomeNoticeScope.FOLLOWED,emptySet()).isEmpty())
  assertTrue(HomeNoticeItems.select(items,HomeNoticeScope.FOLLOWED,setOf("panjin.dlut.edu.cn")).isEmpty())
 }
}

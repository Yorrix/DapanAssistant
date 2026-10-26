package cn.dapan.assistant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
class RadarRestoreTest {
 private val now=Instant.parse("2026-10-26T05:00:00Z")
 private fun root()=JSONObject().put("stale",false).put("fetched_at",now.toString()).put("data",JSONObject())
 @Test fun noForecastDoesNotInventATime(){assertEquals("暂无",RadarRules.next(root(),now).time)}
 @Test fun explicitFutureTimeIsDistinctFromPrediction(){val r=root();r.getJSONObject("data").put("scheduled_reset",JSONObject().put("scheduled_for","2026-10-26T06:00:00Z").put("text","New reset"));assertTrue(RadarRules.next(r,now).confirmed);assertEquals("10月26日 14:00",RadarRules.next(r,now).time)}
 @Test fun expiryIsNeverDisplayedAsResetTime(){val r=root();r.getJSONObject("data").put("active_watch",JSONObject().put("expires_at","2026-10-27T00:00:00Z").put("text","Possible reset"));val n=RadarRules.next(r,now);assertEquals("预告",n.label);assertEquals("时间未定",n.time);assertFalse(n.confirmed)}
 @Test fun staleAndExpiredPredictionsAreNotCurrent(){val r=root();r.getJSONObject("data").put("active_watch",JSONObject().put("expires_at","2026-10-25T00:00:00Z"));assertEquals("暂无",RadarRules.next(r,now).label);r.put("stale",true);assertEquals("待更新",RadarRules.next(r,now).label)}
 @Test fun oldScheduledTimeDoesNotBecomeNextReset(){val r=root();r.getJSONObject("data").put("scheduled_reset",JSONObject().put("scheduled_for","2026-10-25T00:00:00Z"));assertFalse(RadarRules.next(r,now).confirmed)}
 @Test fun firstEnableDoesNotNotifyAllHistory(){assertTrue(RadarRules.fresh(setOf("a"),null).isEmpty());assertEquals(setOf("b"),RadarRules.fresh(setOf("a","b"),setOf("a")))}
 @Test fun cacheTimestampChangesDoNotCreateNewNotices(){val r=root().put("fetched_at",Instant.now().toString());r.getJSONObject("data").put("latest_reset",JSONObject().put("id","abc").put("text","done"));val before=RadarRules.signatures(r);r.put("fetched_at",Instant.now().plusSeconds(5).toString());assertEquals(before,RadarRules.signatures(r))}
}

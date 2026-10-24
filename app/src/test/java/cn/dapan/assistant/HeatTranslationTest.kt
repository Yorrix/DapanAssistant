package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test

class HeatTranslationTest {
 private fun fix(lat:Double=HeatRules.LAT,lon:Double=HeatRules.LON,accuracy:Float=10f,elapsed:Long=100000)=HeatFix(lat,lon,accuracy,elapsed,1760000000000)
 @Test fun onlyFreshPreciseCampusFixesCount(){assertTrue(HeatRules.accepted(fix(),110000));assertFalse(HeatRules.accepted(fix(lat=0.0),110000));assertFalse(HeatRules.accepted(fix(accuracy=100f),110000));assertFalse(HeatRules.accepted(fix(),300001));assertFalse(HeatRules.accepted(fix(accuracy=Float.NaN),110000));assertFalse(HeatRules.accepted(fix(elapsed=120000),110000))}
 @Test fun reportedTwentyTwoMeterCampusAccuracyIsUsable(){
  val first=fix(accuracy=22f);val next=fix(accuracy=22f,elapsed=130000)
  assertTrue(HeatRules.accepted(first,110000));assertTrue(HeatRules.accepted(next,130000))
  assertEquals(30,HeatRules.seconds(first,next))
  assertFalse(HeatRules.accepted(next,250001))
 }
 @Test fun cellCenterRoundTrips(){for(x in -20..20)for(y in -20..20)assertEquals(x to y,HeatRules.cell(fix(HeatRules.latitude(y),HeatRules.longitude(x))))}
 @Test fun pauseAndLongGapsDoNotInventDwell(){assertEquals(0,HeatRules.seconds(null,fix()));assertEquals(15,HeatRules.seconds(fix(),fix(elapsed=115000)));assertEquals(0,HeatRules.seconds(fix(),fix(elapsed=300001)));assertEquals(0,HeatRules.seconds(fix(),fix(elapsed=90000)));assertEquals(60,HeatRules.seconds(fix(),fix(elapsed=190000)))}
 @Test fun movingAcrossCellsDoesNotClaimStationaryTime(){assertEquals(0,HeatRules.seconds(fix(),fix(lon=HeatRules.longitude(3),elapsed=115000)))}
 @Test fun finalSentencesAreNotTranslatedTwice(){val cursor=FinalSentenceCursor();assertEquals(listOf("Hello"),cursor.take(listOf("Hello")));assertTrue(cursor.take(listOf("Hello")).isEmpty());assertEquals(listOf("World"),cursor.take(listOf("Hello","World")));cursor.reset();assertEquals(listOf("Hello"),cursor.take(listOf("Hello")))}
 @Test fun languagePairsUseKnownAsrCodes(){assertEquals("zh",TranslationLanguages.code("中文"));assertEquals("en",TranslationLanguages.code("英语"));assertEquals(setOf("zh","en","ja","ko","fr","de","es","ru"),TranslationLanguages.values.map{it.code}.toSet());assertEquals(8,TranslationLanguages.names.size)}
}

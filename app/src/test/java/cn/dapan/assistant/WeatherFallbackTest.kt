package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
class WeatherFallbackTest {
 private val data="""{"current":{},"hourly":{},"daily":{}}"""
 @Test fun successDoesNotCallBackup(){
  var calls=0
  WeatherData.loadForecast{calls++;assertEquals(WeatherData.URL,it);data}
  assertEquals(1,calls)
 }
 @Test fun tlsFailureUsesBackup(){
  val urls=mutableListOf<String>()
  WeatherData.loadForecast{urls+=it;if(it==WeatherData.URL)throw javax.net.ssl.SSLHandshakeException("failed");data}
  assertEquals(listOf(WeatherData.URL,WeatherData.FALLBACK_URL),urls)
 }
 @Test fun malformedPrimaryUsesBackup(){
  var calls=0
  WeatherData.loadForecast{if(calls++==0)"{}" else data}
  assertEquals(2,calls)
 }
 @Test fun bothFailuresAreReportedWithoutLoop(){
  var calls=0
  try{WeatherData.loadForecast{calls++;throw java.io.IOException("offline")};fail()}
  catch(e:java.io.IOException){assertEquals(2,calls);assertEquals(1,e.suppressed.size)}
 }
}

package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class WeatherNoticeTest {
 @Test fun defaultsAndYearBoundary(){
  assertEquals(LocalTime.of(6,40),WeatherNotice.MORNING.defaultTime)
  assertEquals(LocalTime.of(22,0),WeatherNotice.EVENING.defaultTime)
  val date=LocalDate.of(2026,12,31)
  assertEquals(date,WeatherNotice.MORNING.forecastDate(date))
  assertEquals(LocalDate.of(2027,1,1),WeatherNotice.EVENING.forecastDate(date))
 }
 @Test fun eveningUsesTomorrowForecastNotFirstDailyEntry(){
  val data=JSONObject("""{"daily":{"time":["2026-12-31","2027-01-01"],"weather_code":[0,75],"temperature_2m_min":[10,-5],"temperature_2m_max":[20,1],"precipitation_probability_max":[0,90]}}""")
  val date=LocalDate.of(2026,12,31)
  assertTrue(WeatherData.briefing(data,WeatherNotice.MORNING.forecastDate(date))!!.startsWith("晴，气温 10～20"))
  assertTrue(WeatherData.briefing(data,WeatherNotice.EVENING.forecastDate(date))!!.startsWith("大雪，气温 -5～1"))
  assertNull(WeatherData.briefing(data,LocalDate.of(2027,1,2)))
 }
 @Test fun coincidentWeatherNoticesRemainIndependentAndDoNotRepeat(){
  val now=LocalDate.of(2026,10,20).atTime(22,0).atZone(ZoneId.of("Asia/Shanghai"))
  val due=now.toInstant().toEpochMilli()
  val tickets=WeatherNotice.entries.map{ReminderQueue.daily(it.key,now,LocalTime.of(22,0),due,0)!!}
  val planned=ReminderQueue.plan(tickets,due,{due},{0})
  assertEquals(setOf("weather","weather-evening"),ReminderQueue.due(planned,due).map{it.key}.toSet())
  val finished=ReminderQueue.plan(tickets,due,{due},{if(it=="weather")due else 0})
  assertEquals(listOf("weather-evening"),ReminderQueue.due(finished,due).map{it.key})
  assertNotEquals(WeatherNotice.MORNING.notificationId,WeatherNotice.EVENING.notificationId)
 }
}

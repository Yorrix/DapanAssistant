package cn.dapan.assistant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class WeatherAdviceTest {
 @Test fun clothesUseFeelsRatherThanAir(){val o=JSONObject("""{"temperature_2m":20,"apparent_temperature":5,"weather_code":0}""");assertTrue(WeatherAdvice.text(o).contains("保暖"))}
 @Test fun thunderOverridesOrdinaryUmbrellaSuggestion(){val o=JSONObject("""{"apparent_temperature":18,"weather_code":95,"precipitation_probability":90}""");assertTrue(WeatherAdvice.text(o).contains("减少户外"));assertFalse(WeatherAdvice.text(o).contains("带伞"))}
 @Test fun unknownDataDoesNotInventClothes(){assertEquals("",WeatherAdvice.text(JSONObject()))}
 @Test fun dailySuggestionAndBriefingUseSelectedDate(){val o=JSONObject("""{"daily":{"time":["2026-10-22","2026-10-23"],"weather_code":[0,71],"temperature_2m_min":[15,1],"temperature_2m_max":[20,5],"apparent_temperature_min":[12,-2],"apparent_temperature_max":[19,2],"precipitation_probability_max":[0,80]}}""");val first=WeatherData.briefing(o,LocalDate.parse("2026-10-22"))!!;val next=WeatherData.briefing(o,LocalDate.parse("2026-10-23"))!!;assertFalse(first.contains("0%"));assertTrue(next.contains("体感 -2～2"));assertTrue(next.contains("厚外套"));assertTrue(next.contains("防滑"))}
}

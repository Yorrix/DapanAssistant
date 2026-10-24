package cn.dapan.assistant

import org.json.JSONObject

/** Short, deterministic suggestions based on the selected forecast, never invented by AI. */
object WeatherAdvice {
 fun text(o:JSONObject,i:Int?=null,daily:Boolean=false):String {
  fun n(key:String)=WeatherData.value(o,key,i)
  val code=n("weather_code")?.toInt()?:-1
  val feels=n(if(daily)"apparent_temperature_min"else"apparent_temperature")
   ?:n(if(daily)"temperature_2m_min"else"temperature_2m")
  val clothes=feels?.let{when{
   it<0->"厚外套，注意保暖";it<10->"外套叠穿，早晚保暖";it<18->"长袖配薄外套"
   it<25->"轻薄长袖，按需增减";else->"轻薄透气，随身带水"
  }}
  val wind=n(if(daily)"wind_gusts_10m_max"else"wind_gusts_10m")?:0.0
  val travel=when{
   code in listOf(95,96,97,99)->"雷雨时减少户外停留"
   code in listOf(56,57,66,67)->"路面可能结冰，慢行防滑"
   code in listOf(75,82,86)||(daily&&(n("precipitation_sum")?:0.0)>=50)->"雨雪较强，出行留余量"
   wind>=62->"阵风较强，避开树下和临时搭建物"
   code in listOf(71,73,77,85)->"雪天慢行，穿防滑鞋"
   code in 51..67||code in 80..82||(n(if(daily)"precipitation_probability_max"else"precipitation_probability")?:0.0)>=40->"出门带伞，注意湿滑"
   code in listOf(45,48)||(n("visibility")?:Double.MAX_VALUE)<1000->"能见度较低，过路多留意"
   wind>=40->"风大，外套挡风更合适"
   (n("uv_index_max")?:0.0)>=6->"午间注意遮阳"
   else->null
  }
  return listOfNotNull(clothes,travel).joinToString("；")
 }
}

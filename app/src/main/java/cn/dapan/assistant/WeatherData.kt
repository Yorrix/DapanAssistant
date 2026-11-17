package cn.dapan.assistant

import android.content.Context
import okhttp3.*
import org.json.JSONObject
import java.time.*
import java.util.Locale
import java.util.concurrent.TimeUnit

object WeatherData {
 val zone:ZoneId=ZoneId.of("Asia/Shanghai")
 fun now():LocalDateTime=LocalDateTime.now(zone)
 // Precipitation is the preceding hour's total; use upcoming timestamps, not yesterday's hours.
 const val URL="https://api.open-meteo.com/v1/forecast?latitude=40.6834&longitude=122.119&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,wind_gusts_10m,visibility&hourly=temperature_2m,precipitation_probability,precipitation,rain,showers,weather_code,relative_humidity_2m,apparent_temperature,wind_speed_10m,wind_gusts_10m,visibility,snowfall&daily=temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,precipitation_probability_max,weather_code,rain_sum,showers_sum,precipitation_sum,snowfall_sum,wind_speed_10m_max,wind_gusts_10m_max,uv_index_max&timezone=Asia%2FShanghai&forecast_days=8"
 @Volatile private var memory:JSONObject?=null
 fun snapshot():JSONObject?=memory
 private val client=OkHttpClient.Builder().callTimeout(7,TimeUnit.SECONDS).build()
 fun cached(c:Context):JSONObject?=memory?:runCatching{JSONObject(c.getSharedPreferences("weather-cache",0).getString("data",null)!!)}.getOrNull()?.also{memory=it}
 const val FALLBACK_URL="https://39.105.161.86/weather/campus"
 private val fallbackClient=client.newBuilder().callTimeout(20,TimeUnit.SECONDS).build()
 internal fun loadForecast(load:(String)->String):JSONObject {
  var first:Exception?=null
  for(url in listOf(URL,FALLBACK_URL))try{
   val data=JSONObject(load(url))
   data.getJSONObject("current");data.getJSONObject("hourly");data.getJSONObject("daily")
   return data
  }catch(e:Exception){
   if(e is kotlinx.coroutines.CancellationException)throw e
   if(Thread.currentThread().isInterrupted)throw e
   if(first==null)first=e else {e.addSuppressed(first);throw e}
  }
  error("天气更新失败")
 }
 fun fetch(c:Context):JSONObject {
  val data=loadForecast{url->
   (if(url==FALLBACK_URL)fallbackClient else client).newCall(Request.Builder().url(url).build()).execute().use{r->
    check(r.isSuccessful){"天气服务返回 HTTP ${r.code}"}
    r.body?.string()?:error("天气数据为空")
   }
  }
  c.getSharedPreferences("weather-cache",0).edit().putString("data",data.toString()).putLong("fetched",System.currentTimeMillis()).apply()
  memory=data;return data
 }
 fun failureMessage(e:Exception):String=when(e){
  is javax.net.ssl.SSLException->"天气连接安全握手失败，请稍后重试；已有预报会保留。"
  is java.net.UnknownHostException->"无法解析天气服务地址；已有预报会保留。"
  is java.io.InterruptedIOException->"天气服务连接超时；已有预报会保留。"
  else->if(e.message?.startsWith("天气服务返回 HTTP ")==true)"${e.message}；已有预报会保留。" else "天气更新失败；已有预报会保留。"
 }
 fun value(o:JSONObject,key:String,i:Int?=null):Double? {
  val raw=if(i==null)o.opt(key)else o.optJSONArray(key)?.opt(i)
  return (raw as? Number)?.toDouble()?.takeIf{it.isFinite()}
 }
 fun number(o:JSONObject,key:String,i:Int?=null):String=value(o,key,i)?.let{if(it%1.0==0.0)it.toInt().toString()else String.format(Locale.ROOT,"%.1f",it)}?:"—"
 fun code(o:JSONObject,i:Int)=value(o,"weather_code",i)?.toInt()?:-1
 fun condition(code:Int)=when(code){
  0->"晴";1->"晴间多云";2->"多云";3->"阴";45,48->"雾"
  51,53,55->"毛毛雨";56,57->"冻毛毛雨";61->"小雨";63->"中雨";65->"大雨";66,67->"冻雨"
  71->"小雪";73->"中雪";75->"大雪";77->"雪粒";80->"小阵雨";81->"中阵雨";82->"强阵雨";85->"小阵雪";86->"大阵雪"
  95->"雷阵雨";97->"强雷阵雨";96,99->"雷雨伴冰雹";else->"暂无天气"
 }
 fun hourCondition(o:JSONObject,i:Int):String {
  val code=code(o,i);val liquid=(value(o,"rain",i)?:0.0)+(value(o,"showers",i)?:0.0)
  return if(liquid>=20&&code in listOf(61,63,65,80,81,82,95,97))"短时强降雨"else condition(code)
 }
 fun dailyCondition(o:JSONObject,i:Int):String {
  val code=code(o,i);val rain=value(o,"rain_sum",i);val showers=value(o,"showers_sum",i)
  if(rain==null||showers==null||code in listOf(56,57,66,67,71,73,75,77,85,86,96,99))return condition(code)
  val mm=rain+showers
  // CMA 24-hour accumulation bands, never reuse these thresholds for an hourly forecast.
  val level=when{mm>=250->"特大暴雨";mm>=100->"大暴雨";mm>=50->"暴雨";mm>=25->"大雨";mm>=10->"中雨";code in listOf(51,53,55)->"毛毛雨";mm>=.1->"小雨";else->return condition(code)}
  return if(code in listOf(95,97))"雷雨 · $level"else level
 }
 fun nextHours(o:JSONObject,now:LocalDateTime=now()):List<Int>{
  val times=o.optJSONObject("hourly")?.optJSONArray("time")?:return emptyList()
  return (0 until times.length()).filter{i->runCatching{val t=LocalDateTime.parse(times.getString(i));!t.isBefore(now)&&t<=now.plusHours(12)}.getOrDefault(false)}.take(12)
 }
 fun nextDays(daily:JSONObject,today:LocalDate=now().toLocalDate()):List<Int>{
  val times=daily.optJSONArray("time")?:return emptyList()
  return (0 until times.length()).filter{i->runCatching{val date=LocalDate.parse(times.getString(i));date>today&&date<=today.plusDays(7)}.getOrDefault(false)}.take(7)
 }
 fun briefing(o:JSONObject,date:LocalDate=now().toLocalDate()):String? {
  val daily=o.optJSONObject("daily")?:return null;val dates=daily.optJSONArray("time")?:return null
  val i=(0 until dates.length()).firstOrNull{dates.optString(it)==date.toString()}?:return null
  val feels=if(value(daily,"apparent_temperature_min",i)!=null&&value(daily,"apparent_temperature_max",i)!=null)"，体感 ${number(daily,"apparent_temperature_min",i)}～${number(daily,"apparent_temperature_max",i)}℃"else""
  val rain=if((value(daily,"precipitation_probability_max",i)?:0.0)>0)"，降水概率 ${number(daily,"precipitation_probability_max",i)}%"else""
  return "${dailyCondition(daily,i)}，气温 ${number(daily,"temperature_2m_min",i)}～${number(daily,"temperature_2m_max",i)}℃$feels$rain。${WeatherAdvice.text(daily,i,true)}"
 }
}

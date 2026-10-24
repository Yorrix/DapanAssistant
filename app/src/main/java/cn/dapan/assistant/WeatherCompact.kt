package cn.dapan.assistant

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.time.*

data class WeatherFlag(val level:Int,val text:String)
object WeatherSignals {
 // App travel hints, not official meteorological warning levels.
 fun flags(o:JSONObject,i:Int?=null,daily:Boolean=false):List<WeatherFlag>{
  fun v(key:String)=WeatherData.value(o,key,i)
  val code=v("weather_code")?.toInt()?:-1;val result=mutableListOf<WeatherFlag>()
  fun add(level:Int,text:String){result+=WeatherFlag(level,text)}
  when(code){96,99->add(2,"冰雹");97->add(2,"强雷雨");95->add(1,"雷雨");67->add(2,"冻雨");56,57,66->add(1,"路面结冰风险");75,86->add(1,"大雪");71,73,77,85->add(1,"降雪");45,48->add(1,"有雾");in 51..65,80,81,82->add(1,"有雨")}
  if(daily){val rain=(v("rain_sum")?:0.0)+(v("showers_sum")?:0.0);if(rain>=50)add(2,"暴雨")else if(rain>=25)add(1,"大雨")}
  else if((v("rain")?:0.0)+(v("showers")?:0.0)>=20)add(2,"短时强降雨")
  val gust=v(if(daily)"wind_gusts_10m_max"else"wind_gusts_10m")
  if(gust!=null){if(gust>=88)add(2,"强阵风")else if(gust>=50)add(1,"阵风较强")}
  val visibility=v("visibility")
  if(visibility!=null){if(visibility<200)add(2,"能见度极低")else if(visibility<1000)add(1,"低能见度")}
  val high=v(if(daily)"temperature_2m_max"else"temperature_2m");val low=v(if(daily)"temperature_2m_min"else"temperature_2m")
  if(high!=null){if(high>=40)add(2,"酷热")else if(high>=35)add(1,"高温")}
  if(low!=null){if(low<=-25)add(2,"严寒")else if(low<=-10)add(1,"低温")}
  val uv=v("uv_index_max");if(uv!=null){if(uv>=11)add(2,"紫外线极强")else if(uv>=8)add(1,"紫外线很强")}
  return result.distinctBy{it.text}.sortedByDescending{it.level}
 }
}
fun weatherFlagColor(level:Int)=if(level>=2){if(AppAppearance.dark)Color(0xFFFF9690)else Color(0xFFBA302C)}else{if(AppAppearance.dark)Color(0xFFFFD36F)else Color(0xFF856000)}
@Composable fun WeatherFlagMark(flag:WeatherFlag,compact:Boolean=false){
 Text(if(compact)"!"else"! ${flag.text}",fontSize=if(compact)11.sp else 10.sp,fontWeight=FontWeight.Bold,color=weatherFlagColor(flag.level),maxLines=1,
  modifier=Modifier.background(if(flag.level>=2)Color(0xFFE24740).copy(alpha=if(AppAppearance.dark).25f else .13f)else Color(0xFFFFCA28).copy(alpha=if(AppAppearance.dark).25f else .3f),AppShape(4.dp)).padding(horizontal=if(compact)4.dp else 5.dp,vertical=2.dp))
}
@Composable fun HomeDateWeather(onWeather:()->Unit){
 val c=LocalContext.current;val now by rememberDashboardTime();var data by remember{mutableStateOf(WeatherData.snapshot())}
 LaunchedEffect(now.toLocalDate(),now.hour){
  if(data==null)data=withContext(Dispatchers.IO){WeatherData.cached(c)}
  val age=System.currentTimeMillis()-c.getSharedPreferences("weather-cache",0).getLong("fetched",0)
  if(age !in 0..15*60000L||data?.optJSONObject("current")?.has("visibility")!=true)try{data=withContext(Dispatchers.IO){WeatherData.fetch(c)}}catch(e:CancellationException){throw e}catch(_:Exception){}
 }
 val current=data?.optJSONObject("current")
 val fresh=current?.optString("time")?.let{runCatching{kotlin.math.abs(Duration.between(LocalDateTime.parse(it),WeatherData.now()).toMinutes())<=120}.getOrDefault(false)}?:false
 Column(Modifier.fillMaxWidth().clickable(onClick=onWeather),verticalArrangement=Arrangement.spacedBy(2.dp)){
  Text("${now.monthValue}月${now.dayOfMonth}日 · ${"一二三四五六日"[now.dayOfWeek.value-1]}",fontSize=13.sp,lineHeight=16.sp,fontWeight=FontWeight.SemiBold,color=Ink)
  Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)){
   Text(if(current!=null&&fresh)"${WeatherData.number(current,"temperature_2m")}° · ${WeatherData.condition(current.optInt("weather_code",-1))}"else"天气待更新",fontSize=12.sp,lineHeight=16.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
   if(current!=null&&fresh)WeatherSignals.flags(current).firstOrNull()?.let{WeatherFlagMark(it)}
   LineIcon(AppGlyph.Next,Modifier.size(11.dp),Muted)
  }
 }
}
data class WeatherDetail(val title:String,val rows:List<Pair<String,String>>,val flags:List<WeatherFlag>)
fun weatherDetail(o:JSONObject,i:Int?=null,daily:Boolean=false,title:String):WeatherDetail{
 fun n(k:String)=WeatherData.number(o,k,i)
 val rows=mutableListOf<Pair<String,String>>()
 rows+="天气" to if(daily)WeatherData.dailyCondition(o,i!!)else WeatherData.condition(WeatherData.value(o,"weather_code",i)?.toInt()?:-1)
 if(daily){
  rows+="气温" to "${n("temperature_2m_min")}～${n("temperature_2m_max")}°C"
  rows+="体感" to "${n("apparent_temperature_min")}～${n("apparent_temperature_max")}°C"
  if((WeatherData.value(o,"precipitation_probability_max",i)?:0.0)>0)rows+="降水概率" to "${n("precipitation_probability_max")}%"
  rows+="全天降水量" to "${n("precipitation_sum")} mm";rows+="降雪量" to "${n("snowfall_sum")} cm"
  rows+="最大风速" to "${n("wind_speed_10m_max")} km/h";rows+="最大阵风" to "${n("wind_gusts_10m_max")} km/h";rows+="紫外线指数" to n("uv_index_max")
 }else{
  rows+="气温" to "${n("temperature_2m")}°C";rows+="体感" to "${n("apparent_temperature")}°C";rows+="湿度" to "${n("relative_humidity_2m")}%"
  if(i!=null){if((WeatherData.value(o,"precipitation_probability",i)?:0.0)>0)rows+="降水概率" to "${n("precipitation_probability")}%";rows+="小时降水量" to "${n("precipitation")} mm";rows+="小时降雪量" to "${n("snowfall")} cm"}
  rows+="风速 / 阵风" to "${n("wind_speed_10m")} / ${n("wind_gusts_10m")} km/h"
  rows+="能见度" to (WeatherData.value(o,"visibility",i)?.let{String.format(java.util.Locale.ROOT,"%.1f km",it/1000)}?:"—")
 }
 WeatherAdvice.text(o,i,daily).takeIf{it.isNotBlank()}?.let{rows+="出行穿衣" to it}
 return WeatherDetail(title,rows,WeatherSignals.flags(o,i,daily))
}
@Composable fun WeatherDetailDialog(detail:WeatherDetail,onClose:()->Unit){
 AlertDialog(onDismissRequest=onClose,title={Row(verticalAlignment=Alignment.CenterVertically){QuietIconButton("返回",AppGlyph.Back,onClose);Text(detail.title,fontSize=16.sp)}},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
  detail.flags.forEach{WeatherFlagMark(it)}
  detail.rows.forEach{(name,value)->Row(Modifier.fillMaxWidth()){Text(name,Modifier.weight(1f),fontSize=12.sp,color=Muted);Text(value,Modifier.weight(1.6f),fontSize=when(name){"气温"->18.sp;"体感"->15.sp;else->12.sp},fontWeight=if(name in listOf("气温","体感"))FontWeight.Medium else FontWeight.Normal)}}
  Text("红色：恶劣或极端情况；黄色：出行需留意。标识由预报数据生成，不是气象部门发布的预警。",fontSize=10.sp,color=Muted)
 }},confirmButton={})
}

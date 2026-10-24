package cn.dapan.assistant

import java.time.LocalDate
import java.time.LocalTime

enum class WeatherNotice(val key:String,val preference:String,val notificationId:Int,val label:String,val dayLabel:String,val defaultTime:LocalTime) {
 MORNING("weather","weather-morning",731,"晨间","今日",LocalTime.of(6,40)),
 EVENING("weather-evening","weather-evening",733,"晚间","明日",LocalTime.of(22,0));
 fun forecastDate(deliveryDate:LocalDate)=if(this==EVENING)deliveryDate.plusDays(1)else deliveryDate
}

package cn.dapan.assistant

import org.json.*

import android.Manifest
import android.app.DatePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlinx.coroutines.*
import java.time.*
import java.time.format.DateTimeFormatter

private val heatZone=ZoneId.of("Asia/Shanghai")
private val heatDateFormat=DateTimeFormatter.ofPattern("yyyy/M/d")
private fun heatDuration(seconds:Long):String=when{seconds<60->"不足1分钟";seconds<3600->"${seconds/60}分钟";else->"${seconds/3600}小时${seconds/60%60}分"}

@Composable fun CampusMapHub(initialHeat:Boolean=false,initialFrom:String="",initialTo:String="",routeRequest:Int=0,onBack:()->Unit){
 val c=LocalContext.current;val scope=rememberCoroutineScope()
 var heatMode by rememberSaveable{mutableStateOf(initialHeat)}
 var appliedRoute by rememberSaveable{mutableIntStateOf(routeRequest)}
 LaunchedEffect(routeRequest){if(appliedRoute!=routeRequest){heatMode=initialHeat;appliedRoute=routeRequest}}
 var range by rememberSaveable{mutableStateOf("近7天")}
 val today by produceState(LocalDate.now(heatZone)){while(true){value=LocalDate.now(heatZone);delay(30000)}}
 var customStart by rememberSaveable{mutableStateOf(today.minusDays(6).toString())}
 var customEnd by rememberSaveable{mutableStateOf(today.toString())}
 val start=when(range){"今天"->today;"近30天"->today.minusDays(29);"近半年"->today.minusMonths(6).plusDays(1);"自定义"->LocalDate.parse(customStart);else->today.minusDays(6)}
 val end=if(range=="自定义")LocalDate.parse(customEnd).coerceAtMost(today)else today
 var cells by remember{mutableStateOf(emptyList<HeatCell>())}
 var loading by remember{mutableStateOf(true)}
 var readError by remember{mutableStateOf(false)}
 var info by remember{mutableStateOf(false)};var consent by remember{mutableStateOf(false)}
 var clear by remember{mutableStateOf(false)};var clearing by remember{mutableStateOf(false)}
 var message by remember{mutableStateOf<String?>(null)}
 var starting by remember{mutableStateOf(false)}
 LaunchedEffect(start,end,HeatState.revision){
  loading=true;readError=false;cells=emptyList()
  try{cells=withContext(Dispatchers.IO){HeatStore(c).use{it.read(start,end)}}}
  catch(e:CancellationException){throw e}catch(e:Exception){readError=true}
  finally{loading=false}
 }
 LaunchedEffect(starting){if(starting){delay(2000);starting=false}}
 fun begin(){try{starting=true;androidx.core.content.ContextCompat.startForegroundService(c,Intent(c,HeatService::class.java))}catch(e:Exception){starting=false;message="暂时无法开始记录，请检查定位权限"}}
 val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
  if(c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)begin()
  else message="请允许精确位置；大致位置不足以绘制校园足迹"
 }
 fun chooseDate(value:LocalDate,isStart:Boolean){
  val dialog=DatePickerDialog(c,platformDialogTheme,{_,y,m,d->
   val selected=LocalDate.of(y,m+1,d)
   if(isStart){customStart=selected.toString();if(selected>LocalDate.parse(customEnd))customEnd=selected.toString()}
   else{customEnd=selected.toString();if(selected<LocalDate.parse(customStart))customStart=selected.toString()}
  },value.year,value.monthValue-1,value.dayOfMonth)
  dialog.datePicker.maxDate=today.atTime(23,59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
  dialog.show()
 }
 AssistantSurface("地图",JSONObject().put("mode",if(heatMode)"热力图"else"导航").put("range",range).put("start",start.toString()).put("end",end.toString()).put("heatRunning",HeatState.running),listOf(
  surfaceTool("heat_view","切换导航/热力图，mode=导航/热力图，range=今天/近7天/近30天/近半年/自定义；start/end日期","mode,range,start,end",false){a->val m=a.optString("mode",if(heatMode)"热力图"else"导航");require(m in listOf("导航","热力图"));val rr=a.optString("range",range);require(rr in listOf("今天","近7天","近30天","近半年","自定义"));val st=LocalDate.parse(a.optString("start",customStart));val en=LocalDate.parse(a.optString("end",customEnd));require(st<=en&&en<=today);prepared("调整地图统计视图",a){heatMode=m=="热力图";range=rr;customStart=st.toString();customEnd=en.toString();"地图视图已调整"}},
  surfaceTool("heat_record","开启或停止个人位置记录，enabled布尔；开启需精确定位权限","enabled"){a->val enabled=a.getBoolean("enabled");prepared(if(enabled)"开启个人热力记录"else"停止热力记录",a){if(enabled){if(c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION));"已请求精确定位权限，授权后开始记录"}else{androidx.core.content.ContextCompat.startForegroundService(c,Intent(c,HeatService::class.java));"已提交热力记录启动请求"}}else{c.startService(Intent(c,HeatService::class.java).setAction("stop"));"已请求停止热力记录"}}},
  surfaceTool("heat_clear","删除全部个人热力统计，必须先停止记录","",true){a->require(!HeatState.running){"请先停止记录"};prepared("清空全部个人热力记录",a){withContext(Dispatchers.IO){HeatStore(c).use{it.clear()}};HeatState.revision++;"热力记录已清空"}}
 ))
 val threshold=with(LocalDensity.current){40.dp.toPx()}
 Column(Modifier.fillMaxSize()){
  Row(Modifier.fillMaxWidth().height(44.dp).background(Paper).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
   QuietIconButton("返回",AppGlyph.Back,onBack)
   Row(Modifier.weight(1f).height(36.dp).clip(CircleShape).background(SoftSurface)
    .pointerInput(Unit){var distance=0f;detectHorizontalDragGestures(onDragStart={distance=0f},onDragEnd={if(distance < -threshold)heatMode=true else if(distance>threshold)heatMode=false},onHorizontalDrag={change,amount->change.consume();distance+=amount})},verticalAlignment=Alignment.CenterVertically){
    listOf("导航","热力图").forEachIndexed{index,label->
     val selected=heatMode==(index==1)
     Box(Modifier.weight(1f).fillMaxHeight().clip(CircleShape).background(if(selected)AccentSurface else Color.Transparent).clickable{heatMode=index==1}.semantics{this.selected=selected},contentAlignment=Alignment.Center){Text(label,fontSize=14.sp,color=if(selected)DutBlue else Muted,fontWeight=if(selected)FontWeight.SemiBold else FontWeight.Normal)}
    }
   }
   Spacer(Modifier.width(8.dp))
  }
  Box(Modifier.weight(1f)){
   CampusMapScreen(initialFrom,initialTo,routeRequest=routeRequest,heat=if(heatMode)cells else emptyList(),heatMode=heatMode,onInfo={info=true},heatControls={
    Surface(shape=AppShape(14.dp),color=CardSurface.copy(alpha=.97f),shadowElevation=2.dp){
     Column(Modifier.padding(horizontal=9.dp,vertical=4.dp),verticalArrangement=Arrangement.spacedBy(2.dp)){
      Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
       listOf("今天","近7天","近30天","近半年","自定义").forEach{label->
        Box(Modifier.height(32.dp).clip(CircleShape).background(if(range==label)AccentSurface else Color.Transparent).clickable{range=label}.padding(horizontal=10.dp),contentAlignment=Alignment.Center){Text(label,fontSize=12.sp,color=if(range==label)DutBlue else Muted)}
       }
      }
      Row(Modifier.fillMaxWidth().heightIn(min=44.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
       BoxWithConstraints(Modifier.weight(1f)){
        if(range=="自定义"){
         if(maxWidth<176.dp){
          Column{
           Text("起 ${start.format(heatDateFormat)}",Modifier.fillMaxWidth().clickable{chooseDate(start,true)}.padding(vertical=4.dp),fontSize=12.sp,color=DutBlue)
           Text("止 ${end.format(heatDateFormat)}",Modifier.fillMaxWidth().clickable{chooseDate(end,false)}.padding(vertical=4.dp),fontSize=12.sp,color=DutBlue)
          }
         }else Row(verticalAlignment=Alignment.CenterVertically){
          Text(start.format(heatDateFormat),Modifier.clickable{chooseDate(start,true)}.padding(vertical=8.dp),fontSize=12.sp,color=DutBlue)
          Text(" — ",fontSize=12.sp,color=Muted)
          Text(end.format(heatDateFormat),Modifier.clickable{chooseDate(end,false)}.padding(vertical=8.dp),fontSize=12.sp,color=DutBlue)
         }
        }else Text(if(range=="今天")today.format(heatDateFormat)else"${start.format(heatDateFormat)} — ${end.format(heatDateFormat)}",fontSize=11.sp,lineHeight=16.sp,color=Muted)
       }
       Button(enabled=!starting&&!clearing,onClick={if(HeatState.running)c.startService(Intent(c,HeatService::class.java).setAction("stop"))else consent=true},modifier=Modifier.height(40.dp),shape=CircleShape,contentPadding=PaddingValues(horizontal=14.dp),colors=ButtonDefaults.buttonColors(containerColor=if(HeatState.running)Coral else DutBlue,contentColor=OnAccent)){
        LineIcon(if(HeatState.running)AppGlyph.Stop else AppGlyph.Pin,Modifier.size(15.dp),OnAccent)
        Spacer(Modifier.width(5.dp))
        Text(if(starting)"启动中"else if(HeatState.running)"暂停记录"else"开始记录",fontSize=13.sp,fontWeight=FontWeight.SemiBold)
       }
      }
      Row(Modifier.fillMaxWidth().height(22.dp),verticalAlignment=Alignment.CenterVertically){
       Text("停留短",fontSize=10.sp,color=Muted)
       Box(Modifier.padding(horizontal=6.dp).width(64.dp).height(4.dp).background(Brush.horizontalGradient(listOf(Color(0xFF4B9FE1),Color(0xFF35C2A0),Color(0xFFF7C64A),Color(0xFFF05A41))),CircleShape))
       Text("长",fontSize=10.sp,color=Muted)
       Spacer(Modifier.weight(1f))
       if(!loading&&!readError)Text(if(cells.none{it.seconds>0})"暂无停留记录"else"停留 ${heatDuration(cells.sumOf{it.seconds.toLong()})}",fontSize=10.sp,color=Muted)
      }
      Row(Modifier.fillMaxWidth().height(24.dp),verticalAlignment=Alignment.CenterVertically){
       Box(Modifier.size(5.dp).background(if(HeatState.running)Color(0xFF35A87C)else Muted,CircleShape))
       Text(if(loading)"正在读取…"else if(readError)"读取失败，请切换日期重试"else HeatState.status,Modifier.weight(1f).padding(start=5.dp),fontSize=10.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)

      }
     }
    }
   })
  }
 }
 if(info)AlertDialog(onDismissRequest={info=false},title={Text("地图信息",fontSize=18.sp)},text={
  Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){
   Text("导航",fontSize=14.sp,fontWeight=FontWeight.SemiBold)
   Text("固定模式将校园轴线摆正；跟随模式让手机朝向朝上，转向始终平滑。默认选择已收录路网中的最短参考路线，有不同走法时可切换备选。距离包含虚线接近路段；虚线不是已核实的楼门或道路，通行情况以现场为准。",fontSize=12.sp)
   Text("底图结合 OpenStreetMap 与学校公开地图步行路线，已补彩虹长廊。A01 按学校一层示意图的四侧布局标注，不绘制未经核实的分楼边界。字母为楼体参考点，并非实测入口；底图仍有米级误差，楼门开放与临时封路以现场为准。",fontSize=12.sp)
   TextButton(onClick={runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("http://map.dlut.edu.cn/")))}}){Text("学校公开地图 ↗",fontSize=11.sp)}
   Text("个人热力图",fontSize=14.sp,fontWeight=FontWeight.SemiBold)
   Text("开启后持续获取位置，约每30秒保存一次有效采样；在同一约20米网格内持续获得有效定位时，累计停留时长，停留越久越热。颜色只表示所选时段内你自己的相对停留热度，不代表校园人流。",fontSize=12.sp)
   Text("仅记录校园内精度不超过60米的有效位置。信号过旧、离校、暂停及长时间断点不补算；室内漂移可能影响结果。定位频率受系统省电和信号影响。",fontSize=12.sp)
   Text("日期按北京时间，近7天和近30天包含今天，近半年按6个自然月回溯。记录只留在本机，直到你主动清空。旧版本已自动清理的历史无法补回。",fontSize=12.sp)
   Text("锁屏记录会显示常驻通知，可随时暂停；应用被系统结束后需手动重新开启。",fontSize=12.sp)
   TextButton(onClick={runCatching{c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://www.openstreetmap.org/copyright")))}.onFailure{message="无法打开浏览器"}},contentPadding=PaddingValues(0.dp)){Text("© OpenStreetMap contributors · ODbL",fontSize=11.sp)}
   TextButton(enabled=!HeatState.running&&!starting&&!clearing,onClick={clear=true},contentPadding=PaddingValues(0.dp)){Text(if(HeatState.running)"暂停记录后可清空足迹"else"清空全部热力记录",fontSize=12.sp,color=MaterialTheme.colorScheme.error)}
  }
 },confirmButton={TextButton(onClick={info=false}){Text("知道了")}})
 if(consent)AlertDialog(onDismissRequest={consent=false},title={Text("开启校园足迹记录？")},text={Text("开启后会持续获取位置，锁屏时通过常驻通知记录，直到你暂停。仅在本机保存校园网格的停留统计，不上传服务器；可能增加耗电。",fontSize=13.sp)},confirmButton={TextButton(onClick={consent=false;permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)+if(Build.VERSION.SDK_INT>=33)arrayOf(Manifest.permission.POST_NOTIFICATIONS)else emptyArray())}){Text("开启记录")}},dismissButton={TextButton(onClick={consent=false}){Text("取消")}})
 if(clear)AlertDialog(onDismissRequest={clear=false},title={Text("清空全部热力记录？")},text={Text("删除本机所有日期的个人足迹，无法恢复。")},confirmButton={TextButton(onClick={
  clear=false
  if(HeatState.running){message="请先暂停记录"}else{clearing=true;scope.launch{try{withContext(Dispatchers.IO){HeatStore(c).use{it.clear()}};HeatState.revision++}catch(e:CancellationException){throw e}catch(e:Exception){message="清空失败，请重试"}finally{clearing=false}}}
 }){Text("清空")}},dismissButton={TextButton(onClick={clear=false}){Text("取消")}})
 message?.let{AlertDialog(onDismissRequest={message=null},text={Text(it)},confirmButton={TextButton(onClick={message=null}){Text("知道了")}})}
}

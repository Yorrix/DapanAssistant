package cn.dapan.assistant

import org.json.*

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.SystemClock
import android.view.Surface as AndroidSurface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlin.math.*

// OSM and Android GPS both use WGS84; do not apply a GCJ-02 offset to this map.
private const val CAMPUS_ROTATION=-11f
private const val CAMPUS_LAT=40.6863
private const val CAMPUS_LON=122.1197
private fun world(n:MapNode)=Offset(((n.lon-CAMPUS_LON)*111320*cos(Math.toRadians(CAMPUS_LAT))).toFloat(),(-(n.lat-CAMPUS_LAT)*111320).toFloat())
private fun turn(p:Offset,degrees:Float):Offset{val a=Math.toRadians(degrees.toDouble());return Offset((p.x*cos(a)-p.y*sin(a)).toFloat(),(p.x*sin(a)+p.y*cos(a)).toFloat())}
private fun usable(l:Location?,now:Long)=l!=null&&l.hasAccuracy()&&l.accuracy in 0f..180f&&now-l.elapsedRealtimeNanos/1000000 in 0..120000&&l.latitude in 40.673..40.695&&l.longitude in 122.107..122.132

@Composable private fun MapControl(label:String,symbol:String,active:Boolean=false,onClick:()->Unit){
 Surface(onClick=onClick,modifier=Modifier.size(34.dp).semantics{contentDescription=label},shape=CircleShape,color=CardSurface,shadowElevation=3.dp){
  Box(contentAlignment=Alignment.Center){Text(symbol,fontSize=17.sp,color=if(active)DutBlue else Ink)}
 }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun CampusMapScreen(initialFrom:String="",initialTo:String="",routeRequest:Int=0,heat:List<HeatCell> = emptyList(),heatMode:Boolean=false,onInfo:()->Unit={},heatControls:@Composable ()->Unit={}){
 val c=LocalContext.current
 val data by produceState<CampusData?>(null){value=withContext(Dispatchers.IO){runCatching{CampusData.cached(c)}.getOrNull()}}
 var from by rememberSaveable{mutableStateOf(initialFrom)};var to by rememberSaveable{mutableStateOf(initialTo)}
 var appliedRoute by rememberSaveable{mutableIntStateOf(0)}
 var picking by remember{mutableStateOf<String?>(null)};var section by rememberSaveable{mutableStateOf("A")}
 var tapped by rememberSaveable{mutableStateOf<String?>(null)}
 // A new command must replace a manually edited route even if its endpoints
 // happen to equal the previous AI command. Re-entry alone preserves the view.
 LaunchedEffect(routeRequest){if(appliedRoute!=routeRequest){from=initialFrom;to=initialTo;picking=null;tapped=null;appliedRoute=routeRequest}}
 LaunchedEffect(heatMode){picking=null;tapped=null}
 var scale by rememberSaveable{mutableFloatStateOf(1f)};var pan by rememberSaveable(stateSaver=androidx.compose.runtime.saveable.Saver<Offset,ArrayList<Float>>(save={arrayListOf(it.x,it.y)},restore={Offset(it[0],it[1])})){mutableStateOf(Offset.Zero)}
 var headingMode by rememberSaveable{mutableStateOf(false)};var follow by rememberSaveable{mutableStateOf(false)}
 var azimuth by remember{mutableFloatStateOf(0f)};var hasHeading by remember{mutableStateOf(false)};var headingReliable by remember{mutableStateOf(true)}
 var location by remember{mutableStateOf<Location?>(null)};var now by remember{mutableLongStateOf(SystemClock.elapsedRealtime())}
 var locationMessage by remember{mutableStateOf("正在获取定位…")};var permissionTick by remember{mutableIntStateOf(0)}
 var routes by remember{mutableStateOf(emptyList<CampusRoute>())};var routeIndex by rememberSaveable(from,to){mutableIntStateOf(0)}
 val selectedRoute=routes.getOrNull(routeIndex);val route=selectedRoute?.points.orEmpty()
 var routeMessage by remember{mutableStateOf("")}
 var routeStart by remember{mutableStateOf<MapNode?>(null)};var routeEnd by remember{mutableStateOf<MapNode?>(null)}
 var viewport by remember{mutableStateOf(IntSize.Zero)}
 val permission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){permissionTick++;locationMessage=if(it.values.any{v->v})"正在获取定位…"else"定位出现问题 · 未允许位置权限"}
 fun requestLocation(){permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION))}
 LaunchedEffect(Unit){if(c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED)requestLocation()}
 LaunchedEffect(Unit){while(true){now=SystemClock.elapsedRealtime();delay(3000)}}
 val lifecycle=androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
 // Subscribe in BOTH orientation modes, only while this screen is in the foreground.
 DisposableEffect(lifecycle,permissionTick){
  val lm=c.getSystemService(LocationManager::class.java);val sm=c.getSystemService(SensorManager::class.java)
  val sensor=sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?:sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
  val listener=object:LocationListener{
   override fun onLocationChanged(value:Location){
    CampusLocationFeed.publish(value)
    val previous=location
    if(previous==null||SystemClock.elapsedRealtime()-previous.elapsedRealtimeNanos/1000000>15000||value.accuracy<=previous.accuracy*1.5f){location=value;now=SystemClock.elapsedRealtime();locationMessage=""}
   }
   override fun onProviderDisabled(provider:String){if(!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)&&!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))locationMessage="定位出现问题 · 系统定位已关闭"}
   override fun onProviderEnabled(provider:String){locationMessage="正在获取定位…"}
  }
  val orientation=object:SensorEventListener{
   override fun onAccuracyChanged(sensor:Sensor?,accuracy:Int){headingReliable=accuracy!=SensorManager.SENSOR_STATUS_UNRELIABLE}
   override fun onSensorChanged(event:SensorEvent){
    val raw=FloatArray(9);val remapped=FloatArray(9);SensorManager.getRotationMatrixFromVector(raw,event.values)
    @Suppress("DEPRECATION") val rotation=(c.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
    val axes=when(rotation){AndroidSurface.ROTATION_90->SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X;AndroidSurface.ROTATION_180->SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y;AndroidSurface.ROTATION_270->SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X;else->SensorManager.AXIS_X to SensorManager.AXIS_Y}
    SensorManager.remapCoordinateSystem(raw,axes.first,axes.second,remapped)
    val angles=FloatArray(3);SensorManager.getOrientation(remapped,angles)
    // Magnetic sensor heading -> true north used by the campus coordinates.
    val declination=GeomagneticField(CAMPUS_LAT.toFloat(),CAMPUS_LON.toFloat(),0f,System.currentTimeMillis()).declination
    val target=Math.toDegrees(angles[0].toDouble()).toFloat()+declination
    azimuth=target;hasHeading=true
   }
  }
  fun stop(){runCatching{lm.removeUpdates(listener)};sm.unregisterListener(orientation)}
  fun start(){
   stop();if(sensor!=null)sm.registerListener(orientation,sensor,SensorManager.SENSOR_DELAY_UI)
   if(c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED&&c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){locationMessage="定位出现问题 · 未允许位置权限";return}
   var subscribed=false
   listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,"fused").forEach{provider->
    runCatching{if(lm.isProviderEnabled(provider)){
     lm.getLastKnownLocation(provider)?.takeIf{SystemClock.elapsedRealtime()-it.elapsedRealtimeNanos/1000000 in 0..120000}?.let{listener.onLocationChanged(it)}
     lm.requestLocationUpdates(provider,1500L,1f,listener,android.os.Looper.getMainLooper());subscribed=true
    }}
   }
   if(!subscribed)locationMessage="定位出现问题 · 请开启系统定位"
  }
  val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)start()else if(event==Lifecycle.Event.ON_PAUSE)stop()}
  lifecycle.addObserver(observer);if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))start()
  onDispose{lifecycle.removeObserver(observer);stop()}
 }
 val valid=usable(location,now)
 val user=if(valid)location?.let{MapNode(-1,it.latitude,it.longitude)}else null
 val status=when{
  locationMessage.isNotBlank()->locationMessage
  location==null->"定位出现问题 · 正在等待信号"
  location!!.latitude !in 40.673..40.695||location!!.longitude !in 122.107..122.132->"用户不在大盘"
  !valid->"定位出现问题 · 信号过旧或精度不足"
  !hasHeading->"定位 ±${location!!.accuracy.toInt()}m · 无朝向传感器"
  !headingReliable->"定位 ±${location!!.accuracy.toInt()}m · 朝向待校准"
  else->"定位 ±${location!!.accuracy.toInt()}m"
 }
 val places=remember(data){data?.places?:emptyMap()}
 // Re-route automatically on selection; a moving start is quantized to avoid jitter.
 val userCell=user?.let{(world(it).x/15).toInt() to (world(it).y/15).toInt()}
 LaunchedEffect(data,from,to,if(from=="我的位置")userCell else null){
  routes=emptyList();routeStart=null;routeEnd=null;routeMessage=""
  if(from.isBlank()||to.isBlank())return@LaunchedEffect
  val a=if(from=="我的位置")user else places[from.uppercase()];val b=places[to.uppercase()]
  if(a==null||b==null){routeMessage=if(from=="我的位置")"定位可用后显示路线"else"请选择地图中的楼号";return@LaunchedEffect}
  if(from==to){routeMessage="起点和终点相同";return@LaunchedEffect}
  routeMessage="正在计算参考路线…"
  val computed=withContext(Dispatchers.Default){data?.routes(a,b){ensureActive()}?:emptyList()}
  routes=computed;routeIndex=routeIndex.coerceAtMost((computed.size-1).coerceAtLeast(0));routeStart=a;routeEnd=b
  routeMessage=if(computed.isEmpty())"公开路网未连通，暂无法给出路线"else""
 }
 val dark=AppAppearance.dark;val ground=if(dark)Color(0xFF151D25)else Color(0xFFF0F3ED)
 val lawn=if(dark)Color(0xFF20392E)else Color(0xFFDEECD7);val water=if(dark)Color(0xFF203E50)else Color(0xFFC7E4ED)
 val road=if(dark)Color(0xFF45505A)else Color.White;val roadEdge=if(dark)Color(0xFF27313C)else Color(0xFFD9DFD9)
 val outline=if(dark)Color(0xFF58636D)else Color(0xFFB7C4CE);val textColor=Ink;val routeColor=DutBlue
 // Read animated values only from the drawing phase; do not recompose the UI at 60 Hz.
 val latestAzimuth by rememberUpdatedState(azimuth)
 val latestHeadingMode by rememberUpdatedState(headingMode)
 val displayHeading=remember{mutableFloatStateOf(0f)}
 val cameraRotation=remember{mutableFloatStateOf(CAMPUS_ROTATION)}
 LaunchedEffect(hasHeading){
  if(!hasHeading){cameraRotation.floatValue=CAMPUS_ROTATION;return@LaunchedEffect}
  displayHeading.floatValue=latestAzimuth
  var previous=withFrameNanos{it}
  while(isActive){withFrameNanos{frame->
   val seconds=(frame-previous)/1_000_000_000f;previous=frame
   displayHeading.floatValue=smoothHeading(displayHeading.floatValue,latestAzimuth,seconds)
   val target=if(latestHeadingMode)-latestAzimuth else CAMPUS_ROTATION
   cameraRotation.floatValue=smoothHeading(cameraRotation.floatValue,target,seconds)
  }}
 }
 val rotatedBounds=remember(places){places.values.map{turn(world(it),CAMPUS_ROTATION)}}
 val boundsWidth=if(rotatedBounds.isEmpty())1100f else rotatedBounds.maxOf{it.x}-rotatedBounds.minOf{it.x}+200f
 val boundsHeight=if(rotatedBounds.isEmpty())1450f else rotatedBounds.maxOf{it.y}-rotatedBounds.minOf{it.y}+160f
 val worldCenter=remember(places){if(places.isEmpty())Offset.Zero else {val p=places.values.map(::world);Offset((p.maxOf{it.x}+p.minOf{it.x})/2,(p.maxOf{it.y}+p.minOf{it.y})/2)}}
 AssistantSurface("地图",JSONObject().put("ready",data!=null).put("from",from).put("to",to).put("tapped",tapped).put("orientation",if(headingMode)"跟随"else"固定").put("routeCount",routes.size).put("routeIndex",routeIndex+1).put("routeMeters",JSONArray(routes.map{it.meters.roundToInt()})).put("locationAvailable",valid).put("places",JSONArray(places.keys.toList())),listOf(
  surfaceTool("route","设置参考路线，from=我的位置表示实时定位，或填真实楼号；to填真实楼号。省略沿用当前值，空字符串仅用于明确清除","from,to",false){a->val f=AssistantRoutes.point(a.optString("from",from));val t=AssistantRoutes.point(a.optString("to",to));require((f.isBlank()||f=="我的位置"||f in places)&&(t.isBlank()||t in places)){"楼宇不在校园点位表中"};prepared("调整参考路线",JSONObject().put("from",f).put("to",t)){from=f;to=t;picking=null;"已设置起终点，页面计算校园参考路线"}},
  surfaceTool("view","orientation=固定/跟随，focus=自己/全校，zoom=0.65..7；地图始终平滑转向","orientation,focus,zoom",false){a->val o=a.optString("orientation",if(headingMode)"跟随"else"固定");require(o in listOf("固定","跟随"));val f=a.optString("focus","");require(f in listOf("","自己","全校"));val z=a.optDouble("zoom",scale.toDouble()).toFloat();require(z in .65f..7f);if(f=="自己")require(valid){"当前没有可用的校园定位"};prepared("调整地图视角",a){headingMode=o=="跟随";scale=z;if(f.isNotBlank()){follow=f=="自己";pan=Offset.Zero;if(f=="全校")scale=1f};"地图视角已调整"}},
  surfaceTool("route_select","切换已计算出的路线；index从1开始，1为最短参考路线，2为备选；省略则切换下一条，只有一条时不能切换","index",false){a->require(routes.size>1){"当前没有可切换的备选路线"};val index=a.optInt("index",(routeIndex+1)%routes.size+1)-1;require(index in routes.indices){"该路线不存在"};val snapshot=routes;prepared("切换参考路线",a){check(routes==snapshot){"路线已重新计算，请重试"};routeIndex=index;"已切换到${if(index==0)"最短路线"else"备选路线"}，约 ${routes[index].meters.roundToInt()} 米"}}
 ))
 Box(Modifier.fillMaxSize().background(ground)){
  val density=androidx.compose.ui.platform.LocalDensity.current
  val topReserve=with(density){130.dp.toPx()}
  val bottomReserve=with(density){65.dp.toPx()}
  val base=min((viewport.width-with(density){40.dp.toPx()})/boundsWidth,(viewport.height-topReserve-bottomReserve)/boundsHeight).coerceAtLeast(.05f)
  val focus=if(follow&&user!=null)world(user)else worldCenter
  val origin=Offset(viewport.width/2f,topReserve+(viewport.height-topReserve-bottomReserve)/2f)
  fun screen(n:MapNode)=origin+turn((world(n)-focus)*base*scale,cameraRotation.floatValue)+pan
  fun wingLabels():Map<String,Offset>{
   return a01LabelPositions(places.filterKeys{it.startsWith("A01-")}.mapValues{screen(it.value)})
  }
  val currentWingLabels by rememberUpdatedState<()->Map<String,Offset>>({wingLabels()})
  val currentScreen by rememberUpdatedState<(MapNode)->Offset>({screen(it)})
  val currentOrigin by rememberUpdatedState(origin)
  val detachOffset by rememberUpdatedState<()->Offset>({if(follow&&user!=null)turn((worldCenter-world(user))*base*scale,cameraRotation.floatValue)else Offset.Zero})
  Canvas(Modifier.fillMaxSize().clipToBounds().onSizeChanged{viewport=it}
   .pointerInput(Unit){detectTransformGestures{centroid,delta,zoom,_->
    if(follow){pan+=detachOffset();follow=false}
    val next=(scale*zoom).coerceIn(.65f,7f);val factor=next/scale
    pan=(centroid-currentOrigin)-(centroid-currentOrigin-pan)*factor+delta;scale=next
   }}
   .pointerInput(places,heatMode){detectTapGestures{point->if(heatMode)return@detectTapGestures;val wing=currentWingLabels().minByOrNull{(it.value-point).getDistance()}?.takeIf{(it.value-point).getDistance()<18.dp.toPx()};val nearest=places.minByOrNull{(currentScreen(it.value)-point).getDistance()};tapped=wing?.key?:nearest?.takeIf{(currentScreen(it.value)-point).getDistance()<28.dp.toPx()}?.key;picking=null}}
  ){
   val d=data?:return@Canvas
   fun path(w:MapWay)=Path().apply{w.ids.mapNotNull{d.nodes[it]}.map(::screen).forEachIndexed{i,p->if(i==0)moveTo(p.x,p.y)else lineTo(p.x,p.y)}}
   d.ways.filter{it.tags["natural"]=="water"||it.tags["landuse"] in listOf("grass","forest","meadow")||it.tags["leisure"] in listOf("park","garden","pitch")}.forEach{w->val p=path(w);p.close();drawPath(p,if(w.tags["natural"]=="water")water else lawn)}
   d.ways.filter{it.tags["highway"]!=null}.forEach{w->val p=path(w);val small=w.tags["highway"] in listOf("footway","path","steps");val width=((if(small)3f else 7f)*base*scale).coerceIn(if(small)1.5.dp.toPx()else 3.dp.toPx(),if(small)5.dp.toPx()else 12.dp.toPx());drawPath(p,roadEdge,style=Stroke(width+1.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round));drawPath(p,road,style=Stroke(width,cap=StrokeCap.Round,join=StrokeJoin.Round))}
   d.ways.filter{it.tags["building"]!=null && it.tags["building"]!="roof"}.forEach{w->
    val p=path(w);p.close();val name=w.tags["name"].orEmpty()
    val fill=if(!heatMode&&(name==from||name==to||name==tapped))routeColor.copy(alpha=.28f)else if(dark)Color(0xFF34424E)else when(name.firstOrNull()){'B'->Color(0xFFD7E2EE);'C','D'->Color(0xFFE7DFD2);else->Color(0xFFD2DFE4)}
    drawPath(p,fill);drawPath(p,outline,style=Stroke(.7.dp.toPx()))
   }
   // Covered walkway is visible above road fill, and also participates in routing.
   d.ways.filter{it.tags["building"]=="roof"}.forEach{w->val p=path(w);p.close();drawPath(p,if(dark)Color(0xFF756344).copy(alpha=.65f)else Color(0xFFE5CE9E).copy(alpha=.8f));drawPath(p,if(dark)Color(0xFFCDB179)else Color(0xFFB39657),style=Stroke(1.dp.toPx()))}
   if(heatMode){
    val dwell=heat.filter{it.seconds>0};val maximum=dwell.maxOfOrNull{it.seconds}?:1
    dwell.forEach{cell->
     val intensity=(ln(1.0+cell.seconds)/ln(1.0+maximum)).toFloat()
     val color=when{intensity<.35f->lerp(Color(0xFF4B9FE1),Color(0xFF35C2A0),intensity/.35f);intensity<.7f->lerp(Color(0xFF35C2A0),Color(0xFFF7C64A),(intensity-.35f)/.35f);else->lerp(Color(0xFFF7C64A),Color(0xFFF05A41),(intensity-.7f)/.3f)}
     val point=screen(MapNode(-1,HeatRules.latitude(cell.y),HeatRules.longitude(cell.x)))
     val radius=(HeatRules.GRID*base*scale*1.4).toFloat().coerceAtLeast(7.dp.toPx())
     drawCircle(Brush.radialGradient(listOf(color.copy(alpha=.72f),color.copy(alpha=.35f),Color.Transparent),point,radius),radius,point)
    }
   }
   if(!heatMode&&route.size>1){
    val line=Path().apply{route.map(::screen).forEachIndexed{i,p->if(i==0)moveTo(p.x,p.y)else lineTo(p.x,p.y)}}
    drawPath(line,CardSurface,style=Stroke(7.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round));drawPath(line,routeColor,style=Stroke(4.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
    listOfNotNull(routeStart?.let{it to route.first()},routeEnd?.let{it to route.last()}).forEach{(a,b)->drawLine(routeColor.copy(alpha=.7f),screen(a),screen(b),2.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(),4.dp.toPx())))}
   }
   if(!heatMode)listOf(from to routeStart,to to routeEnd).forEachIndexed{i,(_,n)->if(n!=null){val point=screen(n);drawCircle(if(i==0)Color(0xFF26A888)else routeColor,7.dp.toPx(),point);drawCircle(Color.White,7.dp.toPx(),point,style=Stroke(2.dp.toPx()))}}
   // Reserve A01 as a group before other labels, above endpoint markers.
   val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply{textSize=11.sp.toPx();textAlign=android.graphics.Paint.Align.CENTER;typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)}
   val occupied=mutableListOf<Rect>()
   fun drawLabel(label:String,point:Offset){
    paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=3.dp.toPx();paint.color=ground.toArgb();drawContext.canvas.nativeCanvas.drawText(label,point.x,point.y+4.dp.toPx(),paint)
    paint.style=android.graphics.Paint.Style.FILL;paint.color=textColor.toArgb();drawContext.canvas.nativeCanvas.drawText(label,point.x,point.y+4.dp.toPx(),paint)
   }
   wingLabels().forEach{(name,point)->
    occupied.add(Rect(point.x-9.dp.toPx(),point.y-8.dp.toPx(),point.x+9.dp.toPx(),point.y+9.dp.toPx()))
    drawLabel(name.takeLast(1),point-Offset(0f,4.dp.toPx()+(paint.ascent()+paint.descent())/2))
   }
   places.entries.filter{!it.key.startsWith("A01-")}.sortedBy{if(it.key=="A01")0 else if(it.key==from||it.key==to)1 else 2}.forEach{(name,node)->val point=screen(node);val label=name;val width=paint.measureText(label);val rect=Rect(point.x-width/2-2.dp.toPx(),point.y-6.dp.toPx(),point.x+width/2+2.dp.toPx(),point.y+8.dp.toPx())
    if(point.x in 0f..size.width&&point.y in 0f..size.height&&occupied.none{it.overlaps(rect)}){
     occupied.add(rect);drawLabel(label,point)
    }
   }
   if(user!=null){val point=screen(user);val accuracy=(location!!.accuracy*base*scale).coerceAtMost(100.dp.toPx());drawCircle(routeColor.copy(alpha=.10f),accuracy,point);drawCircle(routeColor.copy(alpha=.22f),accuracy,point,style=Stroke(1.dp.toPx()))
    if(hasHeading){rotate(displayHeading.floatValue+cameraRotation.floatValue,point){val cone=Path().apply{moveTo(point.x,point.y-24.dp.toPx());lineTo(point.x-14.dp.toPx(),point.y-5.dp.toPx());quadraticTo(point.x,point.y+4.dp.toPx(),point.x+14.dp.toPx(),point.y-5.dp.toPx());close()};drawPath(cone,routeColor.copy(alpha=.30f));val arrow=Path().apply{moveTo(point.x,point.y-14.dp.toPx());lineTo(point.x-4.dp.toPx(),point.y-5.dp.toPx());lineTo(point.x+4.dp.toPx(),point.y-5.dp.toPx());close()};drawPath(arrow,routeColor)}}
    drawCircle(Color.White,7.dp.toPx(),point);drawCircle(routeColor,5.dp.toPx(),point)
   }
  }
  Column(Modifier.align(Alignment.TopCenter).padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
   if(heatMode)heatControls()
   if(!heatMode)Surface(shape=AppShape(18.dp),color=CardSurface.copy(alpha=.97f),shadowElevation=3.dp){Column(Modifier.padding(horizontal=8.dp,vertical=4.dp)){
    Row(verticalAlignment=Alignment.CenterVertically){
     Column(Modifier.weight(1f)){
      Row(Modifier.fillMaxWidth().heightIn(min=40.dp).clickable{picking=if(picking=="from")null else"from";tapped=null},verticalAlignment=Alignment.CenterVertically){Text("起",fontSize=12.sp,color=Muted);Text(from.ifBlank{"选择起点"},Modifier.weight(1f).padding(start=10.dp),fontSize=14.sp,color=if(from.isBlank())Muted else Ink);Text("⌄",color=Muted)}
      HorizontalDivider(color=Hairline)
      Row(Modifier.fillMaxWidth().heightIn(min=40.dp).clickable{picking=if(picking=="to")null else"to";tapped=null},verticalAlignment=Alignment.CenterVertically){Text("终",fontSize=12.sp,color=Muted);Text(to.ifBlank{"选择终点"},Modifier.weight(1f).padding(start=10.dp),fontSize=14.sp,color=if(to.isBlank())Muted else Ink);Text("⌄",color=Muted)}
     }
     IconButton(onClick={from="";to="";picking=null;tapped=null},modifier=Modifier.semantics{contentDescription="清除起点和终点"}){LineIcon(AppGlyph.Close,Modifier.size(18.dp),Muted)}
    }
    if(picking!=null){
     Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(2.dp)){
      if(picking=="from")TextButton(onClick={from="我的位置";picking=null}){Text("我的位置",fontSize=12.sp)}
      places.keys.map{it.take(1)}.distinct().sorted().forEach{letter->FilterChip(section==letter,{section=letter},label={Text(letter)})}
     }
     FlowRow(Modifier.fillMaxWidth().heightIn(max=146.dp).verticalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
      val options=remember(places,section){places.keys.filter{it.startsWith(section)}.sortedWith(compareBy<String>{it.substringBefore('-')}.thenBy{if(it.startsWith("A01-"))"NSWE".indexOf(it.last())+1 else 0})}
      options.forEach{name->
       val label=if(name.startsWith("A01-"))name.takeLast(1)else name.drop(1)
       TextButton(onClick={if(picking=="from")from=name else to=name;picking=null},modifier=(if(section=="A")Modifier.width(44.dp)else Modifier).semantics{contentDescription=name}){Text(label,fontSize=14.sp)}
      }
     }
    }
    tapped?.let{name->Row(verticalAlignment=Alignment.CenterVertically){Text(name,Modifier.weight(1f).padding(start=8.dp),fontWeight=FontWeight.Bold);TextButton(onClick={from=name;tapped=null}){Text("设为起点")};TextButton(onClick={to=name;tapped=null}){Text("到这里")}}}
   }}
   Surface(shape=CircleShape,color=CardSurface.copy(alpha=.90f)){Text(status,Modifier.padding(horizontal=10.dp,vertical=5.dp),fontSize=11.sp,color=Muted)}
  }
  Column(Modifier.align(Alignment.TopEnd).padding(top=146.dp,end=6.dp),verticalArrangement=Arrangement.spacedBy(6.dp),horizontalAlignment=Alignment.CenterHorizontally){
   MapControl(if(headingMode)"跟随朝向，点击固定校园方向"else"固定校园方向，点击跟随朝向",if(headingMode)"↑"else"N",headingMode){headingMode=!headingMode;if(headingMode&&valid){follow=true;pan=Offset.Zero}}
   MapControl("回到我的位置","◎",follow){if(valid){follow=true;pan=Offset.Zero;scale=max(scale,1.5f)}else requestLocation()}
  }
  Column(Modifier.align(Alignment.BottomStart).padding(start=10.dp,end=68.dp,bottom=10.dp+LocalFloatingAiHeight.current),verticalArrangement=Arrangement.spacedBy(5.dp)){
   if(!heatMode&&(routeMessage.isNotBlank()||selectedRoute!=null))Surface(shape=AppShape(10.dp),color=CardSurface.copy(alpha=.96f)){
    Row(Modifier.padding(start=10.dp,end=if(routes.size>1)2.dp else 10.dp),verticalAlignment=Alignment.CenterVertically){
     Text(selectedRoute?.let{"${if(routeIndex==0)"最短"else"备选"} · 约 ${it.meters.roundToInt()} 米"}?:routeMessage,Modifier.weight(1f,false).padding(vertical=8.dp),fontSize=12.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
     if(routes.size>1)TextButton(onClick={routeIndex=(routeIndex+1)%routes.size},contentPadding=PaddingValues(horizontal=10.dp,vertical=0.dp),modifier=Modifier.height(36.dp)){Text("⇄ 切换",fontSize=12.sp)}
    }
   }

  }
  Column(Modifier.align(Alignment.BottomEnd).padding(end=6.dp,bottom=6.dp+LocalFloatingAiHeight.current),verticalArrangement=Arrangement.spacedBy(6.dp)){
   MapControl("全校视图","⛶"){follow=false;pan=Offset.Zero;scale=1f;picking=null;tapped=null}
   MapControl("地图信息","i",onClick=onInfo)
  }
  if(data==null)Text("正在加载校园地图…",Modifier.align(Alignment.Center),color=Muted)
 }
}

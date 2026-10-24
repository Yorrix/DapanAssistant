package cn.dapan.assistant

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.database.sqlite.*
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.*
import java.time.*
import java.util.concurrent.Executors

object CampusLocationFeed {
 var latest:Location?=null;private set
 var recorder:((Location)->Unit)?=null
 fun publish(location:Location){
  val old=latest;val now=SystemClock.elapsedRealtime()
  if(old==null||now-old.elapsedRealtimeNanos/1000000>15000||location.accuracy<=old.accuracy*1.5f){latest=Location(location);recorder?.invoke(location)}
 }
}

object HeatState {
 var running by mutableStateOf(false)
 var status by mutableStateOf("尚未开始记录")
 var revision by mutableIntStateOf(0)
}
class HeatStore(c:Context):SQLiteOpenHelper(c,"personal-heat.db",null,1){
 override fun onCreate(db:SQLiteDatabase){db.execSQL("CREATE TABLE cells(day TEXT,x INTEGER,y INTEGER,samples INTEGER,seconds INTEGER,PRIMARY KEY(day,x,y))")}
 override fun onUpgrade(db:SQLiteDatabase,old:Int,new:Int){}
 // Daily aggregates remain on-device until explicitly cleared. Never discard old ranges.
 fun add(f:HeatFix,seconds:Int){
  val (x,y)=HeatRules.cell(f);val zone=ZoneId.of("Asia/Shanghai")
  val day=Instant.ofEpochMilli(f.time).atZone(zone).toLocalDate()
  val midnight=day.atStartOfDay(zone).toInstant().toEpochMilli()
  val currentSeconds=minOf(seconds,((f.time-midnight)/1000).toInt().coerceAtLeast(0))
  val db=writableDatabase;db.beginTransaction()
  fun put(date:LocalDate,samples:Int,dwell:Int){
   db.execSQL("INSERT OR IGNORE INTO cells VALUES(?,?,?,0,0)",arrayOf<Any>(date.toString(),x,y))
   db.execSQL("UPDATE cells SET samples=samples+?,seconds=seconds+? WHERE day=? AND x=? AND y=?",arrayOf<Any>(samples,dwell,date.toString(),x,y))
  }
  try{put(day,1,currentSeconds);if(seconds>currentSeconds)put(day.minusDays(1),0,seconds-currentSeconds);db.setTransactionSuccessful()}finally{db.endTransaction()}
 }
 fun read(start:LocalDate,end:LocalDate):List<HeatCell>{
  require(!end.isBefore(start));val result=mutableListOf<HeatCell>()
  readableDatabase.rawQuery("SELECT x,y,SUM(samples),SUM(seconds) FROM cells WHERE day>=? AND day<=? GROUP BY x,y",arrayOf(start.toString(),end.toString())).use{while(it.moveToNext())result.add(HeatCell(it.getInt(0),it.getInt(1),it.getInt(2),it.getInt(3)))}
  return result
 }
 fun clear(){writableDatabase.delete("cells",null,null)}
}
class HeatService:Service(),LocationListener {
 private val main=Handler(Looper.getMainLooper());private val worker=Executors.newSingleThreadExecutor();private lateinit var store:HeatStore
 private var previous:HeatFix?=null;@Volatile private var ended=false
 private var lastSignal=SystemClock.elapsedRealtime()
 private val signalWatch=object:Runnable{override fun run(){
  if(ended)return
  if(previous==null&&SystemClock.elapsedRealtime()-lastSignal>30000)HeatState.status="尚未收到校园定位 · 请检查系统定位"
  else if(previous!=null&&!HeatRules.accepted(previous!!,SystemClock.elapsedRealtime()))HeatState.status="定位已中断 · 等待新位置，不补算时长"
  main.postDelayed(this,30000)
 }}
 override fun onBind(intent:Intent?)=null
 override fun onCreate(){super.onCreate();store=HeatStore(this)}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
  if(intent?.action=="stop"){HeatState.status="已暂停，足迹保留在本机";stopSelf();return START_NOT_STICKY}
  if(HeatState.running)return START_NOT_STICKY
  if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){HeatState.status="需要精确位置权限才能绘制校园足迹";stopSelf();return START_NOT_STICKY}
  val channel="dapan-heat";getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"校园足迹记录",NotificationManager.IMPORTANCE_LOW))
  val stop=PendingIntent.getService(this,0,Intent(this,HeatService::class.java).setAction("stop"),PendingIntent.FLAG_IMMUTABLE)
  val open=PendingIntent.getActivity(this,900,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
  startForeground(840,NotificationCompat.Builder(this,channel).setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("正在记录个人校园足迹").setContentText("仅保存本机 · 点击暂停可停止定位").setContentIntent(open).setOngoing(true).addAction(0,"暂停记录",stop).build())
  HeatState.running=true;HeatState.status="等待定位 · 尚未开始计入"
  CampusLocationFeed.recorder={onLocationChanged(it)}
  CampusLocationFeed.latest?.let{onLocationChanged(it)}
  
  try{val manager=getSystemService(LocationManager::class.java);var requested=false
   for(provider in listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER,"fused")){
    // Providers are independent: one unavailable provider must not stop all recording.
    runCatching{if(manager.isProviderEnabled(provider)){
     manager.getLastKnownLocation(provider)?.let{onLocationChanged(it)}
     manager.requestLocationUpdates(provider,15000L,0f,this,Looper.getMainLooper());requested=true
    }}
   }
   if(!requested){HeatState.status="系统定位未开启，请开启后再记录";stopSelf()}
  }catch(e:Exception){HeatState.status="定位启动失败，请检查权限与系统定位";stopSelf()}
  main.postDelayed(signalWatch,30000)
  return START_NOT_STICKY
 }
 override fun onLocationChanged(location:Location){
  if(ended)return
  lastSignal=SystemClock.elapsedRealtime()
  val f=HeatFix(location.latitude,location.longitude,if(location.hasAccuracy())location.accuracy else Float.MAX_VALUE,location.elapsedRealtimeNanos/1000000,location.time)
  if(!HeatRules.accepted(f,SystemClock.elapsedRealtime())){
   // Ignore a coarse provider while a recent precise fix still supports recording.
   if(previous?.let{HeatRules.accepted(it,SystemClock.elapsedRealtime())}==true&&(f.accuracy>60f||f.elapsed<previous!!.elapsed))return
   // A coarse network fix must not repeatedly erase a valid GPS dwell interval.
   val fresh=f.elapsed<=SystemClock.elapsedRealtime()&&SystemClock.elapsedRealtime()-f.elapsed<=120000
   val outside=f.lat !in 40.675..40.692||f.lon !in 122.110..122.128
   if(fresh&&f.accuracy in 0f..60f&&outside)previous=null
   HeatState.status=when{!fresh->"定位信号过旧，等待更新";f.accuracy>60->"定位较粗，等待精确位置";outside->"当前不在校园，暂不计入";else->"定位不可用，等待更新"}
   return
  }
  HeatState.status="正在记录 · ±${f.accuracy.toInt()}米"
  if(previous!=null&&f.elapsed-previous!!.elapsed<25000)return
  val seconds=HeatRules.seconds(previous,f);previous=f
  worker.execute{try{store.add(f,seconds);main.post{if(!ended){HeatState.revision++;HeatState.status="正在记录 · ±${f.accuracy.toInt()}米"}}}catch(e:Exception){main.post{HeatState.status="足迹保存失败，已停止记录";stopSelf()}}}
 }
 override fun onProviderDisabled(provider:String){previous=null;if(listOf(LocationManager.GPS_PROVIDER,LocationManager.NETWORK_PROVIDER).none{runCatching{getSystemService(LocationManager::class.java).isProviderEnabled(it)}.getOrDefault(false)})HeatState.status="定位信号不可用，等待恢复"}
 override fun onProviderEnabled(provider:String){}
 override fun onDestroy(){ended=true;main.removeCallbacks(signalWatch);CampusLocationFeed.recorder=null;HeatState.running=false;runCatching{getSystemService(LocationManager::class.java).removeUpdates(this)};worker.execute{store.close();main.post{HeatState.revision++}};worker.shutdown();super.onDestroy()}
}

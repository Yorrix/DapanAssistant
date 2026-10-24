package cn.dapan.assistant

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import org.xmlpull.v1.XmlPullParser
import android.util.Xml
import kotlin.math.*

data class MapNode(val id:Long,val lat:Double,val lon:Double)
data class MapWay(val ids:List<Long>,val tags:Map<String,String>)
class CampusData(c:Context){
 companion object {
  @Volatile private var snapshot:CampusData?=null
  fun cached(c:Context):CampusData=snapshot?:synchronized(this){snapshot?:CampusData(c.applicationContext).also{snapshot=it}}
 }
 val nodes=mutableMapOf<Long,MapNode>();val ways=mutableListOf<MapWay>();val graph=mutableMapOf<Long,MutableMap<Long,Double>>()
 init{val p=Xml.newPullParser();c.assets.open("panjin-enhanced.osm").use{p.setInput(it,"UTF-8");var ids:MutableList<Long>?=null;var tags=mutableMapOf<String,String>();while(p.eventType!=XmlPullParser.END_DOCUMENT){if(p.eventType==XmlPullParser.START_TAG)when(p.name){"node"->{val n=MapNode(p.getAttributeValue(null,"id").toLong(),p.getAttributeValue(null,"lat").toDouble(),p.getAttributeValue(null,"lon").toDouble());nodes[n.id]=n};"way"->{ids=mutableListOf();tags=mutableMapOf()};"nd"->ids?.add(p.getAttributeValue(null,"ref").toLong());"tag"->if(ids!=null)tags[p.getAttributeValue(null,"k")]=p.getAttributeValue(null,"v")};if(p.eventType==XmlPullParser.END_TAG && p.name=="way"){ways.add(MapWay(ids!!.toList(),tags.toMap()));ids=null};p.next()}}
  ways.filter{it.tags["highway"] in listOf("footway","pedestrian","service","living_street","residential","unclassified","path","steps") && it.tags["access"]!in listOf("no","private") && it.tags["foot"]!="no"}.forEach{w->w.ids.zipWithNext().forEach{(a,b)->val x=nodes[a];val y=nodes[b];if(x!=null&&y!=null){val d=distance(x,y);graph.getOrPut(a){mutableMapOf()}[b]=d;graph.getOrPut(b){mutableMapOf()}[a]=d}}}
 }
 val places by lazy {ways.filter{(it.tags["building"]!=null || it.tags["dapan:label_only"]=="yes") && it.tags["name"]?.matches(Regex("[A-H][0-9]{2}(-[NSEW])?"))==true}.associate{it.tags.getValue("name") to center(it)}}
 val buildings by lazy {ways.filter{it.tags["building"]!=null && it.tags["name"]!=null}.sortedBy{it.tags["name"]}}
 fun center(w:MapWay):MapNode{val ns=w.ids.distinct().mapNotNull{nodes[it]};return MapNode(-1,ns.map{it.lat}.average(),ns.map{it.lon}.average())}
 private val router by lazy{CampusRouter(nodes,graph)}
 fun route(a:MapWay,b:MapWay):List<MapNode> = route(center(a),center(b))
 fun route(a:MapNode,b:MapNode):List<MapNode> = routes(a,b).firstOrNull()?.points.orEmpty()
 fun routes(a:MapNode,b:MapNode,checkpoint:()->Unit={}):List<CampusRoute> = router.routes(a,b,checkpoint)
}
fun distance(a:MapNode,b:MapNode):Double{val dy=(a.lat-b.lat)*111320;val dx=(a.lon-b.lon)*111320*cos(Math.toRadians((a.lat+b.lat)/2));return hypot(dx,dy)}

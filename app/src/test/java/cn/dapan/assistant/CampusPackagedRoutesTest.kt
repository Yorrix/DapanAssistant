package cn.dapan.assistant

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.junit.Assert.*
import org.junit.Test

class CampusPackagedRoutesTest {
 @Test fun a01HasOneUnsplitFootprintAndFourAnchorsOnTheBuilding(){
  val asset=listOf(File("src/main/assets/panjin-enhanced.osm"),File("app/src/main/assets/panjin-enhanced.osm")).first{it.exists()}
  val root=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(asset).documentElement
  fun elements(parent:Element,tag:String):List<Element>{val list=parent.getElementsByTagName(tag);return (0 until list.length).map{list.item(it) as Element}}
  val nodes=elements(root,"node").associate{it.getAttribute("id") to (it.getAttribute("lon").toDouble() to it.getAttribute("lat").toDouble())}
  val ways=elements(root,"way").map{w->elements(w,"tag").associate{it.getAttribute("k") to it.getAttribute("v")} to elements(w,"nd").map{nodes.getValue(it.getAttribute("ref"))}}
  val footprint=ways.single{it.first["name"]=="A01"}.also{assertNotNull(it.first["building"])}.second
  val wings=ways.filter{it.first["name"]?.startsWith("A01-")==true}
  assertEquals(setOf("A01-N","A01-S","A01-W","A01-E"),wings.map{it.first["name"]}.toSet())
  wings.forEach{(tags,points)->
   assertNull("Unverified wing boundaries must not be rendered as buildings",tags["building"])
   assertEquals("yes",tags["dapan:label_only"]);assertEquals(1,points.size)
   val (x,y)=points.single();var inside=false
   footprint.zipWithNext().forEach{(a,b)->if((a.second>y)!=(b.second>y)&&x<(b.first-a.first)*(y-a.second)/(b.second-a.second)+a.first)inside=!inside}
   assertTrue("${tags["name"]} belongs on the building, not in the courtyard",inside)
  }
 }
 @Test fun campusRoutesUseRealRoadSegmentsAndDistancesIncludeApproaches(){
  val asset=listOf(File("src/main/assets/panjin-enhanced.osm"),File("app/src/main/assets/panjin-enhanced.osm")).first{it.exists()}
  val document=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(asset)
  fun elements(parent:Element,tag:String):List<Element>{val list=parent.getElementsByTagName(tag);return (0 until list.length).map{list.item(it) as Element}}
  val nodes=elements(document.documentElement,"node").associate{e->val id=e.getAttribute("id").toLong();id to MapNode(id,e.getAttribute("lat").toDouble(),e.getAttribute("lon").toDouble())}
  val graph=mutableMapOf<Long,MutableMap<Long,Double>>();val places=mutableMapOf<String,MapNode>()
  elements(document.documentElement,"way").forEach{w->
   val tags=elements(w,"tag").associate{it.getAttribute("k") to it.getAttribute("v")};val ids=elements(w,"nd").map{it.getAttribute("ref").toLong()}
   if(tags["highway"] in listOf("footway","pedestrian","service","living_street","residential","unclassified","path","steps") && tags["access"] !in listOf("no","private") && tags["foot"]!="no")ids.zipWithNext().forEach{(a,b)->val d=distance(nodes.getValue(a),nodes.getValue(b));graph.getOrPut(a){mutableMapOf()}[b]=d;graph.getOrPut(b){mutableMapOf()}[a]=d}
   val name=tags["name"];if(name!=null&&(tags["building"]!=null||tags["dapan:label_only"]=="yes")){val points=ids.distinct().map{nodes.getValue(it)};places[name]=MapNode(0,points.map{it.lat}.average(),points.map{it.lon}.average())}
  }
  val edges=graph.flatMap{(a,vs)->vs.keys.filter{a<it}.map{nodes.getValue(a) to nodes.getValue(it)}}
  val router=CampusRouter(nodes,graph)
  listOf("H03" to "B01","B03" to "A01-W","B19" to "A01-N","C01" to "E01","F02" to "A01-E","D08" to "A01-S").forEach{(from,to)->
   val a=places.getValue(from);val b=places.getValue(to);val started=System.nanoTime();val routes=router.routes(a,b)
   assertTrue("$from -> $to",routes.isNotEmpty());assertEquals(routes.sortedBy{it.meters},routes)
   routes.forEach{r->
    assertEquals(r.points.size,r.points.distinctBy{it.id}.size)
    val measured=distance(a,r.points.first())+r.points.zipWithNext().sumOf{distance(it.first,it.second)}+distance(r.points.last(),b)
    assertEquals(measured,r.meters,.05)
    r.points.zipWithNext().forEach{(x,y)->assertTrue("route segment must be on an existing road",edges.any{(u,v)->distance(x,roadProjection(x,u,v).second)<.05 && distance(y,roadProjection(y,u,v).second)<.05})}
   }
   println("$from -> $to: ${routes.map{it.meters.toInt()}} m; ${(System.nanoTime()-started)/1_000_000} ms")
  }
 }
}

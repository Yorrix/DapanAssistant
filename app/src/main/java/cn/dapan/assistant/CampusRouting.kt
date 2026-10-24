package cn.dapan.assistant

import java.util.PriorityQueue
import kotlin.math.*

internal fun roadComponents(graph:Map<Long,Map<Long,Double>>):List<Set<Long>> {
 val seen=mutableSetOf<Long>();val components=mutableListOf<Set<Long>>()
 graph.keys.forEach { start->if(start !in seen){
  val component=mutableSetOf<Long>();val queue=ArrayDeque<Long>();queue.add(start);seen.add(start)
  while(queue.isNotEmpty()){val n=queue.removeFirst();component.add(n);graph[n]?.keys?.forEach{if(seen.add(it))queue.add(it)}}
  components.add(component)
 }}
 return components
}

data class CampusRoute(val points:List<MapNode>,val meters:Double)

/** Project onto a road segment, rather than making walkers detour to its vertices. */
internal fun roadProjection(point:MapNode,a:MapNode,b:MapNode):Pair<Double,MapNode> {
 val xScale=cos(Math.toRadians(point.lat))
 val dx=(b.lon-a.lon)*xScale;val dy=b.lat-a.lat
 val length=dx*dx+dy*dy
 val t=if(length==0.0)0.0 else (((point.lon-a.lon)*xScale*dx+(point.lat-a.lat)*dy)/length).coerceIn(0.0,1.0)
 return t to MapNode(0,a.lat+(b.lat-a.lat)*t,a.lon+(b.lon-a.lon)*t)
}

/** Shortest route on the packaged walking graph; approach legs remain reference lines.
 * Alternatives are real, loop-free paths with a visibly different section, never a
 * slightly shifted duplicate from the two source maps. */
internal class CampusRouter(private val nodes:Map<Long,MapNode>,private val graph:Map<Long,Map<Long,Double>>) {
 private data class Edge(val a:Long,val b:Long)
 private data class Snap(val edge:Edge,val t:Double,val point:MapNode,val approach:Double)
 private data class Path(val ids:List<Long>,val meters:Double)
 private data class Network(val nodes:Map<Long,MapNode>,val graph:Map<Long,Map<Long,Double>>,val start:Long,val end:Long,val approach:Double,val path:Path)
 private fun edge(a:Long,b:Long)=if(a<b)Edge(a,b)else Edge(b,a)
 private val edges=graph.flatMap{(a,neighbors)->neighbors.keys.filter{a<it}.map{Edge(a,it)}}
 private val componentEdges=roadComponents(graph).map{part->edges.filter{it.a in part}}

 private fun snap(p:MapNode,part:List<Edge>):Snap?=part.map{e->
  val (t,n)=roadProjection(p,nodes.getValue(e.a),nodes.getValue(e.b));Snap(e,t,n,distance(p,n))
 }.minByOrNull{it.approach}?.takeIf{it.approach<=100}

 private fun shortest(g:Map<Long,Map<Long,Double>>,start:Long,end:Long,blocked:Set<Edge> = emptySet()):Path? {
  val distances=mutableMapOf(start to 0.0);val previous=mutableMapOf<Long,Long>()
  val queue=PriorityQueue<Pair<Long,Double>>(compareBy<Pair<Long,Double>>{it.second}.thenBy{it.first});queue.add(start to 0.0)
  while(queue.isNotEmpty()){
   val (u,cost)=queue.remove();if(cost>distances.getValue(u))continue
   if(u==end){val ids=mutableListOf(end);var at=end;while(at!=start){at=previous.getValue(at);ids.add(at)};return Path(ids.reversed(),cost)}
   g[u]?.forEach{(v,w)->
    if(edge(u,v) !in blocked){
     val next=cost+w
     if(next<(distances[v]?:Double.POSITIVE_INFINITY)){distances[v]=next;previous[v]=u;queue.add(v to next)}
    }
   }
  }
  return null
 }

 private fun network(a:MapNode,b:MapNode,part:List<Edge>):Network? {
  val first=snap(a,part)?:return null;val last=snap(b,part)?:return null
  val ns=nodes.toMutableMap();val g=graph.mapValues{it.value.toMutableMap()}.toMutableMap();var nextId=Long.MIN_VALUE
  val snaps=listOf(first,last)
  val ids=snaps.map{s->when{ s.t<1e-7->s.edge.a;s.t>1-1e-7->s.edge.b;else->{val id=nextId++;ns[id]=s.point.copy(id=id);id}}}
  snaps.indices.groupBy{snaps[it].edge}.forEach{(e,indices)->
   val weight=graph.getValue(e.a).getValue(e.b)
   g.getValue(e.a).remove(e.b);g.getValue(e.b).remove(e.a)
   val splits=(listOf(0.0 to e.a,1.0 to e.b)+indices.map{snaps[it].t to ids[it]}).distinct().sortedBy{it.first}
   splits.zipWithNext().forEach{(x,y)->if(x.second!=y.second){val w=(y.first-x.first)*weight;g.getOrPut(x.second){mutableMapOf()}[y.second]=w;g.getOrPut(y.second){mutableMapOf()}[x.second]=w}}
  }
  val path=shortest(g,ids[0],ids[1])?:return null
  return Network(ns,g,ids[0],ids[1],first.approach+last.approach,path)
 }

 fun routes(a:MapNode,b:MapNode,checkpoint:()->Unit={}):List<CampusRoute> {
  val networks=componentEdges.mapNotNull{checkpoint();network(a,b,it)}.sortedBy{it.path.meters+it.approach}
  val primary=networks.firstOrNull()?:return emptyList()
  fun result(n:Network,p:Path)=CampusRoute(p.ids.map{n.nodes.getValue(it)},p.meters+n.approach)
  val best=result(primary,primary.path);val baseline=best.points.zipWithNext()
  if(baseline.isEmpty())return listOf(best)
  fun distinctEnough(candidate:CampusRoute):Boolean {
   var different=0.0
   candidate.points.zipWithNext().forEach{(x,y)->
    val length=distance(x,y);val samples=ceil(length/8).toInt().coerceAtLeast(1)
    repeat(samples){i->val t=(i+.5)/samples;val p=MapNode(0,x.lat+(y.lat-x.lat)*t,x.lon+(y.lon-x.lon)*t)
     if(baseline.none{(u,v)->distance(p,roadProjection(p,u,v).second)<7})different+=length/samples
    }
   }
   return different>=min(30.0,max(10.0,primary.path.meters*.12))
  }
  var alternative=networks.drop(1).map{result(it,it.path)}.filter(::distinctEnough).minByOrNull{it.meters}
  val seen=mutableSetOf<List<Long>>(primary.path.ids)
  fun consider(blocked:Set<Edge>){
   checkpoint()
   val path=shortest(primary.graph,primary.start,primary.end,blocked)?:return
   if(!seen.add(path.ids))return
   val candidate=result(primary,path)
   if(candidate.meters>=best.meters-0.01 && (alternative==null||candidate.meters<alternative!!.meters) && distinctEnough(candidate))alternative=candidate
  }
  val pathEdges=primary.path.ids.zipWithNext().map{edge(it.first,it.second)}
  // Removing each primary edge finds its shortest replacement without loops.
  pathEdges.forEach{consider(setOf(it))}
  if(alternative==null){
   // Near-parallel source-map duplicates can mask a genuine second corridor.
   // Temporarily exclude a few local 7 m sections for candidate search only.
   val all=primary.graph.flatMap{(u,vs)->vs.keys.filter{u<it}.map{Edge(u,it)}}
   pathEdges.filterIndexed{i,_->i%max(1,pathEdges.size/24)==0}.forEach{section->
    val x=primary.nodes.getValue(section.a);val y=primary.nodes.getValue(section.b)
    val center=MapNode(0,(x.lat+y.lat)/2,(x.lon+y.lon)/2)
    consider(all.filter{e->distance(center,roadProjection(center,primary.nodes.getValue(e.a),primary.nodes.getValue(e.b)).second)<7}.toSet())
   }
  }
  return listOfNotNull(best,alternative)
 }
}

package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos

class CampusRoutingTest {
 private fun point(id:Long,x:Double,y:Double)=MapNode(id,40.686+y/111320,122.12+x/(111320*cos(Math.toRadians(40.686))))
 private fun router(nodes:List<MapNode>,vararg links:Pair<Long,Long>):CampusRouter {
  val ns=nodes.associateBy{it.id};val g=mutableMapOf<Long,MutableMap<Long,Double>>()
  links.forEach{(a,b)->val w=distance(ns.getValue(a),ns.getValue(b));g.getOrPut(a){mutableMapOf()}[b]=w;g.getOrPut(b){mutableMapOf()}[a]=w}
  return CampusRouter(ns,g)
 }
 @Test fun midRoadEndpointsUseTheSegmentInsteadOfDetouringToVertices(){
  val r=router(listOf(point(1,0.0,0.0),point(2,100.0,0.0)),1L to 2L)
  val routes=r.routes(point(0,10.0,10.0),point(0,90.0,10.0))
  assertEquals(1,routes.size);assertEquals(100.0,routes[0].meters,.05)
  assertEquals(80.0,distance(routes[0].points.first(),routes[0].points.last()),.05)
 }
 @Test fun isolatedNearbyStubDoesNotBlockReachableCampusRoad(){
  val r=router(listOf(point(1,0.0,0.0),point(2,0.0,200.0),point(3,2.0,0.0),point(4,2.0,2.0)),1L to 2L,3L to 4L)
  val paths=r.routes(point(0,2.0,0.0),point(0,0.0,200.0))
  assertEquals(1,paths.size);assertEquals(202.0,paths.first().meters,.1)
 }
 @Test fun farAwayLocationsDoNotGetInventedApproachRoads(){
  val r=router(listOf(point(1,0.0,0.0),point(2,50.0,0.0)),1L to 2L)
  assertTrue(r.routes(point(0,0.0,0.0),point(0,0.0,500.0)).isEmpty())
 }
 @Test fun totalDistanceChoosesTheBestConnectedNetwork(){
  val ns=listOf(point(1,0.0,0.0),point(2,0.0,300.0),point(3,100.0,300.0),point(4,100.0,0.0),point(5,0.0,20.0),point(6,100.0,20.0))
  val paths=router(ns,1L to 2L,2L to 3L,3L to 4L,5L to 6L).routes(ns[0],ns[3])
  assertEquals(140.0,paths.first().meters,.1)
  assertEquals(2,paths.size);assertTrue(paths[1].meters>600)
 }
 @Test fun realForkOffersTwoLoopFreeRoutesInDistanceOrder(){
  val ns=listOf(point(1,0.0,0.0),point(2,50.0,20.0),point(3,100.0,0.0),point(4,50.0,-50.0))
  val paths=router(ns,1L to 2L,2L to 3L,1L to 4L,4L to 3L).routes(ns[0],ns[2])
  assertEquals(2,paths.size);assertTrue(paths[0].meters<paths[1].meters)
  paths.forEach{assertEquals(it.points.size,it.points.distinctBy{n->n.id}.size)}
  assertTrue(paths[0].points.any{it.id==2L});assertTrue(paths[1].points.any{it.id==4L})
 }
 @Test fun duplicateNearbyLinesDoNotPretendToBeASecondWalkingRoute(){
  val ns=listOf(point(1,0.0,0.0),point(2,100.0,0.0),point(3,0.0,3.0),point(4,100.0,3.0))
  assertEquals(1,router(ns,1L to 2L,1L to 3L,3L to 4L,4L to 2L).routes(ns[0],ns[1]).size)
 }
 @Test fun reversingEndpointsKeepsTheShortestDistance(){
  val ns=listOf(point(1,0.0,0.0),point(2,50.0,20.0),point(3,100.0,0.0),point(4,50.0,-50.0))
  val r=router(ns,1L to 2L,2L to 3L,1L to 4L,4L to 3L)
  assertEquals(r.routes(ns[0],ns[2])[0].meters,r.routes(ns[2],ns[0])[0].meters,.001)
 }
}

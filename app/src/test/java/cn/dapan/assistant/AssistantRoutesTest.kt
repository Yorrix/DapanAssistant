package cn.dapan.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AssistantRoutesTest {
 private val places=setOf("A01","A01N","F02","G01")
 private val snapshot=AssistantSnapshot(emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),0)
 private fun context(screen:String="导航",from:String="我的位置",to:String="G01")=JSONObject().put("current",JSONObject().put("screen",screen).put("state",JSONObject().put("from",from).put("to",to)))
 private fun decode(text:String,ctx:JSONObject=context())=AssistantRoutes.normalize(AssistantActions.decode(JSONObject(text)),ctx)
 @After fun clear(){listOf("route-old","route-new","route-hub").forEach(AssistantSurfaces::remove)}

 @Test fun currentLocationHasOneValueInMapCommandAndReceipt(){
  listOf("我的位置","我现在的位置","当前位置","这里","my location").forEach{alias->
   val action=decode(JSONObject().put("action","route").put("from",alias).put("to","f02").toString()) as AssistantAction.Route
   AssistantRoutes.validate(action,places)
   val plan=AssistantPlanner.build(snapshot,action)
   assertEquals(AssistantAction.Route("我的位置","F02"),plan.route)
   assertEquals(plan.route!!.from,plan.items.single().after["起点"])
   assertEquals(plan.route!!.to,plan.items.single().after["终点"])
   assertFalse(plan.writes);assertFalse(plan.receipt().contains("尚未选择"))
  }
 }
 @Test fun missingSourceIsRepairedWithinSameRequestBeforeAnyExecution()=runBlocking{
  var calls=0
  val result=AssistantReasoning.resolve(request={feedback->
   calls++
   if(calls==1)JSONObject("""{"action":"route","from":"","to":"F02"}""")
   else {assertTrue(feedback.contains("导航缺少起点"));assertTrue(feedback.contains("\"executed\":false"));JSONObject("""{"action":"route","from":"我现在的位置","to":"F02"}""")}
  },lookup={error("not needed")},validate={AssistantRoutes.validate(it,places)},normalize={AssistantRoutes.normalize(it,context("今天"))})
  assertEquals(2,calls);assertEquals(AssistantAction.Route("我的位置","F02"),result)
 }
 @Test fun missingSourceCannotProduceExecutedPlan(){
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.build(snapshot,AssistantAction.Route("","F02"))}
 }
 @Test fun namedBuildingSourceIsNotReplacedByMyLocation(){
  assertEquals(AssistantAction.Route("A01N","F02"),decode("""{"action":"route","from":"a01n","to":"F02"}"""))
 }
 @Test fun pageToolAndGenericRouteShareTheSameDispatch(){
  val route=decode("""{"action":"tool","module":"导航","op":"route","args":{"from":"当前位置","to":"F02"}}""",context("今天"))
  assertEquals(AssistantAction.Route("我的位置","F02"),route)
 }
 @Test fun partialRouteFreezesRequestEndpointsNotFutureSelection(){
  val ctx=context(from="A01")
  val route=decode("""{"action":"tool","module":"地图","op":"route","args":{"to":"F02"}}""",ctx)
  ctx.getJSONObject("current").getJSONObject("state").put("from","G01")
  assertEquals(AssistantAction.Route("A01","F02"),route)
 }
 @Test fun missingSourceOutsideMapDoesNotInventMyLocation(){
  val route=decode("""{"action":"tool","module":"地图","op":"route","args":{"to":"F02"}}""",context("今天"))
  assertThrows(IllegalArgumentException::class.java){AssistantRoutes.validate(route,places)}
 }
 @Test fun explicitClearRemainsAClearCommand(){
  val tool=decode("""{"action":"tool","module":"地图","op":"route","args":{"from":"","to":""}}""") as AssistantAction.Tool
  assertEquals("",tool.args.getString("from"));assertEquals("",tool.args.getString("to"))
  AssistantRoutes.validate(tool,places)
 }
 @Test fun unsupportedFieldsAndUnknownBuildingsCannotBeDiscarded(){
  assertThrows(IllegalArgumentException::class.java){decode("""{"action":"tool","module":"地图","op":"route","args":{"from":"我的位置","to":"F02","secret":true}}""")}
  assertThrows(IllegalArgumentException::class.java){AssistantRoutes.validate(AssistantAction.Route("我的位置","B02"),places)}
 }
 @Test fun navigationDoesNotDependOnSendingPagesTransientSelection(){
  AssistantRequestGuard.check(AssistantAction.Route("我的位置","F02"),"日历","导航","old binding","new binding")
 }
 @Test fun remountAndLocationRefreshDoNotInvalidateSameSelection(){
  AssistantSurfaces.put(SurfaceBinding("route-old","地图",0,JSONObject().put("from","我的位置").put("to","F02").put("locationAvailable",false),emptyList()))
  val before=AssistantSurfaces.fingerprint("导航")
  AssistantSurfaces.remove("route-old")
  AssistantSurfaces.put(SurfaceBinding("route-new","地图",0,JSONObject().put("from","我的位置").put("to","F02").put("locationAvailable",true),emptyList()))
  AssistantSurfaces.put(SurfaceBinding("route-hub","地图",0,JSONObject().put("range","近7天"),emptyList()))
  assertEquals(before,AssistantSurfaces.fingerprint("地图"))
 }
 @Test fun changedSelectionStillBlocksContextDependentTools(){
  val action=AssistantAction.Tool("录音摘要","delete",JSONObject())
  assertThrows(IllegalStateException::class.java){AssistantRequestGuard.check(action,"录音摘要","录音摘要","clip-a","clip-b")}
  assertThrows(IllegalStateException::class.java){AssistantRequestGuard.check(action,"录音摘要","日历","clip-a","clip-a")}
 }
}

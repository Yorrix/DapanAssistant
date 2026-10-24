package cn.dapan.assistant

import org.json.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class AssistantIntegrationTest {
 private val owners=listOf("test-map-parent","test-map-child","test-wheel")
 @After fun clear(){owners.forEach(AssistantSurfaces::remove)}
 private fun bind(owner:String="test-wheel",module:String="抽签",state:JSONObject=JSONObject(),priority:Int=0,tools:List<SurfaceTool> = emptyList())=AssistantSurfaces.put(SurfaceBinding(owner,module,priority,state,tools))
 @Test fun catalogueExposesToolsWithoutCurrentPageBindings(){
  assertNull(AssistantSurfaces.current("抽签"))
  val tools=AssistantCatalogue.json().getJSONArray("抽签").objects()
  assertTrue(tools.single{it.getString("op")=="save"}.getJSONArray("fields").objectsAsText().contains("spinAfter"))
  assertTrue(AssistantCatalogue.json().has("二手信息"))
 }
 @Test fun mapHubAndNavigationRetainBothSetsOfActions(){
  bind("test-map-parent","地图",JSONObject().put("mode","热力图"),tools=listOf(surfaceTool("heat_view","模式","",false){prepared("模式",it){"ok"}}))
  bind("test-map-child","地图",JSONObject().put("from","A01"),1,listOf(surfaceTool("route","路线","to",false){prepared("路线",it){"ok"}}))
  val merged=AssistantSurfaces.current("导航")!!
  assertEquals(setOf("heat_view","route"),merged.tools.map{it.operation}.toSet())
  assertEquals("热力图",merged.state.getString("mode"));assertEquals("A01",merged.state.getString("from"))
 }
 @Test fun unknownOperationsAndFieldsNeverReachHandler(){
  var calls=0
  bind(tools=listOf(surfaceTool("delete","删除","id"){calls++;prepared("删除",it){"ok"}}))
  assertThrows(IllegalStateException::class.java){AssistantSurfaces.prepare("抽签","shell",JSONObject())}
  assertThrows(IllegalArgumentException::class.java){AssistantSurfaces.prepare("抽签","delete",JSONObject().put("id","a").put("writes",false))}
  assertEquals(0,calls)
 }
 @Test fun selectedObjectChangeInvalidatesPendingConfirmation(){
  val tools=listOf(surfaceTool("delete","删除","id"){prepared("删除",it){"ok"}})
  bind(state=JSONObject().put("selectedId","a"),tools=tools)
  val (writes,pending)=AssistantSurfaces.prepare("抽签","delete",JSONObject().put("id","a"))
  assertTrue(writes);pending.guard()
  bind(state=JSONObject().put("selectedId","b"),tools=tools)
  assertThrows(IllegalStateException::class.java){pending.guard()}
 }
 @Test fun timerTickDoesNotInvalidateButLeavingPageDoes(){
  val tools=listOf(surfaceTool("stop","停止","",true){prepared("停止",it){"ok"}})
  bind(state=JSONObject().put("selectedId","a").put("remaining",60),tools=tools)
  val pending=AssistantSurfaces.prepare("抽签","stop",JSONObject()).second
  bind(state=JSONObject().put("selectedId","a").put("remaining",59),tools=tools);pending.guard()
  AssistantSurfaces.remove("test-wheel");assertThrows(IllegalStateException::class.java){pending.guard()}
 }
 @Test fun permissionsAreCheckedAgainAtExecution(){
  assertThrows(IllegalStateException::class.java){AssistantGate.check(AssistantAccess.READ_ONLY,true,true)}
  assertThrows(IllegalStateException::class.java){AssistantGate.check(AssistantAccess.CONFIRM,true,false)}
  AssistantGate.check(AssistantAccess.CONFIRM,false,false)
  AssistantGate.check(AssistantAccess.READ_ONLY,false,false)
  AssistantGate.check(AssistantAccess.FULL,true,false)
  AssistantGate.check(AssistantAccess.CONFIRM,true,true)
 }
 @Test fun receiptPreviewPreservesWeightsAndPublicContactMeaning(){
  val args=JSONObject("""{"options":[{"name":"小王","weight":1},{"name":"小李","weight":2}],"contacts":{"QQ":"12345"},"publish":true}""")
  val preview=AssistantSurfacePreview.describe(args)
  assertTrue(preview.contains("权重：2"));assertTrue(preview.contains("公开联系方式"));assertTrue(preview.contains("立即发布：是"))
 }
 @Test fun foodExpenseAndWeightedDutyExamplesUseRealDataModels(){
  val expense=AssistantActions.decode(JSONObject("""{"action":"note","module":"账单","title":"午饭","detail":"18.50","group":"吃饭"}""")) as AssistantAction.Note
  assertEquals("吃饭",expense.value.group);assertEquals("18.50",expense.value.detail)
  val tool=AssistantActions.decode(JSONObject("""{"action":"tool","module":"抽签","op":"save","args":{"name":"本周打扫","options":[{"name":"小王","weight":1},{"name":"小李","weight":1},{"name":"小张","weight":2}]}}""")) as AssistantAction.Tool
  val wheel=SavedWheel(name=tool.args.getString("name"),options=tool.args.getJSONArray("options").objects().map{WheelOption(name=it.getString("name"),weight=it.getDouble("weight"))})
  WheelMath.validate(wheel);assertEquals(listOf(90.0,90.0,180.0),WheelMath.sectors(wheel).map{it.sweep})
  assertThrows(IllegalArgumentException::class.java){WheelMath.validate(wheel.copy(options=listOf(WheelOption(name="",weight=2.0))))}
 }
 @Test fun lookupKeepsRealIdAndCannotBecomeUnvalidatedBatchWrite(){
  val read=AssistantActions.decode(JSONObject("""{"action":"lookup","module":"资料库","query":"极限","id":"known-file"}""")) as AssistantAction.Lookup
  assertEquals("known-file",read.id)
  assertThrows(IllegalArgumentException::class.java){AssistantActions.decode(JSONObject("""{"action":"batch","actions":[{"action":"tool","module":"资料库","op":"delete","args":{"id":"known-file"}}]}"""))}
 }
}

package cn.dapan.assistant

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RetiredFeaturesTest {
 private val names=listOf("tibo雷达","tibo重置雷达","TIBO 重置雷达")

 @Test fun restoredNavigationDropsRemovedPagesWithoutLosingTheReturnOrigin(){
  val navigation=AppNavigation(listOf("今天","tibo重置雷达","工具","tibo雷达"))
  assertEquals(listOf("今天","工具"),navigation.path)
  names.forEach{navigation.open(it);navigation.tab(it)}
  assertEquals(listOf("今天","工具"),navigation.path)
  navigation.open("天气",origin="tibo重置雷达")
  navigation.back()
  assertEquals("工具",navigation.current)
  assertEquals("今天",AppNavigation(names).current)
 }

 @Test fun obsoleteAiOperationsCannotReopenOrReenableTheFeature(){
  names.forEach{name->
   assertTrue(RetiredFeatures.removed(name))
   listOf("navigate","lookup","tool").forEach{action->
    val request=JSONObject().put("action",action).put("module",name)
    if(action=="tool")request.put("op","notification").put("args",JSONObject().put("enabled",true))
    assertThrows(IllegalArgumentException::class.java){AssistantActions.decode(request)}
   }
  }
  assertFalse(AssistantActions.destinations.any(RetiredFeatures::removed))
  assertFalse(AssistantCatalogue.json().keys().asSequence().any(RetiredFeatures::removed))
 }

 @Test fun oldHomeOrderDropsTheWidgetAndPreservesOtherUserChoices(){
  val restored=HomeWidgetOrdering.restore("WEATHER,RADAR,PARCEL,RADAR,AGENDA")
  assertEquals(listOf(HomeWidget.WEATHER,HomeWidget.PARCEL,HomeWidget.AGENDA),restored.take(3))
  assertEquals(HomeWidget.entries.toSet(),restored.toSet())
  assertFalse(HomeWidget.entries.any{it.name=="RADAR"})
  assertFalse(RetiredFeatures.removed("官网通知"))
 }
}

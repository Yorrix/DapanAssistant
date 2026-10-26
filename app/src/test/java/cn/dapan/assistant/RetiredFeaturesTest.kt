package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
class RetiredFeaturesTest {
 @Test fun radarIsAvailableWithoutErasingUnrelatedNavigation(){
  assertFalse(RetiredFeatures.removed("tibo雷达"))
  val n=AppNavigation(listOf("今天","工具"));n.open("tibo雷达");assertEquals("tibo雷达",n.current);n.back();assertEquals("工具",n.current)
  assertTrue(AssistantActions.decode(JSONObject().put("action","navigate").put("module","tibo雷达")) is AssistantAction.Navigate)
  assertTrue(AssistantCatalogue.json().has("tibo雷达"));assertTrue(AssistantCatalogue.json().has("课堂助手"))
 }
 @Test fun restoringOldHomeOrderKeepsUnrelatedChoices(){assertEquals(listOf(HomeWidget.WEATHER,HomeWidget.PARCEL,HomeWidget.AGENDA),HomeWidgetOrdering.restore("WEATHER,PARCEL,AGENDA").take(3))}
}

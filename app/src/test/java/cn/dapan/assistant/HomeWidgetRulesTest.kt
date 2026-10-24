package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class HomeWidgetRulesTest {
 @Test fun additionsNeverChangeExistingEnabledDefaults(){
  assertEquals(setOf(HomeWidget.QUOTE,HomeWidget.WEATHER,HomeWidget.AGENDA,HomeWidget.FAVORITES,HomeWidget.NOTICES),HomeLayout.defaultEnabled)
  assertTrue(HomeLayout.defaultOrder.containsAll(HomeWidget.entries))
 }
 @Test fun legacyOrderIsPreservedAndNewWidgetsAreAppended(){
  val old=listOf(HomeWidget.PARCEL,HomeWidget.AGENDA,HomeWidget.BILLING,HomeWidget.FOCUS,HomeWidget.MORNING,HomeWidget.NOTICES,HomeWidget.FAVORITES,HomeWidget.WEATHER,HomeWidget.QUOTE)
  val restored=HomeWidgetOrdering.restore(old.joinToString(","){it.name})
  assertEquals(old,restored.take(old.size))
  assertEquals(listOf(HomeWidget.LIBRARY,HomeWidget.RECORDINGS,HomeWidget.VOCABULARY),restored.drop(old.size))
  assertEquals(HomeWidget.entries.size,restored.distinct().size)
 }
 @Test fun savedUnknownAndDuplicateKeysDoNotHideNewWidgets(){
  val restored=HomeWidgetOrdering.restore("PARCEL,removed,PARCEL,WEATHER")
  assertEquals(listOf(HomeWidget.PARCEL,HomeWidget.WEATHER),restored.take(2))
  assertEquals(HomeWidget.entries.toSet(),restored.toSet())
 }
 @Test fun partialAiOrderRetainsUnmentionedOrder(){
  val old=HomeWidgetOrdering.restore(null)
  val requested=HomeWidgetOrdering.requested(listOf(HomeWidget.VOCABULARY,HomeWidget.LIBRARY),old)
  assertEquals(listOf(HomeWidget.VOCABULARY,HomeWidget.LIBRARY),requested.take(2))
  assertEquals(old.filterNot{it in setOf(HomeWidget.VOCABULARY,HomeWidget.LIBRARY)},requested.drop(2))
  assertThrows(IllegalArgumentException::class.java){HomeWidgetOrdering.requested(listOf(HomeWidget.LIBRARY,HomeWidget.LIBRARY),old)}
 }
 @Test fun summaryPreviewKeepsWordsWithoutMarkdownChrome(){
  assertEquals("重点 · 向量空间 · 例题",HomeWidgetText.summaryExcerpt("# 重点\n- **向量空间**\n[例题](https://example.com)"))
 }
}

package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class AppNavigationTest {
 @Test fun returnsToActualEntryPoint(){
  val n=AppNavigation(listOf("今天"));n.open("账单");n.back();assertEquals("今天",n.current)
  n.tab("工具");n.open("账单");n.back();assertEquals("工具",n.current)
 }
 @Test fun nestedAiDestinationReturnsToCallingFeature(){
  val n=AppNavigation(listOf("今天"));n.open("日历");n.open("地图",origin="日历")
  n.back();assertEquals("日历",n.current);n.back();assertEquals("今天",n.current)
 }
 @Test fun delayedAiNavigationUsesCapturedOrigin(){
  val n=AppNavigation(listOf("今天"));n.open("日历");n.tab("工具")
  n.open("天气",origin="日历");n.back();assertEquals("日历",n.current)
 }
 @Test fun repeatedOpenDoesNotGrowAndTabSelectionDoesNotTrapBack(){
  val n=AppNavigation(listOf("今天"));repeat(20){n.open("账单")}
  assertEquals(listOf("今天","账单"),n.path)
  n.tab("工具");assertEquals(listOf("工具"),n.path);n.back();assertEquals("今天",n.current)
 }
 @Test fun openingPreviouslyVisitedPageStillReturnsToImmediateCaller(){
  val n=AppNavigation(listOf("今天"));n.open("日历");n.open("地图");n.open("日历")
  assertEquals(listOf("今天","日历","地图","日历"),n.path)
  val restored=AppNavigation(n.path);restored.back();assertEquals("地图",restored.current)
  restored.back();assertEquals("日历",restored.current)
 }
 @Test fun toolCategoriesAreAlwaysUniqueAndCommonIncludesSmallTools(){
  val common=toolGroups.first{it.first=="常用工具"}.second
  assertTrue(common.containsAll(listOf("抽签","手持弹幕")))
  val all=toolGroups.flatMap{it.second};assertEquals(all.size,all.distinct().size)
 }
}

package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class CampusServicesTest {
 @Test fun upgradeRemovesSourceFieldWithoutRestoringDeletedOrReplacingEdits(){
  val first=CampusServices.merge(emptyList(),emptySet())
  val edited=first.notes.first().copy(title="自定义店名",detail="开放时间：08:00–20:00\n来源：旧资料\n电话：123\n备注：保留来源说明文字")
  val personal=NoteItem(module="信息查询",group="个人信息",title="资料来源",detail="来源：自己记录")
  val result=CampusServices.merge(listOf(edited,personal),first.installed)
  assertEquals(2,result.notes.size)
  assertEquals("自定义店名",result.notes.first().title)
  assertEquals("开放时间：08:00–20:00\n电话：123\n备注：保留来源说明文字",result.notes.first().detail)
  assertEquals(personal,result.notes.last())
  assertEquals(result,CampusServices.merge(result.notes,result.installed))
  assertTrue(CampusServices.defaults.none{it.detail.lines().any{line->line.startsWith("来源：")}})
 }
 @Test fun freshInstallIncludesAllSourcesAndOnlyKnownLocations(){
  val result=CampusServices.merge(emptyList(),emptySet())
  assertEquals(18,result.notes.size)
  assertEquals(18,result.installed.size)
  assertTrue(result.notes.all{it.module=="信息查询"&&it.group=="校园服务"})
  assertEquals(9,result.notes.count{it.detail.lines().contains("地点：商业街")})
  assertEquals(1,result.notes.count{it.title=="医务室"})
  assertFalse(result.notes.first{it.title=="公共澡堂"}.detail.contains("地点：商业街"))
  assertFalse(result.notes.first{it.title=="京东快递房"}.detail.contains("地点："))
 }
 @Test fun repeatedStartupDoesNotDuplicate(){
  val first=CampusServices.merge(emptyList(),emptySet())
  assertEquals(first,CampusServices.merge(first.notes,first.installed))
 }
 @Test fun editedDefaultsAreNeverOverwritten(){
  val first=CampusServices.merge(emptyList(),emptySet())
  val edited=first.notes.first().copy(title="我的超市名称",detail="自定义开放时间")
  val current=listOf(edited)+first.notes.drop(1)
  assertEquals(current,CampusServices.merge(current,first.installed).notes)
 }
 @Test fun deletedOrClearedDefaultsStayDeleted(){
  val first=CampusServices.merge(emptyList(),emptySet())
  assertEquals(first.notes.drop(1),CampusServices.merge(first.notes.drop(1),first.installed).notes)
  assertTrue(CampusServices.merge(emptyList(),first.installed).notes.isEmpty())
 }
 @Test fun upgradePreservesExistingSameNameService(){
  val personal=NoteItem(id="user-service",module="信息查询",title="天猫超市",detail="用户原有内容",group="校园服务")
  val result=CampusServices.merge(listOf(personal),emptySet())
  assertEquals(18,result.notes.size)
  assertEquals(personal,result.notes.single{it.title=="天猫超市"})
  assertTrue(CampusServices.merge(result.notes.filterNot{it.id==personal.id},result.installed).notes.none{it.title==personal.title})
 }
 @Test fun personalRecordWithSameTitleDoesNotHideCampusService(){
  val personal=NoteItem(module="信息查询",title="天猫超市",group="个人信息")
  assertEquals(19,CampusServices.merge(listOf(personal),emptySet()).notes.size)
 }
 @Test fun partialPhonePatternAndAdmissionCutoffRemainExplicit(){
  val dorm=CampusServices.defaults.single{it.title=="宿舍楼管"}
  assertTrue(dorm.detail.contains("0427-26312xx"));assertTrue(dorm.detail.contains("不是完整可拨号码"))
  val bath=CampusServices.defaults.single{it.title=="公共澡堂"}
  assertTrue(bath.detail.contains("21:30 后不可进入"));assertTrue(bath.detail.contains("周一不营业"))
 }
}

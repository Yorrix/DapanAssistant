package cn.dapan.assistant

import org.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimetableImportTest {
 private val slots=listOf("800/845","850/935","1005/1050","1055/1140","1330/1415","1420/1505","1520/1605","1615/1700","1800/1845","1850/1935","1945/2030","2035/2120")
 private fun term(date:String="2026-08-31",id:String="443")=TimetableImport.term(JSONObject().put("kind","grid").put("semester",id).put("semesterName","样例学期").put("startDate",date).put("slots",JSONArray(slots.map{val parts=it.split('/');JSONObject().put("start",parts[0]).put("end",parts[1])})))
 private fun payload(vararg schedules:String)=JSONObject().put("kind","courses").put("semester","443").put("courses",JSONArray(schedules.mapIndexed{i,s->JSONObject().put("code","C$i").put("title","样例课程$i").put("schedule",s)}))
 @Test fun mixedOddEvenWeeksApplyToTheirOwnSegment(){
  assertEquals(listOf(4,8,10,11,12,13,14,15),TimetableImport.weeks("4,8~10(双),11~15"))
  assertEquals(listOf(1,3,5),TimetableImport.weeks("1～6（单）周"))
  assertThrows(IllegalArgumentException::class.java){TimetableImport.weeks("0~60")}
  assertThrows(IllegalStateException::class.java){TimetableImport.weeks("1~8待定")}
 }
 @Test fun semesterAndTimesComeFromEachStudentsPage(){
  val schedule="1周 周一 第一节~第二节 盘锦校区 A101 教师"
  val first=TimetableImport.read(term(),payload(schedule)).entries.single()
  val next=TimetableImport.read(term("2027-03-01"),payload(schedule)).entries.single()
  assertEquals(LocalDateTime.parse("2026-08-31T08:00"),first.start)
  assertEquals(LocalTime.of(9,35),first.end.toLocalTime())
  assertEquals(LocalDate.parse("2027-03-01"),next.start.toLocalDate());assertNotEquals(first.id,next.id)
  assertEquals(LocalTime.of(7,40),first.reminderTime()!!.toLocalTime())
 }
 @Test fun changedRoomUsesSeparateWeeksAndKeepsFullTeacherText(){
  val p=TimetableImport.read(term(),payload("7周 周三 第五节~第六节 盘锦校区 A101 Teacher One;\n8~16周 周三 第五节~第六节 盘锦校区 B201 Teacher Two","不排课"))
  assertEquals(10,p.entries.size);assertEquals(listOf("样例课程1"),p.unscheduled)
  assertEquals("A101",p.entries.first().location);assertEquals("B201",p.entries[1].location)
  assertTrue(p.entries.first().notes.contains("Teacher One"));assertEquals(LocalTime.of(13,30),p.entries.first().start.toLocalTime())
 }
 @Test fun currentSchoolRuleShapesExpandWithoutMergingDifferentTeachers(){
  // Anonymized rule shapes observed on the actual page, not a bundled personal timetable.
  val rules=arrayOf(
   "3~10周 周二 第九节~第十节 盘锦校区 A1 甲",
   "4,8~10(双),11~15周 周二 第一节~第二节 盘锦校区 A2 甲;5~7,9周 周二 第一节~第二节 盘锦校区 A2 乙;4,8~10(双),11~15周 周四 第一节~第二节 盘锦校区 A2 甲;5~7,9周 周四 第一节~第二节 盘锦校区 A2 乙",
   "3~10周 周三 第九节~第十一节 盘锦校区 A3 甲;3~10周 周五 第九节~第十一节 盘锦校区 A3 甲",
   "8~16周 周一 第三节~第四节 盘锦校区 A4 甲;8~16周 周二 第三节~第四节 盘锦校区 A4 甲;8~16周 周三 第三节~第四节 盘锦校区 A4 甲;7~16周 周四 第三节~第四节 盘锦校区 A4 甲;7~16周 周五 第一节~第二节 盘锦校区 A4 甲",
   "8~16周 周一 第五节~第六节 盘锦校区 A5 甲;8~16周 周二 第五节~第六节 盘锦校区 A5 甲;7周 周三 第五节~第六节 盘锦校区 B5 甲;8~16周 周三 第五节~第六节 盘锦校区 A5 甲;7~16周 周四 第五节~第六节 盘锦校区 A5 甲;7~16周 周五 第三节~第四节 盘锦校区 A5 甲",
   "3~14周 周一 第一节~第二节 盘锦校区 A6 甲;3~14周 周三 第一节~第二节 盘锦校区 B6 甲",
   "3~18周 周一 第七节~第八节 盘锦校区 A7 甲;3~18周 周四 第七节~第八节 盘锦校区 B7 甲",
   "不排课","不排课","不排课")
  val p=TimetableImport.read(term(),payload(*rules))
  assertEquals(199,p.entries.size);assertEquals(3,p.unscheduled.size)
  assertTrue(p.entries.all{it.error()==null})
 }
 @Test fun reimportIsIdempotentAndNeverOverwritesUserEdits(){
  val list=TimetableImport.read(term(),payload("1~3周 周一 第一节~第二节 盘锦校区 A1 甲")).entries
  assertEquals(3,TimetableImport.merge(list,emptyList()).fresh.size)
  assertEquals(3,TimetableImport.merge(list,list).unchanged)
  val edited=list.map{it.copy(title="用户改名")}
  val m=TimetableImport.merge(list,edited);assertEquals(0,m.fresh.size);assertEquals(3,m.changed.size)
  assertEquals(3,TimetableImport.merge(list,list.map{it.copy(reminderMode=ReminderMode.OFF)}).unchanged)
 }
 @Test fun missingScheduleIsExplicitlySkippableButSemesterMismatchStillBlocks(){
  val result=TimetableImport.read(term(),payload("1周 周一 第一节~第二节 盘锦校区 A1 甲","待安排","","不排课"))
  assertEquals(1,result.entries.size);assertEquals(listOf(1,2,3),result.issues.map{it.index})
  assertTrue(result.issues.all{it.reason.isNotBlank()})
  assertThrows(IllegalArgumentException::class.java){TimetableImport.read(term(id="444"),payload("不排课"))}
 }
 @Test fun oneBadMeetingNeverCreatesAHalfImportedCourse(){
  val result=TimetableImport.read(term(),payload("1周 周一 第一节~第二节 盘锦校区 A1 甲;未排课","1周 周二 第一节~第二节 盘锦校区 A2 乙"))
  assertEquals(1,result.issues.size);assertEquals(1,result.entries.size);assertEquals("样例课程1",result.entries.single().title)
  val conflicts=TimetableImport.read(term(),payload("1周 周一 第一节~第二节 盘锦校区 A1 甲;1周 周一 第一节~第二节 盘锦校区 B1 乙"))
  assertTrue(conflicts.entries.isEmpty());assertEquals(1,conflicts.issues.size)
 }
 @Test fun malformedRowsAreListedAndAllUnscheduledCannotCreateEvents(){
  val data=payload("1周 周一 第一节~第二节 盘锦校区 A1 甲")
  data.getJSONArray("courses").put(JSONObject().put("title","未安排课程")).put("invalid-row")
  val result=TimetableImport.read(term(),data)
  assertEquals(1,result.entries.size);assertEquals(2,result.issues.size)
  val empty=TimetableImport.read(term(),payload("不排课","待安排",""))
  assertTrue(empty.entries.isEmpty());assertEquals(3,empty.issues.size)
  assertThrows(IllegalStateException::class.java){TimetableImport.read(term(),JSONObject().put("kind","courses").put("semester","443"))}
  assertThrows(IllegalArgumentException::class.java){TimetableImport.read(term(),payload())}
 }
 @Test fun missingOrInvalidTermTimesAreNotGuessed(){
  val grid=JSONObject().put("kind","grid").put("semester","443").put("semesterName","测试学期").put("startDate","2026-08-31").put("slots",JSONArray())
  assertThrows(IllegalArgumentException::class.java){TimetableImport.term(grid)}
  grid.put("slots",JSONArray().put(JSONObject().put("start","800").put("end","")))
  assertThrows(IllegalArgumentException::class.java){TimetableImport.term(grid)}
 }
 @Test fun loginOnlyAllowsExactOfficialOrigins(){
  assertTrue(TimetableImport.allowedUrl(TimetableImport.ENTRY));assertTrue(TimetableImport.allowedUrl("https://sso.dlut.edu.cn/cas/login"))
  listOf("http://sso.dlut.edu.cn/cas/login","https://sso.dlut.edu.cn.evil.test/","https://evil@sso.dlut.edu.cn/","file:///etc/passwd","javascript:alert(1)","https://jxgl.dlut.edu.cn:8080/").forEach{assertFalse(it,TimetableImport.allowedUrl(it))}
 }
}

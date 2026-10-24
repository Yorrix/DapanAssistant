package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime

class DataClearTest {
 private val course=CalendarEntry(id="teaching:test",title="课程",start=LocalDateTime.of(2026,10,15,8,0))
 @Test fun importedOnlyKeepsManualCoursesAndOtherRecords(){
  val selected=setOf(ClearDataKind.COURSES)
  assertTrue(ClearDataRules.entry(course,selected))
  assertFalse(ClearDataRules.entry(course.copy(id="manual",category="课程"),selected))
  assertFalse(ClearDataRules.note(NoteItem(module="待办",title="任务"),selected))
 }
 @Test fun calendarIncludesLegacyTodoButKeepsBills(){
  val selected=setOf(ClearDataKind.CALENDAR)
  EventKind.entries.forEach{assertTrue(ClearDataRules.entry(course.copy(kind=it,id="manual"),selected))}
  assertTrue(ClearDataRules.note(NoteItem(module="待办",title="任务"),selected))
  assertFalse(ClearDataRules.note(NoteItem(module="账单",title="午饭"),selected))
 }
 @Test fun emptySelectionAndKeysCannotClearRecords(){
  listOf(emptySet(),setOf(ClearDataKind.KEYS,ClearDataKind.CHAT)).forEach{selected->
   assertFalse(ClearDataRules.entry(course,selected))
   listOf("账单","快递","信息查询","待办","其他").forEach{assertFalse(ClearDataRules.note(NoteItem(module=it,title="记录"),selected))}
  }
 }
 @Test fun selectedNoteModulesStayIsolated(){
  assertTrue(ClearDataRules.note(NoteItem(module="账单",title="午饭"),setOf(ClearDataKind.BILLS)))
  assertFalse(ClearDataRules.note(NoteItem(module="信息查询",title="学号"),setOf(ClearDataKind.BILLS)))
 }
}

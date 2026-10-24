package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarOverlapTest {
 private val date=LocalDate.of(2026,10,15)
 private fun e(a:Int,b:Int)=CalendarEntry(title="$a-$b",start=date.atTime(a,0),end=date.atTime(b,0))
 private fun group()=CalendarOverlap.groups(CalendarDayLayout.build(listOf(e(8,10),e(9,11)),date)).single()
 @Test fun adjacentItemsAndTravelDoNotGroup(){
  val items=CalendarDayLayout.build(listOf(e(8,9),e(9,10).copy(travelMinutes=20)),date)
  assertEquals(2,CalendarOverlap.groups(items).size)
 }
 @Test fun chainIsGroupedButSeparatedAppointmentsRemainIndependent(){
  val groups=CalendarOverlap.groups(CalendarDayLayout.build(listOf(e(8,10),e(9,11),e(10,12),e(13,14)),date))
  assertEquals(listOf(3,1),groups.map{it.items.size});assertEquals(2,groups.first().lanes)
 }
 @Test fun smartRespondsToZoomAndFontSize(){
  val g=group()
  assertTrue(CalendarOverlap.folded(g,CalendarOverlapMode.SMART,120f,1f))
  assertFalse(CalendarOverlap.folded(g,CalendarOverlapMode.SMART,240f,1f))
  assertTrue(CalendarOverlap.folded(g,CalendarOverlapMode.SMART,240f,1.5f))
  assertFalse(CalendarOverlap.folded(g,CalendarOverlapMode.SIDE_BY_SIDE,80f,1f))
  assertFalse(CalendarOverlap.folded(g,CalendarOverlapMode.STACK,80f,1f))
 }
 @Test fun stackedCardsStayWideAndInsideTheDay(){
  val g=CalendarOverlap.groups(CalendarDayLayout.build(listOf(e(8,12),e(8,11),e(9,10)),date)).single()
  g.items.forEach{val r=CalendarOverlap.bounds(it,g,CalendarOverlapMode.STACK,150f);assertEquals(108f,r.width,.001f);assertTrue(r.left>=0);assertTrue(r.left+r.width<=150.001f)}
 }
 @Test fun parallelCardsPreserveIndependentLanes(){
  val g=group();val bounds=g.items.map{CalendarOverlap.bounds(it,g,CalendarOverlapMode.SIDE_BY_SIDE,200f)}
  assertTrue(bounds.all{it.width==100f});assertEquals(setOf(0f,100f),bounds.map{it.left}.toSet())
 }
 @Test fun travelOnlyDayDoesNotCreateEmptyGroup(){
  val event=CalendarEntry(title="凌晨",start=date.plusDays(1).atTime(0,10),travelMinutes=30)
  assertTrue(CalendarOverlap.groups(CalendarDayLayout.build(listOf(event),date)).isEmpty())
 }
}

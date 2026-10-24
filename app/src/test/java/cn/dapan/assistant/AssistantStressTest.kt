package cn.dapan.assistant

import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.LocalDate
import kotlin.random.Random

/** Reproducible varied fixtures, asserting state invariants rather than model wording. */
@RunWith(Parameterized::class)
class AssistantStressTest(private val seed:Int) {
 companion object {@JvmStatic @Parameterized.Parameters(name="seed={0}") fun seeds()=(0 until 100).map{arrayOf<Any>(it)}}
 private val r=Random(seed)
 private fun snapshot():AssistantSnapshot{
  val entries=(0 until 25).map{i->CalendarEntry(id="e-$i",title="课程 $i",start=LocalDate.of(2026,9,1).plusDays(i.toLong()).atTime(8,0),location="A01N",notes="备注 $i")}
  return AssistantSnapshot(entries,(0..9).map{NoteItem(id="n-$it",module="账单",title="餐饮 $it",detail="${10+it}.50",group="吃饭",created=1790000000000)},emptyList(),emptyList(),emptyList(),0)
 }
 @Test fun mixedWritesAndUndoPreserveUnrelatedChanges(){
  val before=snapshot();val target=before.entries[r.nextInt(25)];val bill=before.notes[r.nextInt(10)]
  val action=AssistantAction.Batch(listOf(
   AssistantAction.Change("update","日历",target.title,JSONObject().put("location","F02").put("notes","带玉兰卡 $seed"),target.id),
   AssistantAction.Change("update","账单",bill.title,JSONObject().put("detail","${seed+1}.25"),bill.id),
   AssistantAction.Note(NoteItem(id="new-$seed",module="快递",title="3-2-${1000+seed}"))
  ))
  val plan=AssistantPlanner.build(before,action);assertEquals(3,plan.operationCount)
  assertEquals(target.copy(location="F02",notes="带玉兰卡 $seed"),plan.after.entries.single{it.id==target.id})
  before.entries.filter{it.id!=target.id}.forEach{row->assertEquals(row,plan.after.entries.single{it.id==row.id})}
  val outside=NoteItem(id="outside",module="信息查询",title="寝室",detail="B12-401")
  val current=plan.after.copy(notes=plan.after.notes+outside)
  val undo=AssistantUndo.fromJson(AssistantUndo.capture(before,plan.after)!!.json()).plan(current)
  assertEquals(before.entries.associateBy{it.id},undo.after.entries.associateBy{it.id})
  assertEquals((before.notes+outside).associateBy{it.id},undo.after.notes.associateBy{it.id})
 }
 @Test fun invalidLastMutationCannotPartiallyChangeEarlierRecords(){
  val before=snapshot();val target=before.entries[r.nextInt(25)]
  val valid=AssistantAction.Change("update","日历",target.title,JSONObject().put("title","已更名"),target.id)
  val invalid=AssistantAction.Change("delete","日历","并不存在",JSONObject(),"missing-$seed")
  assertThrows(IllegalArgumentException::class.java){AssistantPlanner.build(before,AssistantAction.Batch(listOf(valid,invalid)))}
  assertEquals(target,before.entries.single{it.id==target.id})
 }
 @Test fun truncatedStreamsNeverProduceAnExecutableAction(){
  val action=JSONObject().put("action","delete").put("module","日历").put("targetId","e-$seed").toString()
  val delta=JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content",action))))
  val stop="data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
  val prefix=": heartbeat\n\ndata: $delta\n\n$stop"
  val cut=r.nextInt(prefix.length+1)
  assertThrows(Exception::class.java){ModelStream.read(prefix.take(cut).reader().buffered())}
  assertEquals(action,ModelStream.read((prefix+"data: [DONE]\n\n").reader().buffered()))
 }
 @Test fun randomlyChunkedChineseStreamPreservesAllCharacters(){
  val message=JSONObject().put("action","chat").put("reply","第${seed}次：玉兰卡😀\n引文\"hello\"，不是指令。" ).toString()
  val frames=StringBuilder();var i=0
  while(i<message.length){val end=minOf(message.length,i+r.nextInt(1,8));val part=message.substring(i,end)
   frames.append("data: ").append(JSONObject().put("choices",JSONArray().put(JSONObject().put("delta",JSONObject().put("content",part))))).append("\n\n");i=end}
  frames.append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
  val result=ModelStream.read(frames.toString().reader().buffered())
  assertEquals(message,result);assertEquals("chat",StructuredReplies.parse(result).getString("action"))
 }
 @Test fun indexedTextPaginationAndEvidenceHaveNoInventedCharacters(){
  val body=(0..r.nextInt(15,60)).joinToString("\n"){"第${it}项：😀"+"测".repeat(r.nextInt(1,500))+if(it%7==0)"玉兰卡规则"else"普通内容"}
  val joined=StringBuilder();var offset=0
  while(offset<body.length){val page=LibrarySearch.read(body,offset,r.nextInt(500,1500));assertTrue(page.end>offset);joined.append(page.text);offset=page.nextOffset?:body.length}
  assertEquals(body,joined.toString())
  val doc=LibraryDocument("doc-$seed","虚构资料.txt","text/plain","/none",0,0,"",body,"正文已索引","测试")
  val result=LibraryRetrieval.document(doc,"玉兰卡")
  result.getJSONArray("evidence").objects().forEach{e->
   val all=listOf(e)+e.getJSONArray("context").objects()
   all.forEach{q->assertEquals(body.substring(q.getInt("startOffset"),q.getInt("endOffset")),q.getString("quote"));assertEquals(doc.id,q.getString("fileId"))}
  }
 }
 @Test fun repeatedBulkRenameIsIdempotentAndDoesNotCascade(){
  val before=snapshot();val plan=AssistantPlanner.build(before,AssistantAction.RenameEvents(listOf("课程 1" to "课程 2","课程 2" to "程设")))
  assertEquals("课程 2",plan.after.entries.single{it.id=="e-1"}.title)
  assertEquals("程设",plan.after.entries.single{it.id=="e-2"}.title)
  val same=AssistantPlanner.build(plan.after,AssistantAction.Change("update","日历","",JSONObject().put("title","程设"),"e-2"))
  assertTrue(same.noOp);assertFalse(same.writes)
  val corrupted=plan.after.copy(entries=plan.after.entries.map{if(it.id=="e-1")it.copy(notes="另一次操作 $seed")else it})
  assertThrows(IllegalStateException::class.java){AssistantUndo.capture(before,plan.after)!!.plan(corrupted)}
 }
}

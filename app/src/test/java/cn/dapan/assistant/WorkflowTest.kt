package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WorkflowTest {
 private val day=LocalDate.of(2026,10,14)
 @Test fun travelAppearsOnPreviousDayWithoutEventBody(){
  val e=CalendarEntry(title="午夜出发",start=day.atTime(0,10),travelMinutes=20)
  val previous=CalendarSlices.forDay(e,day.minusDays(1))
  assertNull(previous.body);assertEquals(DaySpan(1430f,1440f),previous.travel)
  val today=CalendarSlices.forDay(e,day)
  assertEquals(DaySpan(0f,10f),today.travel);assertEquals(DaySpan(10f,70f),today.body)
 }
 @Test fun multiDayEventIsClippedAtBothEnds(){
  val e=CalendarEntry(title="连续日程",start=day.minusDays(1).atTime(23,0),end=day.plusDays(1).atTime(1,0))
  assertEquals(DaySpan(0f,1440f),CalendarSlices.forDay(e,day).body)
  assertEquals(DaySpan(0f,60f),CalendarSlices.forDay(e,day.plusDays(1)).body)
  assertNull(CalendarSlices.forDay(e,day.plusDays(2)).body)
 }
 @Test fun eventEndingAtMidnightDoesNotSpill(){
  val e=CalendarEntry(title="夜间",start=day.atTime(23,0),end=day.plusDays(1).atStartOfDay())
  assertNull(CalendarSlices.forDay(e,day.plusDays(1)).body)
 }
 @Test fun allDayNormalizesToOneDate(){
  val e=CalendarEntry(title="全天",kind=EventKind.ALL_DAY,start=day.atTime(9,0),travelMinutes=20).normalized()
  assertEquals(day.atStartOfDay(),e.start);assertEquals(day.plusDays(1).atStartOfDay(),e.end);assertEquals(0,e.travelMinutes)
 }
 @Test fun deadlineHasNoOccupiedInterval(){
  val e=CalendarEntry(title="交作业",kind=EventKind.DEADLINE,start=day.atTime(9,0)).normalized()
  assertEquals(e.start,e.end);assertEquals(EventDaySlices(null,null),CalendarSlices.forDay(e,day))
 }
 @Test fun chunkingPreservesLongMultilingualText(){
  val text=("第一行🙂 second line\n".repeat(37))+"末尾"
  val chunks=TextChunks.split(text,25)
  assertEquals(text,chunks.joinToString(""));assertTrue(chunks.all{it.length<=25&&it.isNotEmpty()})
  assertTrue(chunks.none{Character.isHighSurrogate(it.last())||Character.isLowSurrogate(it.first())})
 }
 @Test fun chunkingHandlesVeryLongLinesAndTinyLimit(){
  val text="🙂🙂abc🙂".repeat(20);val chunks=TextChunks.split(text,2)
  assertEquals(text,chunks.joinToString(""));assertTrue(chunks.all{it.length<=2});assertTrue(TextChunks.split("",2).isEmpty())
 }
 @Test fun brightPageProposalIsNearTrueCorners(){
  val pixels=IntArray(100*100){i->if(i%100 in 20..80&&i/100 in 15..85)0xffffff else 0x222222}
  val corners=DocumentCorners.detect(100,100,pixels)!!
  assertEquals(20f/99,corners[0].x,.001f);assertEquals(15f/99,corners[0].y,.001f)
  assertEquals(80f/99,corners[2].x,.001f);assertEquals(85f/99,corners[2].y,.001f)
 }
 @Test fun uniformAndFullFrameImagesHaveNoConfidentProposal(){
  assertNull(DocumentCorners.detect(50,50,IntArray(2500){0xeeeeee}))
  val pixels=IntArray(2500){if(it%50 in 24..26&&it/50 in 24..26)0x222222 else 0xffffff}
  assertNull(DocumentCorners.detect(50,50,pixels))
 }
 @Test fun crossedOrNonFiniteCornersAreRejected(){
  assertFalse(DocumentCorners.valid(listOf(PageCorner(0f,0f),PageCorner(1f,1f),PageCorner(1f,0f),PageCorner(0f,1f))))
  assertFalse(DocumentCorners.valid(listOf(PageCorner(Float.NaN,0f),PageCorner(1f,0f),PageCorner(1f,1f),PageCorner(0f,1f))))
 }
 private fun wav(channels:Int=1,dataSize:Int=32000,actualSize:Int=dataSize,junk:Boolean=false):File {
  val out=ByteArrayOutputStream()
  fun ascii(s:String){out.write(s.toByteArray(Charsets.US_ASCII))}
  fun int(n:Int){out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(n).array())}
  fun short(n:Int){out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(n.toShort()).array())}
  ascii("RIFF");int(36+actualSize+if(junk)12 else 0);ascii("WAVE")
  if(junk){ascii("JUNK");int(3);out.write(byteArrayOf(1,2,3,0))}
  ascii("fmt ");int(16);short(1);short(channels);int(16000);int(32000);short(2);short(16)
  ascii("data");int(dataSize);out.write(ByteArray(actualSize))
  return File.createTempFile("dapan-test-",".wav").also{it.writeBytes(out.toByteArray())}
 }
 @Test fun wavSkipsPaddedUnknownChunks(){val file=wav(junk=true);try{val pcm=WavPcm.inspect(file);assertEquals(56L,pcm.offset);assertEquals(1000L,pcm.durationMs)}finally{file.delete()}}
 @Test fun wavRejectsTruncation(){val file=wav(actualSize=100);try{assertThrows(IllegalArgumentException::class.java){WavPcm.inspect(file)}}finally{file.delete()}}
 @Test fun wavRejectsWrongChannelCount(){val file=wav(channels=2);try{assertThrows(IllegalArgumentException::class.java){WavPcm.inspect(file)}}finally{file.delete()}}
 @Test fun wavRejectsEmptyAudio(){val file=wav(dataSize=0);try{assertThrows(IllegalArgumentException::class.java){WavPcm.inspect(file)}}finally{file.delete()}}
 @Test fun wavRejectsOddSampleBytes(){val file=wav(dataSize=3);try{assertThrows(IllegalArgumentException::class.java){WavPcm.inspect(file)}}finally{file.delete()}}
}

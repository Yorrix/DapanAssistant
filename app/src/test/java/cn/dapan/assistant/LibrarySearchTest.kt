package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class LibrarySearchTest {
 @Test fun findsTailAndRanksCoverageWithoutRequiringEveryWord(){
  val text="资料正文\n".repeat(6000)+"携带玉兰卡，到 A01 报名。\n"
  val hit=LibrarySearch.match("讲义.txt","",text,"报名 玉兰卡 不存在")
  assertEquals(listOf("报名","玉兰卡"),hit.matchedTerms)
  assertTrue(hit.evidence.any{it.part.start>=30000&&it.quote.contains("玉兰卡")})
  hit.evidence.forEach{assertEquals(it.quote,text.substring(it.part.start,it.part.end))}
  assertTrue(hit.score>LibrarySearch.match("普通文件","","报名","报名 玉兰卡 不存在").score)
 }
 @Test fun keywordAcrossChunkBoundaryIsNotLost(){
  val text="字".repeat(LibrarySearch.PART_SIZE-2)+"玉兰卡借书规则"+"字".repeat(100)
  val hit=LibrarySearch.match("规则","",text,"玉兰卡")
  assertTrue(hit.evidence.single().quote.contains("玉兰卡"))
 }
 @Test fun sourceMarkersAreOnlyTrustedForActualFormats(){
  val pdf="第1页\n普通内容\n第2页\n报名截止10月28日\n"
  assertEquals(2,LibrarySearch.match("文件.pdf","",pdf,"报名",LibraryTextFormat.PDF).evidence.single().part.page)
  assertNull(LibrarySearch.match("笔记.txt","",pdf,"报名").evidence.single().part.page)
  val sheet="xl/worksheets/sheet2.xml（单元格值，不重算公式）\nA3=报名 B3=日期\n"
  val part=LibrarySearch.match("表.xlsx","",sheet,"报名",LibraryTextFormat.SHEET).evidence.single().part
  assertEquals("工作表 2",part.sheet);assertEquals("A3–B3",part.cells)
 }
 @Test fun readingAllPagesDoesNotSkipOrDuplicateCharacters(){
  val text=("行😀中文\n").repeat(1000)
  val out=StringBuilder();var offset=0
  do{val page=LibrarySearch.read(text,offset,501);out.append(page.text);offset=page.nextOffset?:text.length}while(offset<text.length)
  assertEquals(text,out.toString())
 }
 @Test fun filenameMatchesDoNotInventSourceQuotes(){
  val hit=LibrarySearch.match("奖学金.pdf","", "其他正文", "奖学金")
  assertFalse(hit.matchedTerms.isEmpty());assertTrue(hit.evidence.isEmpty())
 }
}

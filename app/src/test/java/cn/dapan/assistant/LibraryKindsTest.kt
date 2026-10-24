package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class LibraryKindsTest {
 @Test fun genericMimeStillClassifiesCommonFiles(){
  mapOf("课件.JPG" to LibraryKind.IMAGE,"课件.pdf" to LibraryKind.PDF,"预算.xlsx" to LibraryKind.SHEET,
   "报告.pptx" to LibraryKind.SLIDES,"笔记.md" to LibraryKind.DOCUMENT,"录音.m4a" to LibraryKind.AUDIO,
   "录像.MP4" to LibraryKind.VIDEO,"资料.7z" to LibraryKind.ARCHIVE,"二进制.bin" to LibraryKind.OTHER).forEach{(name,kind)->
    assertEquals(name,kind,LibraryKind.of(name,"application/octet-stream"))
   }
 }
 @Test fun missingOrRenamedExtensionsUseMime(){
  assertEquals(LibraryKind.IMAGE,LibraryKind.of("课堂拍照","IMAGE/JPEG"))
  assertEquals(LibraryKind.PDF,LibraryKind.of("扫描件","application/pdf"))
  assertEquals(LibraryKind.SHEET,LibraryKind.of("统计","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
  assertEquals(LibraryKind.SLIDES,LibraryKind.of("课件","application/vnd.openxmlformats-officedocument.presentationml.presentation"))
 }
 @Test fun csvTakesPriorityOverPlainTextCategory(){
  assertEquals(LibraryKind.SHEET,LibraryKind.of("账单.csv","text/csv"))
  assertEquals(LibraryKind.DOCUMENT,LibraryKind.of("课堂笔记","text/plain"))
 }
 @Test fun smallFilesDoNotAllDisplayZeroMegabytes(){
  assertEquals("600 B",libraryFileSize(600));assertEquals("24 KB",libraryFileSize(24576));assertEquals("2.5 MB",libraryFileSize(2621440))
 }
 @Test fun unsupportedAiFilterDoesNotSilentlySelectAll(){
  assertEquals(LibraryKind.IMAGE,LibraryKind.parse("图片"));assertEquals(LibraryKind.IMAGE,LibraryKind.parse("image"))
  assertThrows(IllegalArgumentException::class.java){LibraryKind.parse("未知类型")}
 }
}

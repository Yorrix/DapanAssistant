package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LibraryIndexTest {
 @Test fun arbitraryFileRemainsStorableWithoutClaimingReadableText(){
  val f=File.createTempFile("dapan-library-",".bin")
  try{f.writeBytes(byteArrayOf(0,1,2,3));val (text,state)=AssistantFiles.extractText(f,"压缩包.zip","application/zip");assertEquals("",text);assertTrue(state.contains("尚不支持正文"))}finally{f.delete()}
 }
 @Test fun textIndexPreservesChineseKnowledgeKeywords(){
  val f=File.createTempFile("dapan-library-",".txt")
  try{f.writeText("极限与连续\n老师办公室 A01-203");val (text,state)=AssistantFiles.extractText(f,"高数笔记.txt","text/plain");assertTrue(text.contains("极限"));assertTrue(text.contains("A01-203"));assertEquals("正文已索引",state)}finally{f.delete()}
 }
 @Test fun oversizedTextIsExplicitlyPartialAndBounded(){
  val f=File.createTempFile("dapan-library-",".txt")
  try{f.writeText("学".repeat(210000));val (text,state)=AssistantFiles.extractText(f,"长笔记.md","text/plain");assertEquals(200000,text.length);assertTrue(state.startsWith("部分正文"))}finally{f.delete()}
 }
 @Test fun binaryPretendingToBeTextIsNotIndexedAsKnowledge(){
  val f=File.createTempFile("dapan-library-",".txt")
  try{f.writeBytes(byteArrayOf(65,0,66));assertThrows(IllegalArgumentException::class.java){AssistantFiles.extractText(f,"伪文本.txt","text/plain")}}finally{f.delete()}
 }
}

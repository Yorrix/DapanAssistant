package cn.dapan.assistant

import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class RecordingMarkdownTest {
 private fun parse(text:String)=Parser.builder().build().parse(RecordingMarkdown.normalize(text)).also{RecordingMarkdown.linkReferences(it)}
 private fun links(node:Node):List<Link>{val result=mutableListOf<Link>();node.accept(object:AbstractVisitor(){override fun visit(link:Link){result+=link;visitChildren(link)}});return result}
 @Test fun markdownStructureAndAudioCitationsSurvive(){
  val node=parse("## 重点\n\n- **结论** 【R2 00:03:05】\n  - 补充\n\n> 引用\n\n```kotlin\nval a = 1\n```")
  assertTrue(node.firstChild is Heading)
  assertTrue(node.firstChild.next is BulletList)
  assertEquals("dapan-audio://seek/2/185000",links(node).single().destination)
  assertTrue(node.lastChild is FencedCodeBlock)
 }
 @Test fun citationsInsideCodeAndExistingLinksStayLiteral(){
  assertTrue(links(parse("`【R1 00:00:05】`\n\n```\n【R2 00:00:05】\n```")).isEmpty())
  assertEquals(listOf("https://example.org"),links(parse("[【R1 00:00:05】](https://example.org)")).map{it.destination})
 }
 @Test fun badReferencesStayPlainAndMultipleReferencesRemainSeparate(){
  val links=links(parse("前【R0 00:01:00】中【R1 00:60:00】后【R3 01:02:03】与【R4 00:00:01】"))
  assertEquals(listOf("dapan-audio://seek/3/3723000","dapan-audio://seek/4/1000"),links.map{it.destination})
  assertNull(RecordingMarkdown.reference("dapan-audio://seek/0/1"))
  assertNull(RecordingMarkdown.reference("https://evil.test/seek/1/2"))
 }
 @Test fun unwrapOnlyWholeDocumentMarkdownFence(){
  assertEquals("# 标题\n正文",RecordingMarkdown.normalize("```markdown\n# 标题\n正文\n```"))
  val code="```kotlin\nval a=1\n```"
  assertEquals(code,RecordingMarkdown.normalize(code))
  val mixed="```md\n# 示例\n```\n\n正文\n```\n其他\n```"
  assertEquals(mixed,RecordingMarkdown.normalize(mixed))
 }
}

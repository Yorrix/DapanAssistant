package cn.dapan.assistant
import org.junit.Assert.*
import org.junit.Test

class CampusEmailTest {
 @Test fun acceptsNamedAccountsAndBothOfficialDomains(){
  assertEquals("mail.dlut.edu.cn",CampusEmail.domains.first())
  assertEquals("yorrix@mail.dlut.edu.cn",CampusEmail.address("yorrix","mail.dlut.edu.cn"))
  assertEquals("first.last@dlut.edu.cn",CampusEmail.address("first.last","dlut.edu.cn"))
 }
 @Test fun pastedAddressSelectsItsSuffix(){
  assertEquals("yorrix" to "dlut.edu.cn",CampusEmail.split(" yorrix@DLUT.EDU.CN ","mail.dlut.edu.cn"))
  assertEquals("123" to "mail.dlut.edu.cn",CampusEmail.split("123","mail.dlut.edu.cn"))
 }
 @Test fun rejectsDoubleSuffixWhitespaceAndForeignDomains(){
  listOf("a@b","a b","","a..b",".ab","ab.").forEach{assertNull(CampusEmail.address(it,"mail.dlut.edu.cn"))}
  assertNull(CampusEmail.address("a","evil.dlut.edu.cn"))
  val pasted=CampusEmail.split("name@example.com","mail.dlut.edu.cn")
  assertNull(CampusEmail.address(pasted.first,pasted.second))
 }
}

package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class ConnectivityRulesTest {
 @Test fun onlyValidModelListCanPassDeepSeekCheck(){
  listOf("", "<html>ok</html>", "{}", "{\"object\":\"list\",\"data\":[]}", "{\"object\":\"list\",\"data\":[{}]}").forEach{
   assertEquals(ConnectivityStatus.FAILED,ConnectivityRules.models(it,"deepseek-flash").status)
  }
  val valid="{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-flash\"}]}"
  assertEquals(ConnectivityStatus.PASSED,ConnectivityRules.models(valid,"deepseek-flash").status)
  assertEquals(ConnectivityStatus.LIMITED,ConnectivityRules.models(valid,"deepseek-v4-pro").status)
 }
 @Test fun httpErrorsNeverCountAsSuccess(){
  val expected=mapOf(401 to ConnectivityReason.AUTH,402 to ConnectivityReason.BALANCE,403 to ConnectivityReason.PERMISSION,429 to ConnectivityReason.RATE_LIMIT,405 to ConnectivityReason.ENDPOINT,302 to ConnectivityReason.ENDPOINT,500 to ConnectivityReason.SERVER,504 to ConnectivityReason.TIMEOUT)
  expected.forEach{(code,reason)->val result=ConnectivityRules.http(code,"",KeyProvider.ALIYUN);assertEquals(ConnectivityStatus.FAILED,result.status);assertEquals(reason,result.reason)}
 }
 @Test fun serviceCodesClassifyQuotaWithoutExposingResponse(){
  val secret="sk-never-render-this-secret"
  val result=ConnectivityRules.http(400,"{\"code\":\"Arrearage\",\"message\":\"$secret\"}",KeyProvider.ALIYUN)
  assertEquals(ConnectivityReason.BALANCE,result.reason)
  assertFalse(result.toString().contains(secret))
  assertFalse(ConnectivityRules.network(IllegalStateException(secret)).toString().contains(secret))
 }
 @Test fun transportFailuresAreNotMisreportedAsBadKeys(){
  assertEquals(ConnectivityReason.DNS,ConnectivityRules.network(UnknownHostException()).reason)
  assertEquals(ConnectivityReason.TIMEOUT,ConnectivityRules.network(SocketTimeoutException()).reason)
  assertEquals(ConnectivityReason.TLS,ConnectivityRules.network(SSLHandshakeException("bad certificate")).reason)
 }
 @Test fun inputValidationRejectsEmptyAndHeaderInjection(){
  assertNotNull(ConnectivityRules.invalidInput(""));assertNotNull(ConnectivityRules.invalidInput("sk-key\r\nAuthorization: other"))
  assertNotNull(ConnectivityRules.invalidInput("sk-密钥"));assertNull(ConnectivityRules.invalidInput("sk-valid-ascii-key"))
 }
}

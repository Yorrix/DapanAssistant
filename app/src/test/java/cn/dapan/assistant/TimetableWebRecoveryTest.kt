package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class TimetableWebRecoveryTest {
 @Test fun rendererExitAndTwoLifecycleDisposalsReleaseOnlyOnce() {
  val view = Any()
  val lease = TimetableWebLease(view)
  assertSame(view, lease.takeForRelease()) // onRenderProcessGone
  assertNull(lease.value) // callbacks cannot access a destroyed WebView
  assertNull(lease.takeForRelease()) // AndroidView.onRelease
  assertNull(lease.takeForRelease()) // Activity.onDestroy
 }

 @Test fun lateDisposalOfOldViewDoesNotReleaseTheReplacement() {
  val old = TimetableWebLease(Any())
  old.takeForRelease()
  val replacement = Any()
  val next = TimetableWebLease(replacement)
  assertNull(old.takeForRelease())
  assertSame(replacement, next.value)
  assertSame(replacement, next.takeForRelease())
 }

 @Test fun recoveryNeverReplaysLoginTicketsOrUntrustedUrls() {
  listOf(null, "https://sso.dlut.edu.cn/cas/login?ticket=secret",
   "http://jxgl.dlut.edu.cn/student/ucas-sso/login?ticket=secret",
   "https://jxgl.dlut.edu.cn.evil.test/student/for-std/course-table",
   "https://user:secret@jxgl.dlut.edu.cn/student/for-std/course-table",
   "javascript:alert(1)", "file:///data/local/test.html").forEach {
   assertEquals(TimetableImport.ENTRY, TimetableWebRecovery.retryUrl(it))
  }
  assertEquals(TimetablePage.COURSE_URL, TimetableWebRecovery.retryUrl(
   "http://jxgl.dlut.edu.cn/student/for-std/course-table/info/all-courses?semester=443&ticket=secret"))
  assertEquals("https://jxgl.dlut.edu.cn/student/for-std/course-table", TimetableWebRecovery.retryUrl(
   "https://jxgl.dlut.edu.cn/student/for-std/course-table?semester=443"))
 }
}

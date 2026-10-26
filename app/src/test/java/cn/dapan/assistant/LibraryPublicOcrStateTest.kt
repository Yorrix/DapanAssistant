package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class LibraryPublicOcrStateTest {
 @Test fun importingOrRestartingNeverAutomaticallyResumesPendingPublicConsent(){
  assertFalse(LibraryIndexState.resumable(LibraryIndexState.PUBLIC_CONSENT))
  assertTrue(LibraryIndexState.resumable(LibraryIndexState.WAITING))
  assertTrue(LibraryIndexState.resumable(LibraryIndexState.RUNNING))
 }
 @Test fun oldKeyMissingStatesRemainRecoverableAfterUpgrade(){
  assertTrue(LibraryIndexState.needsAiSetup("等待配置 DeepSeek 密钥"))
  assertTrue(LibraryIndexState.needsAiSetup(LibraryIndexState.NO_KEY))
  assertTrue(LibraryIndexState.resumable("等待配置 DeepSeek 密钥"))
 }
}

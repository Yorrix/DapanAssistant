package cn.dapan.assistant

import org.junit.Assert.*
import org.junit.Test

class VocabularyRulesTest {
 private val dictionary=mapOf(
  "the" to VocabularyWord("the","ðə","定冠词",1),
  "student" to VocabularyWord("student","stjuːdənt","学生",3),
  "students" to VocabularyWord("student","stjuːdənt","学生",3),
  "derive" to VocabularyWord("derive","dɪˈraɪv","推导",4),
  "derived" to VocabularyWord("derive","dɪˈraɪv","推导",4),
  "hypothesis" to VocabularyWord("hypothesis","haɪˈpɒθəsɪs","假设",8),
  "eigenvalue" to VocabularyWord("eigenvalue","ˈaɪɡənvæljuː","特征值",0),
  "eigenvalues" to VocabularyWord("eigenvalue","ˈaɪɡənvæljuː","特征值",0)
 )
 private fun words(text:String,level:VocabularyLevel=VocabularyLevel.HIGH_SCHOOL,known:Set<String> = emptySet())=VocabularyRules.collect(text,level,known,dictionary::get)
 @Test fun mixedLectureShowsOnlyUnknownWordsNotWholeSentences(){
  val hits=words("今天 the students derived the eigenvalues. Eigenvalue means 特征值。")
  assertEquals(listOf("derive","eigenvalue"),hits.map{it.word.word})
  assertEquals(2,hits.last().count)
 }
 @Test fun levelsAreCumulativeAndBasicWordsAlwaysFiltered(){
  val text="the student derive hypothesis eigenvalue"
  assertEquals(listOf("derive","hypothesis","eigenvalue"),words(text).map{it.word.word})
  assertEquals(listOf("hypothesis","eigenvalue"),words(text,VocabularyLevel.CET4).map{it.word.word})
  assertEquals(listOf("eigenvalue"),words(text,VocabularyLevel.CET6).map{it.word.word})
  assertFalse(words(text,VocabularyLevel.BASIC).any{it.word.word=="the"})
 }
 @Test fun personalKnownAlsoHidesInflections(){assertTrue(words("eigenvalue eigenvalues",known=setOf("eigenvalue")).isEmpty())}
 @Test fun contractionsAndPossessivesDoNotBecomeNoise(){assertEquals(listOf("student","eigenvalue"),VocabularyRules.words("I'm, it's, we'll, can't. student's EIGENVALUE"))}
 @Test fun missingDictionaryEntryDoesNotInventMeaning(){assertTrue(words("abcdefghxyz").isEmpty())}
 @Test fun finalSegmentIsNotCountedTwiceButReconnectIdsAreIndependent(){val gate=VocabularySentenceGate();assertTrue(gate.accept(1,0,0));assertFalse(gate.accept(1,0,0));assertTrue(gate.accept(2,0,0));assertTrue(gate.accept(2,1,0))}
 @Test fun unknownVocabularySelectionIsRejected(){assertThrows(IllegalStateException::class.java){VocabularyLevel.parse("all")}}
 @Test fun transientProviderFailuresCanReconnectWithoutHidingAccountErrors(){
  assertTrue(VocabularyAsrFailure.retryable("CLIENT_ERROR","request timeout after 23 seconds"))
  assertTrue(VocabularyAsrFailure.retryable("InternalError","service unavailable"))
  assertFalse(VocabularyAsrFailure.retryable("InvalidApiKey","authentication timeout"))
  assertFalse(VocabularyAsrFailure.retryable("QuotaExceeded","quota exceeded"))
  assertFalse(VocabularyAsrFailure.retryable("InvalidParameter","unsupported format"))
 }
 @Test fun classroomToolIsAvailableToAiAcrossPages(){
  assertTrue("课堂生词" in AssistantActions.destinations)
  val ops=AssistantCatalogue.json().getJSONArray("课堂生词").objects().map{it.getString("op")}
  assertEquals(setOf("configure","start","stop","known","search","clear"),ops.toSet())
 }
}

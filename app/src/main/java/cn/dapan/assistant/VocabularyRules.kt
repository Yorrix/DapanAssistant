package cn.dapan.assistant

import java.util.Locale

enum class VocabularyLevel(val label:String,val mask:Int) {
 BASIC("基础",1), HIGH_SCHOOL("高中",3), CET4("四级",7), CET6("六级",15);
 companion object { fun parse(value:String)=entries.firstOrNull{it.label==value}?:error("请选择基础、高中、四级或六级") }
}
data class VocabularyWord(val word:String,val phonetic:String,val meaning:String,val tags:Int)
data class VocabularyHit(val word:VocabularyWord,val count:Int)

/** Lookup returns a dictionary lemma, never an invented meaning or guessed pronunciation. */
object VocabularyRules {
 private val token=Regex("[A-Za-z]+(?:['’][A-Za-z]+)?(?:-[A-Za-z]+)*")
 private val contractions=setOf("i'm","you're","we're","they're","he's","she's","it's","that's","there's","what's","let's","don't","doesn't","didn't","isn't","aren't","wasn't","weren't","can't","cannot","couldn't","won't","wouldn't","shouldn't","mustn't","haven't","hasn't","hadn't","i've","you've","we've","they've","i'll","you'll","he'll","she'll","we'll","they'll","i'd","you'd","he'd","she'd","we'd","they'd")
 fun words(text:String):List<String> = token.findAll(text.take(16000)).map{it.value.lowercase(Locale.ROOT).replace('’','\'')}.filter{it.length>=3&&it !in contractions}.map{it.removeSuffix("'s")}.toList()
 fun collect(text:String,level:VocabularyLevel,known:Set<String>,lookup:(String)->VocabularyWord?):List<VocabularyHit> {
  val counts=linkedMapOf<String,VocabularyHit>()
  for(token in words(text)) {
   val entry=lookup(token)?:continue
   if(entry.tags and level.mask != 0 || entry.word in known || token in known || entry.meaning.isBlank())continue
   counts[entry.word]=VocabularyHit(entry,(counts[entry.word]?.count?:0)+1)
  }
  return counts.values.toList()
 }
}

/** Provider may repeat a final segment; retain only a bounded identity window. */
class VocabularySentenceGate {
 private val seen=linkedSetOf<String>()
 fun accept(connection:Int,id:Int,start:Long):Boolean {
  if(!seen.add("$connection:$id:$start"))return false
  if(seen.size>256)seen.remove(seen.first())
  return true
 }
}

internal object VocabularyAsrFailure {
 fun retryable(code:String,message:String):Boolean {
  val detail="$code $message".lowercase(Locale.ROOT)
  if(listOf("unauthorized","forbidden","invalid_api_key","invalidapikey","authentication","quota","balance","billing","invalidparameter","invalid_parameter").any{it in detail})return false
  return listOf("timeout","timed out","internal_error","internalerror","server_error","servererror","service_unavailable","serviceunavailable","service unavailable","overloaded").any{it in detail}
 }
}

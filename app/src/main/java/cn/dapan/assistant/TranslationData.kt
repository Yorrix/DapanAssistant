package cn.dapan.assistant

/** Frame-rate independent, forward-only easing. A changing tail must never cause a reverse jump. */
internal fun translationFollowStep(remaining:Float,elapsedMs:Float):Float{
 if(!remaining.isFinite()||remaining<=0f)return 0f
 if(remaining<=0.75f)return remaining
 val dt=if(elapsedMs.isFinite())elapsedMs.coerceIn(0f,64f)else 0f
 return (remaining*(1.0-kotlin.math.exp(-dt/85.0))).toFloat().coerceIn(0f,remaining)
}

data class TranslationLanguage(val name:String,val code:String)
object TranslationLanguages {
 val values=listOf(TranslationLanguage("中文","zh"),TranslationLanguage("英语","en"),TranslationLanguage("日语","ja"),TranslationLanguage("韩语","ko"),TranslationLanguage("法语","fr"),TranslationLanguage("德语","de"),TranslationLanguage("西班牙语","es"),TranslationLanguage("俄语","ru"))
 val names=values.map{it.name}.toSet()
 fun code(name:String)=values.first{it.name==name}.code
}
/** Final ASR sentences are translated once; partial revisions are never submitted. */
class FinalSentenceCursor {
 private var consumed=0
 fun reset(){consumed=0}
 fun take(texts:List<String>):List<String>{val new=texts.drop(consumed);consumed=texts.size;return new.filter{it.isNotBlank()}}
}

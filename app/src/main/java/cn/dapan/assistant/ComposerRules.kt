package cn.dapan.assistant

object ComposerRules {
 /** Insert after the active caret, never replace pre-existing text/selection. */
 fun insert(text:String,caret:Int?,speech:String):Pair<String,Int>{
  val at=(caret?:text.length).coerceIn(0,text.length)
  return (text.substring(0,at)+speech+text.substring(at)) to (at+speech.length)
 }
}

object VoiceRelease {
 fun inside(x:Float,y:Float,width:Float,height:Float)=x>=0f&&y>=0f&&x<width&&y<height
 fun shouldSend(released:Boolean,cancelled:Boolean,error:String?,text:String)=released&&!cancelled&&error==null&&text.isNotBlank()
}

package cn.dapan.assistant

import java.util.Locale

/** Conservative de-duplication of an event's own packing note, before preview or execution. */
internal object AssistantIntent {
 private val separators=Regex("[\\s\\p{P}]+")
 private fun key(text:String)=text.lowercase(Locale.ROOT).replace(separators,"")
  .replace("带上","带").replace("带着","带").replace("携带","带").replace("拿上","带")
 private val carrying=Regex("^(?:(?:记得|记住|别忘了|别忘|不要忘记|需要|要|请|一定要|记得要)*)?(?:带|携带|拿上).+")
 private val independent=Regex("待办|待办事项|任务清单|打包清单|行李清单|物品清单|tdl|to[ -]?do|(?:单独|独立|另建|另加|再建|再加|额外).{0,12}(?:提醒|事项|任务|记录|清单)|(?:两|2|二)个(?:事项|任务|提醒)",RegexOption.IGNORE_CASE)
 private fun wantsIndependent(request:String)=independent.findAll(request).any{match->
  val preceding=request.substring(maxOf(0,match.range.first-14),match.range.first).substringAfterLast('，').substringAfterLast('。')
  !Regex("不要|不用|不需要|不必|别|无需|无须|不是|不用再|don't|do not|without",RegexOption.IGNORE_CASE).containsMatchIn(preceding)
 }
 private fun noteParts(text:String)=text.split(Regex("[\n；;。，,]+"))
  .map{key(it).removePrefix("备注").removePrefix("记得").removePrefix("别忘了").removePrefix("别忘")}

 fun normalize(action:AssistantAction,request:String):AssistantAction {
  if(action !is AssistantAction.Batch||wantsIndependent(request))return action
  // Do not guess which event a packing note belongs to when several events were requested.
  val event=action.actions.filterIsInstance<AssistantAction.Event>().map{it.value}
   .filter{it.kind in setOf(EventKind.TIMED,EventKind.ALL_DAY)}.singleOrNull()?:return action
  val eventNotes=noteParts(event.notes)
  if(eventNotes.isEmpty())return action
  val requestKey=key(request)
  val retained=action.actions.filterNot{candidate->
   val title:String;val detail:String
   when(candidate){
    is AssistantAction.Event->{if(candidate.value.kind!=EventKind.TODO)return@filterNot false;title=candidate.value.title;detail=candidate.value.notes}
    is AssistantAction.Note->{if(candidate.value.module!="待办")return@filterNot false;title=candidate.value.title;detail=candidate.value.detail}
    else->return@filterNot false
   }
   val packing=key(title).removePrefix("记得").removePrefix("别忘了").removePrefix("别忘")
   // Exact whole note matching prevents swallowing a separate shopping/preparation task or a
   // longer instruction which merely mentions the same object. User input must also contain it.
   carrying.matches(title.trim())&&packing.length>=3&&requestKey.contains(packing)&&
    packing in eventNotes&&(detail.isBlank()||noteParts(detail).all{it in eventNotes})
  }
  return when{retained.size==action.actions.size->action;retained.size==1->retained.single();else->AssistantAction.Batch(retained)}
 }
}

package cn.dapan.assistant

/** A damaged action verb is not evidence of completion. Ask the missing distinction
 * once instead of executing the wrong mutation or cycling through model retries. */
internal object AssistantSpokenIntent {
 fun clarify(action:AssistantAction,request:String):AssistantAction {
  if(!Regex("TDL|待办",RegexOption.IGNORE_CASE).containsMatchIn(request))return action
  if(!Regex("截了|解了").containsMatchIn(request))return action
  if(Regex("删掉|删除|删了|去掉|移除|取消|做完|完成|搞定|办完|标记|标为|打勾").containsMatchIn(request))return action
  val actions=if(action is AssistantAction.Batch)action.actions else listOf(action)
  val change=actions.filterIsInstance<AssistantAction.Change>().firstOrNull{it.module in setOf("待办","日历")&&
   (it.operation in setOf("delete","complete")||it.patch.optBoolean("completed"))}?:return action
  val name=change.target.takeIf{it.isNotBlank()}?.let{"“$it”"}?:"这条待办"
  return AssistantAction.Reply("$name 要删除，还是标为完成？")
 }
}

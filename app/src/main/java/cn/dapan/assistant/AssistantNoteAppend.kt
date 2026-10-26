package cn.dapan.assistant

/** Protect explicit literal additions without rewriting the user's meaning locally. */
internal object AssistantNoteAppend {
 fun validate(action:AssistantAction,text:String,state:AssistantSnapshot?){
  val replacements=Regex("备注(?:全部|直接)?(?:改成|改为|换成|替换为|设为)[：:\\s]*([^，。！？;；\\n]+)").findAll(text).toList()
  val replacement=replacements.singleOrNull()
  if(replacement!=null){
   val prefix=text.substring(0,replacement.range.first).takeLast(8)
   val value=replacement.groupValues[1].trim().trim('“','”','\"')
   // Only an explicit replacement of a literal clause, never an inferred rewrite.
   val trailing=text.substring(replacement.range.last+1).trimStart('，',',','。','；',';',' ','\n')
   val literalBoundary=trailing.isBlank()||Regex("^(?:别改|不要改|其他|其它|时间.{0,4}地点|保持|其余)").containsMatchIn(trailing)
   if(literalBoundary&&!Regex("(?:不|别|不要|不用).{0,3}$").containsMatchIn(prefix)&&!Regex("保留|追加|加上|以及|并且").containsMatchIn(value)){
    val changes=(if(action is AssistantAction.Batch)action.actions else listOf(action)).filterIsInstance<AssistantAction.Change>()
     .filter{it.module in setOf("日历","待办")&&it.operation=="update"&&it.patch.has("notes")}
    changes.singleOrNull()?.let{require(it.patch.optString("notes").trim().trimEnd('。','.')==value.trimEnd('。','.')){
     "用户明确把备注替换为“$value”，不能继续保留旧备注；只有‘追加/再加’才保留旧内容。尚未执行。"
    }}
   }
  }
  if(Regex("不(?:要|用)|别|无需|怎么|如何").containsMatchIn(text))return
  val addition=Regex("(?:加(?:上|个)?|补充|追加)(?:一(?:条|个))?备注[：:\\s]*([^，。！？;；\\n]+)").find(text)?.groupValues?.get(1)?.trim()?.trim('“','”','\"')?:return
  if(addition.isBlank()||addition.length>1200)return
  val changes=(if(action is AssistantAction.Batch)action.actions else listOf(action)).filterIsInstance<AssistantAction.Change>()
   .filter{it.module in setOf("日历","待办")&&it.operation=="update"}
  changes.forEach{change->
   val notes=change.patch.optString("notes")
   require(notes.contains(addition)){"用户明确追加的备注是“$addition”，notes 不能遗漏或改成旧内容；请核对当前记录后保留原备注并追加这句话。尚未执行。"}
   val old=state?.calendar?.filter{if(change.targetId.isNotBlank())it.id==change.targetId else it.title==change.target}?.singleOrNull()
   require(old==null||old.notes.isBlank()||notes.contains(old.notes)){"用户要求追加备注，不能覆盖原备注“${old?.notes}”。请保留原内容并加入“$addition”，其余字段不变。尚未执行。"}
  }
 }
}

package cn.dapan.assistant

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Export only the conversation the user can read, never undo snapshots or model evidence. */
internal object ConversationFeedback {
 const val MAX_BYTES=1_000_000
 private const val MASK="[已隐藏密钥]"
 fun redact(text:String):String = text
  .replace(Regex("\\bsk-[a-z0-9._-]{12,}",RegexOption.IGNORE_CASE),MASK)
  .replace(Regex("\\bBearer\\s+[a-z0-9._~+/-]+=*",RegexOption.IGNORE_CASE),"Bearer $MASK")
  .replace(Regex("([\"']?(?:api[_ -]?key|access[_ -]?token|password|密码|密钥)[\"']?\\s*[:=：]\\s*)[\"']?[^\\s,，;；\"'}]+",RegexOption.IGNORE_CASE)){it.groupValues[1]+MASK}

 fun operation(op:AssistantOperation):String=buildString {
  appendLine("${op.status.label} · ${op.summary}")
  op.items.forEach { item->
   appendLine("${item.verb} · ${item.title}"+(if(item.count>1)" · ${item.count} 项"else""))
   (item.before.keys+item.after.keys).distinct().forEach { field->
    val before=item.before[field];val after=item.after[field]
    appendLine("$field："+if(before!=null&&after!=null)"$before → $after"else after?:before.orEmpty())
   }
   if(item.context.isNotBlank())appendLine(item.context)
  }
  if(op.detail.isNotBlank())appendLine(op.detail)
 }.trim()

 fun payload(lines:List<ChatLine>,installationId:String,version:String,code:Int,device:String,androidVersion:String,screen:String):JSONObject {
  require(lines.isNotEmpty()&&lines.size<=100){"没有可提交的对话"}
  val messages=lines.map { line->
   val text=redact(line.text);val operation=redact(line.operation?.let(::operation).orEmpty())
   require(text.length<=100_000&&operation.length<=200_000){"对话记录过长，本次未提交"}
   JSONObject().put("role",if(line.role=="user")"user"else"assistant").put("text",text)
    .put("time",line.updatedAt.coerceAtLeast(0)).put("operation",operation)
  }
  val value=JSONObject().put("schemaVersion",1).put("installationId",installationId).put("consent",true)
   .put("appVersion",version).put("versionCode",code).put("device",device.take(100))
   .put("androidVersion",androidVersion.take(40)).put("screen",screen.take(80)).put("messages",JSONArray(messages))
  // Same snapshot keeps one ID, including after timeouts, reopening the dialog or app restart.
  val id=UUID.nameUUIDFromBytes(value.toString().toByteArray(Charsets.UTF_8)).toString()
  value.put("reportId",id)
  require(value.toString().toByteArray(Charsets.UTF_8).size<=MAX_BYTES){"对话记录过长，本次未提交；请先保存需要的内容，缩短历史后重试"}
  return value
 }
}

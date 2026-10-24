package cn.dapan.assistant

import org.json.JSONObject

internal data class RecordingTarget(val folderId:String?,val title:String,val kind:String)

/** Explicit creation must never inherit the folder currently open on the screen. */
internal object AssistantRecordingTarget {
 fun validate(args:JSONObject){
  val namesNewFolder=args.has("title")||args.has("kind")
  require(!args.has("folderId")||!namesNewFolder){"录音目标冲突：追加已有记录只传 folderId，新建录音记录只传 title 和 kind"}
  if(args.has("folderId"))args.text("folderId",100)
  if(namesNewFolder){
   require(args.has("title")&&args.has("kind")){"新建并录音需要 title 和 kind（课程/会议/其他）"}
   args.text("title",120);args.onlyChoice("kind",listOf("课程","会议","其他"))
  }
 }
 fun resolve(args:JSONObject,selectedId:String?,folders:List<MeetingFolder>):RecordingTarget{
  validate(args)
  if(args.has("title"))return RecordingTarget(null,args.text("title",120),args.getString("kind"))
  val id=if(args.has("folderId"))args.getString("folderId").trim()else selectedId.orEmpty()
  require(id.isNotBlank()){"请指定已有录音记录，或提供新记录的名称和类型"}
  val folder=folders.singleOrNull{it.id==id}
  require(folder!=null){"指定的录音记录不存在，请重新查找；没有创建或开始录音"}
  return RecordingTarget(folder.id,folder.title,folder.kind)
 }
}

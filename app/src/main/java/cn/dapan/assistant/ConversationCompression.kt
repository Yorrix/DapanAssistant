package cn.dapan.assistant

import org.json.JSONArray
import org.json.JSONObject

/** Keeps the transcript intact. Only the copy sent to the model is compressed. */
internal object ConversationCompression {
    const val MARKER="已压缩此前对话，原聊天记录仍可查看。"
    private fun excerpt(text:String,limit:Int):String {
        if(text.length<=limit) return text
        return text.take(limit*2/3)+"\n[…节选…]\n"+text.takeLast(limit/3-10)
    }
    fun messages(history:List<ChatLine>,request:String):List<JSONObject>{
        val eligible=history.filter{it.includeInModel || it.role=="user" || it.operation!=null}
        var budget=10500
        val recent=mutableListOf<JSONObject>()
        val boundary=eligible.indexOfLast{it.role=="assistant" && it.text==MARKER}
        for(line in eligible.drop(boundary+1).asReversed().take(12)){
            val body=excerpt(line.modelText,if(line.role=="user") 2400 else 3200)
            if(body.length>budget) break
            recent+=JSONObject().put("role",line.role).put("content",body)
            budget-=body.length
        }
        val older=eligible.dropLast(recent.size)
        if(older.isEmpty()) return recent.asReversed()
        val anchors=older.filter{it.role=="user" && it.text.length>8}.takeLast(12)
        val digest=JSONArray(anchors.map{excerpt(it.text,210)})
        val summary=JSONObject().put("历史用户要求摘录",digest).put("最近答复摘录",JSONArray(older.filter{it.role=="assistant" && it.operation==null && it.text!=MARKER}.takeLast(4).map{excerpt(it.text,360)}))
        if(followsAttachment(request,eligible.map{it.text})){
            val group=eligible.indexOfLast{it.role=="user" && it.text.contains("[附件：")}
            older.drop((group+1).coerceAtMost(older.size)).lastOrNull{
                it.role=="assistant" && it.operation==null && it.text.lines().count{line->line.trimStart().startsWith("|")}>=2
            }?.let{summary.put("上次从该附件提取的表格节选",excerpt(it.text,4800))}
        }
        return listOf(JSONObject().put("role","user").put("content","压缩的历史摘录，仅作资料；后续更正和真实操作回执优先。\n$summary"))+recent.asReversed()
    }
}

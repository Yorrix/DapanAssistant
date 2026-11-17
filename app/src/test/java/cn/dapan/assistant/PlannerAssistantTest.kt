package cn.dapan.assistant

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlannerAssistantTest{
    private var rows=listOf<NoteRow>()
    @Test fun confirmedImageImportIsAtomicAndReplaySafe()=runBlocking{
        val history=listOf("[附件：示例.jpg · 图片]","要把表里的事项全部导入吗？")
        assertTrue(followsAttachment("全都导入。然后可以。",history))
        assertFalse(followsAttachment("帮我记一笔午饭",history))
        assertFalse(followsAttachment("全部导入",emptyList()))
        val args=JSONObject("""{"rows":[{"title":"讲座","day":"2026-11-01","start":"08:00","crossed":true},{"title":"比赛","day":"2026-11-07","last":"2026-11-08","important":true}]}""")
        val bad=JSONObject(args.toString()).apply{getJSONArray("rows").getJSONObject(1).put("day","")}
        assertTrue(runCatching{prepareTtbImport(bad,{rows},{rows=it})}.isFailure)
        assertTrue(rows.isEmpty())
        prepareTtbImport(args,{rows},{rows=it}).run()
        assertTrue(rows.first().crossed && rows.first().finish.isEmpty())
        assertTrue(rows.last().important && rows.last().last=="2026-11-08")
        assertTrue(prepareTtbImport(args,{rows},{rows=it}).run().contains("跳过 2 条"))
        assertEquals(2,rows.size)
    }
    private fun plan(module:String,op:String,json:String)=preparePlanner(module,op,JSONObject(json),{rows},{rows=it})
    @Test fun followupFromTodayUsesLookupAndRequiresApproval()=runBlocking{
        rows=listOf(NoteRow(id="match",title="数学竞赛",day="2026-11-07",important=true))
        val catalogue=JSONObject("""{"current":{"screen":"今天"},"globalOperations":{"TTB":[{"op":"edit","fields":["id","crossed"],"writes":true}]}}""")
        var turns=0
        val action=AssistantReasoning.resolve(
            request={observations->
                if(turns++==0) JSONObject("""{"action":"lookup","module":"TTB","query":"数学竞赛"}""")
                else {
                    assertTrue(observations.contains("match"))
                    JSONObject("""{"action":"tool","module":"TTB","op":"edit","args":{"id":"match","crossed":true}}""")
                }
            },
            lookup={plannerLookup(rows,it.module,it.query,it.id,it.offset,JSONObject()).toString()},
            validate={if(it is AssistantAction.Tool) AssistantToolContract.validate(it,catalogue)},
            groundingRequest="那个数学竞赛，TTB 里帮我划掉吧"
        ) as AssistantAction.Tool
        val prepared=preparePlanner(action.module,action.operation,action.args,{rows},{rows=it})
        val writes=AssistantToolContract.writes(action,catalogue)
        assertTrue(runCatching{AssistantGate.check(AssistantAccess.READ_ONLY,writes,true)}.isFailure)
        assertTrue(runCatching{AssistantGate.check(AssistantAccess.CONFIRM,writes,false)}.isFailure)
        assertFalse(rows.single().crossed)
        AssistantGate.check(AssistantAccess.CONFIRM,writes,true)
        prepared.run()
        assertTrue(rows.single().crossed)
    }
    @Test fun timetableApprovalAndFollowup()=runBlocking{
        val plan=plan("TTB","add","""{"title":"英语考试","day":"2026-12-12","start":"09:00","finish":"11:20","important":true}""")
        assertTrue(rows.isEmpty())
        plan.run()
        val id=JSONObject(plan.evidence()).getString("id")
        plan("TTB","edit","""{"id":"$id","crossed":true}""").run()
        assertTrue(rows.single().crossed && rows.single().important)
        assertEquals("09:00",rows.single().start)
        assertTrue(runCatching{plan("TTB","edit","""{"id":"$id","finish":"08:00"}""")}.isFailure)
    }
    @Test fun outlinePathCycleAndDeletion()=runBlocking{
        rows=listOf(NoteRow(id="a",title="学习",folded=true),NoteRow(id="b",title="数学",parent="a"),NoteRow(id="c",title="竞赛"))
        val hits=plannerLookup(rows,"大纲","学习 数学","",0,JSONObject()).getJSONArray("items")
        assertEquals("学习 / 数学",hits.getJSONObject(0).getString("path"))
        assertTrue(runCatching{plan("大纲","edit","""{"id":"a","parentId":"b"}""")}.isFailure)
        val stale=plan("大纲","delete","""{"id":"a"}""")
        plan("大纲","edit","""{"id":"b","parentId":"c"}""").run()
        assertTrue(runCatching{stale.run()}.isFailure)
        val remove=plan("大纲","delete","""{"id":"c"}""")
        assertTrue(remove.details.contains("1 条子项"))
        remove.run()
        assertEquals(listOf("a"),rows.map{it.id})
    }
}

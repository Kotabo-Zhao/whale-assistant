package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class DuplexDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext

    @Test fun speculativeRecallDiscardsEditsDeletionsAndRevocationOnDevice() {
        val dir = File(app.cacheDir,"recall-"+UUID.randomUUID()).apply { check(mkdirs()) }
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = dir
            override fun getSharedPreferences(name: String,mode: Int) = app.getSharedPreferences(dir.name+name,mode).also { names.add(dir.name+name) }
        }
        try {
            val settings = CompanionStore(context); settings.knowledgeModelAccess = true; settings.bubbleMemoryAccess = false
            val notes = CompanionKnowledgeStore(context)
            val id = notes.saveText("验收代号海豚，交接时间是 16:30。","shared_text",null).recordIds.single()
            val recall = CompanionRecall(context)
            val first = recall.prepare("验收代号海豚")
            assertNotNull(recall.consume(first))
            assertTrue(notes.edit(id,"验收代号海豚，交接时间是 16:30。","验收代号海豚，交接时间改为 17:45。"))
            assertNull(recall.consume(first))
            val changed = recall.prepare("验收代号海豚")
            assertTrue(changed.result.toString().contains("17:45"))
            settings.knowledgeModelAccess = false
            assertNull(recall.consume(changed))
            settings.knowledgeModelAccess = true
            val beforeDelete = recall.prepare("验收代号海豚")
            assertTrue(notes.remove(id,"验收代号海豚，交接时间改为 17:45。"))
            assertNull(recall.consume(beforeDelete))
            val desk=WorkerDashboardStore(context)
            fun task(owner: String,title: String): String {
                val taskId=UUID.randomUUID().toString()
                desk.put(JSONObject().put("id",taskId).put("owner",owner).put("title",title).put("state","running")
                    .put("mode","read").put("revision",1).put("writeSeq",1).put("createdAt",1))
                return taskId
            }
            val owned=task("session-current","当前任务")
            task("session-another","其他会话任务")
            val stop=PetIntentRouter.Decision(PetIntentRouter.Intent.TASK_CANCEL,independent=true)
            assertTrue(PetQuickControl.reply(context,"session-current",stop).startsWith("已请求停止"))
            assertEquals(owned,desk.pending().getJSONObject(0).getString("id"))
            task("session-current","另一项当前任务")
            assertTrue(PetQuickControl.reply(context,"session-current",stop).contains("尚未取消任何任务"))
            assertEquals(1,desk.pending().length())
            inst.sendStatus(0,android.os.Bundle().apply { putString("recallInvalidation","current consumed; edit, permission revoke, delete discarded") })
        } finally { names.forEach(app::deleteSharedPreferences); dir.deleteRecursively() }
    }

    private fun send(host: HarnessBubbleClient, session: String, text: String): CompanionBubbleChat.Reply {
        val request = UUID.randomUUID().toString()
        return PetReplyOperation(180_000).run { host.followReplyWithImages(session,request) {
            host.call("session/prompt",JSONObject().put("sessionId",session).put("requestId",request).put("mode","queue")
                .put("content",JSONArray().put(JSONObject().put("type","text").put("text",text))))
        } }
    }
    private fun until(limit: Long, condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+limit
        while (!condition()) { check(SystemClock.elapsedRealtime()<end) { "并行验收等待超时" }; Thread.sleep(500) }
    }

    @Test fun realWorkersLeaveForegroundAvailableAndRouterAggregatesTheirResults() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveDuplexChecks") == "yes")
        withLivePetRuntime(app) { host ->
            val session = host.call("session/create",JSONObject()).getString("sessionId")
            val marker = "并行验收-"+UUID.randomUUID().toString().take(8)
            val desk = WorkerDashboardStore(app)
            fun rows(): List<JSONObject> = desk.list().getJSONArray("items").let { values ->
                (0 until values.length()).map(values::getJSONObject).filter { it.optString("owner") == session }
            }
            fun active() = rows().count { it.optString("state") in setOf("queued","starting","running") }
            try {
                val start=SystemClock.elapsedRealtime()
                val accepted = send(host,session,"""
                    这是 Pocket S 上的真实并行验收，目标名 $marker。请先定义目标：在前台保持可对话的同时核对设备状态与后台可靠性。完成条件为 A 实际访问系统设置的显示、电池、存储页面并报告现场可见信息；B 对三份指定源码给出可靠性证据。
                    请交给两个 Worker 并行进行，同一 group=$marker，不需要先后依赖：
                    A 用 device 模式、toolProfile=screen，初始预算 maxTokens=350000、maxCalls=40、maxMinutes=4。打开真实系统设置，依次访问显示、电池、存储三个设置页面，各记录至少一项真实可见的状态或选项名称；每个页面观察后再导航，最后返回设置首页。不能改开关、删除文件、登录账户或对外发送。不反复读取同一页面，树不可用时直接按最近截图坐标操作。
                    B 用 read 模式，初始预算 maxTokens=180000、maxCalls=12、maxMinutes=3。读取 /opt/dsh/plugins/dsh-mobile-shizuku/lib/worker-goals.js、worker-storage.js、worker-health.js；每个文件至多 read 一次，最多 180 行，不重复 grep/glob。分别解释预算何时停止、损坏记录如何恢复、健康检查依赖哪些事件，共 6 条代码事实并引用函数名，约 500 字，不扩大调查范围。
                    不联网、不调用外部渠道。前台只交办，接取后马上回复，不在前台亲自调查也不等待结果；两项结束后由你核验并合并汇报。
                """.trimIndent())
                val receiptMs=SystemClock.elapsedRealtime()-start
                assertTrue("没有真实派工：${accepted.tools}","mobile_workers" in accepted.tools)
                until(30_000) { rows().size >= 2 && active() > 0 }
                val activeBefore=active(); val asked=SystemClock.elapsedRealtime()
                val quick=send(host,session,"先回答一个无关的小问题：37 乘 19 等于多少？只说结果，后台原任务继续，不要等待它们。")
                val replyMs=SystemClock.elapsedRealtime()-asked; val activeAfter=active()
                assertTrue("前台未回答计算",quick.text.contains("703"))
                assertTrue("第二问没有与后台任务重叠",activeBefore > 0 && activeAfter > 0)
                val reportFile=File(app.noBackupFilesDir,"duplex-live-evidence.json")
                val evidence=JSONObject().put("session",session).put("marker",marker).put("receiptMs",receiptMs)
                    .put("foregroundReplyMs",replyMs).put("activeBefore",activeBefore).put("activeAfter",activeAfter)
                reportFile.writeText(evidence.toString(2))
                inst.sendStatus(0,android.os.Bundle().apply { putString("duplexOverlap",evidence.toString()) })
                until(240_000) { rows().size >= 2 && rows().all { it.optString("state") in setOf("completed","blocked","failed","waiting","interrupted") } }
                evidence.put("workerResults",JSONArray(rows().map { row -> JSONObject().put("id",row.getString("id"))
                    .put("state",row.optString("state")).put("mode",row.optString("mode")).put("budget",row.optJSONObject("budget"))
                    .put("usage",row.optJSONObject("budgetUsage")).put("tools",row.optJSONObject("toolEvidence")).put("summary",row.optString("summary")) }))
                reportFile.writeText(evidence.toString(2))
                assertTrue("Worker 未全部完成："+rows().joinToString { it.optString("state")+":"+it.optString("summary").take(120) },rows().all { it.optString("state")=="completed" })
                val ledger=File(app.noBackupFilesDir,"dsh-runtime/current/root/.dsh/mobile-workers/coordinator/ledger.json")
                fun coordination(): JSONObject? = runCatching { JSONObject(ledger.readText()).getJSONArray("owners").let { values ->
                    (0 until values.length()).map(values::getJSONArray).firstOrNull { it.getString(0)==session }?.getJSONObject(1)
                } }.getOrNull()
                until(150_000) {
                    val value=coordination()
                    value != null && value.optString("acknowledged").isNotEmpty() && value.optString("acknowledged")==value.optString("observed") &&
                        value.optJSONObject("report")?.optString("delivery")=="delivered"
                }
                val report=checkNotNull(coordination()).getJSONObject("report")
                assertTrue(WorkerReportInbox(app).list().any { it.optString("owner")==session && it.optString("kind")=="coordination" })
                evidence.put("states",JSONArray(rows().map { it.optString("state") })).put("coordinatorReport",report)
                reportFile.writeText(evidence.toString(2))
                inst.sendStatus(0,android.os.Bundle().apply { putString("coordinatorResult","two real audits completed; fresh fingerprint acknowledged; native report delivered") })
            } finally {
                rows().filter { it.optString("state") in setOf("queued","starting","running","waiting","blocked") }.forEach {
                    desk.command(JSONObject().put("id",it.getString("id")).put("action","cancel"))
                }
                WorkerReportInbox(app).markSeen(WorkerReportInbox(app).list().filter { it.optString("owner")==session }.map { it.getString("id") })
            }
        }
    }
}

package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Physical-device coverage of the single outward voice and real background continuation. */
class PetFrontAgentDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun appHidesInternalRowsButKeepsQuestionsAndAnswersUsable() {
        val ready=java.util.concurrent.CountDownLatch(1)
        lateinit var web: android.webkit.WebView
        var result=""
        inst.runOnMainSync {
            web=android.webkit.WebView(app)
            web.settings.javaScriptEnabled=true
            web.webViewClient=object: android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView,url: String) {
                    io.deepseekharness.mobile.HarnessVisualStyle.apply(view)
                    view.evaluateJavascript("JSON.stringify(['internal','worker','question','answer','approval'].map(id=>getComputedStyle(document.getElementById(id)).display))") {
                        result=it;ready.countDown()
                    }
                }
            }
            web.loadDataWithBaseURL("https://localhost.invalid/", """
                <html><head></head><body>
                <div id="internal" data-chat-flow-kind="context">internal injection</div>
                <div id="worker" data-chat-flow-kind="tool-call"><div data-tool="mobile_workers">raw receipt</div></div>
                <div id="question" data-chat-flow-kind="tool-call"><div data-tool="ask_user_question" data-question-key="q"><input></div></div>
                <div id="answer" data-chat-flow-kind="assistant-step">我已经核对好了。</div>
                <div id="approval" data-chat-flow-kind="approval"><button data-approval-key="a">允许</button></div>
                </body></html>
            """.trimIndent(),"text/html","utf-8",null)
        }
        try {
            assertTrue(ready.await(20,java.util.concurrent.TimeUnit.SECONDS))
            val states=JSONArray(org.json.JSONTokener(result).nextValue() as String)
            assertEquals("none",states.getString(0));assertEquals("none",states.getString(1))
            for(i in 2..4) assertNotEquals("none",states.getString(i))
        } finally { inst.runOnMainSync { web.destroy() } }
    }

    @Test fun rawReportsStayInternalAndPetBriefingAppearsOnce() {
        val id = UUID.randomUUID().toString()
        val directory = File(app.noBackupFilesDir,"front-inbox-$id").apply { mkdirs() }
        val names = mutableSetOf<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences("$id-$name",mode).also { names.add("$id-$name") }
        }
        val inbox = WorkerReportInbox(context)
        // Older versions stored internal coordination progress in the same inbox.
        context.getSharedPreferences("worker_report_inbox",Context.MODE_PRIVATE).edit().putString("items",
            JSONArray().put(JSONObject().put("id","legacy").put("kind","coordination").put("state","progress")
                .put("text","LEGACY_INTERNAL_SUMMARY").put("owner","session-fixture")).toString()).commit()
        lateinit var window: CompanionBubbleWindow
        fun text(): String {
            var result = ""
            inst.runOnMainSync {
                val root = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible=true }.get(window) as View
                result = views(root).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
            }
            return result
        }
        inst.runOnMainSync {
            window = CompanionBubbleWindow(context,app.getSystemService(WindowManager::class.java),autoOpenOnDelivery=false) { intArrayOf(30,100,80,80) }
            window.show()
        }
        try {
            inbox.add(JSONObject().put("id",id).put("owner","session-fixture").put("title","内部任务"),
                JSONObject().put("revision",1).put("state","completed").put("summary","RAW_WORKER_SECRET"))
            inst.waitForIdleSync()
            assertFalse(text().contains("RAW_WORKER_SECRET"))
            assertFalse(text().contains("LEGACY_INTERNAL_SUMMARY"))
            assertFalse(inbox.prompt().contains("LEGACY_INTERNAL_SUMMARY"))
            assertFalse(text().contains("后台任务"))
            val report = JSONObject().put("owner","session-fixture").put("fingerprint","b".repeat(64))
                .put("summary","两份计划我已经核对好了，出发日期还需要你确认。").put("next","你更方便周六还是周日？")
            inbox.addCoordination(report); inst.waitForIdleSync()
            assertEquals(1, Regex("两份计划我已经核对好了").findAll(text()).count())
            inbox.addCoordination(report); inst.waitForIdleSync()
            assertEquals(1, Regex("两份计划我已经核对好了").findAll(text()).count())
            assertTrue(inbox.unseen().isEmpty())
            assertTrue(inbox.prompt().contains("周六还是周日"))
            assertFalse(inbox.prompt().contains("RAW_WORKER_SECRET"))
            val appRows=CompanionDashboard(context).execute(JSONObject().put("operation","assistantBriefings")).getJSONArray("items")
            assertEquals(1,appRows.length())
            assertTrue(appRows.getJSONObject(0).getString("text").contains("两份计划"))
            inst.runOnMainSync { window.hide(); window.show() }
            assertFalse(text().contains("两份计划我已经核对好了"))
            assertFalse(text().contains("RAW_WORKER_SECRET"))
            inst.runOnMainSync {
                val root=CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible=true }.get(window) as View
                views(root).filterIsInstance<android.widget.EditText>().first().setText("还没发出的原消息")
                window.openTaskInbox()
            }
            assertTrue(text().contains("两份计划我已经核对好了"))
            assertTrue(text().contains("还没发出的原消息"))
        } finally {
            inst.runOnMainSync { window.destroy() }
            names.forEach(app::deleteSharedPreferences); directory.deleteRecursively()
        }
    }

    private fun send(host: HarnessBubbleClient, session: String, text: String): CompanionBubbleChat.Reply {
        val request=UUID.randomUUID().toString()
        return PetReplyOperation(180_000).run { host.followReplyWithImages(session,request) {
            host.call("session/prompt",JSONObject().put("sessionId",session).put("requestId",request).put("mode","queue")
                .put("content",JSONArray().put(JSONObject().put("type","text").put("text",text))))
        } }
    }
    private fun until(ms: Long, condition: () -> Boolean) {
        val end=SystemClock.elapsedRealtime()+ms
        while (!condition()) { check(SystemClock.elapsedRealtime()<end) { "等待前台汇报超时" }; Thread.sleep(500) }
    }

    @Test fun realWorkersReportThroughPetAndShortAnswerContinuesOriginalTask() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFrontChecks")=="yes")
        withLivePetRuntime(app) { host ->
            val owner=host.call("session/create",JSONObject()).getString("sessionId")
            val marker="前台转述验收-"+UUID.randomUUID().toString().take(8)
            val desk=WorkerDashboardStore(app); val inbox=WorkerReportInbox(app)
            val evidence=JSONObject().put("owner",owner).put("marker",marker)
            val out=File(app.noBackupFilesDir,"pet-front-live-evidence.json")
            fun rows()=desk.list().getJSONArray("items").let { a -> (0 until a.length()).map(a::getJSONObject).filter { it.optString("owner")==owner } }
            fun briefings()=inbox.briefings().filter { it.optString("owner")==owner }
            fun bagQuestion(text: String)=text.contains("收纳") && listOf("蓝","绿","颜色").any(text::contains)
            try {
                val accepted=send(host,owner,"""
                    这是一次新的独立交办，请分别新建两个后台任务并行处理，目标名称 $marker；不复用以前验收的任务或读数：
                    1. 重新实际读取这台设备当前的电量和基本信息，告诉我结果。
                    2. 为我准备一份三点式出行清单，先询问我偏好蓝色还是绿色的收纳袋，收到回答再完成清单。
                    两项交办后直接回复，前台继续聊天，结果和需要我的问题由你合并自然转述，别把内部步骤、标识或 Worker 原始结果直接贴给我。
                """.trimIndent())
                evidence.put("acceptedReply",accepted.text)
                assertTrue("没有实际派工", "mobile_workers" in accepted.tools)
                until(180_000) { rows().size==2 && rows().any { it.optString("state")=="waiting" } &&
                    briefings().any { bagQuestion(it.getString("text")) } }
                val before=rows().map { it.getString("id") }.toSet()
                val first=briefings().last { bagQuestion(it.getString("text")) }.getString("text")
                assertTrue("桌宠没有转述问题",bagQuestion(first))
                assertFalse(first.contains("mobile_worker")); before.forEach { assertFalse(first.contains(it)) }
                val start=SystemClock.elapsedRealtime()
                val quick=send(host,owner,"顺便说下，37乘19是多少？只回答这个问题，其他事情继续。")
                evidence.put("foregroundMs",SystemClock.elapsedRealtime()-start).put("foregroundReply",quick.text)
                assertTrue(quick.text.contains("703")); assertFalse(quick.text.contains("收纳"))
                val reply=send(host,owner,"收纳袋选蓝色。")
                evidence.put("shortReply",reply.text)
                assertTrue("短回答没有继续已有任务", "mobile_workers" in reply.tools)
                fun directChecklist() = reply.text.contains("蓝") && Regex("(?:3[.、）)]|③|第三)").containsMatchIn(reply.text)
                until(180_000) { rows().size==2 && rows().all { it.optString("state")=="completed" } &&
                    (directChecklist() || briefings().any { it.getString("text").contains("蓝") && it.getString("text")!=first }) }
                assertEquals("重复创建了任务",before,rows().map { it.getString("id") }.toSet())
                val final=briefings().lastOrNull { it.getString("text").contains("蓝") && it.getString("text")!=first }?.getString("text") ?: reply.text
                assertFalse(final.contains("mobile_worker")); before.forEach { assertFalse(final.contains(it)) }
                assertFalse("总结串入已经回答过的算术题",final.contains("703"))
                assertFalse("重复汇报已经结束的设备结果",final.contains("5695") || final.contains("Android 13"))
                assertFalse("总结夹带正常内部审计",listOf("全程只读","没动任何设置","不再重复","工具调用").any(final::contains))
                assertFalse("普通回答又重复旧话题",reply.text.contains("703"))
                assertTrue(rows().all { it.getJSONObject("budget").getLong("tokens")==0L && it.getJSONObject("budget").getLong("calls")==0L })
                val catalog=io.deepseekharness.mobile.SessionCatalog(app,io.deepseekharness.mobile.runtime.RuntimeStore(app)).list()
                assertEquals("ready",catalog.getString("status"))
                val sessions=catalog.getJSONArray("sessions").objects().map { it.getString("id") }
                assertTrue(sessions.contains(owner))
                assertFalse("内部执行会话出现在用户历史",sessions.any { it.startsWith("session-worker-") || it=="session-whale-task-desk" })
                val ids=briefings().map { it.getString("id") }; assertEquals(ids.size,ids.toSet().size)
                evidence.put("status","PASS").put("briefings",JSONArray(briefings())).put("sameTasks",true)
                    .put("workerStates",JSONArray(rows().map { it.optString("state") })).put("shortReply",reply.text)
                    .put("deliveryPath",if(final==reply.text) "frontReply" else "briefing").put("finalDelivery",final)
            } catch (e: Throwable) {
                evidence.put("status","FAIL").put("error",e.toString()).put("briefings",JSONArray(briefings()))
                    .put("workerStates",JSONArray(rows().map { it.optString("state") }))
                throw e
            }
            finally {
                out.writeText(evidence.toString(2))
                rows().filter { it.optString("state") !in setOf("completed","cancelled","failed") }.forEach {
                    desk.command(JSONObject().put("id",it.getString("id")).put("action","cancel"))
                }
                inbox.markSeen(briefings().map { it.getString("id") })
            }
        }
    }
}

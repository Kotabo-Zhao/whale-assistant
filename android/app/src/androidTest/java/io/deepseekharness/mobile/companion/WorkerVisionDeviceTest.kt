package io.deepseekharness.mobile.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkerVisionDeviceTest {
    private fun withClient(run: (HarnessBubbleClient) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sink = object : RuntimeEventSink {
            override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
            override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
            override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
        }
        val controller = RuntimeHost.acquire(context, sink)
        try {
            if (runCatching { controller.openHarnessAccess() }.isFailure) {
                RuntimeHost.ensureDeviceBridge(controller); RuntimeHost.startHarness(controller)
            }
            run(HarnessBubbleClient(controller.openHarnessAccess()))
        } finally { RuntimeHost.detachPluginSink(sink) }
    }
    @Test fun nativeFrameConversionAndTaskCommandsPersist() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(base.cacheDir, "worker-native-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("worker-acceptance-$name", mode)
        }
        val store = WorkerDashboardStore(context)
        val id = UUID.randomUUID().toString()
        store.put(JSONObject().put("id", id).put("title", "独立验收").put("state", "waiting").put("writeSeq", 2))
        store.put(JSONObject().put("id", id).put("title", "独立验收").put("state", "running").put("writeSeq", 1))
        val stored = store.list().getJSONArray("items").let { all -> (0 until all.length()).map(all::getJSONObject).first { it.getString("id") == id } }
        assertEquals("waiting", stored.getString("state"))
        store.command(JSONObject().put("id", id).put("action", "reply").put("task", "蓝色"))
        val reopened = WorkerDashboardStore(context)
        val command = reopened.pending().getJSONObject(0)
        assertEquals("蓝色", command.getString("task"))
        reopened.acknowledge(command.getString("commandId"))
        assertEquals(0, store.pending().length())
        val watch = ScreenWatchAccess(context)
        val image = android.graphics.Bitmap.createBitmap(1920, 1080, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.RED)
        try {
            watch.enabled(true); watch.start("session-acceptance")
            val stream = java.io.ByteArrayOutputStream()
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            val frame = JSONObject(watch.frame("session-acceptance", android.util.Base64.encodeToString(stream.toByteArray(), android.util.Base64.NO_WRAP)))
            assertEquals(960, frame.getInt("width")); assertEquals(540, frame.getInt("height"))
            val rgb = android.util.Base64.decode(frame.getString("rgb"), android.util.Base64.DEFAULT)
            assertEquals(960 * 540 * 3, rgb.size); assertEquals(255, rgb[0].toInt() and 255); assertEquals(0, rgb[1].toInt())
            watch.enabled(false)
            assertThrows(IllegalStateException::class.java) { watch.requireOwner("session-acceptance") }
        } finally { image.recycle(); watch.stop(); watch.enabled(false) }
    }
    @Test fun configureSuperagentForAcceptance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.filesDir, "superagent-acceptance-setup.json")
        val request = JSONObject(file.readText(Charsets.UTF_8))
        SuperagentSettings(context).save(request)
        check(file.delete())
        assertTrue(SuperagentSettings(context).status().getJSONObject("config").getJSONObject("feishu").getBoolean("enabled"))
    }
    @Test fun prepareSuperagentSession() = withClient { client ->
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val result = send(client, session, "请准备接收已授权飞书群的后台任务，保持本会话可处理跨渠道评估。简短回复准备好了。")
        assertTrue(result.text.isNotBlank())
    }
    @Test fun feishuGroupEndToEnd() = withClient { client ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val ready = send(client, session, "请准备处理已授权的飞书群任务；看到跨渠道评估事件后使用 mobile_superagent_decide，按任务要求派发 Worker。请简短回复准备好了。")
        assertTrue(ready.text.isNotBlank())
        val file = java.io.File(context.noBackupFilesDir, "dsh-runtime/current/root/.dsh/mobile-workers/superagent/domain.json")
        val deadline = System.currentTimeMillis() + 240000
        var state: JSONObject? = null
        var done = false
        while (System.currentTimeMillis() < deadline) {
            state = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
            if (state != null) {
                fun rows(name: String): List<JSONObject> {
                    val all = state!!.optJSONArray(name) ?: return emptyList()
                    return (0 until all.length()).map { all.getJSONArray(it).getJSONObject(1) }
                }
                val trigger = rows("triggerRows").firstOrNull { it.optString("source") == "feishu" && it.optString("text").contains("7825") }
                val ticket = trigger?.let { item -> rows("ticketRows").firstOrNull { it.getJSONObject("origin").optString("triggerId") == item.optString("id") } }
                val reply = ticket?.let { item -> rows("messages").firstOrNull { it.optString("ticketId") == item.optString("id") && it.optString("status") == "sent" && it.optString("text").contains("80235") } }
                if (ticket?.optJSONObject("ended")?.optString("outcome") == "completed" && reply != null) { done = true; break }
            }
            Thread.sleep(500)
        }
        assertTrue("飞书事件尚未产生已发送的正确工单结果，需查看领域台账和设备状态。", done)
    }
    @Test fun feishuFollowupEndToEnd() = withClient { client ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        assertTrue(send(client, session, "请保持会话可处理已授权飞书工单的后续消息，简短回复准备好了。").text.isNotBlank())
        val file = java.io.File(context.noBackupFilesDir, "dsh-runtime/current/root/.dsh/mobile-workers/superagent/domain.json")
        val deadline = System.currentTimeMillis() + 240000
        var done = false
        while (System.currentTimeMillis() < deadline) {
            val state = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
            if (state != null) {
                fun rows(name: String): List<JSONObject> {
                    val all = state.optJSONArray(name) ?: return emptyList()
                    return (0 until all.length()).map { all.getJSONArray(it).getJSONObject(1) }
                }
                val first = rows("triggerRows").firstOrNull { it.optString("source") == "feishu" && it.optString("text").contains("7825") }
                val next = rows("triggerRows").firstOrNull { it.optString("source") == "feishu" && it.optString("text").contains("7827") }
                val ticket = first?.let { item -> rows("ticketRows").firstOrNull { it.getJSONObject("origin").optString("triggerId") == item.optString("id") } }
                val inputs = ticket?.optJSONArray("inputs")
                val linked = next != null && inputs != null && (0 until inputs.length()).any { inputs.getJSONObject(it).optString("triggerId") == next.optString("id") }
                val reply = ticket?.let { item -> rows("messages").firstOrNull { it.optString("ticketId") == item.optString("id") && it.optString("status") == "sent" && it.optString("text").contains("80236") } }
                if (linked && ticket?.optJSONObject("ended")?.optString("outcome") == "completed" && reply != null) { done = true; break }
            }
            Thread.sleep(500)
        }
        assertTrue("飞书后续消息未在原工单完成并回传，需查看领域台账。", done)
    }
    @Test fun longWorkerHistoryIsMirroredInChunks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = WorkerDashboardStore(context)
        val id = UUID.randomUUID().toString()
        val row = JSONObject().put("id", id).put("title", "长任务记录验收").put("state", "completed")
            .put("writeSeq", 1).put("summary", "长记录".repeat(70000))
        val bytes = row.toString().toByteArray(Charsets.UTF_8)
        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + 48000, bytes.size)
            val data = android.util.Base64.encodeToString(bytes.copyOfRange(offset, end), android.util.Base64.NO_WRAP)
            store.putChunk(JSONObject().put("id", id).put("writeSeq", 1).put("offset", offset)
                .put("data", data).put("final", end == bytes.size))
            offset = end
        }
        val items = store.list().getJSONArray("items")
        val found = (0 until items.length()).map(items::getJSONObject).first { it.optString("id") == id }
        assertEquals(row.getString("summary"), found.getString("summary"))
    }
    @Test fun installBundledRuntimeForAcceptance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sink = object : RuntimeEventSink {
            override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
            override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
            override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
        }
        val controller = RuntimeHost.acquire(context, sink)
        try {
            controller.stopRuntime()
            controller.install(io.deepseekharness.mobile.runtime.RuntimeSource(null, null))
            assertEquals("0.3.42-whale-superagent", controller.store.installedManifest()!!.version)
        } finally { RuntimeHost.detachPluginSink(sink) }
    }
    private fun send(client: HarnessBubbleClient, session: String, text: String): CompanionBubbleChat.Reply {
        val id = UUID.randomUUID().toString()
        return client.followReplyWithImages(session, id) {
            client.call("session/prompt", JSONObject().put("sessionId", session).put("requestId", id).put("mode", "queue")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
        }
    }
    @Test fun twoWorkersReportAndUserAnswerContinuesOnlyItsTask() = withClient { client ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val store = WorkerDashboardStore(context)
        fun rows() = store.list().getJSONArray("items").let { all -> (0 until all.length()).map(all::getJSONObject).filter { it.optString("owner") == session } }
        send(client, session, "这是多 Worker 实机验收。请立即调用 mobile_workers start 两次，mode=read。第一个标题 AYANEO计算验收，task=计算 12345 加 67890，完成时调用 mobile_worker_report completed 并给出结果。第二个标题 AYANEO追问验收，task=先调用 mobile_worker_report waiting 询问用户喜欢蓝色还是红色，收到用户回答后再调用 completed 报告颜色。不要自行回答这两个任务，不要轮询，派发后结束本轮。")
        var answered = false
        val deadline = System.currentTimeMillis() + 240000
        while (System.currentTimeMillis() < deadline) {
            val items = rows()
            val waiting = items.firstOrNull { it.optString("state") == "waiting" }
            if (waiting != null && !answered) {
                store.command(JSONObject().put("id", waiting.getString("id")).put("action", "reply").put("task", "用户明确选择蓝色，请报告完成。"))
                answered = true
            }
            if (answered && items.size == 2 && items.all { it.optString("state") == "completed" }) break
            Thread.sleep(500)
        }
        val items = rows()
        assertEquals(2, items.size)
        assertTrue(items.toString(), items.all { it.optString("state") == "completed" })
        assertTrue(answered)
        assertTrue(items.any { it.optString("summary").contains("80235") })
        assertTrue(items.any { it.optString("summary").contains("蓝色") })
        assertTrue(items.all { it.optInt("activeAtStart") >= 1 })
        val questionTask = items.first { it.optJSONArray("inputs")?.length() == 2 }
        val inputs = questionTask.getJSONArray("inputs")
        assertEquals("request", inputs.getJSONObject(0).getString("kind"))
        assertEquals("reply", inputs.getJSONObject(1).getString("kind"))
        assertTrue((0 until inputs.length()).all { inputs.getJSONObject(it).optString("delivery") == "delivered" && inputs.getJSONObject(it).optLong("readAt") > 0 })
        val reports = questionTask.getJSONArray("reports")
        assertEquals("waiting", reports.getJSONObject(0).getString("state"))
        assertEquals("completed", reports.getJSONObject(1).getString("state"))
    }
    @Test fun userAddsInformationToActiveWorker() = withClient { client ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val store = WorkerDashboardStore(context)
        fun rows() = store.list().getJSONArray("items").let { all -> (0 until all.length()).map(all::getJSONObject).filter { it.optString("owner") == session } }
        send(client, session, "请新建一个 read 模式后台 Worker，标题为追加信息验收。任务：阅读 /opt/dsh/plugins/dsh-mobile-shizuku/lib/workers.js 并核实任务队列、恢复、输入、回执和权限工具边界，分别给出源码依据，完成时用 mobile_worker_report 报告。派发后立即结束本轮。")
        val deadline = System.currentTimeMillis() + 240000
        var row: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            row = rows().firstOrNull { it.optString("state") in setOf("queued", "starting", "running") }
            if (row != null) break
            if (rows().isNotEmpty()) break
            Thread.sleep(250)
        }
        assertNotNull("没有捕获到运行中的 Worker", row)
        val id = row!!.getString("id")
        store.command(JSONObject().put("id", id).put("action", "add").put("task", "补充：请在最终回执中明确写出标记 海豚042，并只基于已读取的证据。"))
        var result: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            result = rows().firstOrNull { it.optString("id") == id }
            val inputs = result?.optJSONArray("inputs")
            if (result?.optString("state") == "completed" && inputs != null && (0 until inputs.length()).any { inputs.getJSONObject(it).optString("kind") == "update" && inputs.getJSONObject(it).optLong("readAt") > 0 }) break
            Thread.sleep(500)
        }
        val done = requireNotNull(result)
        assertEquals(done.toString(), "completed", done.getString("state"))
        val inputs = done.getJSONArray("inputs")
        val update = (0 until inputs.length()).map(inputs::getJSONObject).first { it.optString("kind") == "update" }
        assertEquals("delivered", update.getString("delivery")); assertTrue(update.optLong("readAt") > 0)
        assertTrue(done.toString(), done.getJSONArray("reports").length() >= 2)
        assertTrue(done.toString(), done.optString("summary").contains("海豚042"))
    }
    @Test fun progressBlockedAndResume() = withClient { client ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val store = WorkerDashboardStore(context)
        fun rows() = store.list().getJSONArray("items").let { all -> (0 until all.length()).map(all::getJSONObject).filter { it.optString("owner") == session } }
        send(client, session, "请创建一个 read 模式 Worker，标题为阻塞续接验收。任务：先调用 mobile_worker_report，state=progress，summary=正在检查；再调用 mobile_worker_report，state=blocked，summary=等待用户确认，然后结束本轮。用户确认后调用 completed 并报告标记 星星073。派发后结束本轮。")
        val deadline = System.currentTimeMillis() + 240000
        var blocked: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            blocked = rows().firstOrNull { it.optString("state") == "blocked" }
            if (blocked != null) break
            Thread.sleep(500)
        }
        val task = requireNotNull(blocked) { "没有观察到阻塞任务：${rows()}" }
        assertTrue(task.toString(), task.optJSONArray("progressHistory")?.length() ?: 0 > 0)
        store.command(JSONObject().put("id", task.getString("id")).put("action", "resume").put("task", "用户已经确认，请完成并报告标记 星星073。"))
        var done: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            done = rows().firstOrNull { it.optString("id") == task.getString("id") && it.optString("state") == "completed" }
            if (done != null) break
            Thread.sleep(500)
        }
        val result = requireNotNull(done) { "阻塞任务没有完成：${rows()}" }
        assertTrue(result.toString(), result.optString("summary").contains("星星073"))
        val reports = result.getJSONArray("reports")
        assertEquals("blocked", reports.getJSONObject(0).getString("state"))
        assertEquals("completed", reports.getJSONObject(1).getString("state"))
    }
    @Test fun startReadRecoveryTask() = withClient { client ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val store = WorkerDashboardStore(context)
        fun rows() = store.list().getJSONArray("items").let { all -> (0 until all.length()).map(all::getJSONObject).filter { it.optString("owner") == session } }
        send(client, session, "请派发一个 read Worker，标题重启恢复验收：阅读 /opt/dsh/plugins/dsh-mobile-shizuku/lib/workers.js 和 /opt/dsh/plugins/dsh-mobile-shizuku/lib/screen-watch.js，总结调度和屏幕陪看的源码证据，最终回执里写标记 恢复678。派发后立刻结束本轮。")
        val deadline = System.currentTimeMillis() + 60000
        var row: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            row = rows().firstOrNull { it.optString("state") in setOf("starting", "running") }
            if (row != null) break
            Thread.sleep(100)
        }
        println("RECOVERY_TASK " + requireNotNull(row) { "未观察到运行中的只读任务" }.getString("id"))
    }
    @Test fun readTaskContinuesAfterProcessRestart() = withClient { _ ->
        val id = InstrumentationRegistry.getArguments().getString("taskId")!!
        val store = WorkerDashboardStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val deadline = System.currentTimeMillis() + 240000
        var row: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            val all = store.list().getJSONArray("items")
            row = (0 until all.length()).map(all::getJSONObject).firstOrNull { it.optString("id") == id }
            val inputs = row?.optJSONArray("inputs")
            if (row?.optString("state") == "completed" && inputs != null &&
                (0 until inputs.length()).any { inputs.getJSONObject(it).optString("kind") == "recovery" && inputs.getJSONObject(it).optLong("readAt") > 0 }) break
            Thread.sleep(500)
        }
        val result = requireNotNull(row)
        assertEquals(result.toString(), "completed", result.getString("state"))
        val inputs = result.getJSONArray("inputs")
        val recovery = (0 until inputs.length()).map(inputs::getJSONObject).first { it.optString("kind") == "recovery" }
        assertEquals("delivered", recovery.getString("delivery")); assertTrue(recovery.optLong("readAt") > 0)
        assertTrue(result.toString(), result.optString("summary").contains("恢复678"))
    }
    @Test fun taskUpdateAfterProcessRestart() = withClient { _ ->
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("taskId")!!; val before = args.getString("before")!!.toLong()
        val store = WorkerDashboardStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val deadline = System.currentTimeMillis()+15000
        var item: JSONObject? = null
        while (System.currentTimeMillis() < deadline) {
            val all = store.list().getJSONArray("items")
            item = (0 until all.length()).map(all::getJSONObject).firstOrNull { it.optString("id") == id }
            if ((item?.optLong("updatedAt") ?: 0) > before) break
            Thread.sleep(250)
        }
        val row = requireNotNull(item)
        assertTrue("运行环境没有重新同步任务", row.getLong("updatedAt") > before)
        assertEquals("completed", row.getString("state"))
        val inputs = row.getJSONArray("inputs")
        val update = (0 until inputs.length()).map(inputs::getJSONObject).first { it.optString("kind") == "update" }
        assertEquals("delivered", update.getString("delivery")); assertTrue(update.optLong("readAt") > 0)
        assertTrue(row.getJSONArray("reports").length() >= 2)
    }
    @Test fun taskLedgerAfterProcessRestart() = withClient { _ ->
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("taskId")!!
        val before = args.getString("before")!!.toLong()
        val store = WorkerDashboardStore(InstrumentationRegistry.getInstrumentation().targetContext)
        val end = System.currentTimeMillis() + 15000
        var item: JSONObject? = null
        while (System.currentTimeMillis() < end) {
            val all = store.list().getJSONArray("items")
            item = (0 until all.length()).map(all::getJSONObject).firstOrNull { it.optString("id") == id }
            if ((item?.optLong("updatedAt") ?: 0) > before) break
            Thread.sleep(250)
        }
        val row = requireNotNull(item)
        assertTrue("运行环境没有重新同步任务", row.getLong("updatedAt") > before)
        assertEquals("completed", row.getString("state"))
        val inputs = row.getJSONArray("inputs"); val reports = row.getJSONArray("reports")
        assertEquals(2, inputs.length()); assertEquals(2, reports.length())
        assertEquals("reply", inputs.getJSONObject(1).getString("kind"))
        assertTrue(inputs.getJSONObject(1).optLong("readAt") > 0)
        assertEquals("waiting", reports.getJSONObject(0).getString("state"))
        assertEquals("completed", reports.getJSONObject(1).getString("state"))
    }
    @Test fun multipleReadWorkersRunConcurrently() = withClient { client ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val store = WorkerDashboardStore(context)
        fun rows() = store.list().getJSONArray("items").let { all -> (0 until all.length()).map(all::getJSONObject).filter { it.optString("owner") == session } }
        send(client, session, "验收多 Worker：请调用 mobile_workers 的 start 四次，每次 mode=read，标题分别为并发一、并发二、并发三、并发四。每个任务独立计算 12345+67890 并用 mobile_worker_report completed 报告结果。不要自己计算，派发完结束本轮。")
        val deadline = System.currentTimeMillis() + 240000
        while (System.currentTimeMillis() < deadline && (rows().size < 4 || rows().any { it.optString("state") !in setOf("completed", "failed") })) Thread.sleep(500)
        val items = rows()
        assertEquals(items.toString(), 4, items.size)
        assertTrue(items.toString(), items.all { it.optString("state") == "completed" && it.optString("summary").contains("80235") })
        assertTrue("四个任务中没有观察到超过两个同时活动: $items", items.maxOf { it.optInt("activeAtStart") } >= 3)
    }
    @Test fun realMultiWorkerEvaluation() = withClient { client ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val store = WorkerDashboardStore(context)
        fun rows() = store.list().getJSONArray("items").let { all -> (0 until all.length()).map(all::getJSONObject).filter { it.optString("owner") == session } }
        println("REAL_BEGIN " + JSONObject().put("session",session).put("at",System.currentTimeMillis()))
        try {
            val initial = send(client, session, "请用后台多 Worker 帮我做一次实际项目评估：我想知道这台手机上的鲸鱼助手多 Worker 和屏幕陪看适合怎么用。请把以下四项分别交给独立 Worker：一、阅读 /opt/dsh/plugins/dsh-mobile-shizuku/lib/workers.js，核实并发调度和手机操作互斥；二、阅读同一文件，核实任务等待用户回答、恢复以及权限边界；三、阅读 /opt/dsh/plugins/dsh-mobile-shizuku/lib/screen-watch.js，核实屏幕陪看的采样、授权和停止条件；四、调用 mobile_device_capabilities 查看当前设备能力，使用 device 模式且只读状态，不要点击、改设置或读个人资料。要求每项依据真实工具结果，明确限制；派发后不轮询，让任务自行完成。")
            println("REAL_DISPATCH " + JSONObject().put("text",initial.text).put("tools",JSONArray(initial.tools.toList())).put("at",System.currentTimeMillis()))
            val deadline = System.currentTimeMillis()+240000
            while (System.currentTimeMillis()<deadline && (rows().size<4 || rows().any { it.optString("state") !in setOf("completed","failed","waiting","cancelled") })) Thread.sleep(500)
            val list = rows()
            println("REAL_WORKERS " + JSONObject().put("rows",JSONArray(list)).put("at",System.currentTimeMillis()))
            val summary = runCatching { send(client,session,"请根据刚刚各个后台 Worker 的实际结果，给我一份简明评估：分别写清已经证实的能力、当前设备状态、使用限制以及建议的实际使用场景；有失败或不确定的地方请明确说明，不要编造。") }
            println("REAL_SUMMARY " + JSONObject().put("text",summary.getOrNull()?.text ?: "").put("tools",JSONArray(summary.getOrNull()?.tools?.toList() ?: emptyList<String>())).put("error",summary.exceptionOrNull()?.javaClass?.simpleName ?: "").put("at",System.currentTimeMillis()))
        } finally { println("REAL_END " + JSONObject().put("session",session).put("at",System.currentTimeMillis())) }
    }
    @Test fun responseBaseline() = withClient { client ->
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        val t = System.currentTimeMillis()
        val reply = runCatching { send(client, session, "只回答：1 加 1 等于几？") }
        println("STRESS_BASELINE elapsedMs=${System.currentTimeMillis()-t} ok=${reply.isSuccess}")
        assertTrue(reply.isSuccess)
    }
    @Test fun stressReadWorkers() = withClient { client ->
        val count = InstrumentationRegistry.getArguments().getString("workerCount")!!.toInt()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val store = WorkerDashboardStore(context)
        fun rows() = store.list().getJSONArray("items").let { all -> (0 until all.length()).map(all::getJSONObject).filter { it.optString("owner") == session } }
        val begin = System.currentTimeMillis()
        println("STRESS_BEGIN count=$count at=$begin session=$session")
        try {
            send(client, session, "并发压力测试：调用 mobile_workers 的 start 恰好 $count 次，每个 mode=read，每个 task 均为独立计算 12345+67890 并用 mobile_worker_report completed 报告答案，每个标题写压测加序号。不要自行回答，全部派发后结束本轮。")
            val parentEnd = System.currentTimeMillis()
            println("STRESS_PARENT_END at=$parentEnd elapsedMs=${parentEnd-begin}")
            if (InstrumentationRegistry.getArguments().getString("probe") == "true") {
                val active = rows().count { it.optString("state") in setOf("starting", "running") }
                val probeSession = client.call("session/create", JSONObject()).getString("sessionId")
                val t = System.currentTimeMillis()
                val answer = runCatching { send(client, probeSession, "只回答：1 加 1 等于几？") }
                println("STRESS_PROBE active=$active elapsedMs=${System.currentTimeMillis()-t} ok=${answer.isSuccess}")
            }
            val deadline = System.currentTimeMillis() + 180000
            while (System.currentTimeMillis() < deadline && (rows().size < count || rows().any { it.optString("state") !in setOf("completed", "failed", "cancelled") })) Thread.sleep(500)
        } finally {
            val all = JSONArray()
            rows().forEach { r -> all.put(JSONObject().put("id",r.optString("id")).put("state",r.optString("state")).put("startedAt",r.optLong("startedAt")).put("finishedAt",r.optLong("finishedAt")).put("activeAtStart",r.optInt("activeAtStart")).put("summaryCorrect",r.optString("summary").contains("80235")).put("endReason",r.optJSONObject("endReason"))) }
            println("STRESS_RESULT " + JSONObject().put("count",count).put("begin",begin).put("end",System.currentTimeMillis()).put("rows",all))
        }
    }
    @Test fun screenWatchCapturesSettingsAndCanBeRevoked() = withClient { client ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val watch = ScreenWatchAccess(context)
        val original = watch.status().getBoolean("enabled")
        try {
            watch.enabled(true)
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val session = client.call("session/create", JSONObject()).getString("sessionId")
            val answer = send(client, session, "屏幕陪看验收：用户已明确要求且开启授权。请调用 mobile_see_now action=start detail=true 获取当前屏幕，只简要说出看到的设置页面，不要点屏幕。然后调用 action=stop 并回复已停止。")
            assertTrue("模型未调用屏幕工具", answer.tools.contains("mobile_see_now"))
            assertTrue("没有实际返回屏幕图片", answer.images.isNotEmpty())
            assertFalse("采集未停止", watch.status().getBoolean("active"))
            watch.start(session); watch.enabled(false)
            assertThrows(IllegalStateException::class.java) { watch.requireOwner(session) }
        } finally { watch.stop(); watch.enabled(original) }
    }
}

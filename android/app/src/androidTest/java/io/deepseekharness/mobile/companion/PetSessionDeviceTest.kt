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

/** Neutral live-model acceptance in a fresh read-only Session; no user conversation is changed. */
@RunWith(AndroidJUnit4::class)
class PetSessionDeviceTest {
    @Test fun unrelatedReplyDoesNotRepeatWorkerResult() {
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
            val client = HarnessBubbleClient(controller.openHarnessAccess())
            val session = client.call("session/create", JSONObject()).getString("sessionId")
            client.command(session, "/permission read-only")
            fun send(text: String): CompanionBubbleChat.Reply {
                val id = UUID.randomUUID().toString()
                return client.followReplyWithImages(session, id) {
                    client.call("session/prompt", JSONObject().put("sessionId", session).put("requestId", id).put("mode", "queue")
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
                }
            }
            val marker = "OLD" + UUID.randomUUID().toString().replace("-", "").take(12)
            send("请调用 mobile_workers start 创建只读任务，标题 $marker，任务内容：调用 mobile_worker_report completed，报告中写入 $marker。派发后不要轮询。")
            val dashboard = WorkerDashboardStore(context)
            fun row(): JSONObject? = dashboard.list().getJSONArray("items").objects().firstOrNull { it.optString("title").contains(marker) }
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline && row()?.optString("state") != "completed") Thread.sleep(300)
            assertEquals("Worker 任务未完成", "completed", row()?.optString("state"))
            assertTrue("测试任务未产生标记", row()?.optString("summary")?.contains(marker) == true)
            val answer = send("现在只回答：一加一等于几？请直接回答，不要执行工具。")
            assertFalse("无关的新回复包含旧任务结果", answer.text.contains(marker))
            assertTrue("新问题没有得到回答", answer.text.contains("2") || answer.text.contains("二"))
        } finally { RuntimeHost.detachPluginSink(sink) }
    }
    @Test fun shownWorkerReportIsNotReplayedWhenPetReopens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val directory = java.io.File(base.noBackupFilesDir, "worker-once-acceptance/$id").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): java.io.File = directory
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                base.getSharedPreferences("worker-once-$id-$name", mode)
        }
        val store = WorkerDashboardStore(context)
        val row = JSONObject().put("id", id).put("title", "一次通知验收").put("state", "running")
            .put("writeSeq", 1).put("reports", JSONArray())
        store.put(row)
        row.put("state", "completed").put("writeSeq", 2).put("summary", "旧结果标记7482")
            .put("reports", JSONArray().put(JSONObject().put("revision", 1).put("state", "completed")
                .put("summary", "旧结果标记7482").put("finishedAt", System.currentTimeMillis())))
        store.put(row)
        lateinit var window: CompanionBubbleWindow
        var created = false
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun root(): android.view.View = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(window) as android.view.View
        try {
            instrumentation.runOnMainSync {
                window = CompanionBubbleWindow(context, base.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager) { intArrayOf(40, 120, 80, 80) }
                created = true; window.show()
                assertTrue("新结果应显示一次", descendants(root()).filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("旧结果标记7482") })
                window.hide(); window.show()
                assertFalse("已显示结果不能在重新打开时重放", descendants(root()).filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("旧结果标记7482") })
            }
            assertEquals("历史结果仍需在任务记录中保留", 1, WorkerReportInbox(context).list().size)
        } finally { instrumentation.runOnMainSync { if (created) window.destroy() } }
    }
    @Test fun petTaskPanelShowsLiveStatusAndQueuesAnAnswer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val id = UUID.randomUUID().toString()
        val directory = java.io.File(base.noBackupFilesDir, "worker-panel-acceptance/$id").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getNoBackupFilesDir(): java.io.File = directory
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                base.getSharedPreferences("panel-test-$id-$name", mode)
        }
        val store = WorkerDashboardStore(context)
        val row = JSONObject().put("id", id).put("title", "面板验收任务").put("writeSeq", 1)
            .put("state", "waiting").put("question", "请选择颜色")
        store.put(row)
        lateinit var panel: CompanionWorkerPanel
        lateinit var badge: android.widget.TextView
        fun field(name: String): Any? = CompanionWorkerPanel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(panel)
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            instrumentation.runOnMainSync { panel = CompanionWorkerPanel(context, {}); badge = android.widget.TextView(context); panel.attach(badge) }
            val deadline = System.currentTimeMillis() + 15_000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = badge.text == "待处理 1" && badge.contentDescription?.contains("1项待处理") == true }
                if (!ready) Thread.sleep(100)
            }
            assertTrue("桌宠任务入口未显示待处理数量", ready)
            instrumentation.runOnMainSync { panel.open() }
            var cardReady = false
            while (!cardReady && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    val dialog = field("dialog") as? android.app.AlertDialog
                    cardReady = dialog?.window?.decorView?.let { view -> descendants(view).filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("请选择颜色") } } == true
                }
                if (!cardReady) Thread.sleep(100)
            }
            assertTrue("面板没有显示任务问题", cardReady)
            instrumentation.runOnMainSync {
                val dialog = field("dialog") as android.app.AlertDialog
                descendants(dialog.window!!.decorView).filterIsInstance<android.widget.Button>().first { it.text.toString() == "回答" }.performClick()
                val edit = field("editDialog") as android.app.AlertDialog
                descendants(edit.window!!.decorView).filterIsInstance<android.widget.EditText>().first().setText("蓝色")
                edit.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            while (store.pending().length() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertEquals("reply", store.pending().getJSONObject(0).getString("action"))
            assertEquals("蓝色", store.pending().getJSONObject(0).getString("task"))
            row.put("state", "running").put("question", "").put("writeSeq", 2); store.put(row)
            var updated = false
            while (!updated && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { updated = badge.text == "运行中 1" && badge.contentDescription?.contains("1项运行中") == true }
                if (!updated) Thread.sleep(100)
            }
            assertTrue("任务状态变化没有更新桌宠入口", updated)
        } finally { instrumentation.runOnMainSync { panel.destroy() } }
    }
    @Test fun workerReportIsPersistedAndDeduplicated() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val testId = UUID.randomUUID().toString()
        val directory = java.io.File(base.noBackupFilesDir, "worker-report-acceptance/$testId").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getNoBackupFilesDir(): java.io.File = directory
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                base.getSharedPreferences("acceptance-$testId-$name", mode)
        }
        val store = WorkerDashboardStore(context)
        val received = mutableListOf<JSONObject>()
        val listener: (JSONObject) -> Unit = { received.add(it) }
        WorkerReportInbox.subscribe(listener)
        try {
            val row = JSONObject().put("id", testId).put("title", "报告去重验收").put("writeSeq", 1).put("reports", JSONArray())
            store.put(row)
            row.put("writeSeq", 2).put("reports", JSONArray().put(JSONObject().put("revision", 1)
                .put("state", "completed").put("summary", "验收完成").put("finishedAt", System.currentTimeMillis())))
            store.put(row)
            row.put("writeSeq", 3); store.put(row)
            assertEquals("同一报告不应重复推送", 1, received.size)
            val inbox = WorkerReportInbox(context)
            assertEquals("验收完成", inbox.list().single().getString("text"))
            assertEquals(1, inbox.unseen().size)
            inbox.markSeen(listOf(inbox.unseen().single().getString("id")))
            assertTrue("已经显示的报告不能再次当作新回复", WorkerReportInbox(context).unseen().isEmpty())
            assertEquals("已读报告仍应保留在历史", 1, inbox.list().size)
        } finally { WorkerReportInbox.unsubscribe(listener) }
    }
    @Test fun deliveryInboxShowsNewItemsOnlyOnce() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = java.io.File(base.noBackupFilesDir, "delivery-seen-acceptance/$id").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): java.io.File = directory
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                base.getSharedPreferences("delivery-test-$id-$name", mode)
        }
        val inbox = CompanionDeliveryInbox(context)
        inbox.add(CompanionDeliveryInbox.Item(1, "第一次通知", System.currentTimeMillis(), "session-test"))
        assertEquals(1, inbox.unseen().size)
        inbox.markSeen(inbox.unseen())
        assertTrue(CompanionDeliveryInbox(context).unseen().isEmpty())
        inbox.add(CompanionDeliveryInbox.Item(2, "第二次通知", System.currentTimeMillis(), "session-test"))
        assertEquals("第二次通知", inbox.unseen().single().text)
        assertEquals(2, inbox.load().size)
    }
    @Test fun newSessionContinuesOldWorkerAndPushesToPet() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val directory = java.io.File(base.noBackupFilesDir, "pet-cross-session-acceptance").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): java.io.File = directory
        }
        val sink = object : RuntimeEventSink {
            override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
            override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
            override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
        }
        val controller = RuntimeHost.acquire(base, sink)
        lateinit var window: CompanionBubbleWindow
        var created = false
        val marker = "跨会话验收${UUID.randomUUID().toString().take(8)}"
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            if (runCatching { controller.openHarnessAccess() }.isFailure) {
                RuntimeHost.ensureDeviceBridge(controller); RuntimeHost.startHarness(controller)
            }
            val client = HarnessBubbleClient(controller.openHarnessAccess())
            fun send(session: String, text: String): CompanionBubbleChat.Reply {
                val id = UUID.randomUUID().toString()
                return client.followReplyWithImages(session, id) {
                    client.call("session/prompt", JSONObject().put("sessionId", session).put("requestId", id).put("mode", "queue")
                        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text))))
                }
            }
            val first = client.call("session/create", JSONObject()).getString("sessionId")
            client.command(first, "/permission read-only")
            send(first, "请立即调用 mobile_workers start 创建只读后台任务，标题 $marker，任务内容：先用 mobile_worker_report waiting 问用户喜欢哪种颜色，收到回答后报告 completed，结果包含收到的颜色。派发后不要轮询。")
            val dashboard = WorkerDashboardStore(base)
            fun row(): JSONObject? = dashboard.list().getJSONArray("items").objects().firstOrNull { it.optString("title") == marker }
            val waitDeadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < waitDeadline && row()?.optString("state") != "waiting") Thread.sleep(300)
            assertEquals("原任务未进入等待回答", "waiting", row()?.optString("state"))
            val originalId = row()!!.getString("id")
            val second = client.call("session/create", JSONObject()).getString("sessionId")
            client.command(second, "/permission read-only")
            CompanionBubbleHistory(context).save(CompanionBubbleHistory.Snapshot(second, "a".repeat(64), emptyList(), continuedMain = true))
            instrumentation.runOnMainSync {
                window = CompanionBubbleWindow(context, base.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager) { intArrayOf(40, 120, 80, 80) }
                created = true; window.show()
            }
            send(second, "请先调用 mobile_workers status 找到之前的后台任务 $marker，然后对这个原任务调用 reply 回答：蓝色。不要创建新任务。")
            val deadline = System.currentTimeMillis() + 180_000
            var pushed = false
            while (System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    val field = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }
                    val root = field.get(window) as? android.view.View
                    pushed = root != null && descendants(root).filterIsInstance<android.widget.TextView>().any {
                        it.text.toString().contains(marker) && it.text.toString().contains("蓝色")
                    }
                }
                if (pushed && row()?.optString("state") == "completed") break
                Thread.sleep(300)
            }
            assertEquals("原任务没有完成", "completed", row()?.optString("state"))
            assertEquals("任务被重新创建", originalId, row()?.optString("id"))
            assertTrue("后台结果未推送到桌宠窗口", pushed)
        } finally {
            instrumentation.runOnMainSync { if (created) window.destroy() }
            RuntimeHost.detachPluginSink(sink)
        }
    }
    @Test fun nativePetQuestionPanelSubmitsAndKeepsTheSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val directory = java.io.File(base.noBackupFilesDir, "pet-ui-acceptance").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): java.io.File = directory
        }
        val sink = object : RuntimeEventSink {
            override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
            override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
            override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
        }
        val controller = RuntimeHost.acquire(base, sink)
        if (runCatching { controller.openHarnessAccess() }.isFailure) {
            RuntimeHost.ensureDeviceBridge(controller); RuntimeHost.startHarness(controller)
        }
        val access = controller.openHarnessAccess()
        val client = HarnessBubbleClient(access)
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val store = CompanionBubbleHistory(context)
        store.save(CompanionBubbleHistory.Snapshot(session, "a".repeat(64), emptyList(), continuedMain = true))
        lateinit var window: CompanionBubbleWindow
        var created = false
        fun field(name: String): Any? = CompanionBubbleWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(window)
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            instrumentation.runOnMainSync {
                window = CompanionBubbleWindow(context, base.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager) { intArrayOf(40, 120, 80, 80) }
                created = true
                window.show()
                val views = descendants(field("root") as android.view.View)
                views.filterIsInstance<android.widget.EditText>().first().setText("桌宠界面验收：请实际调用 ask_user_question 问我喜欢蓝色还是红色，收到回答后回复已选择的颜色，不要调用其他工具，也不要替我选择。")
                views.filterIsInstance<android.widget.TextView>().first { it.text.toString() == "发送" }.performClick()
            }
            var submitted = false
            val deadline = System.currentTimeMillis() + 180_000
            while (System.currentTimeMillis() < deadline) {
                var finished = false
                instrumentation.runOnMainSync {
                    val dialog = field("questionDialog") as? android.app.AlertDialog
                    if (!submitted && dialog?.isShowing == true) {
                        val inputs = descendants(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>()
                        assertTrue(inputs.isNotEmpty())
                        assertEquals("waiting", PetExecutionStore(context).page(0).getJSONArray("items").getJSONObject(0).getString("state"))
                        inputs.forEach { it.setText("蓝色") }
                        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                        submitted = true
                    }
                    finished = submitted && field("busy") == false
                }
                if (finished) break
                Thread.sleep(250)
            }
            assertTrue("Pet did not display the question dialog", submitted)
            val saved = requireNotNull(store.load())
            assertNull("Question did not finish", saved.pending)
            assertEquals(session, saved.sessionId)
            val execution = PetExecutionStore(context).page(0).getJSONArray("items").getJSONObject(0)
            assertEquals("completed", execution.getString("state"))
            assertEquals(session, execution.getString("sessionId"))
            assertTrue(saved.messages.last().text.contains("蓝色"))
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(directory, "question-completed.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }
            android.util.Log.i("PetSessionAcceptance", "NATIVE_UI_PASS question_submitted=true session_preserved=true")
        } finally { instrumentation.runOnMainSync { if (created) window.destroy() }; RuntimeHost.detachPluginSink(sink) }
    }
    @Test fun nativePetDispatchesWorkerAndShowsResult() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val base = instrumentation.targetContext
        val directory = java.io.File(base.noBackupFilesDir, "pet-worker-acceptance").apply { mkdirs() }
        val context = object : android.content.ContextWrapper(base) {
            override fun getApplicationContext(): android.content.Context = this
            override fun getNoBackupFilesDir(): java.io.File = directory
        }
        val sink = object : RuntimeEventSink {
            override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
            override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
            override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
        }
        val controller = RuntimeHost.acquire(base, sink)
        if (runCatching { controller.openHarnessAccess() }.isFailure) { RuntimeHost.ensureDeviceBridge(controller); RuntimeHost.startHarness(controller) }
        val client = HarnessBubbleClient(controller.openHarnessAccess())
        val session = client.call("session/create", JSONObject()).getString("sessionId")
        client.command(session, "/permission read-only")
        val store = CompanionBubbleHistory(context)
        store.save(CompanionBubbleHistory.Snapshot(session, "a".repeat(64), emptyList(), continuedMain = true))
        lateinit var window: CompanionBubbleWindow
        var created = false
        fun field(name: String): Any? = CompanionBubbleWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(window)
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        try {
            instrumentation.runOnMainSync {
                window = CompanionBubbleWindow(context, base.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager) { intArrayOf(40, 120, 80, 80) }
                created = true; window.show()
                val views = descendants(field("root") as android.view.View)
                views.filterIsInstance<android.widget.EditText>().first().setText("请交给后台 Worker 计算 12345 加 67890，完成后告诉我结果。")
                views.filterIsInstance<android.widget.TextView>().first { it.text.toString() == "发送" }.performClick()
            }
            val deadline = System.currentTimeMillis() + 240_000
            var completed = false
            var visible = false
            while (System.currentTimeMillis() < deadline) {
                val all = WorkerDashboardStore(base).list().getJSONArray("items")
                completed = (0 until all.length()).map(all::getJSONObject).any { it.optString("owner") == session && it.optString("state") == "completed" && it.optString("summary").contains("80235") }
                instrumentation.runOnMainSync { visible = descendants(field("root") as android.view.View).filterIsInstance<android.widget.TextView>().any { it.text.toString().contains("80235") } }
                if (completed && visible) break
                Thread.sleep(500)
            }
            assertTrue("桌宠 Worker 没有完成", completed)
            assertTrue("桌宠界面没有显示 Worker 的最终结果", visible)
            assertEquals(session, requireNotNull(store.load()).sessionId)
        } finally { instrumentation.runOnMainSync { if (created) window.destroy() }; RuntimeHost.detachPluginSink(sink) }
    }
    @Test fun streamsRestoresAndAnswersInTheSameHarnessSession() {
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
            val access = controller.openHarnessAccess()
            val client = HarnessBubbleClient(access)
            val session = client.call("session/create", JSONObject()).getString("sessionId")
            android.util.Log.i("PetSessionAcceptance", "START session=$session")
            client.command(session, "/permission read-only")
            val id = UUID.randomUUID().toString()
            val partials = mutableListOf<String>()
            fun submit(request: String, prompt: String) {
                client.call("session/prompt", JSONObject().put("sessionId", session).put("requestId", request).put("mode", "queue")
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt))))
            }
            val answer = client.followReplyWithImages(session, id, { partials.add(it) }) {
                submit(id, "这是桌宠共享会话验收，请仅用中文写三段关于鲸鱼的科普，每段约100字，不调用工具。最后写：共享会话验收完成。")
            }
            assertTrue("No live text before settlement", partials.distinct().size > 2)
            assertTrue(answer.text.contains("共享会话验收完成"))
            // A new client starts entirely from the Host snapshot, with no prompt replay.
            val restored = HarnessBubbleClient(access).followReplyWithImages(session, id) { fail("Completed request was submitted again") }
            assertEquals(answer.text, restored.text)
            java.io.File(context.noBackupFilesDir, "pet-session-acceptance.json").writeText(JSONObject()
                .put("sessionId", session).put("requestId", id).put("text", answer.text).toString())
            android.util.Log.i("PetSessionAcceptance", "STREAM_PASS callbacks=${partials.size} restore_equal=true")
            val questionRequest = UUID.randomUUID().toString()
            var answered = false
            val continued = client.followReplyWithImages(session, questionRequest, onStructuredQuestion = { question ->
                val answers = question.getJSONArray("questions").objects().map { row ->
                    JSONObject().put("id", row.getString("id")).put("selected", JSONArray()).put("custom", "蓝色")
                }
                client.answerQuestion(session, question.getString("callId"), JSONArray(answers)); answered = true
            }) {
                submit(questionRequest, "这是追问接口验收。必须实际调用 ask_user_question 问我喜欢蓝色还是红色，等待回答；收到回答后回复：已选择蓝色。不要自行替我选择，也不要使用其他工具。")
            }
            assertTrue("The model did not ask a structured question", answered)
            assertTrue(continued.text.contains("蓝色"))
            android.util.Log.i("PetSessionAcceptance", "PASS streaming_callbacks=${partials.size} restored_equal=true question_answered=true session=$session")
        } finally { RuntimeHost.detachPluginSink(sink) }
    }

    @Test fun restoresAfterAppProcessRestart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File(context.noBackupFilesDir, "pet-session-acceptance.json")
        assertTrue("Run the streaming acceptance before restarting the app", file.exists())
        val saved = JSONObject(file.readText())
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
            val restored = HarnessBubbleClient(controller.openHarnessAccess()).followReplyWithImages(saved.getString("sessionId"), saved.getString("requestId")) {
                fail("Restart replayed a completed request")
            }
            assertEquals(saved.getString("text"), restored.text)
            android.util.Log.i("PetSessionAcceptance", "PROCESS_RESTART_PASS restored_equal=true replay=false")
        } finally { RuntimeHost.detachPluginSink(sink) }
    }
}

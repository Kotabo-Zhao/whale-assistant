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

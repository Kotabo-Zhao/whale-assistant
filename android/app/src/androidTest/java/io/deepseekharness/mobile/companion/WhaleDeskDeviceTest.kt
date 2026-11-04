package io.deepseekharness.mobile.companion

import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhaleDeskDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun inspectDeskFailures() {
        val list = WorkerDashboardStore(context).list().getJSONArray("items")
        val tasks = (0 until list.length()).map(list::getJSONObject).filter { it.optString("title").startsWith("派工验收-") }
        val store = io.deepseekharness.mobile.runtime.RuntimeStore(context)
        instrumentation.sendStatus(0, Bundle().apply { putBoolean("configuredDeepSeek", !store.readModelCredential("deepseek").isNullOrBlank()) })
        for (row in tasks.take(4)) {
            val reason = row.optString("summary").replace(Regex("(?i)bearer \\S+|sk-[A-Za-z0-9_-]+|https?://\\S+"), "[redacted]")
            instrumentation.sendStatus(0, Bundle().apply {
                putString("testWorker", row.optString("state")); putString("testDetail", reason.take(340))
                putString("testEndReason", row.optString("endReason").replace(Regex("(?i)bearer \\S+|sk-[A-Za-z0-9_-]+|https?://\\S+"), "[redacted]").take(360))
            })
        }
    }

    @Test fun ledgerFingerprint() {
        val root = File(context.noBackupFilesDir, "dsh-runtime/current/root/.dsh/mobile-workers")
        assertTrue("缺少原有任务目录", root.isDirectory)
        val digest = MessageDigest.getInstance("SHA-256")
        val entries = root.walkTopDown().filter { it.isFile && it.extension == "json" }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        for (file in entries) {
            digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
            digest.update(0)
            digest.update(MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putInt("ledgerCount", entries.size); putString("ledgerSHA256", digest.digest().joinToString("") { "%02x".format(it) })
        })
    }

    @Test fun twoDeskSubmissionsGetDistinctWorkers() {
        val store = WorkerDashboardStore(context)
        val runtime = WorkerRuntimeWake(context)
        val marker = "派工验收-" + UUID.randomUUID().toString().take(8)
        val start = SystemClock.elapsedRealtime()
        try {
            val first = WorkerRuntimeWake.submit(context, "$marker 甲：请汇报等待，并询问要选择哪种颜色。", "read", runtime)
            val second = WorkerRuntimeWake.submit(context, "$marker 乙：请计算 123 加 456，报告结果。", "read", runtime)
            assertNotEquals(first.getString("commandId"), second.getString("commandId"))
            instrumentation.sendStatus(0, Bundle().apply { putLong("twoSubmissionsMs", SystemClock.elapsedRealtime() - start) })
            val until = SystemClock.elapsedRealtime() + 150_000
            var rows: List<JSONObject>
            do {
                val list = store.list().getJSONArray("items")
                rows = (0 until list.length()).map(list::getJSONObject).filter { it.optString("title").startsWith(marker) }
                if (rows.size == 2) break
                Thread.sleep(450)
            } while (SystemClock.elapsedRealtime() < until)
            assertEquals(2, rows.size)
            assertEquals(2, rows.map { it.getString("domainTicketId") }.distinct().size)
            assertTrue(rows.all { it.getString("mode") == "read" && it.getString("owner") == "session-whale-task-desk" })
            instrumentation.sendStatus(0, Bundle().apply { putString("statesAtPickup", rows.joinToString { it.getString("state") }) })
        } finally { runtime.close() }
    }

    private inline fun withAcceptanceModel(run: () -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        val store = io.deepseekharness.mobile.runtime.RuntimeStore(context)
        val provider = io.deepseekharness.mobile.runtime.ModelProvider.DEEPSEEK
        val existing = store.readModelCredential("deepseek")
        // Optional one-shot host relay. The credential never appears in instrumentation arguments.
        val temporary = if (!existing.isNullOrBlank()) null else {
            val nonce = requireNotNull(args.getString("relay"))
            require(nonce.matches(Regex("[a-f0-9]{32}")))
            android.net.LocalSocket().use { socket ->
                socket.connect(android.net.LocalSocketAddress("whale-test-$nonce", android.net.LocalSocketAddress.Namespace.ABSTRACT))
                socket.soTimeout = 10_000
                socket.outputStream.write("$nonce\n".toByteArray())
                requireNotNull(socket.inputStream.bufferedReader().readLine()).also { require(it.length in 20..200) }
            }
        }
        try {
            temporary?.let { key ->
                val settings = store.settings()
                store.saveSettings(settings, mapOf(provider to key), customProviders = settings.customModelProviders)
            }
            run()
        } finally {
            if (temporary != null && args.getString("configureModel") != "true" &&
                store.readModelCredential("deepseek") == temporary) {
                val settings = store.settings()
                store.saveSettings(settings, clearedProviderApiKeys = setOf(provider), customProviders = settings.customModelProviders)
            }
        }
    }

    @Test fun realPetRigFollowsWorker(): Unit = withAcceptanceModel {
        val service = io.deepseekharness.mobile.OverlayBallService
        val type = io.deepseekharness.mobile.OverlayBallService::class.java
        val active = type.getDeclaredField("activeService").apply { isAccessible = true }
        val actor = type.getDeclaredField("petActing").apply { isAccessible = true }
        val ball = type.getDeclaredField("ballView").apply { isAccessible = true }
        val drawable = type.getDeclaredField("petDrawable").apply { isAccessible = true }
        val rt = io.deepseekharness.mobile.runtime.RuntimeStore(context)
        val pref = CompanionStore(context)
        val oldEnabled = rt.overlayBallEnabled()
        val oldReduced = pref.reduceMotion
        val wasRunning = service.isRunning
        val runtime = WorkerRuntimeWake(context)
        val dashboard = WorkerDashboardStore(context)
        val tag = "关节联测-" + UUID.randomUUID().toString().take(8)
        val states = mutableSetOf<String>()
        var previous = ""
        fun observe(): String {
            var name = ""
            instrumentation.runOnMainSync {
                active.get(null)?.let { instance ->
                    if (drawable.get(instance) != null) name = (actor.get(instance) as io.deepseekharness.mobile.overlay.PetActing).action.name
                }
            }
            if (name.isNotEmpty() && name != previous) {
                previous = name
                states.add(name)
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("observedAction", name); putLong("eventUptimeMs", SystemClock.uptimeMillis())
                })
            }
            return name
        }
        try {
            pref.reduceMotion = false
            rt.setOverlayBallEnabled(true)
            service.start(context)
            val attachUntil = SystemClock.elapsedRealtime() + 15_000
            while (observe().isEmpty() && SystemClock.elapsedRealtime() < attachUntil) {
                service.updateForHostAppForeground(false); Thread.sleep(100)
            }
            val rect = android.graphics.Rect()
            instrumentation.runOnMainSync {
                val view = ball.get(requireNotNull(active.get(null))) as android.view.View
                val location = IntArray(2); view.getLocationOnScreen(location)
                rect.set(location[0], location[1], location[0] + view.width, location[1] + view.height)
            }
            val screen = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            instrumentation.sendStatus(0, Bundle().apply {
                putString("avatarCrop", "${rect.width()}:${rect.height()}:${rect.left}:${rect.top}")
                putString("screenExtent", "${screen.width}:${screen.height}")
                putLong("captureUptimeMs", SystemClock.uptimeMillis())
            })
            screen.recycle()
            Thread.sleep(3500)
            WorkerRuntimeWake.submit(context, "$tag 请调用 mobile_worker_report waiting 问我想选哪种颜色。收到我的回答后，调用 mobile_worker_report completed 报告我选择的颜色。", "read", runtime)
            val until = SystemClock.elapsedRealtime() + 210_000
            var answered = false
            var askedAt = 0L
            var checkedAt = 0L
            var sawResult = false
            while (SystemClock.elapsedRealtime() < until) {
                val action = observe()
                val now = SystemClock.elapsedRealtime()
                if (action == "ASK" && askedAt == 0L) askedAt = now
                if (now - checkedAt > 400) {
                    checkedAt = now
                    val items = dashboard.list().getJSONArray("items")
                    val row = (0 until items.length()).map(items::getJSONObject).firstOrNull { it.optString("title").startsWith(tag) }
                    if (row != null) when (row.optString("state")) {
                        "waiting" -> if (askedAt != 0L && now - askedAt >= 3600 && !answered) {
                            dashboard.command(JSONObject().put("id", row.getString("id")).put("action", "reply").put("task", "我选择蓝色。"))
                            answered = true
                        }
                        "completed" -> { check("蓝色" in row.optString("summary")); sawResult = true }
                        "failed" -> error("实机动作验收工单失败")
                    }
                }
                if (sawResult && "CELEBRATE" in states && action != "CELEBRATE") break
                Thread.sleep(75)
            }
            assertTrue("未收到实际工单结果", sawResult)
            assertTrue(states.toString(), states.containsAll(listOf("IDLE", "ACCEPT", "WORK", "ASK", "CELEBRATE")))
            // A receipt must leave the foreground alone. Opening chat by hand must leave the pet visible.
            lateinit var chat: CompanionBubbleWindow
            instrumentation.runOnMainSync {
                val instance = requireNotNull(active.get(null))
                chat = type.getDeclaredMethod("getBubbleChat").apply { isAccessible = true }.invoke(instance) as CompanionBubbleWindow
                assertFalse(chat.visible)
                (ball.get(instance) as android.view.View).performClick()
            }
            Thread.sleep(600)
            instrumentation.runOnMainSync {
                val panel = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(chat) as android.view.View
                val xy = IntArray(2); panel.getLocationOnScreen(xy)
                val chatRect = android.graphics.Rect(xy[0], xy[1], xy[0] + panel.width, xy[1] + panel.height)
                assertFalse("桌宠 $rect 与对话 $chatRect 重叠", android.graphics.Rect.intersects(chatRect, rect))
                instrumentation.sendStatus(0, Bundle().apply { putString("chatRect", chatRect.toShortString()) })
                chat.hide()
            }
        } finally {
            runtime.close()
            pref.reduceMotion = oldReduced
            rt.setOverlayBallEnabled(oldEnabled)
            if (!wasRunning) service.stop(context)
            else service.updateForHostAppForeground(io.deepseekharness.mobile.AppForeground.isForeground())
        }
    }

    @Test fun realWorkersFinishIndependently() = verifyIndependentWorkers(false)
    @Test fun realPetAcceptsTwoTasksAndKeepsChatEditable() = verifyIndependentWorkers(true)
    @Test fun realPetDelegatesCreationAndAnotherTask() = verifyIndependentWorkers(true, create = true)
    private fun verifyIndependentWorkers(viaPet: Boolean, create: Boolean = false): Unit = withAcceptanceModel {
        val wake = WorkerRuntimeWake(context)
        val tasks = WorkerDashboardStore(context)
        val tag = "双任务联测-" + UUID.randomUUID().toString().take(8)
        var window: CompanionBubbleWindow? = null
        fun children(view: android.view.View): List<android.view.View> = listOf(view) +
            if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
        fun submit(task: String, mode: String = "read") {
            if (!viaPet) { WorkerRuntimeWake.submit(context, task, mode, wake); return }
            val pet = requireNotNull(window)
            var editor: android.app.AlertDialog? = null
            instrumentation.runOnMainSync {
                val root = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(pet) as android.view.View
                val composer = children(root).filterIsInstance<android.widget.EditText>().single()
                assertTrue(composer.isEnabled)
                if (task.startsWith("$tag 甲")) composer.setText("还可以继续聊天")
                else assertEquals("还可以继续聊天", composer.text.toString())
                check(children(root).single { it.contentDescription == "会话选项" }.performClick())
                check(children(root).single { it.isShown && it.contentDescription == "交办独立后台任务" }.performClick())
                val panel = CompanionBubbleWindow::class.java.getDeclaredField("workerPanel").apply { isAccessible = true }.get(pet)
                val dialog = CompanionWorkerPanel::class.java.getDeclaredField("editDialog").apply { isAccessible = true }.get(panel) as android.app.AlertDialog
                val choice = children(dialog.window!!.decorView).filterIsInstance<android.widget.RadioButton>().single { it.tag == mode }
                choice.performClick()
                check(choice.isChecked && (choice.parent as android.widget.RadioGroup).checkedRadioButtonId == choice.id)
                children(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single().setText(task)
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                editor = dialog
            }
            val until = SystemClock.elapsedRealtime() + 5000
            var showing = true
            while (showing && SystemClock.elapsedRealtime() < until) {
                instrumentation.runOnMainSync { showing = editor!!.isShowing }; if (showing) Thread.sleep(20)
            }
            assertFalse("任务没有保存，输入窗口仍在等待", showing)
        }
        try {
            if (viaPet) instrumentation.runOnMainSync {
                window = CompanionBubbleWindow(context, context.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager,
                    autoOpenOnDelivery = false) { intArrayOf(10, 200, 80, 80) }.also { it.show() }
            }
            val time = SystemClock.elapsedRealtime()
            if (create) submit("$tag 甲 创作独立工具「工时换算」。先用 mobile_worker_report waiting 询问一天算几小时。用户答复后生成紧凑的离线工具草稿。输入小时数 id=hours，随输入变动把换算出的天数写入 output data-whale-result。使用工单指定的唯一标识。真正提交后报告完成。", "create")
            else submit("$tag 甲 请调用 mobile_worker_report waiting 询问选择哪种颜色。在用户回答后再报告 completed 和颜色。")
            submit("$tag 乙 计算 123 加 456，调用 mobile_worker_report completed 报告结果。")
            instrumentation.sendStatus(0, Bundle().apply { putLong("twoRealSubmissionsMs", SystemClock.elapsedRealtime() - time) })
            var replied = false
            val until = time + if (create) 600_000 else 240_000
            while (SystemClock.elapsedRealtime() < until) {
                val items = tasks.list().getJSONArray("items")
                val owned = (0 until items.length()).map(items::getJSONObject).filter { it.optString("title").startsWith(tag) }
                check(owned.none { it.optString("state") in setOf("failed", "blocked") }) {
                    "工单未完成：" + owned.joinToString { it.optString("state") + " " + it.optString("summary").take(210) }
                }
                val question = owned.firstOrNull { it.optString("title").startsWith("$tag 甲") }
                val answer = owned.firstOrNull { it.optString("title").startsWith("$tag 乙") }
                if (answer?.optString("state") == "completed" && question != null) {
                    assertTrue(answer.optString("summary"), "579" in answer.optString("summary"))
                    if (question.optString("state") == "waiting" && !replied) {
                        instrumentation.sendStatus(0, Bundle().apply {
                            putString("otherTaskWhileCreatorWaits", answer.optString("state")); putString("firstTask", question.optString("state"))
                        })
                        tasks.command(JSONObject().put("id", question.getString("id")).put("action", "reply").put("task",
                            if (create) "每天按 8 小时计算。请继续完成工具草稿，20 小时应换算为 2.5 天。" else "我选择蓝色。"))
                        replied = true
                    } else if (replied && question.optString("state") == "completed") {
                        if (create) {
                            assertEquals("create", question.getString("mode"))
                            val draft = question.getJSONObject("authoredDraft")
                            assertEquals("task-" + question.getString("localCreation"), draft.getString("id"))
                            val feature = requireNotNull(io.deepseekharness.mobile.features.WhaleFeatureStore(context).read(draft.getString("id")))
                            assertEquals(draft.getString("revision"), feature.getString("latest"))
                            assertTrue(feature.optString("active").isEmpty())
                            assertTrue(question.getJSONObject("toolEvidence").getJSONObject("mobile_whale_feature").getInt("calls") > 0)
                            instrumentation.sendStatus(0, Bundle().apply {
                                putString("authorWorker",question.getString("id")); putString("authoredId",draft.getString("id"))
                                putString("draftHash",draft.getString("revision"))
                            })
                        } else assertTrue(question.optString("summary"), "蓝色" in question.optString("summary"))
                        return@withAcceptanceModel
                    }
                }
                Thread.sleep(500)
            }
            fail("两个任务未在四分钟内独立完成")
        } finally {
            instrumentation.runOnMainSync { window?.destroy() }
            wake.close()
        }
    }

    @Test fun realHarnessAuthorsTemperatureTool(): Unit = withAcceptanceModel {
        val wake = WorkerRuntimeWake(context)
        try {
            wake.wake()
            val access = requireNotNull(io.deepseekharness.mobile.runtime.RuntimeHost.controllerOrNull()).openHarnessAccess()
            val client = HarnessBubbleClient(access)
            val session = client.call("session/create", JSONObject()).getString("sessionId")
            val request = UUID.randomUUID().toString()
            val id = "whale-temperature"
            val prompt = "请为鲸鱼助手创建可在功能工坊使用的摄氏华氏换算器，标识 $id，标题「温度换算」。使用 mobile_whale_feature 工具提交真正的 HTML/CSS/JS 功能。使用数字输入框 id=celsius，监听 input（无需点击按钮），把华氏数字写入 id=fahrenheit 的普通结果文字。清空输入时清空输出，支持负值和小数。界面使用中文并适合手机，不依赖外部资源。若功能已存在，先 read，在已有版本上更新。"
            val reply = client.followReplyWithImages(session, request) {
                client.call("session/prompt", JSONObject().put("sessionId", session).put("requestId", request).put("mode", "queue")
                    .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", prompt))))
            }
            assertTrue("模型未调用宿主创作工具", "mobile_whale_feature" in reply.tools)
            val features = io.deepseekharness.mobile.features.WhaleFeatureStore(context)
            val row = requireNotNull(features.read(id))
            val revision = row.getString("latest")
            instrumentation.sendStatus(0, Bundle().apply { putString("authoredRevision", revision) })
            assertTrue(row.getJSONObject("versions").getJSONObject(revision).getString("html").contains("fahrenheit"))
        } finally { wake.close() }
    }
    @Test fun realHarnessAuthorsReadingCard(): Unit = withAcceptanceModel {
        val id = "whale-reading-card"
        val store = io.deepseekharness.mobile.features.WhaleFeatureStore(context)
        check(store.read(id) == null) { "同名工具已存在，保留现有内容" }
        val wake = WorkerRuntimeWake(context)
        try {
            wake.wake()
            val access = requireNotNull(io.deepseekharness.mobile.runtime.RuntimeHost.controllerOrNull()).openHarnessAccess()
            val client = HarnessBubbleClient(access)
            val session = client.call("session/create", JSONObject()).getString("sessionId")
            val request = UUID.randomUUID().toString()
            val prompt = """请为鲸鱼助手创作一个新的离线应用功能，标识 whale-reading-card，标题严格使用「阅读摘录卡」。
                提供书名输入框 id=book 和摘录输入框 id=passage，边输入边生成带书名和原句的纯文本阅读卡。
                使用一个 output data-whale-result 显示最终结果，让用户可以通过鲸鱼原生收藏按钮保存到资料库。
                在两个输入都有内容时才输出卡片。中文界面，适合横屏掌机和手机。使用 mobile_whale_feature 提交完整 HTML/CSS/JS 草稿。""".trimIndent()
            val reply = client.followReplyWithImages(session, request) {
                client.call("session/prompt", JSONObject().put("sessionId", session).put("requestId", request).put("mode", "queue")
                    .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", prompt))))
            }
            assertTrue("未调用宿主创作工具", "mobile_whale_feature" in reply.tools)
            val row = requireNotNull(store.read(id))
            val version = row.getString("latest")
            assertEquals("阅读摘录卡", row.getJSONObject("versions").getJSONObject(version).getString("title"))
            assertTrue(row.optString("active").isEmpty())
            instrumentation.sendStatus(0, Bundle().apply { putString("createdDraft", version) })
        } finally { wake.close() }
    }

    @Test fun realMainSessionReadsTicketResource(): Unit = withAcceptanceModel {
        val wake = WorkerRuntimeWake(context)
        try {
            wake.wake()
            val access = requireNotNull(io.deepseekharness.mobile.runtime.RuntimeHost.controllerOrNull()).openHarnessAccess()
            val client = HarnessBubbleClient(access)
            val session = client.call("session/create", JSONObject()).getString("sessionId")
            val request = UUID.randomUUID().toString()
            val reply = client.followReplyWithImages(session, request) {
                client.call("session/prompt", JSONObject().put("sessionId", session).put("requestId", request).put("mode", "queue")
                    .put("content", org.json.JSONArray().put(JSONObject().put("type", "text")
                        .put("text", "请只用 mobile_superagent_read 读取 resource=health。说明有多少项待处理派工，勿读取历史消息或执行外部操作。"))))
            }
            assertTrue("主会话未实际读取工单健康资源", "mobile_superagent_read" in reply.tools)
        } finally { wake.close() }
    }
}

package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhaleConversationalTaskTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private fun storage(dir: File) = object : ContextWrapper(app) {
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir() = dir
    }
    private fun views(v: View): Sequence<View> = sequence {
        yield(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(views(v.getChildAt(i)))
    }
    private fun await(message: String, duration: Long = 90_000, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + duration
        while (!check()) { if (SystemClock.elapsedRealtime() >= until) fail(message); Thread.sleep(100) }
    }

    @Test fun recoversOneSubmissionAfterUncertainAcknowledgement() {
        val tmp = File(app.cacheDir,"pet-worker-retry-"+UUID.randomUUID()).apply { mkdirs() }
        val ctx = storage(tmp)
        val store = WorkerDashboardStore(ctx); val history = CompanionBubbleHistory(ctx)
        val id = UUID.randomUUID().toString(); val text = "整理这一份旅行清单"
        val turn = listOf(CompanionBubbleChat.Message("user",text))
        val request = JSONObject().put("requestId",id).put("kind","worker").put("user",text)
            .put("prompt",text).put("needs","none").put("mode","read")
        try {
            history.save(CompanionBubbleHistory.Snapshot("pet-direct-"+UUID.randomUUID(),"a".repeat(64), emptyList(),request.toString()))
            val interrupted = CompanionBubbleChat(ctx,history.load(),history) { task, mode, key, title ->
                store.submit(task,mode,key,title); error("simulated lost acknowledgement")
            }
            assertThrows(IllegalStateException::class.java) { interrupted.fusedReply(turn,"a".repeat(64),false,false) {} }
            val restored = requireNotNull(history.load())
            assertEquals(id,JSONObject(requireNotNull(restored.pending)).getString("requestId"))
            val recovered = CompanionBubbleChat(ctx,restored,history) { task, mode, key, title -> store.submit(task,mode,key,title) }
            val reply = recovered.fusedReply(turn,"a".repeat(64),false,false) {}
            assertTrue(reply.text.contains("已交给后台")); assertEquals(1,store.pending().length())
            assertEquals(id,store.pending().getJSONObject(0).getString("commandId"))
            assertNull(history.load()!!.pending)
            assertEquals(2,history.load()!!.messages.size)
        } finally { check(tmp.parentFile == app.cacheDir); tmp.walkBottomUp().forEach { it.delete() } }
    }

    @Test fun modelDistinguishesIdeasFollowupsAndExternalActions() {
        val store = io.deepseekharness.mobile.runtime.RuntimeStore(app)
        val key = requireNotNull(store.readModelCredential("deepseek")?.takeIf { it.isNotBlank() })
        val model = store.settings().defaultModels[io.deepseekharness.mobile.runtime.ModelProvider.DEEPSEEK] ?: "deepseek-flash"
        fun user(text: String) = CompanionBubbleChat.Message("user",text)
        fun assistant(text: String) = CompanionBubbleChat.Message("assistant",text)
        fun decision(vararg messages: CompanionBubbleChat.Message) = PetIntentRouter.classify(key,model,messages.toList()).intent
        assertEquals(PetIntentRouter.Intent.CHAT, decision(user("我在想象一款旅行工具的界面，只聊聊有哪些常见布局。")))
        assertEquals(PetIntentRouter.Intent.AGENT, decision(user("逐项比较新旧行程计划，整理成报告。"),
            assistant("已交给后台处理。"),user("刚才那份报告目前进展怎么样？")))
        assertEquals(PetIntentRouter.Intent.AGENT, decision(user("把这份行程报告发到外部群里。")))
        inst.sendStatus(0,Bundle().apply { putString("decisions","idea=chat, follow-up=existing agent, external send=existing agent; classified only") })
    }

    @Test fun revokedLibraryPermissionStopsRecoveryBeforeEnqueue() {
        val tmp = File(app.cacheDir,"pet-worker-denied-"+UUID.randomUUID()).apply { mkdirs() }
        val prefNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        try {
            val ctx = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun getNoBackupFilesDir() = tmp
                override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences((tmp.name + name).also { prefNames.add(it) },mode)
            }
            val file = CompanionBubbleHistory(ctx)
            val message = "整理我收藏的笔记"
            val req = JSONObject().put("kind","worker").put("requestId",UUID.randomUUID().toString())
                .put("prompt",message).put("user",message).put("needs","library").put("mode","read")
            file.save(CompanionBubbleHistory.Snapshot("pet-direct-"+UUID.randomUUID(),"c".repeat(64),emptyList(),req.toString()))
            val chat = CompanionBubbleChat(ctx,file.load(),file) { _,_,_,_ -> error("must not enqueue") }
            val reply = chat.fusedReply(listOf(CompanionBubbleChat.Message("user",message)),"",false,false) {}
            assertTrue(reply.text.contains("开启对话访问")); assertNull(file.load()!!.pending)
            val desk = WorkerDashboardStore(ctx)
            val id = req.getString("requestId")
            val held = desk.page("attention").getJSONArray("submissions").getJSONObject(0)
            assertEquals(id,held.getString("commandId")); assertTrue(held.getBoolean("held"))
            assertEquals(0,desk.batch(0).length())
            assertTrue(desk.heldAction(id,"resume").getString("message").contains("开启对话访问"))
            assertEquals(0,desk.pending().length())
            val otherId = UUID.randomUUID().toString()
            desk.submit("独立普通任务","read",otherId)
            assertEquals(otherId,desk.batch(0).getJSONObject(0).getString("commandId"))
            CompanionStore(ctx).knowledgeModelAccess = true
            val reopened = WorkerDashboardStore(ctx)
            // A crash between saving the hold and clearing the conversational checkpoint may replay the turn.
            file.save(CompanionBubbleHistory.Snapshot("pet-direct-"+UUID.randomUUID(),"c".repeat(64),emptyList(),req.toString()))
            val replay = CompanionBubbleChat(ctx,file.load(),file) { _,_,_,_ -> error("hold requires explicit resume") }
            val answer = replay.fusedReply(listOf(CompanionBubbleChat.Message("user",message)),"",false,true) {}
            assertTrue(answer.text.contains("等待确认"))
            assertEquals(1,reopened.batch(0).length())
            reopened.heldAction(id,"resume")
            reopened.heldAction(id,"resume")
            val commands = reopened.batch(0)
            assertEquals(2,commands.length())
            assertEquals(id,commands.getJSONObject(1).getString("commandId"))
            assertEquals(message,commands.getJSONObject(1).getString("task"))
            assertEquals(0,reopened.page("attention").getJSONArray("submissions").let { items ->
                (0 until items.length()).count { items.getJSONObject(it).optBoolean("held") }
            })
            val cancelled = UUID.randomUUID().toString()
            reopened.hold(cancelled,"另一项请求","create","none","未提交的草稿","需要创作权限")
            lateinit var inbox: CompanionWorkerPanel
            inst.runOnMainSync { inbox = CompanionWorkerPanel(ctx,{}); inbox.open() }
            try {
                await("桌宠未展示待授权请求的操作", 8000) {
                    var clicked = false
                    inst.runOnMainSync {
                        val dialog = CompanionWorkerPanel::class.java.getDeclaredField("dialog").apply { isAccessible=true }.get(inbox) as? android.app.AlertDialog
                        val labels = dialog?.window?.decorView?.let { views(it).filterIsInstance<TextView>().toList() }.orEmpty()
                        if (labels.any { it.text.contains("未提交的草稿") } && labels.any { it.text == "权限设置" } && labels.any { it.text == "继续原任务" })
                            clicked = labels.singleOrNull { it.text == "移除" }?.performClick() == true
                    }
                    clicked
                }
                await("移除后的请求仍出现在后台任务") {
                    val items = reopened.page("attention").getJSONArray("submissions")
                    (0 until items.length()).none { items.getJSONObject(it).optString("commandId") == cancelled }
                }
            } finally { inst.runOnMainSync { inbox.destroy() } }
            reopened.heldAction(cancelled,"resume")
            assertEquals(2,reopened.batch(0).length())
            inst.sendStatus(0,Bundle().apply { putString("permission","held locally across reopen, unrelated command accepted, grant resumed original ID exactly once; removed request not executed") })
        } finally { prefNames.forEach { app.deleteSharedPreferences(it) }; check(tmp.parentFile == app.cacheDir); tmp.walkBottomUp().forEach { it.delete() } }
    }

    /** Run twice with one fixture UUID in separate instrumentation processes. No device-wide grant is changed. */
    @Test fun permissionRequestSurvivesAProcessBoundary() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue("Needs an isolated two-process fixture", args.containsKey("fixtureId"))
        val id = UUID.fromString(args.getString("fixtureId")).toString()
        val dir = File(app.cacheDir, "permission-process-$id")
        val ctx = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences("$id-$name",mode)
        }
        val pid = android.os.Process.myPid()
        val marker = File(dir,"first.pid")
        val task = "为本次测试整理一份报告"
        if (args.getString("phase") == "save") {
            check(dir.mkdirs())
            val desk = WorkerDashboardStore(ctx)
            desk.hold(id,task,"read","library","测试报告","需要资料授权")
            assertEquals(0,desk.batch(0).length())
            marker.writeText(pid.toString())
        } else {
            check(args.getString("phase") == "resume")
            assertNotEquals(marker.readText().toInt(),pid)
            val desk = WorkerDashboardStore(ctx)
            assertTrue(desk.isHeld(id))
            assertTrue(desk.heldAction(id,"resume").getString("message").contains("开启对话访问"))
            assertEquals(0,desk.batch(0).length())
            CompanionStore(ctx).knowledgeModelAccess = true
            desk.heldAction(id,"resume"); desk.heldAction(id,"resume")
            assertFalse(desk.isHeld(id))
            val commands = desk.batch(0)
            assertEquals(1,commands.length()); assertEquals(id,commands.getJSONObject(0).getString("commandId"))
            assertEquals(task,commands.getJSONObject(0).getString("task"))
            check(dir.parentFile == app.cacheDir); dir.walkBottomUp().forEach { it.delete() }
            for (file in File(app.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty()) {
                if (file.name.startsWith("$id-") && file.extension == "xml") app.deleteSharedPreferences(file.nameWithoutExtension)
            }
        }
        inst.sendStatus(0,Bundle().apply { putString("phase",args.getString("phase")); putInt("process",pid); putString("commandId",id) })
    }

    @Test fun establishedHarnessSessionChoosesDelegationForAnIndependentDeliverable() {
        val sink = object : io.deepseekharness.mobile.runtime.RuntimeEventSink {
            override fun onProgress(snapshot: io.deepseekharness.mobile.runtime.RuntimeStateSnapshot) = Unit
            override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
            override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
        }
        val tmp = File(app.cacheDir,"pet-bound-"+UUID.randomUUID()).apply { mkdirs() }
        val controller = io.deepseekharness.mobile.runtime.RuntimeHost.acquire(app,sink)
        try {
            io.deepseekharness.mobile.runtime.RuntimeHost.ensureDeviceBridge(controller)
            if (runCatching { controller.openHarnessAccess() }.isFailure) io.deepseekharness.mobile.runtime.RuntimeHost.startHarness(controller)
            val client = HarnessBubbleClient(controller.openHarnessAccess())
            val session = client.call("session/create",JSONObject()).getString("sessionId")
            client.command(session,"/permission read-only")
            val chat = CompanionBubbleChat(app,historyStore=CompanionBubbleHistory(storage(tmp)))
            chat.resumeSession(session)
            val phrase = "海岸核对-" + UUID.randomUUID().toString().take(8)
            val message = "明天评审要用两份独立的技术核对笔记「$phrase」。一份查已安装的手机插件怎样保存任务回执，另一份查它怎样限制只读任务的工具权限。各自读取本机插件源码，列出具体文件、机制、失败情况和复现步骤，再给我对照摘要。我还要在这里接着聊别的。"
            val reply = chat.fusedReply(listOf(CompanionBubbleChat.Message("user",message)),CompanionBubbleChatPolicy.memoryDigest("test-scope"),false,false) {}
            val ledger = WorkerDashboardStore(app)
            fun owned() = ledger.list().getJSONArray("items").let { rows -> (0 until rows.length()).map(rows::getJSONObject)
                .filter { it.optString("owner")==session } }
            await("已建立的 Harness 主会话没有自主派工") { owned().isNotEmpty() }
            assertTrue(reply.text.isNotBlank())
            await("主会话派出的任务没有结束",240_000) { owned().all { it.optString("state") == "completed" } }
            inst.sendStatus(0,Bundle().apply { putInt("boundWorkers",owned().size); putString("boundState","completed") })
        } finally {
            io.deepseekharness.mobile.runtime.RuntimeHost.detachPluginSink(sink)
            check(tmp.parentFile == app.cacheDir); tmp.walkBottomUp().forEach { it.delete() }
        }
    }

    @Test fun followupSurvivesSeparatePetProcesses() {
        val args = InstrumentationRegistry.getArguments()
        org.junit.Assume.assumeTrue(args.containsKey("fixtureId"))
        val id = UUID.fromString(args.getString("fixtureId")).toString()
        val dir = File(app.cacheDir,"queue-process-$id")
        val save = args.getString("phase") == "save"
        if (save) check(dir.mkdirs()) else check(args.getString("phase") == "resume" && dir.isDirectory)
        val transcript = CompanionBubbleHistory(storage(dir))
        val second = "七加九是多少？请只回答数字。"
        val marker = File(dir,"first.pid")
        lateinit var window: CompanionBubbleWindow
        fun field(name: String) = CompanionBubbleWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(window)
        fun nodes() = views(field("root") as View).filterIsInstance<TextView>().toList()
        try {
            inst.runOnMainSync {
                window = CompanionBubbleWindow(app, app.getSystemService(WindowManager::class.java), autoOpenOnDelivery=false, historyStore=transcript) { intArrayOf(52,260,128,128) }
                window.show()
                if (save) {
                    val input = nodes().filterIsInstance<EditText>().single()
                    val send = nodes().single { it.text == "发送" }
                    input.setText("用两句话介绍蓝鲸。"); send.performClick()
                    input.setText(second); send.performClick()
                    assertEquals(second,transcript.followups.entries().single().text)
                    val uncertain = transcript.followups.accept("交办状态待核对")
                    transcript.followups.advance(uncertain,"handoff")
                    marker.writeText(android.os.Process.myPid().toString())
                } else {
                    assertNotEquals(marker.readText().toInt(),android.os.Process.myPid())
                    nodes().single { it.text.toString().startsWith("上次退出时的消息 ·") }.performClick()
                    val dialog = field("recoveryDialog") as android.app.AlertDialog
                    assertEquals("继续这条消息",dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text)
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                }
            }
            if (!save) {
                await("恢复的消息没有完成或日志未核销") { transcript.followups.entries().size == 1 &&
                    transcript.load()?.messages?.count { it.role == "user" && it.text == second } == 1 }
                assertTrue(transcript.load()!!.messages.last().text.contains("16"))
                assertEquals("handoff",transcript.followups.entries().single().stage)
                inst.runOnMainSync {
                    nodes().single { it.text.toString().startsWith("上次退出时的消息 ·") }.performClick()
                    val dialog = field("recoveryDialog") as android.app.AlertDialog
                    assertEquals("已核对",dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).text)
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                }
                await("已核对的记录仍在恢复列表") { transcript.followups.entries().isEmpty() }
            }
            inst.sendStatus(0,Bundle().apply { putString("phase",args.getString("phase")); putInt("pid",android.os.Process.myPid()) })
        } finally { inst.runOnMainSync { window.destroy() }; if (!save) dir.deleteRecursively() }
    }

    @Test fun queueFollowupWhileReplyIsInFlight() {
        val tmp = File(app.cacheDir, "pet-inflight-" + UUID.randomUUID()).apply { mkdirs() }
        val transcript = CompanionBubbleHistory(storage(tmp))
        lateinit var window: CompanionBubbleWindow
        val first = "蓝鲸是哺乳动物吗？请简短回答。"
        val second = "它用什么呼吸？"
        try {
            inst.runOnMainSync {
                window = CompanionBubbleWindow(app, app.getSystemService(WindowManager::class.java), autoOpenOnDelivery = false, historyStore = transcript) { intArrayOf(52,260,128,128) }
                window.show()
                val panel = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(window) as View
                val nodes = views(panel).toList()
                val input = nodes.filterIsInstance<EditText>().single()
                val send = nodes.filterIsInstance<TextView>().single { it.text == "发送" }
                input.setText(first); send.performClick()
                assertTrue(send.isEnabled)
                input.setText(second); send.performClick()
                assertEquals("继续发送", send.text.toString())
                input.setText("下一条还没写完")
                window.hide()
                window.show()
                val reopened = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(window) as View
                val shown = views(reopened).filterIsInstance<TextView>().toList()
                assertTrue(shown.any { it.text.toString() == first })
                assertEquals("下一条还没写完", shown.filterIsInstance<EditText>().single().text.toString())
                val status = shown.single { it.contentDescription?.toString()?.endsWith("查看消息状态") == true }
                assertTrue(status.performClick())
                assertTrue(shown.any { it.isShown && it.text.contains(second) })
                assertTrue(shown.single { it.text == "继续发送" }.isEnabled)
            }
            await("第二条消息未按顺序处理", 150_000) {
                transcript.load()?.messages?.filter { it.role == "user" }?.map { it.text } == listOf(first, second)
            }
            inst.runOnMainSync {
                val panel = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }.get(window) as View
                assertEquals("下一条还没写完", views(panel).filterIsInstance<EditText>().single().text.toString())
            }
        } finally {
            inst.runOnMainSync { window.destroy() }
            check(tmp.parentFile == app.cacheDir); tmp.walkBottomUp().forEach { it.delete() }
        }
    }

    @Test fun independentReportIsAcceptedDuringAnotherReply() {
        val token = UUID.randomUUID().toString().take(8)
        val tmp = File(app.cacheDir, "pet-independent-$token").apply { mkdirs() }
        val transcript = CompanionBubbleHistory(storage(tmp))
        val desk = WorkerDashboardStore(app)
        val first = "请直接用中文给我讲一篇完整的长童话，主角是小鲸鱼和海猫，分十五段写，每段都写出场景、对白和细节。"
        val next = "另一件独立的事：明早交接要用「蓝港差异报告 $token」。甲表写周四交付九箱，乙表写周五交付六箱，整理成一份列出差异和待确认事项的交接报告。"
        lateinit var window: CompanionBubbleWindow
        fun work() = desk.list().getJSONArray("items").let { rows -> (0 until rows.length()).map(rows::getJSONObject)
            .filter { it.optString("title").contains(token) || it.optString("scope").contains(token) } }
        try {
            inst.runOnMainSync {
                window = CompanionBubbleWindow(app, app.getSystemService(WindowManager::class.java), autoOpenOnDelivery=false, historyStore=transcript) { intArrayOf(52,260,128,128) }
                window.show()
                val panel = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible=true }.get(window) as View
                val input = views(panel).filterIsInstance<EditText>().single()
                val send = views(panel).filterIsInstance<TextView>().single { it.text == "发送" }
                input.setText(first); send.performClick()
                input.setText(next); send.performClick()
                input.setText("还没发的第三句")
            }
            await("首轮回复期间未见独立工单",120_000) {
                val accepted = work().isNotEmpty() || desk.pending().let { commands -> (0 until commands.length()).any { commands.getJSONObject(it).optString("task").contains(token) } }
                var outstanding = false
                inst.runOnMainSync { outstanding = CompanionBubbleWindow::class.java.getDeclaredField("inFlightUser").apply { isAccessible=true }.get(window) == first }
                check(outstanding || accepted) { "首轮回复已结束，独立工单尚未接取" }
                accepted && outstanding
            }
            await("首轮仍在处理时没有显示后台接取状态", 8000) {
                var seen = false
                inst.runOnMainSync {
                    val panel = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible=true }.get(window) as View
                    seen = views(panel).filterIsInstance<TextView>().any { it.isShown && it.text.contains(token) && it.text.contains("已交给后台处理") } &&
                        views(panel).filterIsInstance<EditText>().single().text.toString() == "还没发的第三句"
                }
                seen
            }
            await("独立交办回执未按对话顺序保存",180_000) {
                val messages = transcript.load()?.messages.orEmpty()
                messages.size == 4 && messages[2].text == next && messages[3].text.contains("已交给后台处理")
            }
            await("后台报告没有完成",180_000) { work().singleOrNull()?.optString("state") == "completed" }
            inst.sendStatus(0, Bundle().apply { putString("overlap", "worker accepted while first reply outstanding; one report completed") })
        } finally {
            inst.runOnMainSync { window.destroy() }
            check(tmp.parentFile == app.cacheDir); tmp.walkBottomUp().forEach { it.delete() }
        }
    }

    @Test fun ordinaryPetMessagesChooseWorkWithoutTheManualComposer() {
        val token = UUID.randomUUID().toString().take(8)
        val tmp = File(app.cacheDir,"pet-conversation-"+token).apply { mkdirs() }
        val transcript = CompanionBubbleHistory(storage(tmp))
        val ledger = WorkerDashboardStore(app)
        val first = "蓝湾交接报告 $token"
        val second = "海星工时卡 $token"
        // A separate transcript prevents test prompts from including the user's past chat.
        lateinit var window: CompanionBubbleWindow
        fun root() = requireNotNull(CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible=true }.get(window) as? View)
        fun send(text: String, nextDraft: String? = null): String {
            val before = transcript.load()?.messages?.size ?: 0
            inst.runOnMainSync {
                val tree = views(root()).toList()
                val input = tree.filterIsInstance<EditText>().single(); assertTrue(input.isEnabled)
                input.setText(text)
                assertTrue(tree.filterIsInstance<TextView>().single { it.text == "发送" }.performClick())
                if (nextDraft != null) { assertTrue(input.isEnabled); input.setText(nextDraft) }
            }
            await("未收到这条普通消息的回复") {
                val snapshot = transcript.load()
                snapshot != null && snapshot.pending == null && snapshot.messages.size > before &&
                    snapshot.messages.takeLast(2).firstOrNull()?.text == text
            }
            var available = false
            await("桌宠输入未恢复",8000) { inst.runOnMainSync { available = views(root()).filterIsInstance<EditText>().single().isEnabled }; available }
            if (nextDraft != null) inst.runOnMainSync { assertEquals(nextDraft, views(root()).filterIsInstance<EditText>().single().text.toString()) }
            return transcript.load()!!.messages.last().text
        }
        fun rows() = ledger.list().getJSONArray("items").let { list ->
            (0 until list.length()).map(list::getJSONObject).filter { it.optString("title").contains(token) || it.optString("text").contains(token) || it.optString("scope").contains(token) || it.optString("task").contains(token) }
        }
        try {
            inst.runOnMainSync {
                window = CompanionBubbleWindow(app, app.getSystemService(WindowManager::class.java), autoOpenOnDelivery=false, historyStore=transcript) { intArrayOf(52,260,128,128) }
                window.show()
            }
            val read = send("明早交接会要用「$first」，手里还缺一份按时间、地点、人手列出差异、影响和待确认事项的报告。A 版是周六十点在东馆集合，两名讲解员；B 版是周日九点在南馆集合，只有一名讲解员。这两版还没对上。", nextDraft = "我还想做一个工具")
            assertTrue("应从普通对话直接接下报告",read.contains("已交给后台"))
            val create = send("每次排班都得手算。我想在手机上有个叫「$second」的小工具，能输入小时数和每天工时，直接看到换算出的工作日。")
            assertTrue("应从普通对话直接接下工具草稿",create.contains("工具草稿"))
            val casual = send("鲸鱼为什么要浮到水面呼吸？")
            assertFalse("普通问答不应交办",casual.contains("已交给后台")); assertTrue(casual.isNotBlank())
            await("没有生成两个独立的工单") { rows().size == 2 }
            val tickets = rows()
            assertEquals(setOf("read","create"),tickets.map { it.getString("mode") }.toSet())
            assertEquals(2,tickets.map { it.getString("id") }.distinct().size)
            await("两个自然语言工单没有产出报告和草稿", 240_000) {
                rows().let { current -> current.size == 2 && current.all { it.optString("state")=="completed" } &&
                    current.single { it.optString("mode")=="create" }.optJSONObject("authoredDraft")!=null }
            }
            inst.sendStatus(0,Bundle().apply {
                putString("automaticRead","ordinary chat -> report completed")
                putString("automaticCreate","ordinary chat -> isolated tool draft completed")
                putString("casualChat","answered with chat still editable")
            })
        } finally {
            inst.runOnMainSync { window.destroy() }
            check(tmp.parentFile == app.cacheDir); tmp.walkBottomUp().forEach { it.delete() }
        }
    }
}

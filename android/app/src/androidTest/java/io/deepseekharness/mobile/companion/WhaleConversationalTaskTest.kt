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
        try {
            val ctx = storage(tmp); val file = CompanionBubbleHistory(ctx)
            val message = "整理我收藏的笔记"
            val req = JSONObject().put("kind","worker").put("requestId",UUID.randomUUID().toString())
                .put("prompt",message).put("user",message).put("needs","library").put("mode","read")
            file.save(CompanionBubbleHistory.Snapshot("pet-direct-"+UUID.randomUUID(),"c".repeat(64),emptyList(),req.toString()))
            val chat = CompanionBubbleChat(ctx,file.load(),file) { _,_,_,_ -> error("must not enqueue") }
            val reply = chat.fusedReply(listOf(CompanionBubbleChat.Message("user",message)),"",false,false) {}
            assertTrue(reply.text.contains("开启对话访问")); assertNull(file.load()!!.pending)
            assertEquals(0,WorkerDashboardStore(ctx).pending().length())
        } finally { check(tmp.parentFile == app.cacheDir); tmp.walkBottomUp().forEach { it.delete() } }
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

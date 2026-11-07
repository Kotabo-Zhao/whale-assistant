package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PetComposerDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext

    private inner class Pet(private val live: Boolean = false) : AutoCloseable {
        private val dir = File(app.cacheDir, "pet-composer-" + UUID.randomUUID()).apply { check(mkdirs()) }
        private val names = ConcurrentHashMap.newKeySet<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(
                if (live && name == "runtime_settings") name else (dir.name + name).also(names::add), mode)
        }
        val store = CompanionBubbleHistory(context)
        lateinit var window: CompanionBubbleWindow
        fun open(timeout: Long = 180_000, receive: ((String,String,String,String,String) -> JSONObject)? = null,
                 reply: (CompanionBubbleChat, List<CompanionBubbleChat.Message>, String, Boolean, Boolean, (String) -> Unit) -> CompanionBubbleChat.Reply) {
            inst.runOnMainSync {
                window = CompanionBubbleWindow(context, app.getSystemService(WindowManager::class.java),
                    autoOpenOnDelivery = false, historyStore = store, replyRunner = reply,
                    classifyWaiting = { null }, replyTimeoutMillis = timeout, receiveTask=receive) { intArrayOf(32, 120, 100, 100) }
                window.show()
            }
            await("输入框没有显示") { input().width > 0 }
        }
        fun labels(): List<TextView> {
            val root = window.javaClass.getDeclaredField("root").apply { isAccessible = true }.get(window) as View
            fun collect(v: View): List<View> = listOf(v) + if (v is ViewGroup)
                (0 until v.childCount).flatMap { collect(v.getChildAt(it)) } else emptyList()
            return collect(root).filterIsInstance<TextView>()
        }
        fun input() = labels().filterIsInstance<EditText>().single()
        fun tap(label: () -> TextView) {
            inst.waitForIdleSync()
            val xy = IntArray(2)
            var previous = Rect()
            var changed = SystemClock.elapsedRealtime()
            await("触控位置没有稳定") {
                val v = label()
                val rect = Rect()
                if (!v.isShown || !v.getGlobalVisibleRect(rect)) return@await false
                v.getLocationOnScreen(xy)
                rect.set(xy[0],xy[1],xy[0]+v.width,xy[1]+v.height)
                if (rect != previous) { previous = rect; changed = SystemClock.elapsedRealtime() }
                xy[0] += v.width / 2
                xy[1] += v.height / 2
                SystemClock.elapsedRealtime() - changed >= 120
            }
            val t = SystemClock.uptimeMillis()
            for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                val event = MotionEvent.obtain(t, SystemClock.uptimeMillis(), action, xy[0].toFloat(), xy[1].toFloat(), 0)
                event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
                try { assertTrue(inst.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
            }
        }
        fun send(value: String) {
            inst.runOnMainSync { input().setText(value) }
            tap { labels().single { it.text == "发送" || it.text == "继续发送" } }
        }
        fun reopen() = inst.runOnMainSync { window.hide(); window.show() }
        override fun close() {
            if (::window.isInitialized) inst.runOnMainSync { window.destroy() }
            names.forEach(app::deleteSharedPreferences)
            dir.deleteRecursively()
        }
    }

    private fun await(label: String, millis: Long = 5000, check: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + millis
        while (true) {
            var ok = false
            inst.runOnMainSync { ok = check() }
            if (ok) return
            assertTrue(label, SystemClock.elapsedRealtime() < end)
            Thread.sleep(40)
        }
    }

    @Test fun imeSendSubmitsCommittedChineseExactlyOnce() {
        Pet().use { pet ->
            val sends = CopyOnWriteArrayList<String>()
            pet.open { chat, h, _, _, _, _ ->
                sends.add(h.last().text)
                chat.finishReply(h,CompanionBubbleChat.Reply("收到", emptyList(), emptyList()))
            }
            inst.runOnMainSync {
                val editor = pet.input()
                editor.requestFocus()
                val info = EditorInfo()
                val connection = requireNotNull(editor.onCreateInputConnection(info))
                assertEquals(EditorInfo.IME_ACTION_SEND,info.imeOptions and EditorInfo.IME_MASK_ACTION)
                assertEquals(0,info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION)
                connection.setComposingText("桌宠", 1)
                connection.commitText("桌宠输入测试", 1)
                connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
                connection.performEditorAction(EditorInfo.IME_ACTION_SEND)
            }
            await("输入法发送没有提交消息") { sends.size == 1 }
            assertEquals(listOf("桌宠输入测试"), sends.toList())
        }
    }

    @Test fun failedHeadDoesNotHoldTwoFollowingMessagesAcrossReopen() {
        val release = CountDownLatch(1)
        try {
            Pet().use { pet ->
                val seen = CopyOnWriteArrayList<String>()
                pet.open { chat, h, _, _, _, _ ->
                    val message = h.last().text
                    seen.add(message)
                    if (message == "第一条") {
                        check(release.await(15, TimeUnit.SECONDS))
                        error("模型服务暂时不可用")
                    }
                    if (message == "第二条") error("模型连接中断")
                    chat.finishReply(h,CompanionBubbleChat.Reply("收到第三条", emptyList(), emptyList()))
                }
                pet.send("第一条")
                await("首条没有提交") { seen.size == 1 }
                pet.send("第二条")
                pet.send("第三条")
                await("连续两次点发送没有接收两条消息") { pet.store.followups.entries().size == 2 }
                assertEquals(listOf("第二条", "第三条"), pet.store.followups.entries().map { it.text })
                pet.reopen()
                release.countDown()
                await("失败的队首堵住后续消息") { seen.size >= 3 }
                assertEquals(listOf("第一条", "第二条", "第三条"), seen.toList())
                await("第三条回复没有显示") { pet.labels().any { it.isShown && it.text.toString() == "收到第三条" } }
                assertEquals(setOf("第一条", "第二条"), pet.store.followups.entries().map { it.text }.toSet())
                assertEquals(listOf("第三条","收到第三条"),pet.store.load()?.messages?.map { it.text })
            }
        } finally { release.countDown() }
    }

    @Test fun failureIsActionableWithSessionMenuClosed() {
        Pet().use { pet ->
            pet.open { chat,h,_,_,_,_ ->
                if (h.last().text=="离线消息") error("模型服务连接失败")
                chat.finishReply(h,CompanionBubbleChat.Reply("已恢复",emptyList(),emptyList()))
            }
            pet.send("离线消息")
            await("重试入口被折叠") { pet.labels().any { it.isShown && it.text.contains("点此重试") } }
            pet.reopen()
            await("重开后没有显示重试入口") { pet.labels().any { it.isShown && it.text.contains("点此重试") } }
            pet.send("单独发送下一条")
            await("之前失败的请求挡住下一次点发送") { pet.store.load()?.messages?.lastOrNull()?.text == "已恢复" }
            assertEquals("离线消息",pet.store.followups.entries().single().text)
        }
    }

    private fun seedHost(pet: Pet): Pair<String,String> {
        val session = "session-" + UUID.randomUUID()
        val id = UUID.randomUUID().toString()
        val pending = JSONObject().put("requestId",id).put("user","原请求").put("prompt","原请求")
        pet.store.save(CompanionBubbleHistory.Snapshot(session,"a".repeat(64),emptyList(),pending.toString()),true)
        return session to id
    }

    @Test fun confirmedHostFailureRetriesOnlyByUserWithFreshId() = checkHostRetry(true)
    @Test fun uncertainHostFailureRetriesOnlyByUserWithOriginalId() = checkHostRetry(false)

    private fun checkHostRetry(terminal: Boolean) {
        Pet().use { pet ->
            val (_,id) = seedHost(pet)
            val ids = CopyOnWriteArrayList<String>()
            pet.open { chat,h,_,_,_,_ ->
                ids.add(JSONObject(requireNotNull(chat.snapshot(emptyList())?.pending)).getString("requestId"))
                if (ids.size == 1) {
                    if (terminal) throw BubbleTurnFailed()
                    error("本机对话连接已关闭")
                }
                chat.finishReply(h,CompanionBubbleChat.Reply("重试完成",emptyList(),emptyList()))
            }
            await("失败没有显示") { pet.labels().any { it.isShown && it.text.contains("点此重试") } }
            pet.reopen()
            inst.waitForIdleSync()
            assertEquals(listOf(id),ids.toList())
            inst.runOnMainSync { pet.input().setText("未发出的草稿") }
            pet.tap { pet.labels().single { it.isShown && it.text.contains("点此重试") } }
            await("重试未完成") { pet.store.load()?.messages?.lastOrNull()?.text == "重试完成" }
            assertEquals(2,ids.size)
            assertEquals(!terminal,ids[0] == ids[1])
            inst.runOnMainSync { assertEquals("未发出的草稿",pet.input().text.toString()) }
        }
    }

    @Test fun uninterruptibleHostDoesNotOwnNextTurnsOrOverwriteThemWhenItReturns() {
        val release = CountDownLatch(1)
        val staleFinished = CountDownLatch(1)
        try {
            Pet().use { pet ->
                val (oldSession, oldId) = seedHost(pet)
                val seen = CopyOnWriteArrayList<String>()
                pet.open(timeout=3500) { chat,h,_,_,_,_ ->
                    val text = h.last().text
                    seen.add(text)
                    if (text == "原请求") {
                        try {
                            while (release.count > 0) {
                                try { release.await() } catch (_: InterruptedException) { /* emulate a native call */ }
                            }
                            chat.finishReply(h,CompanionBubbleChat.Reply("迟到回复",emptyList(),emptyList()))
                        } finally { staleFinished.countDown() }
                    } else {
                        assertNotEquals(oldSession,chat.handoffSessionId())
                        chat.finishReply(h,CompanionBubbleChat.Reply("收到" + text,emptyList(),emptyList()))
                    }
                }
                await("没有恢复原请求") { seen.isNotEmpty() }
                pet.send("后续一"); pet.send("后续二")
                pet.reopen()
                await("超时后后续两条没有完成",10000) { pet.store.load()?.messages?.size == 4 }
                assertEquals(listOf("原请求","后续一","后续二"),seen.toList())
                val entry = pet.store.followups.entries().single()
                assertEquals("host",entry.stage)
                assertEquals(oldId,entry.plan?.getString("requestId"))
                assertEquals(oldSession,entry.plan?.getString("sourceSession"))
                assertEquals(oldSession,pet.store.load()?.topicId)
                release.countDown()
                assertTrue(staleFinished.await(3,TimeUnit.SECONDS))
                assertEquals(listOf("后续一","收到后续一","后续二","收到后续二"),pet.store.load()?.messages?.map { it.text })
            }
        } finally { release.countDown() }
    }

    @Test fun restoredQueueResumesOnlyEntriesAcceptedForThisTopic() {
        Pet().use { pet ->
            val topic = "pet-direct-" + UUID.randomUUID()
            val pending = JSONObject().put("kind","direct").put("stopped",true)
                .put("requestId",UUID.randomUUID().toString()).put("user","失败消息").put("prompt","失败消息")
            pet.store.save(CompanionBubbleHistory.Snapshot(topic,"b".repeat(64),emptyList(),pending.toString()),true)
            pet.store.followups.accept("旧话题","","other-topic")
            pet.store.followups.accept("早期无归属")
            pet.store.followups.accept("后续一","",topic)
            pet.store.followups.accept("后续二","",topic)
            val seen=CopyOnWriteArrayList<String>()
            pet.open { chat,h,_,_,_,_ ->
                seen.add(h.last().text)
                chat.finishReply(h,CompanionBubbleChat.Reply("完成",emptyList(),emptyList()))
            }
            await("重建窗口后没有恢复队列") { pet.store.load()?.messages?.size == 4 }
            assertEquals(listOf("后续一","后续二"),seen.toList())
            assertEquals(setOf("失败消息","旧话题","早期无归属"),pet.store.followups.entries().map { it.text }.toSet())
        }
    }

    @Test fun interruptedWorkerRecoveryKeepsTheOriginalCommandAndResolvedTask() {
        Pet().use { pet ->
            val id=UUID.randomUUID().toString()
            val task="保留前文已经解析出的材料"
            val pending=JSONObject().put("kind","worker").put("requestId",id).put("mode","read")
                .put("needs","none").put("title","测试工单").put("user","整理材料").put("prompt",task)
            val snapshot=CompanionBubbleHistory.Snapshot("pet-direct-"+UUID.randomUUID(),"a".repeat(64),emptyList(),pending.toString())
            pet.store.save(snapshot,true)
            pet.store.followups.park(snapshot) // Process stopped between the journal write and clearing pending.
            val calls=CopyOnWriteArrayList<Pair<String,String>>()
            pet.open(receive={ text,_,command,_,_ ->
                calls.add(command to text)
                JSONObject().put("commandId",command)
            }) { _,_,_,_,_,_ -> error("不能把未确认的工单当成新消息发送") }
            assertNull(pet.store.load()?.pending)
            pet.tap { pet.labels().single { it.isShown && it.text.contains("待处理消息 · 1") } }
            inst.runOnMainSync {
                val dialog=pet.window.javaClass.getDeclaredField("recoveryDialog").apply { isAccessible=true }.get(pet.window) as android.app.AlertDialog
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            }
            await("原工单回执没有保存") { pet.store.followups.entries().single().stage=="handoff" }
            pet.reopen()
            assertEquals(listOf(id to task),calls.toList())
        }
    }

    @Test fun realModelRepliesFromOverlayAndCompletionSurvivesReopen() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveComposer") == "yes")
        Pet(live = true).use { pet ->
            pet.open { chat, h, scope, memory, knowledge, partial -> chat.fusedReply(h, scope, memory, knowledge, partial) }
            val start = SystemClock.elapsedRealtime()
            pet.send("请仅用数字回答，37 加 19 等于多少？")
            await("未收到真实模型的完整回复", 180_000) {
                val error = pet.labels().firstOrNull { it.isShown && it.text.contains("点此重试") }
                assertNull("真实请求失败：${error?.text}",error)
                pet.store.load()?.messages?.lastOrNull()?.text?.contains("56") == true
            }
            pet.reopen()
            await("已完成回复未恢复") { pet.labels().any { it.isShown && it.text.contains("56") } }
            val saved = requireNotNull(pet.store.load())
            assertNull(saved.pending)
            assertEquals(listOf("user", "assistant"), saved.messages.map { it.role })
            inst.sendStatus(0, android.os.Bundle().apply { putLong("realOverlayReplyMs", SystemClock.elapsedRealtime() - start) })
        }
    }
}

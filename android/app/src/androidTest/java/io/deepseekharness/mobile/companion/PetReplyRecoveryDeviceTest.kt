package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PetReplyRecoveryDeviceTest {
    @Test fun stalledBackendCanBeDetachedAndNewTopicAcceptsAnotherMessage() = recovery(false)
    @Test fun endingStalledWaitDrainsQueuedFollowupInSameTopic() = recovery(true)

    private fun recovery(withQueuedMessage: Boolean) {
        val inst=InstrumentationRegistry.getInstrumentation(); val app=inst.targetContext
        val key="pet-recovery-"+java.util.UUID.randomUUID()
        val directory=File(app.cacheDir,key).apply { check(mkdirs()) }
        val preferences=mutableSetOf<String>()
        val ctx=object: ContextWrapper(app) {
            override fun getApplicationContext(): Context=this
            override fun getNoBackupFilesDir()=directory
            override fun getSharedPreferences(name: String,mode: Int)=app.getSharedPreferences(key+name,mode).also { preferences.add(key+name) }
        }
        val server=ServerSocket(0,1,java.net.InetAddress.getByName("127.0.0.1"))
        val accepted=CountDownLatch(1); val release=CountDownLatch(1)
        val peer=Thread { runCatching { server.accept().use { accepted.countDown(); release.await(20,TimeUnit.SECONDS) } } }.apply { isDaemon=true; start() }
        val calls=AtomicInteger()
        var created=false
        lateinit var window: CompanionBubbleWindow
        fun panel()=CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible=true }.get(window) as LinearLayout
        fun busy()=CompanionBubbleWindow::class.java.getDeclaredField("busy").apply { isAccessible=true }.getBoolean(window)
        fun all(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
        fun nodes()=all(panel()).filterIsInstance<TextView>()
        fun until(check: () -> Boolean) {
            val end=android.os.SystemClock.elapsedRealtime()+5000
            while(true) {
                var ready=false; inst.runOnMainSync { ready=check() }
                if(ready)return
                assertTrue("桌宠恢复超时",android.os.SystemClock.elapsedRealtime()<end); Thread.sleep(40)
            }
        }
        try {
            inst.runOnMainSync {
                window=CompanionBubbleWindow(ctx,app.getSystemService(WindowManager::class.java),autoOpenOnDelivery=false,
                    classifyWaiting={ null }, replyRunner={ chat,h,_,_,_,_ ->
                        if(calls.incrementAndGet()==1) PetModelHttp.execute(okhttp3.Request.Builder().url("http://127.0.0.1:${server.localPort}/").build(),60_000) { it.readText() }
                        chat.finishReply(h,CompanionBubbleChat.Reply("恢复后回复",emptyList(),emptyList()))
                    }) { intArrayOf(36,140,120,120) }
                created=true
                window.show()
                nodes().filterIsInstance<EditText>().single().setText("不会响应的测试请求")
                nodes().single { it.text=="发送" }.performClick()
            }
            assertTrue(accepted.await(5,TimeUnit.SECONDS))
            if (withQueuedMessage) {
                inst.runOnMainSync {
                    nodes().filterIsInstance<EditText>().single().setText("随后补充的消息")
                    nodes().single { it.text=="继续发送" }.performClick()
                }
                until { nodes().any { it.text.toString().contains("等待对话 1") } }
            }
            inst.runOnMainSync {
                assertTrue(busy()); assertTrue(nodes().filterIsInstance<EditText>().single().isEnabled)
                window.hide(); window.show()
                assertTrue(nodes().single { it.text=="结束等待" }.isShown)
                nodes().single { it.text=="结束等待" }.performClick()
            }
            until { !busy() && (!withQueuedMessage || calls.get()==2) }
            val store=CompanionBubbleHistory(ctx)
            if (withQueuedMessage) {
                assertEquals("不会响应的测试请求",store.followups.entries().single().text)
                assertEquals(listOf("随后补充的消息","恢复后回复"),store.load()?.messages?.map { it.text })
                assertNull(store.load()?.pending)
            } else assertTrue(store.load()?.pending.orEmpty().contains("不会响应的测试请求"))
            inst.runOnMainSync {
                nodes().single { it.contentDescription=="会话选项" }.performClick()
                val next=nodes().single { it.text=="新话题" }; assertTrue(next.isEnabled); next.performClick()
            }
            until { !busy() && nodes().filterIsInstance<EditText>().single().text.isEmpty() }
            if (withQueuedMessage) {
                assertEquals("不会响应的测试请求",store.followups.entries().single().text)
                inst.runOnMainSync { assertTrue(nodes().any { it.text.toString().contains("待处理消息 · 1") }) }
                assertEquals(2,calls.get()) // Switching topics must not replay the parked request.
            }
            inst.runOnMainSync {
                nodes().filterIsInstance<EditText>().single().setText("恢复后的新消息")
                nodes().single { it.text=="发送" }.performClick()
            }
            until { calls.get()==(if (withQueuedMessage) 3 else 2) && !busy() }
        } finally {
            if(created) inst.runOnMainSync { window.destroy() }
            release.countDown(); server.close(); peer.join(1000)
            preferences.forEach(app::deleteSharedPreferences); directory.deleteRecursively()
        }
    }
}

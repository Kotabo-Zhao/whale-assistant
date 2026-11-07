package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class WorkerBriefingConversationDeviceTest {
    @Test fun oldReadReportsAndFirstDeliveryInADraftKeepTheirOwnership() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "briefing-ledger-${UUID.randomUUID()}-"
        val ctx = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name,mode)
        }
        val inbox = WorkerReportInbox(ctx)
        fun add(owner: String, key: Char) = inbox.addCoordination(JSONObject().put("owner",owner)
            .put("summary","测试$key").put("next","").put("fingerprint",key.toString().repeat(64)))
        try {
            add("session-owner-a",'a'); add("session-owner-b",'b'); add("session-whale-task-desk",'c')
            inbox.markSeen(inbox.unseen().map { it.getString("id") })
            val old = inbox.forConversation("session-owner-a")
            assertEquals(listOf("测试a"),old.rows.map { it.getString("text") })
            assertEquals(3,inbox.forConversation("session-owner-a",recent=true).rows.size)
            inbox.displayed(old,old.localIds)
            inbox.startTopic(null)
            add("session-whale-task-desk",'d')
            val draft = inbox.forConversation(null)
            assertEquals(listOf("测试d"),draft.rows.map { it.getString("text") })
            inbox.displayed(draft,draft.localIds)
            val direct = WorkerReportInbox(ctx).forConversation("pet-direct-fixture")
            assertEquals(draft.localIds,direct.localIds)
            inbox.startTopic(null)
            assertTrue(inbox.forConversation(null).rows.isEmpty())
            assertEquals(direct.localIds,inbox.forConversation("pet-direct-fixture").localIds)
            assertEquals(old.localIds,inbox.forConversation("session-owner-a").localIds)
            assertTrue(inbox.unseen().isEmpty())
        } finally { base.deleteSharedPreferences(prefix + "worker_report_inbox") }
    }

    @Test fun aReadWorkerReplySurvivesClosingAndRecreatingItsConversation() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val base = inst.targetContext
        val prefix = "briefing-window-${UUID.randomUUID()}-"
        val directory = File(base.noBackupFilesDir,prefix).apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = directory
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(prefix + name,mode)
        }
        val owner = "session-fixture-pet"
        val result = "测试工单的最终结果已经返回"
        val first = "已接下测试工单"
        val inbox = WorkerReportInbox(context)
        val history = CompanionBubbleHistory(context)
        history.save(CompanionBubbleHistory.Snapshot(owner,"a".repeat(64),listOf(
            CompanionBubbleChat.Message("user","请交办测试工单"),CompanionBubbleChat.Message("assistant",first))))
        val summary = JSONObject().put("owner",owner).put("fingerprint","1".repeat(64)).put("summary",result).put("next","")
        var replySignals = 0
        fun window() = CompanionBubbleWindow(context,base.getSystemService(WindowManager::class.java),
            onState={ if (it == "reply") replySignals++ },autoOpenOnDelivery=false) { intArrayOf(40,120,80,80) }
        lateinit var pet: CompanionBubbleWindow
        fun labels(): List<String> {
            fun read(v: View): List<String> = if (v is TextView) listOf(v.text.toString()) else if (v is ViewGroup)
                (0 until v.childCount).flatMap { read(v.getChildAt(it)) } else emptyList()
            var items = emptyList<String>()
            inst.runOnMainSync { (PetControlDeviceUi.field(pet,"root") as? View)?.let { items = read(it) } }
            return items
        }
        try {
            inst.runOnMainSync { pet = window(); pet.show() }
            inbox.addCoordination(summary)
            PetControlDeviceUi.await("live briefing appears once") { labels().count { it == result } == 1 }
            assertTrue(inbox.unseen().isEmpty())
            inst.runOnMainSync { pet.hide(); pet.show() }
            assertEquals("reopening the window lost the read result",1,labels().count { it == result })
            val followup = "结果里的第二项确认完成"
            val saved = history.load()!!
            history.save(saved.copy(messages=saved.messages + listOf(CompanionBubbleChat.Message("user",followup),
                CompanionBubbleChat.Message("assistant","明白，不会重复交办"))))
            inst.runOnMainSync { pet.destroy(); pet = window(); pet.show() }
            assertEquals("recreating the service window lost the result",1,labels().count { it == result })
            inbox.addCoordination(summary)
            inst.waitForIdleSync()
            assertEquals(1,labels().count { it == result })
            assertEquals("restoring or retrying a read report must not signal a new reply",1,replySignals)
            assertTrue(labels().indexOf(first) in 0 until labels().indexOf(result))
            assertTrue("restored report moved below a later reply",labels().indexOf(result) in 0 until labels().indexOf(followup))
            assertEquals(4,history.load()!!.messages.size)
            inst.runOnMainSync { assertTrue(pet.resumeSession("session-fixture-other")) }
            PetControlDeviceUi.await("second topic opens") { labels().any { it.contains("已接续主对话会话") } }
            assertFalse(result in labels())
            inst.runOnMainSync { assertTrue(pet.resumeSession(owner)) }
            PetControlDeviceUi.await("resumed owner restores its briefing") { labels().count { it == result } == 1 }
            // A queued live event and a simultaneous rebuild both see the item. Only one bubble is allowed.
            val concurrent = "同时到达的转述"
            inst.runOnMainSync {
                inbox.addCoordination(JSONObject(summary.toString()).put("fingerprint","2".repeat(64)).put("summary",concurrent))
                pet.hide(); pet.show()
            }
            inst.waitForIdleSync()
            assertEquals(1,labels().count { it == concurrent })
        } finally {
            inst.runOnMainSync { pet.destroy() }
            base.deleteSharedPreferences(prefix + "worker_report_inbox")
            directory.deleteRecursively()
        }
    }
}

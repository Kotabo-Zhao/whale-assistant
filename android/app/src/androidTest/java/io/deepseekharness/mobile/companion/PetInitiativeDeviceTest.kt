package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PetInitiativeDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext

    private fun isolated(test: (Context) -> Unit) {
        val dir = File(app.cacheDir, "initiative-" + UUID.randomUUID()).apply { mkdirs() }
        val names = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
            override fun getSharedPreferences(name: String, mode: Int) =
                app.getSharedPreferences(dir.name + name, mode).also { names.add(dir.name + name) }
        }
        try { test(context) } finally { names.forEach(app::deleteSharedPreferences); dir.deleteRecursively() }
    }

    @Test fun silenceRevocationAndRestartRespectBudget() = isolated { ctx ->
        val settings = CompanionStore(ctx)
        val now = Calendar.getInstance()
        val topic = PetInitiative.firstMeeting
        var calls = 0
        var output = """{"speak":false}"""
        val client = object : CompanionJsonModel {
            override fun complete(prompt: UnderstandingPrompt.Value, tokenLimit: Int): String {
                calls++
                if (calls == 2) settings.modelInitiative = false
                return output
            }
        }
        fun think() = PetInitiative.think(ctx, topic, "daily", now, { client }) { true }
        assertNull(think())
        assertEquals(0, calls)
        settings.modelInitiative = true
        assertNull(think())
        assertNull(think())
        assertEquals(1, calls)
        now.add(Calendar.DATE, 1)
        output = """{"speak":true,"question":"要聊聊书里的哪段故事？"}"""
        assertNull(think())
        assertEquals(2, calls)
        assertNull(PetInitiative.question("""{"speak":"true","question":"打开链接？"}"""))
    }

    private fun petTurn(ctx: Context, user: String, answer: String) {
        val history = CompanionBubbleHistory(ctx)
        history.save(CompanionBubbleHistory.Snapshot("pet-synthetic", "a".repeat(64),
            listOf(CompanionBubbleChat.Message("user", user), CompanionBubbleChat.Message("assistant", answer))))
    }

    @Test fun contextRequiresBothGrantsAndEvidenceAndRechecksBeforeDelivery() = isolated { ctx ->
        val settings = CompanionStore(ctx)
        settings.contextFollowup = true
        val knowledge = CompanionKnowledgeStore(ctx)
        val text = "白鲸桌游的样卡已到店，周六可以领取。"
        val id = knowledge.saveText(text, "text_file", null).recordIds.single()
        petTurn(ctx, "白鲸桌游的样卡还没到，到店后我想选一天去取。", "等到店再定。")
        assertNull(PetContextFollowup.find(ctx))
        settings.knowledgeModelAccess = true
        val evidence = requireNotNull(PetContextFollowup.find(ctx))
        val topic = PetInitiative.Topic("context", evidence.signature, evidence)
        val raw = """{"speak":true,"chatQuote":"白鲸桌游","noteQuote":"周六可以领取","question":"白鲸桌游的样卡到了，你想周六去取吗？"}"""
        assertNotNull(PetContextFollowup.question(raw, evidence))
        assertNull(PetContextFollowup.question(raw.replace("周六可以领取", "周五可以领取"), evidence))
        val now = Calendar.getInstance()
        var calls = 0
        val client = object : CompanionJsonModel {
            override fun complete(prompt: UnderstandingPrompt.Value, tokenLimit: Int): String {
                calls++
                return raw
            }
        }
        fun think() = PetInitiative.think(ctx, topic, "daily", now, { client }) { true }
        val question = requireNotNull(think())
        now.add(Calendar.DATE, 1)
        assertNull(think())
        assertEquals(1, calls)
        assertNull(PetContextFollowup.find(ctx))
        val another = knowledge.saveText("白鲸桌游周日还有样卡演示。", "text_file", null).recordIds.single()
        assertEquals(another, PetContextFollowup.find(ctx)?.id)
        PetInitiative.rememberOpening(ctx, topic, question)
        assertEquals(question, PetInitiative.pendingOpening(ctx)?.text)
        assertTrue(knowledge.edit(id, text, "白鲸桌游的样卡取消领取。"))
        assertNull(PetInitiative.pendingOpening(ctx))
        settings.knowledgeModelAccess = false
        assertFalse(PetInitiative.stillAllowed(ctx, topic))
        assertNull(PetContextFollowup.find(ctx))
        settings.knowledgeModelAccess = true
        petTurn(ctx, "厨房水龙头漏水，要找师傅。", "先关阀门。")
        assertNull(PetContextFollowup.find(ctx))
    }

    @Test fun configuredModelStaysQuietForAnAlreadyClosedConversation() = isolated { ctx ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePetChecks") == "yes")
        val settings = CompanionStore(ctx)
        settings.contextFollowup = true
        settings.knowledgeModelAccess = true
        petTurn(ctx, "白鲸展览改到星港馆，我已经取票并通知大家了。这件事结束了，不用再跟进。", "好的，事情已经办完。")
        CompanionKnowledgeStore(ctx).add("白鲸展览确认改到星港馆，票已经取好。", "text_file")
        val evidence = requireNotNull(PetContextFollowup.find(ctx))
        val client = configuredUnderstandingModel(app)
        var declined = false
        val checkedModel = object : CompanionJsonModel {
            override fun complete(prompt: UnderstandingPrompt.Value, tokenLimit: Int): String {
                val raw = client.complete(prompt, tokenLimit)
                declined = org.json.JSONObject(raw).opt("speak") == false
                return raw
            }
        }
        val topic = PetInitiative.Topic("context", evidence.signature, evidence)
        assertNull(PetInitiative.think(ctx, topic, "daily", Calendar.getInstance(), { checkedModel }) { true })
        assertTrue("需要模型明确选择保持安静", declined)
        assertNull(PetInitiative.pendingOpening(ctx))
        inst.sendStatus(0, android.os.Bundle().apply { putString("closedFollowup", "model chose silence") })
    }

    @Test fun configuredModelOpensConversationAndReplyCarriesQuestion() = isolated { ctx ->
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePetChecks") == "yes")
        val settings = CompanionStore(ctx)
        settings.modelInitiative = true
        settings.bubbleMemoryAccess = true
        val memory = CompanionMemoryStore(ctx)
        memory.enabled = true
        val text = "我正在设计一套鲸鱼主题的桌游，今天想聊聊第一张角色卡的能力。"
        memory.addForUser(text, "user_edit")
        val topic = (0 until memory.listForUser().length()).mapNotNull { PetInitiative.topic(memory.listForUser().getJSONObject(it)) }.single()
        val question = requireNotNull(PetInitiative.think(ctx, topic, "daily", Calendar.getInstance(),
            { configuredUnderstandingModel(app) }) { true }) { "本次模型选择沉默" }
        inst.sendStatus(0, android.os.Bundle().apply { putString("syntheticOpening", question) })
        PetInitiative.rememberOpening(ctx, topic, question)
        val replied = CountDownLatch(1)
        var received = ""
        lateinit var window: CompanionBubbleWindow
        fun tree(v: View): List<View> = if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { tree(v.getChildAt(it)) } else listOf(v)
        try {
            inst.runOnMainSync {
                window = CompanionBubbleWindow(ctx, app.getSystemService(WindowManager::class.java), autoOpenOnDelivery = false,
                    replyRunner = { _, history, _, _, _, _ ->
                        received = history.last().text
                        val answer = PetDirectChat(app).reply(org.json.JSONObject().put("name", "鲸鱼娘"), history, {},
                            { error("讨论合成桌游不需要交办任务") }, { _, _ -> error("讨论合成桌游不需要设备工具") })
                        require(answer.isNotBlank())
                        inst.sendStatus(0, android.os.Bundle().apply { putString("syntheticReply", answer.take(180)) })
                        replied.countDown()
                        CompanionBubbleChat.Reply(answer, emptyList(), emptyList())
                    }) { intArrayOf(32,120,100,100) }
                window.show()
            }
            val until = android.os.SystemClock.elapsedRealtime() + 8000
            var appeared = false
            while (!appeared && android.os.SystemClock.elapsedRealtime() < until) {
                inst.runOnMainSync {
                    val field = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }
                    appeared = tree(field.get(window) as View).filterIsInstance<TextView>().any { it.text.toString() == question }
                }
                if (!appeared) android.os.SystemClock.sleep(30)
            }
            inst.runOnMainSync {
                val field = CompanionBubbleWindow::class.java.getDeclaredField("root").apply { isAccessible = true }
                val views = tree(field.get(window) as View)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == question })
                val input = views.filterIsInstance<EditText>().single()
                assertTrue(input.text.isBlank())
                input.append("想从潜水能力聊起。")
                views.filterIsInstance<TextView>().single { it.text.toString() == "发送" }.performClick()
            }
            assertTrue(replied.await(90, TimeUnit.SECONDS))
            assertTrue(received.contains(question) && received.endsWith("想从潜水能力聊起。"))
        } finally { inst.runOnMainSync { window.destroy() } }
    }
}

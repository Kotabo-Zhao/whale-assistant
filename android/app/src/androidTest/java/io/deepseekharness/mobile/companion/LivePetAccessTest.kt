package io.deepseekharness.mobile.companion

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.RuntimeEventSink
import io.deepseekharness.mobile.runtime.RuntimeHost
import io.deepseekharness.mobile.runtime.RuntimeStateSnapshot
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

internal fun withLivePetRuntime(context: Context, block: (HarnessBubbleClient) -> Unit) {
    val sink = object : RuntimeEventSink {
        override fun onProgress(snapshot: RuntimeStateSnapshot) = Unit
        override fun onTerminalOutput(sessionId: String, dataBase64: String, suppressPublicOutput: Boolean) = Unit
        override fun onTerminalExit(sessionId: String, exitCode: Int) = Unit
    }
    val controller = RuntimeHost.acquire(context,sink)
    try {
        if (runCatching { controller.openHarnessAccess() }.isFailure) {
            RuntimeHost.ensureDeviceBridge(controller)
            RuntimeHost.startHarness(controller)
        }
        block(HarnessBubbleClient(controller.openHarnessAccess()))
    } finally { RuntimeHost.detachPluginSink(sink) }
}

class LivePetAccessTest {
    @Test fun petSavesANoteAndMainChatEditsTheSameRecord() {
        val inst = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePetChecks") == "yes")
        val context = inst.targetContext
        val marker = "鲸鱼笔记验收" + UUID.randomUUID().toString().take(10)
        val firstText = "$marker 今天核对蓝色封面"
        val editedText = "$marker 明天核对绿色封面"
        val library = CompanionKnowledgeStore(context)
        val settings = CompanionStore(context)
        val hadWrite = settings.knowledgeModelWrite
        val hadRead = settings.knowledgeModelAccess
        val dir = java.io.File(context.cacheDir, marker).apply { check(mkdir()) }
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = dir
        }
        fun fixtures() = library.search(marker).let { rows -> (0 until rows.length()).map(rows::getJSONObject) }
        try {
            settings.knowledgeModelWrite = true
            withLivePetRuntime(context) { host ->
                val chat = CompanionBubbleChat(context, historyStore = CompanionBubbleHistory(isolated))
                val prompt = "帮我存一条文字到资料库，正文原样保留引号里的文字：「$firstText」。"
                val scope = CompanionBubbleChatPolicy.toolScope(false, hadRead, false, false)
                val saved = PetReplyOperation(120_000).run {
                    chat.fusedReply(listOf(CompanionBubbleChat.Message("user", prompt)), scope, false, hadRead) {}
                }
                assertTrue("桌宠缺少写入回执，工具 ${saved.tools}", "mobile_knowledge_save" in saved.tools)
                val row = fixtures().single()
                assertEquals(firstText, row.getString("text"))
                val id = row.getString("id")
                if (!hadRead) settings.knowledgeModelAccess = true
                val session = host.call("session/create", org.json.JSONObject()).getString("sessionId")
                val requestId = UUID.randomUUID().toString()
                val edit = "请只读取资料库中 ID 为 $id 的这条文字笔记，把正文改为「$editedText」。保留同一条记录。"
                val result = host.followReplyWithImages(session, requestId) {
                    host.call("session/prompt", org.json.JSONObject().put("sessionId", session).put("requestId", requestId)
                        .put("mode", "queue").put("content", org.json.JSONArray().put(org.json.JSONObject().put("type", "text").put("text", edit))))
                }
                assertTrue("主对话缺少读取和修改回执，工具 ${result.tools}", result.tools.containsAll(setOf("mobile_knowledge_read", "mobile_knowledge_update")))
                assertEquals(editedText, library.findById(id)!!.getString("text"))
                assertEquals(1, fixtures().size)
                inst.sendStatus(0, android.os.Bundle().apply { putString("liveKnowledge", "pet saved exact text; main chat read and edited same id") })
            }
        } finally {
            try {
                fixtures().forEach { check(library.remove(it.getString("id"), it.getString("text"))) }
            } finally {
                if (!hadRead && settings.knowledgeModelAccess) settings.knowledgeModelAccess = false
                settings.knowledgeModelWrite = hadWrite
                dir.deleteRecursively()
            }
        }
    }

    @Test fun grantedPetWritesAndFreshPetReadsSameLocalFact() = verifyMemory(writeViaAgent = true)

    @Test fun grantedPetReadsUserConfirmedFactWithExistingSavePolicy() = verifyMemory(writeViaAgent = false)

    @Test fun requestedMemoryRoundTripWhileAutomaticSourcesPaused() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePetChecks") == "yes")
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("companion_private_v1", Context.MODE_PRIVATE)
        val key = "automatic_memory_suppressed_all"
        val existed = prefs.contains(key)
        val paused = prefs.getBoolean(key, false)
        try {
            check(prefs.edit().putBoolean(key, true).commit())
            verifyMemory(writeViaAgent = true, checkDeleted = true)
            assertTrue(prefs.getBoolean(key, false))
        } finally {
            val restore = prefs.edit()
            if (existed) restore.putBoolean(key, paused) else restore.remove(key)
            check(restore.commit())
        }
    }

    private fun verifyMemory(writeViaAgent: Boolean, checkDeleted: Boolean = false) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePetChecks")=="yes")
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val settings = CompanionStore(context)
        val memory = CompanionMemoryStore(context)
        val wasEnabled = memory.enabled
        val wasGranted = settings.bubbleMemoryAccess
        val key = "鲸鱼验收"+UUID.randomUUID().toString().take(8)
        val value = listOf("松塔","青梅","风铃","木舟","雨滴","贝壳","山茶","麦穗").shuffled().take(4).joinToString("与")
        val fact = key+"的图案是"+value
        fun fixtureRows() = memory.listForUser().let { rows ->
            (0 until rows.length()).map(rows::getJSONObject).filter { it.optString("text").contains(key) }
        }
        try {
            if (!wasEnabled) memory.enabled = true
            settings.bubbleMemoryAccess = true
            withLivePetRuntime(context) { host ->
                var lastSession = ""
                fun askInFreshPet(prompt: String): CompanionBubbleChat.Reply {
                    val dir = java.io.File(context.cacheDir,"live-pet-"+UUID.randomUUID()).apply { check(mkdir()) }
                    val isolated = object : android.content.ContextWrapper(context) {
                        override fun getApplicationContext(): Context = this
                        override fun getNoBackupFilesDir() = dir
                    }
                    val chat = CompanionBubbleChat(context,historyStore=CompanionBubbleHistory(isolated))
                    val memoryAllowed = settings.bubbleMemoryAccess && memory.enabled
                    val scope = CompanionBubbleChatPolicy.toolScope(memoryAllowed,false,
                        memoryAllowed && SemanticControl(context).memoryEnabled,false)
                    try {
                        return PetReplyOperation(100_000).run {
                            chat.fusedReply(listOf(CompanionBubbleChat.Message("user",prompt)),scope,memoryAllowed,false) { }
                        }.also {
                            lastSession = checkNotNull(chat.handoffSessionId())
                            check(BubbleSessionRegistry(context).contains(lastSession))
                        }
                    } finally { dir.deleteRecursively() }
                }
                if (writeViaAgent) {
                    val saved = askInFreshPet("请帮我把这句话原样记进长期记忆：「"+fact+"」。")
                    assertTrue("模型回合缺少成功的记忆保存回执", "mobile_memory_save" in saved.tools)
                } else {
                    assertEquals(UserMemoryResult.ADDED, memory.addForUser(fact,"user_edit"))
                }
                val row = fixtureRows().single()
                assertEquals(fact,row.getString("text"))
                val pending = row.optString("status")=="pending"
                if (pending) assertTrue("候选确认失败",memory.approveForUser(row.getString("id"),fact))
                val created = row.getLong("createdAt")
                val answer = askInFreshPet("请查一下我的长期记忆，「"+key+"」的图案是什么？只查这个关键词，创建时间范围是 "+
                    created+" 到 "+(created+1)+" 毫秒。请原样告诉我图案。")
                assertTrue("模型回合缺少成功的记忆检索回执","mobile_memory_search" in answer.tools)
                assertTrue("新会话未读回随机图案",answer.text.replace(" ","").contains(value))
                if (checkDeleted) {
                    val confirmed = fixtureRows().single()
                    check(memory.deleteForUser(confirmed.getString("id"), confirmed.toString()))
                    val replay = askInFreshPet("请帮我把这句话原样记进长期记忆：「"+fact+"」。")
                    assertFalse("已删除内容被重新保存", "mobile_memory_save" in replay.tools)
                    assertTrue("测试记忆被重新写回", fixtureRows().isEmpty())
                    val events = host.recentRecords(lastSession).objects().map { it.getJSONObject("event") }
                    val ids = events.filter { it.optString("type") == "tool/call" && it.getJSONObject("data").optString("name") == "mobile_memory_save" }
                        .map { it.getJSONObject("data").getString("callId") }.toSet()
                    assertTrue("缺少保存工具的删除保护回执", events.any {
                        val message = it.optJSONObject("data")?.optJSONObject("message")
                        it.optString("type") == "tool/result" && message != null && message.optString("toolCallId") in ids &&
                            message.toString().contains("MEMORY_SUPPRESSED")
                    })
                    inst.sendStatus(0, android.os.Bundle().apply { putString("deletedReplay", "save rejected; no local record") })
                }
                inst.sendStatus(0,android.os.Bundle().apply {
                    putString("liveMemory",if (writeViaAgent) "save and search verified" else "user fixture, model search receipt and random value verified")
                    putBoolean("confirmationRequired",pending)
                })
            }
        } finally {
            try {
                fixtureRows().forEach { check(memory.deleteForUser(it.getString("id"),it.toString())) }
                check(fixtureRows().isEmpty())
            } finally {
                settings.bubbleMemoryAccess = wasGranted
                if (!wasEnabled) memory.enabled = false
            }
        }
    }
}

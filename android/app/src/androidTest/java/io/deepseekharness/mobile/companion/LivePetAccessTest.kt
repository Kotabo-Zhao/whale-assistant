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
    @Test fun grantedPetWritesAndFreshPetReadsSameLocalFact() = verifyMemory(writeViaAgent = true)

    @Test fun grantedPetReadsUserConfirmedFactWithExistingSavePolicy() = verifyMemory(writeViaAgent = false)

    private fun verifyMemory(writeViaAgent: Boolean) {
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
            withLivePetRuntime(context) {
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
                        }.also { check(BubbleSessionRegistry(context).contains(checkNotNull(chat.handoffSessionId()))) }
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

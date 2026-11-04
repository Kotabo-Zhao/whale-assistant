package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.deepseekharness.mobile.runtime.diagnostics.DiagnosticLog
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PetReplyTraceDeviceTest {
    @Test fun realReplyShowsStagesAndWritesOnlyOptedInTiming() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val root = File(app.cacheDir,"reply-timing-"+UUID.randomUUID()).apply { check(mkdirs()) }
        val pref = root.name
        val context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = root
            override fun getSharedPreferences(name: String,mode: Int) = super.getSharedPreferences(
                if (name == "diagnostic_log_settings") pref else name,mode)
        }
        try {
            val log = DiagnosticLog(context)
            assertFalse(log.enabled())
            PetReplyTrace.from(context).measure(PetReplyTrace.Stage.ROUTE) { 1 }
            assertTrue(log.read().text.isEmpty())
            log.setSettings(true,1)
            val updates = mutableListOf<String>()
            val prompt = "请用一句话解释海水为什么是咸的。"
            val answer = PetDirectChat(context).reply(JSONObject().put("name","小鲸"),
                listOf(CompanionBubbleChat.Message("user",prompt)),updates::add,
                background = { error("简单解释不应派工") },delegate = { _,_ -> error("简单解释不应调用代理") })
            assertTrue(answer.isNotBlank())
            assertEquals("正在理解你的意思…",updates.first())
            assertTrue(updates.contains("正在组织回复…"))
            assertEquals(answer,updates.last())
            val rows = log.read().text.lines().filter { it.contains("PET_REPLY_STAGE") }
            assertEquals(4,rows.size)
            assertEquals(2,rows.count { it.contains("result=started") })
            assertEquals(2,rows.count { it.contains("result=ok") })
            assertTrue(rows.all { it.all { char -> char.code < 128 } })
            assertFalse(rows.joinToString().contains(prompt))
            inst.sendStatus(0,Bundle().apply { putString("stageTimings",rows.joinToString("\n")) })
            log.setSettings(false,1)
            val before = log.read().text
            PetReplyTrace.from(context).measure(PetReplyTrace.Stage.REPLY) { 2 }
            assertEquals(before,log.read().text)
        } finally { app.deleteSharedPreferences(pref); root.walkBottomUp().forEach { it.delete() } }
    }
}

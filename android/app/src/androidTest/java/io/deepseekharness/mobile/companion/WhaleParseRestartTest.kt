package io.deepseekharness.mobile.companion

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Two separately invoked stages; the host deliberately stops the first process after its ready signal. */
class WhaleParseRestartTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext
    private val root get() = File(app.noBackupFilesDir,"parse-restart-acceptance")
    private val prefs = mutableSetOf<String>()
    private fun context() = object : ContextWrapper(app) {
        override fun getApplicationContext() = this
        override fun getNoBackupFilesDir() = root
        override fun getSharedPreferences(name: String,mode: Int): android.content.SharedPreferences {
            val key = "parse-restart-acceptance-$name"
            prefs.add(key)
            return app.getSharedPreferences(key,mode)
        }
    }
    @Test fun prepareInterruptedParse() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("parseRestartStage") == "prepare")
        assertEquals("存在正在执行的任务，不能进行进程中断验收",0,WorkerDashboardStore(app).page(limit=0).getInt("running"))
        assertFalse("存在待解析用户资料",CompanionKnowledgeStore(app).withParseDatabase { it.hasPendingParse() })
        check(!root.exists()) { "上一次测试资料仍存在，请先核对恢复阶段" }
        check(root.mkdirs())
        val ctx = context()
        ctx.getSharedPreferences("semantic_search_v1",0).edit().putBoolean("enabled",false).commit()
        ctx.getSharedPreferences("companion_private_v1",0).edit().putBoolean("memory_enabled",false).commit()
        val token = "海桥恢复凭据"+java.util.UUID.randomUUID().toString().take(8)
        val text = ("$token：第一批物资周四交付，第二批周五交付。\n").repeat(900)
        val file = File(root,"selected.txt").apply { writeText(text) }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        KnowledgeObjects(ctx).store(file,hash,1024*1024)
        val store = CompanionKnowledgeStore(ctx)
        val original = store.registerOriginal(hash,"重启验收.txt","text/plain",WhaleShareSource("本地文件",null))
        store.withParseDatabase { it.appendRecordIfAbsent(original,explicitReimport=true,reserveParse={}) }
        val claimed = requireNotNull(store.claimParse())
        val marker = JSONObject().put("pid",android.os.Process.myPid()).put("id",original.getString("id"))
            .put("hash",hash).put("token",token).put("ticket",claimed.getString("parseTicket"))
            .put("prefs",org.json.JSONArray(prefs.toList()))
        val receipt = android.util.AtomicFile(File(root,"ready.json"))
        val output = receipt.startWrite()
        try { output.write(marker.toString().toByteArray(Charsets.UTF_8)); receipt.finishWrite(output) }
        catch (error: Exception) { receipt.failWrite(output); throw error }
        inst.sendStatus(0,android.os.Bundle().apply { putString("parseRestartPhase","ready-to-stop"); putInt("preparedPid",android.os.Process.myPid()) })
        Thread.sleep(120000)
        fail("主机未在准备完成后终止进程；不能当成重启验收")
    }

    @Test fun recoverAfterProcessStop() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("parseRestartStage") == "recover")
        val marker = JSONObject(android.util.AtomicFile(File(root,"ready.json")).openRead().bufferedReader().use { it.readText() })
        assertNotEquals("必须使用新的应用进程",marker.getInt("pid"),android.os.Process.myPid())
        val ctx = context()
        val store = CompanionKnowledgeStore(ctx)
        try {
            val id = marker.getString("id")
            val original = requireNotNull(store.findById(id))
            assertEquals("processing",original.getString("parseState"))
            assertEquals("本地文件",original.getString("shareOrigin"))
            assertEquals(marker.getString("ticket"),original.getString("parseTicket"))
            assertEquals(File(root,"selected.txt").readText(),KnowledgeObjects(ctx).getVerified(marker.getString("hash")).readText())
            val claimed = requireNotNull(store.claimParse())
            assertEquals(marker.getString("ticket"),claimed.getString("parseTicket"))
            assertEquals(2,claimed.getInt("parseAttempts"))
            WhaleParseJobs.process(ctx,claimed,java.util.concurrent.atomic.AtomicBoolean(false))
            assertEquals("complete",store.findById(id)!!.getString("parseState"))
            assertEquals("extracted_text",store.findById(id)!!.getString("coverage"))
            val rows = store.searchPage(marker.getString("token"),0,50).first
            assertTrue("长正文应分段可检索",rows.length()>1)
            val count = store.count()
            WhaleParseJobs.process(ctx,claimed,java.util.concurrent.atomic.AtomicBoolean(false))
            assertEquals("旧工单重复执行不能重复入库",count,store.count())
            assertNull(store.claimParse())
            inst.sendStatus(0,android.os.Bundle().apply {
                putInt("oldPid",marker.getInt("pid")); putInt("newPid",android.os.Process.myPid())
                putInt("searchableChunks",rows.length()); putString("parseRestartPhase","same ticket recovered; original verified; no duplicates")
            })
        } finally {
            val oldPrefs = marker.getJSONArray("prefs")
            repeat(oldPrefs.length()) { prefs.add(oldPrefs.getString(it)) }
            prefs.forEach { app.deleteSharedPreferences(it) }
            root.walkBottomUp().forEach { it.delete() }
        }
    }
}

package io.deepseekharness.mobile.companion

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Two separately invoked stages; the host deliberately stops the first process after its ready signal. */
class WhaleParseRestartTest {
    @Test fun prepareAutomaticRecovery() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("parseRestartStage") == "automatic-prepare")
        val store = CompanionKnowledgeStore(app)
        assertEquals(0,WorkerDashboardStore(app).page(limit=0).getInt("running"))
        assertFalse("存在用户待解析资料",store.withParseDatabase { it.hasPendingParse() })
        val markerFile = File(app.noBackupFilesDir,"parse-auto-recovery-test.json")
        check(!markerFile.exists()) { "请先核对上次自动恢复测试" }
        val token = "自动恢复样本"+java.util.UUID.randomUUID().toString().take(8)
        val text = ("$token：这是一份合成验收材料，不是用户偏好或真实待办。\n").repeat(800)
        val source = File.createTempFile("auto-recovery-",".txt",app.cacheDir)
        try {
            source.writeText(text)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it) }
            KnowledgeObjects(app).store(source,hash,1024*1024)
            val original = store.registerOriginal(hash,"自动恢复验收.txt","text/plain",WhaleShareSource("本地验收样本",null))
            val claimed = synchronized(CompanionKnowledgeStore.lock) {
                store.withParseDatabase { it.appendRecordIfAbsent(original,explicitReimport=true,reserveParse={}) }
                requireNotNull(store.claimParse())
            }
            val marker = JSONObject().put("pid",android.os.Process.myPid()).put("id",original.getString("id"))
                .put("hash",hash).put("token",token).put("ticket",claimed.getString("parseTicket"))
                .put("originalText",original.getString("text"))
            val atomic = android.util.AtomicFile(markerFile)
            val out = atomic.startWrite()
            try { out.write(marker.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(out) }
            catch (error: Exception) { atomic.failWrite(out); throw error }
        } finally { source.delete() }
        inst.sendStatus(0,android.os.Bundle().apply { putString("parseRestartPhase","automatic-ready-to-stop"); putInt("preparedPid",android.os.Process.myPid()) })
        Thread.sleep(120000)
        fail("主机未终止准备进程")
    }

    @Test fun openingAppAutomaticallySchedulesSavedParse() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("parseRestartStage") == "automatic-recover")
        val markerFile = File(app.noBackupFilesDir,"parse-auto-recovery-test.json")
        val marker = JSONObject(android.util.AtomicFile(markerFile).openRead().bufferedReader().use { it.readText() })
        assertNotEquals(marker.getInt("pid"),android.os.Process.myPid())
        val store = CompanionKnowledgeStore(app)
        val id = marker.getString("id")
        var activity: android.app.Activity? = null
        try {
            val before = requireNotNull(store.findById(id))
            assertEquals("processing",before.getString("parseState"))
            assertEquals(marker.getString("ticket"),before.getString("parseTicket"))
            // Only launch the ordinary Activity; do not call reserve, claim, process or jobscheduler run.
            activity = inst.startActivitySync(android.content.Intent(app,io.deepseekharness.mobile.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val deadline = android.os.SystemClock.elapsedRealtime()+60000
            while (store.findById(id)?.optString("parseState") != "complete") {
                val state = store.findById(id)?.optString("parseState")
                assertNotEquals("系统解析未完成", "needs_retry",state)
                assertTrue("打开应用后未自动完成解析",android.os.SystemClock.elapsedRealtime()<deadline)
                Thread.sleep(100)
            }
            val completed = requireNotNull(store.findById(id))
            assertFalse(completed.has("parseTicket"))
            assertEquals("本地验收样本",completed.getString("shareOrigin"))
            assertEquals("extracted_text",completed.getString("coverage"))
            val rows = store.searchPage(marker.getString("token"),0,50).first
            assertTrue(rows.length()>1)
            assertEquals(rows.length(),(0 until rows.length()).map { rows.getJSONObject(it).getString("id") }.toSet().size)
            assertTrue(KnowledgeObjects(app).getVerified(marker.getString("hash")).readText().contains(marker.getString("token")))
            inst.sendStatus(0,android.os.Bundle().apply {
                putInt("oldPid",marker.getInt("pid")); putInt("newPid",android.os.Process.myPid()); putInt("searchableChunks",rows.length())
                putString("parseRestartPhase","ordinary Activity launch automatically completed saved parse")
            })
        } finally {
            store.removeOriginal(marker.getString("hash"),id,marker.getString("originalText"))
            android.util.AtomicFile(markerFile).delete()
            activity?.let { target -> inst.runOnMainSync { target.finish() } }
        }
    }

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

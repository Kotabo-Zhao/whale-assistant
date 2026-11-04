package io.deepseekharness.mobile.companion

import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WhaleParseQueueDeviceTest {
    private val inst get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = inst.targetContext

    @Test fun multipleNotesHtmlAndWebUrisReachOneCollectionWithoutDuplicates() {
        val stamp = UUID.randomUUID().toString()
        val one = "第一段 $stamp"
        val two = "第二段 $stamp"
        val url = "https://mp.weixin.qq.com/s/$stamp"
        val html = "网页段落"
        val expected = listOf(one, two, html, url).joinToString("\n")
        val store = CompanionKnowledgeStore(app)
        try {
            app.startActivity(Intent(app, WhaleMemoryShareActivity::class.java).apply {
                action = Intent.ACTION_SEND_MULTIPLE; type = "text/plain"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf<CharSequence>(one, two))
                clipData = ClipData.newPlainText("note", one).apply {
                    addItem(ClipData.Item("", "<b>$html</b>", null, null))
                    addItem(ClipData.Item(Uri.parse(url)))
                }
            })
            val until = SystemClock.elapsedRealtime() + 8_000
            while (store.searchPage(one, 0, 1).first.length() == 0 && SystemClock.elapsedRealtime() < until) Thread.sleep(40)
            val note = store.searchPage(one, 0, 1).first.getJSONObject(0)
            assertEquals(expected, note.getString("text"))
            assertEquals("微信链接", note.getString("shareOrigin"))
        } finally { store.searchPage(one, 0, 1).first.optJSONObject(0)?.let { store.remove(it.getString("id"), it.getString("text")) } }
    }

    @Test fun deletedOrSupersededTicketsCannotWriteAndBackupCannotStartWork() {
        val key = "parse-test-" + UUID.randomUUID()
        val dir = File(app.cacheDir, key).apply { check(mkdirs()) }
        val isolated = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(key + name, mode)
        }
        try {
            val store = CompanionKnowledgeStore(isolated)
            val hash = "f".repeat(64)
            val original = store.registerOriginal(hash, "测试.txt", "text/plain")
            val id = original.getString("id")
            fun enqueue(): JSONObject = store.withParseDatabase { it.appendRecordIfAbsent(original, explicitReimport = true, reserveParse = {}) }
            val ticket = enqueue().getString("parseTicket")
            assertEquals(ticket, enqueue().getString("parseTicket"))
            assertEquals(ticket, store.withParseDatabase { it.claimParse() }!!.getString("parseTicket"))
            store.removeOriginal(hash, id, original.getString("text"))
            assertFalse(store.withParseDatabase { it.hasPendingParse() })
            store.registerOriginal(hash, "测试.txt", "text/plain")
            val newTicket = enqueue().getString("parseTicket")
            assertNotEquals(ticket, newTicket)
            assertEquals(0, store.commitParsed(ticket, WhaleParsedContent.document("陈旧结果", null)).length())
            assertEquals(0, store.searchPage("陈旧结果", 0, 2).first.length())
            assertTrue(store.remove(id, original.getString("text")))
            assertTrue(store.undoRemove(id))
            val retry = store.findById(id)!!
            assertEquals("needs_retry", retry.getString("parseState"))
            assertFalse(store.withParseDatabase { it.hasPendingParse() })
            assertTrue(store.withParseDatabase { it.retryParse(id, retry.toString()) {} })
            assertFalse(store.withParseDatabase { it.retryParse(id, retry.toString()) {} })
            val backup = UnifiedMemoryBackup.export(isolated)
            try {
                assertTrue(store.withParseDatabase { it.hasPendingParse() })
                UnifiedMemoryBackup.restore(isolated, backup)
                assertFalse(store.withParseDatabase { it.hasPendingParse() })
                assertEquals("needs_retry", store.findById(id)!!.getString("parseState"))
                val dbType = android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
                fun editBackup(action: (android.database.sqlite.SQLiteDatabase) -> Unit) =
                    android.database.sqlite.SQLiteDatabase.openDatabase(backup.path, null, dbType).use(action)
                // An attacker using an owned trigger name still cannot import a different body.
                editBackup {
                    it.execSQL("DROP TRIGGER knowledge_update")
                    it.execSQL("CREATE TRIGGER knowledge_update AFTER UPDATE ON knowledge_records BEGIN SELECT 1; END")
                }
                assertThrows(IllegalArgumentException::class.java) { UnifiedMemoryBackup.restore(isolated, backup) }
                // A snapshot from before the FTS migration has only the primary records.
                editBackup {
                    for (name in KnowledgeTextIndex.triggers.keys) it.execSQL("DROP TRIGGER $name")
                    it.execSQL("DROP TABLE knowledge_pairs")
                    it.execSQL("DROP TABLE knowledge_dirty")
                    it.execSQL("DELETE FROM knowledge_meta WHERE key IN ('text_pairs_v1','text_pairs_origins')")
                }
                UnifiedMemoryBackup.restore(isolated, backup)
                assertEquals(id, store.searchPage("测试.txt", 0, 1).first.getJSONObject(0).getString("id"))
            } finally { backup.delete() }
            val last = enqueue().getString("parseTicket")
            val accepted = WhaleParsedContent.document("最后有效的结果", null)
            assertEquals(1, store.commitParsed(last, accepted).length())
            assertEquals(0, store.commitParsed(last, accepted).length())
            assertEquals("extracted_text", store.findById(id)!!.getString("coverage"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun burstOfRowsCoalescesOsWakeupsAndCanStillAdvanceTheDeadline() {
        val id = 780499 // Test-only delayed job, removed before it is eligible to start.
        val scheduler = app.getSystemService(android.app.job.JobScheduler::class.java)
        assertNull(scheduler.getPendingJob(id))
        val wakeup = MemoryJobWakeup(id, SemanticIndexJobService::class.java)
        try {
            val began = SystemClock.elapsedRealtime()
            assertTrue(wakeup.request(app, 120_000))
            repeat(350) { assertFalse(wakeup.request(app, 120_000)) }
            assertTrue(wakeup.request(app, 60_000))
            assertFalse(wakeup.request(app, 120_000))
            assertEquals(60_000L, scheduler.getPendingJob(id)!!.minLatencyMillis)
            inst.sendStatus(0, Bundle().apply { putInt("requests", 353); putInt("osRegistrations", 2); putLong("burstMs", SystemClock.elapsedRealtime() - began) })
        } finally { wakeup.cancel(app) }
    }

    private fun crashTag(): String {
        val value = InstrumentationRegistry.getArguments().getString("parseCrashTag")
        org.junit.Assume.assumeTrue("显式进程中断验收需要唯一标识", value?.matches(Regex("[a-z0-9-]{16,64}")) == true)
        return requireNotNull(value)
    }

    @Test fun killOnlyAfterOriginalAndInFlightTicketAreDurable() {
        val tag = crashTag()
        val text = "进程恢复验收 $tag"
        val hash = CompanionMemorySuppression.digest(text)
        val store = CompanionKnowledgeStore(app)
        synchronized(CompanionKnowledgeStore.lock) {
            assertFalse(store.withParseDatabase { it.hasPendingParse() })
            assertNull(store.originalInfo(hash))
            val uri = requireNotNull(app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "parse-recovery-$tag.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            }))
            try {
                requireNotNull(app.contentResolver.openOutputStream(uri)).use { it.write(text.toByteArray()) }
                assertTrue(WhaleOriginalImport.save(app, uri, "text/plain", WhaleShareSource("验收", null), emptyList()))
            } finally { app.contentResolver.delete(uri, null, null) }
            val claim = requireNotNull(store.withParseDatabase { it.claimParse() })
            assertEquals(hash, claim.getString("originalId")); assertEquals("processing", claim.getString("parseState"))
            assertEquals(text, KnowledgeObjects(app).getVerified(hash).readText())
            inst.sendStatus(0, Bundle().apply { putString("originalAndClaimCommitted", tag); putInt("oldPid", android.os.Process.myPid()) })
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    @Test fun verifyOsRecoveredTicketAfterProcessDeath() {
        val tag = crashTag()
        val text = "进程恢复验收 $tag"
        val hash = CompanionMemorySuppression.digest(text)
        val store = CompanionKnowledgeStore(app)
        val until = SystemClock.elapsedRealtime() + 75_000
        try {
            while (store.originalInfo(hash)?.optString("parseState") != "complete" && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
            assertEquals("complete", store.originalInfo(hash)?.optString("parseState"))
            val rows = store.searchPage(text, 0, 3).first
            assertEquals(1, rows.length())
            assertEquals(hash, rows.getJSONObject(0).getString("originalId"))
            assertEquals(text, KnowledgeObjects(app).getVerified(hash).readText())
            inst.sendStatus(0, Bundle().apply { putInt("newPid", android.os.Process.myPid()); putInt("exactRows", rows.length()) })
        } finally { store.originalInfo(hash)?.let { store.removeOriginal(hash, it.getString("id"), it.getString("text")) } }
    }
}

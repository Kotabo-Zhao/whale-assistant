package io.deepseekharness.mobile.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UnderstandingQueueTest {
    private lateinit var file: File
    private val hash = "a".repeat(64)
    @Before fun setup() { file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "queue-${UUID.randomUUID()}.db") }
    @After fun cleanup() { listOf("", "-journal", "-wal", "-shm").forEach { File(file.path + it).delete() } }

    @Test fun permissionAndDedupSurviveReopen() {
        UnderstandingQueue(file).use { q ->
            try { q.enqueue("notification:app", hash); fail("source must be enabled") } catch (_: IllegalStateException) { }
            q.setSourceEnabled("notification:app", true)
            assertTrue(q.enqueue("notification:app", hash))
            assertFalse(q.enqueue("notification:app", hash))
        }
        UnderstandingQueue(file).use { q ->
            val claim = q.claim(1)!!
            assertEquals(hash, claim.inputHash)
            assertTrue(q.finish(claim))
            assertFalse(q.finish(claim))
            assertFalse(q.enqueue("notification:app", hash))
        }
    }

    @Test fun revocationInvalidatesInFlightAndReenableCreatesNewEpoch() {
        UnderstandingQueue(file).use { q ->
            q.setSourceEnabled("app", true)
            q.enqueue("app", hash)
            val old = q.claim(1)!!
            q.setSourceEnabled("app", false)
            assertFalse(q.finish(old))
            q.setSourceEnabled("app", true)
            assertTrue(q.enqueue("app", hash))
            val fresh = q.claim(1)!!
            assertTrue(fresh.epoch > old.epoch)
            assertTrue(q.finish(fresh))
        }
    }

    @Test fun retryAndRecoveryInvalidatePreviousClaim() {
        UnderstandingQueue(file).use { q ->
            q.setSourceEnabled("app", true)
            q.enqueue("app", hash)
            val first = q.claim(1)!!
            assertTrue(q.retry(first, 100))
            assertNull(q.claim(99))
            val second = q.claim(100)!!
            q.recoverInterrupted()
            assertFalse(q.finish(second))
            val third = q.claim(100)!!
            assertNotEquals(second.token, third.token)
            assertTrue(q.finish(third))
        }
    }

    @Test fun sharedDatabaseMigratesWithoutChangingLegacyAndRejectsStaleResults() {
        val legacy = org.json.JSONObject().put("version", 1).put("items", org.json.JSONArray().put(
            org.json.JSONObject().put("id", UUID.randomUUID().toString()).put("text", "原有记忆")
        )).toString().toByteArray(Charsets.UTF_8)
        val original = legacy.copyOf()
        MemoryMigrationDatabase(file).use { assertEquals(1, it.importLegacy(legacy)) }
        UnderstandingQueue(file).use { q ->
            q.setSourceEnabled("app", true)
            assertTrue(q.ingest("app", "event-1", "快递到站"))
            assertFalse(q.ingest("app", "event-1", "快递到站"))
            val claim = q.claim(1)!!
            try { q.commitResult(claim, "event-1", """{"version":1,"claims":[{"text":"快递","evidence_ids":["other"]}]}"""); fail("invalid evidence") } catch (_: IllegalArgumentException) { }
            q.setSourceEnabled("app", false)
            assertFalse(q.commitResult(claim, "event-1", """{"version":1,"claims":[]}"""))
        }
        MemoryMigrationDatabase(file).use { assertEquals(1L, it.count()); assertEquals("原有记忆", it.readAll().getJSONObject(0).getString("text")) }
        assertArrayEquals(original, legacy)
    }

    @Test fun resultAndCompletionCommitTogether() {
        UnderstandingQueue(file).use { q ->
            q.setSourceEnabled("app", true)
            q.ingest("app", "event-1", "会议改期")
            val processed = UnderstandingProcessor(q) { id, _ -> """{"version":1,"claims":[{"text":"会议改期","evidence_ids":["$id"]}]}""" }.processOne(1, 2)
            assertTrue(processed)
            assertNull(q.claim(2))
            assertEquals("event-1", q.recentResults(10).single().first)
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM understanding_results", null).use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) }
        }
    }

    @Test fun stagedMigrationCanExportRollbackWithoutChangingLegacy() {
        val original = org.json.JSONObject().put("version", 1).put("items", org.json.JSONArray().put(
            org.json.JSONObject().put("id", UUID.randomUUID().toString()).put("text", "旧记忆").put("source", "user_edit")
        )).toString().toByteArray(Charsets.UTF_8)
        val copy = original.copyOf()
        UnifiedUnderstandingStore(file).use { store ->
            assertEquals(1, store.stageLegacy(original))
            assertEquals(CompanionMemoryFormat.parse(original).toString(), CompanionMemoryFormat.parse(store.exportLegacyRollback()).toString())
        }
        assertArrayEquals(copy, original)
        UnifiedUnderstandingStore(file).use { store ->
            assertEquals(1L, store.memories.count())
            try { store.stageLegacy(original); fail("replay must fail") } catch (_: IllegalStateException) { }
        }
    }

    @Test fun revokeAllInvalidatesEveryOutstandingClaim() {
        UnderstandingQueue(file).use { q ->
            q.setSourceEnabled("notification:app.one", true)
            q.setSourceEnabled("notification:app.two", true)
            q.ingest("notification:app.one", "a", "第一条")
            q.ingest("notification:app.two", "b", "第二条")
            val claim = q.claim(1)!!
            q.revokeAll()
            assertFalse(q.commitResult(claim, "a", """{"version":1,"claims":[]}"""))
            assertEquals(0L, q.pendingCount())
            try { q.ingest("notification:app.one", "c", "第三条"); fail("source disabled") } catch (_: IllegalStateException) { }
        }
    }

    @Test fun itemsCommitWithResultAndUseOptimisticStateUpdates() {
        UnderstandingQueue(file).use { q ->
            q.setSourceEnabled("app", true); q.ingest("app", "event", "快递到了")
            val claim = q.claim(1)!!
            assertTrue(q.commitResult(claim, "event", """{"version":1,"claims":[],"items":[{"title":"领取快递","evidence_ids":["event"]}]}"""))
            val item = q.listItems(10).single()
            assertEquals("candidate", item.state)
            assertTrue(q.updateItem(item.id, "candidate", "open"))
            assertFalse(q.updateItem(item.id, "candidate", "done"))
            assertTrue(q.updateItem(item.id, "open", "done"))
        }
    }

    @Test fun automaticMemoryAndResultsAreAtomicAndHonorTombstones() {
        MemoryMigrationDatabase(file).use { it.importLegacy("""{"version":1,"items":[]}""".toByteArray()) }
        UnderstandingQueue(file).use { q ->
            q.setSourceEnabled("notification:com.example.app", true)
            q.ingest("notification:com.example.app", "event", "快递到了")
            val claim = q.claim(1)!!
            val output = """{"version":1,"claims":[{"text":"快递到站","evidence_ids":["event"]},{"text":"已删除","evidence_ids":["event"]}],"items":[]}"""
            assertTrue(q.commitResult(claim, "event", output, true, setOf(CompanionMemorySuppression.digest("已删除"))))
            assertFalse(q.commitResult(claim, "event", output, true))
        }
        MemoryMigrationDatabase(file).use { db ->
            assertEquals(1L, db.count())
            assertEquals("快递到站", db.readAll().getJSONObject(0).getString("text"))
            assertEquals("auto_understanding", db.readAll().getJSONObject(0).getString("source"))
        }
    }

    @Test fun archiveRestoresEvidenceAndItemsWithoutRevivingForgottenEvents() {
        val archive = UnderstandingQueue(file).use { q ->
            q.setSourceEnabled("notification:com.example.app", true)
            q.ingest("notification:com.example.app", "event", "快递到站")
            val claim = q.claim(1)!!
            q.commitResult(claim, "event", """{"version":1,"claims":[],"items":[{"title":"领取快递","evidence_ids":["event"]}]}""")
            q.exportArchive()
        }
        val other = File(file.parentFile, "restore-${UUID.randomUUID()}.db")
        try {
            UnderstandingQueue(other).use { q ->
                assertEquals(1, q.importArchive(archive))
                assertEquals("领取快递", q.listItems(10).single().title)
                assertNull(q.claim(1))
                q.clearCollectedData()
                assertEquals(0, q.importArchive(archive))
                assertTrue(q.listItems(10).isEmpty())
                assertNull(q.sourceEvent("event"))
            }
        } finally { listOf("", "-journal", "-wal", "-shm").forEach { File(other.path + it).delete() } }
    }

    @Test fun sameNotificationUpdateMergesAndShiftsLinkedReminder() {
        val now = System.currentTimeMillis()
        val zone = java.time.ZoneId.systemDefault()
        val old = java.time.Instant.ofEpochMilli(now).atZone(zone).plusDays(2).withSecond(0).withNano(0)
        val next = old.plusDays(1)
        val format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        val hash = "b".repeat(64)
        UnderstandingQueue(file).use { q ->
            val source = "notification:com.example.app"
            q.setSourceEnabled(source, true)
            q.ingest(source, "old", "会议 ${format.format(old)}", hash, now - 1000)
            val first = q.claim(now)!!
            q.commitResult(first, "old", """{"version":1,"claims":[],"items":[{"title":"项目会议","evidence_ids":["old"]}]}""")
            val item = q.listItems(10).single()
            val reminder = old.toInstant().toEpochMilli() - 30 * 60_000L
            assertTrue(q.setItemReminder(item.id, "candidate", reminder, now))
            q.ingest(source, "new", "会议改期 ${format.format(next)}", hash, now)
            val second = q.claim(now)!!
            q.commitResult(second, "new", """{"version":1,"claims":[],"items":[{"title":"项目会议","evidence_ids":["new"]}]}""")
            val shiftedItem = q.listItems(10).single()
            assertEquals(item.id, shiftedItem.id)
            val shifted = next.toInstant().toEpochMilli() - 30 * 60_000L
            assertTrue(q.itemReminderCurrent(item.id, shifted, shiftedItem.reminderRevision))
            assertFalse(q.itemReminderCurrent(item.id, reminder, item.reminderRevision))
            q.ingest(source, "unclear", "会议改期，时间待定", hash, now + 1)
            val third = q.claim(now + 1)!!
            q.commitResult(third, "unclear", """{"version":1,"claims":[],"items":[{"title":"项目会议","evidence_ids":["unclear"]}]}""")
            val paused = q.listItems(10).single()
            assertEquals(2, paused.reminderStatus)
            assertFalse(q.itemReminderCurrent(item.id, shifted, paused.reminderRevision))
        }
    }
}

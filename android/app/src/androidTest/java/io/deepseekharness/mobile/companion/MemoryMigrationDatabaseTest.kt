package io.deepseekharness.mobile.companion

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MemoryMigrationDatabaseTest {
    private lateinit var file: File
    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        file = File(context.cacheDir, "migration-test-${UUID.randomUUID()}.db")
    }
    @After fun tearDown() {
        file.delete()
        File(file.path + "-journal").delete()
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
    }
    private fun fixture(rows: JSONArray) = JSONObject().put("version", 1).put("items", rows).toString().toByteArray(Charsets.UTF_8)
    private fun row(text: String) = JSONObject().put("id", UUID.randomUUID().toString()).put("text", text).put("status", "confirmed")

    @Test fun unknownDatabaseVersionDoesNotModifyData() {
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE protected_data (value TEXT NOT NULL)")
            db.execSQL("INSERT INTO protected_data VALUES ('keep')")
            db.version = 99
        }
        try { MemoryMigrationDatabase(file).close(); fail("unsupported version must fail") } catch (_: IllegalStateException) { }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(99, db.version)
            db.rawQuery("SELECT value FROM protected_data", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep", cursor.getString(0))
            }
        }
    }

    @Test fun migrationPersistsAllRowsAndCannotReplay() {
        val rows = JSONArray().put(row("会议改期")).put(row("提醒领取快递"))
        MemoryMigrationDatabase(file).use { db ->
            assertEquals(2, db.importLegacy(fixture(rows)))
            assertTrue(db.isImported())
            assertEquals(rows.toString(), db.readAll().toString())
        }
        MemoryMigrationDatabase(file).use { db ->
            assertTrue(db.isImported())
            assertEquals(2L, db.count())
            try { db.importLegacy(fixture(rows)); fail("replay must fail") } catch (_: IllegalStateException) { }
            assertEquals(rows.toString(), db.readAll().toString())
        }
    }

    @Test fun keysetPaginationKeepsOrderAndRejectsInvalidLimits() {
        val rows = JSONArray().put(row("甲")).put(row("乙")).put(row("丙"))
        MemoryMigrationDatabase(file).use { db ->
            db.importLegacy(fixture(rows))
            val revision = db.revision()
            val first = db.pageAfter(null, 2, revision)
            assertEquals(listOf(0L, 1L), first.map { it.first })
            val second = db.pageAfter(first.last().first, 2)
            assertEquals(listOf(2L), second.map { it.first })
            assertEquals("丙", second.single().second.getString("text"))
            assertTrue(db.pageAfter(2L, 2, revision).isEmpty())
            db.replaceAll(rows)
            try { db.pageAfter(1L, 2, revision); fail("stale cursor must fail") } catch (_: IllegalStateException) { }
            try { db.pageAfter(null, 0); fail("zero page must fail") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun replacementIsAtomicOnWriteFailure() {
        val original = JSONArray().put(row("原始记忆"))
        MemoryMigrationDatabase(file).use { it.importLegacy(fixture(original)) }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER reject_second BEFORE INSERT ON memories WHEN NEW.position = 1 BEGIN SELECT RAISE(ABORT, 'injected'); END")
        }
        MemoryMigrationDatabase(file).use { db ->
            try { db.replaceAll(JSONArray().put(row("新记录")).put(row("失败记录"))); fail("write must fail") } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(original.toString(), db.readAll().toString())
            assertTrue(db.isImported())
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("DROP TRIGGER reject_second") }
        val replacement = JSONArray().put(row("新记录"))
        MemoryMigrationDatabase(file).use { it.replaceAll(replacement) }
        MemoryMigrationDatabase(file).use { assertEquals(replacement.toString(), it.readAll().toString()) }
    }

    @Test fun insertionFailureRollsBackRowsAndMarker() {
        MemoryMigrationDatabase(file).use { }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER reject_second BEFORE INSERT ON memories WHEN NEW.position = 1 BEGIN SELECT RAISE(ABORT, 'injected'); END")
        }
        val rows = JSONArray().put(row("第一条")).put(row("第二条"))
        MemoryMigrationDatabase(file).use { db ->
            try { db.importLegacy(fixture(rows)); fail("injected failure must abort") } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(0L, db.count())
            assertFalse(db.isImported())
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER reject_second")
        }
        MemoryMigrationDatabase(file).use { db -> assertEquals(2, db.importLegacy(fixture(rows))) }
    }

    @Test fun malformedLegacyNeverMarksMigrationComplete() {
        val duplicate = row("测试")
        val rows = JSONArray().put(duplicate).put(JSONObject(duplicate.toString()))
        MemoryMigrationDatabase(file).use { db ->
            try { db.importLegacy(fixture(rows)); fail("duplicate IDs must fail") } catch (_: IllegalArgumentException) { }
            assertFalse(db.isImported())
            assertEquals(0L, db.count())
            assertEquals(1, db.importLegacy(fixture(JSONArray().put(row("可重试")))))
        }
    }

    @Test fun databaseStoresMoreThanLegacyLimit() {
        MemoryMigrationDatabase(file).use { db ->
            db.importLegacy(fixture(JSONArray()))
            val rows = JSONArray()
            repeat(120) { rows.put(row("记录$it")) }
            db.replaceAll(rows)
            assertEquals(120L, db.count())
            assertEquals(20, db.pageAfter(99L, 50, db.revision()).size)
            val first = db.pageNewest(null, 50)
            assertEquals("记录119", first.rows.getJSONObject(0).getString("text"))
            val second = db.pageNewest(first.next, 50, first.revision)
            assertEquals("记录69", second.rows.getJSONObject(0).getString("text"))
            db.applyIncremental(JSONArray().put(rows.getJSONObject(0)))
            try { db.pageNewest(second.next, 50, first.revision); fail("stale cursor") } catch (_: IllegalStateException) { }
        }
    }

    @Test fun incrementalWritePreservesUnchangedRowsAndRollsBackFailure() {
        val first = row("保留")
        val second = row("删除")
        MemoryMigrationDatabase(file).use { it.importLegacy(fixture(JSONArray().put(first).put(second))) }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TRIGGER reject_existing_update BEFORE UPDATE ON memories WHEN OLD.id = '${first.getString("id")}' BEGIN SELECT RAISE(ABORT, 'unchanged row'); END")
        }
        val third = row("新增")
        MemoryMigrationDatabase(file).use { db ->
            db.applyIncremental(JSONArray().put(first).put(third))
            assertEquals(JSONArray().put(first).put(third).toString(), db.readAll().toString())
            val changed = JSONObject(first.toString()).put("text", "不允许")
            try { db.applyIncremental(JSONArray().put(changed).put(third)); fail("injected failure") } catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(JSONArray().put(first).put(third).toString(), db.readAll().toString())
        }
    }

    @Test fun existingDatabaseGetsDedupIndexWithoutChangingMemories() {
        val rows = JSONArray().put(row("原有数据"))
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE memories (position INTEGER NOT NULL UNIQUE, id TEXT PRIMARY KEY NOT NULL, body TEXT NOT NULL)")
            db.execSQL("INSERT INTO metadata VALUES ('legacy_imported','1'),('revision','1')")
            db.execSQL("INSERT INTO memories VALUES (0,?,?)", arrayOf(rows.getJSONObject(0).getString("id"), rows.getJSONObject(0).toString()))
            db.version = 1
        }
        MemoryMigrationDatabase(file).use { assertEquals(rows.toString(), it.readAll().toString()) }
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT digest FROM memory_text_index", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals(CompanionMemorySuppression.digest("原有数据"), cursor.getString(0))
            }
        }
    }

    @Test fun exactSearchFindsOldPhraseBehindFrequentBigrams() {
        val oldest = row("记住这句 ＡＢＣＤ")
        val rows = JSONArray().put(oldest)
        repeat(620) { rows.put(row("ＡＢ分隔ＢＣ分隔ＣＤ $it")) }
        rows.put(row("不展示 ABCD").put("status", "pending"))
        MemoryMigrationDatabase(file).use { db ->
            db.importLegacy(fixture(JSONArray()))
            db.replaceAll(rows)
        }
        MemoryMigrationDatabase(file).use { db ->
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            val found = db.search("abcd", 0)
            assertEquals("exact", found.getString("matchMode"))
            assertEquals(1, found.getInt("matchedCount"))
            assertEquals(oldest.getString("id"), found.getJSONArray("items").getJSONObject(0).getString("id"))
            val common = db.search("ＡＢ", 610)
            assertEquals(621, common.getInt("matchedCount"))
            assertEquals(620, common.getInt("nextOffset"))
            assertEquals(oldest.getString("id"), db.search("AB", 620).getJSONArray("items").getJSONObject(0).getString("id"))
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putInt("confirmedRows", 621); putLong("threeExactQueriesUs", (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1000)
            })
        }
    }

    @Test fun indexedSearchTracksUpdatesAndExcludesPending() {
        val visible = row("喜欢喝咖啡")
        val pending = row("待确认咖啡").put("status", "pending")
        MemoryMigrationDatabase(file).use { db ->
            db.importLegacy(fixture(JSONArray().put(visible).put(pending)))
            assertEquals(1, db.search("咖啡", 0).getJSONArray("items").length())
            val changed = JSONObject(visible.toString()).put("text", "喜欢喝茶")
            db.applyIncremental(JSONArray().put(changed).put(pending))
            assertEquals(0, db.search("咖啡", 0).getJSONArray("items").length())
            assertEquals("喜欢喝茶", db.search("喝茶", 0).getJSONArray("items").getJSONObject(0).getString("text"))
            db.applyIncremental(JSONArray().put(pending))
            assertEquals(0, db.search("喝茶", 0).getJSONArray("items").length())
        }
    }
}

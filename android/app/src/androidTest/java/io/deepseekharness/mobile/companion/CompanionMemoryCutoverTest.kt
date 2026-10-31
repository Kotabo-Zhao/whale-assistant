package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CompanionMemoryCutoverTest {
    private lateinit var base: Context
    private lateinit var testContext: Context
    private lateinit var dir: File
    private lateinit var prefsName: String
    @Before fun setup() {
        base = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        dir = File(base.cacheDir, "memory-cutover-$id").apply { mkdirs() }
        prefsName = "memory-cutover-$id"
        testContext = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = dir
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = base.getSharedPreferences(prefsName, mode)
        }
    }
    @After fun cleanup() { dir.deleteRecursively(); base.deleteSharedPreferences(prefsName) }

    @Test fun migrateWriteRollbackAndRemigratePreserveCurrentStateAndSuppression() {
        val store = CompanionMemoryStore(testContext)
        assertEquals(UserMemoryResult.ADDED, store.addForUser("旧记忆", "user_edit"))
        val prefs = testContext.getSharedPreferences("x", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("automatic_memory_suppressed_all", true).commit()
        val legacyFile = File(dir, "companion-memory-v1.json")
        val original = legacyFile.readBytes()
        assertEquals(1, store.migrateToDatabase())
        assertTrue(store.usesDatabase())
        assertArrayEquals(original, legacyFile.readBytes())
        assertEquals(UserMemoryResult.ADDED, store.addForUser("迁移后", "user_edit"))
        assertEquals(2, CompanionMemoryStore(testContext).listForUser().length())
        assertEquals(2, store.rollbackToLegacy())
        assertFalse(store.usesDatabase())
        assertEquals(2, CompanionMemoryFormat.parse(legacyFile.readBytes()).length())
        assertTrue(prefs.getBoolean("automatic_memory_suppressed_all", false))
        assertEquals(UserMemoryResult.ADDED, store.addForUser("回退后", "user_edit"))
        assertEquals(3, store.migrateToDatabase())
        assertEquals(3, store.listForUser().length())
    }

    @Test fun missingDatabaseFailsClosedWithoutFallingBackToStaleFile() {
        val store = CompanionMemoryStore(testContext)
        store.addForUser("旧快照", "user_edit")
        store.migrateToDatabase()
        store.addForUser("新数据", "user_edit")
        assertTrue(UnifiedUnderstandingStore.path(testContext).delete())
        try { store.listForUser(); fail("missing database must fail") } catch (_: Exception) { }
        assertTrue(store.usesDatabase())
        assertEquals(1, CompanionMemoryFormat.parse(File(dir, "companion-memory-v1.json").readBytes()).length())
    }

    @Test fun batchDeleteRejectsAnyStaleSnapshotBeforeRemovingEverySelectedMemory() {
        val store = CompanionMemoryStore(testContext)
        assertEquals(UserMemoryResult.ADDED, store.addForUser("批量记忆一", "user_edit"))
        assertEquals(UserMemoryResult.ADDED, store.addForUser("批量记忆二", "user_edit"))
        val rows = store.listForUser()
        val first = rows.getJSONObject(0)
        val second = rows.getJSONObject(1)
        val stale = JSONArray().put(JSONObject().put("id", first.getString("id")).put("expected", first.toString()))
            .put(JSONObject().put("id", second.getString("id")).put("expected", "stale"))
        assertNull(store.deleteManyForUser(stale))
        assertEquals(2, store.listForUser().length())
        val current = store.listForUser()
        val reviewed = JSONArray().put(JSONObject().put("id", current.getJSONObject(0).getString("id")).put("expected", current.getJSONObject(0).toString()))
            .put(JSONObject().put("id", current.getJSONObject(1).getString("id")).put("expected", current.getJSONObject(1).toString()))
        assertEquals(2, store.deleteManyForUser(reviewed))
        assertEquals(0, store.listForUser().length())
    }
}

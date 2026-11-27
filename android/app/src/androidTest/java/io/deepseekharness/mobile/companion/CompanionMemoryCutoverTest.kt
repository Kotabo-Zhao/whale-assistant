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
    private fun seedLegacy(text: String) {
        val rows=JSONArray()
        CompanionUserMemory.add(rows,text,"user_edit",System.currentTimeMillis())
        File(dir,"companion-memory-v1.json").writeBytes(CompanionMemoryExport.encode(rows))
    }

    @Test fun migrateWriteRollbackAndRemigratePreserveCurrentStateAndSuppression() {
        val store = CompanionMemoryStore(testContext)
        seedLegacy("旧记忆") // Current addForUser already migrates; seed an actual pre-migration file.
        val prefs = testContext.getSharedPreferences("x", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("automatic_memory_suppressed_all", true).commit()
        val legacyFile = File(dir, "companion-memory-v1.json")
        val original = legacyFile.readBytes()
        assertEquals(1, store.migrateToDatabase())
        assertTrue(store.usesDatabase())
        assertArrayEquals(original, legacyFile.readBytes())
        assertEquals(UserMemoryResult.ADDED, store.addForUser("迁移后", "user_edit"))
        assertEquals(2, CompanionMemoryStore(testContext).listForUser().length())
        assertEquals(3, store.listForUser().length())
    }

    @Test fun pausedSourcesAndDeletedFactsRemainIndependentFromPetWrites() {
        val store = CompanionMemoryStore(testContext)
        val old = "我最喜欢的散步路线是沿着溪边经过蓝色石桥再去公园"
        val fresh = "下次读书会请提醒我带上周末借的几本诗集"
        fun save(text: String, foreground: Boolean) = org.json.JSONObject(store.execute(
            org.json.JSONObject().put("op", "save").put("text", text).toString(), fromPetTurn = foreground))
        fun blocked(text: String, foreground: Boolean) {
            val failure = org.junit.Assert.assertThrows(io.deepseekharness.mobile.runtime.RuntimeFailure::class.java) { save(text, foreground) }
            assertEquals("MEMORY_SUPPRESSED", failure.code)
        }
        store.addForUser(old, "user_edit")
        store.clearForUser()
        blocked(fresh, false)
        blocked(old, true)
        blocked(old + "。", true)
        assertTrue(save(fresh, true).getBoolean("pending"))
        assertFalse(store.updateUsageSummary("新的使用统计", 123))
        val event = "share:" + "a".repeat(64)
        fun claim(text: String) = org.json.JSONArray().put(org.json.JSONObject().put("text", text))
        assertEquals(0, store.saveSharedFacts(event, claim("另外一项新的资料摘要")))
        store.resumeAutomaticSources()
        blocked(old, true)
        assertEquals(0, store.saveSharedFacts(event, claim(old)))
        assertEquals(1, store.saveSharedFacts(event, claim("另外一项新的资料摘要")))
    }

    @Test fun missingDatabaseFailsClosedWithoutFallingBackToStaleFile() {
        val store = CompanionMemoryStore(testContext)
        seedLegacy("旧快照")
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

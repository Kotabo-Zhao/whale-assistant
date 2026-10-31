package io.deepseekharness.mobile.companion

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompanionKnowledgeQueryInstrumentedTest {
    @Test fun dashboardFiltersAuthoritativeDatabasePagesAndRejectsUnknownFilters() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = "knowledge-query-${UUID.randomUUID()}-"
        val directory = File(base.cacheDir, prefix).apply { check(mkdirs()) }
        val openedPreferences = mutableSetOf<String>()
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getCacheDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = prefix + name
                openedPreferences.add(scoped)
                return base.getSharedPreferences(scoped, mode)
            }
        }
        try {
            val store = CompanionKnowledgeStore(context)
            repeat(21) { index ->
                val originalId = "%064x".format(index + 1)
                store.registerOriginal(originalId, "图片$index.png", "image/png")
                store.registerImageAnalysis(originalId, "图片描述 $index")
            }
            store.registerOriginal("f".repeat(64), "未解析.pdf", "application/pdf")
            val dashboard = CompanionDashboard(context)
            val first = dashboard.execute(JSONObject().put("operation", "knowledgeList")
                .put("query", "").put("source", "image_analysis").put("coverage", "model_description"))
            assertEquals(20, first.getJSONArray("items").length())
            assertEquals(20, first.getInt("nextOffset"))
            for (index in 0 until 20) {
                val row = first.getJSONArray("items").getJSONObject(index)
                assertEquals("image_analysis", row.getString("source"))
                assertEquals("model_description", row.getString("coverage"))
                assertTrue(row.getString("revision").matches(Regex("[a-f0-9]{64}")))
            }
            val second = dashboard.execute(JSONObject().put("operation", "knowledgeList")
                .put("query", "").put("offset", first.getInt("nextOffset"))
                .put("source", "image_analysis").put("coverage", "model_description"))
            assertEquals(1, second.getJSONArray("items").length())
            assertNull(second.opt("nextOffset").takeUnless { it == JSONObject.NULL })
            val originals = dashboard.execute(JSONObject().put("operation", "knowledgeList")
                .put("query", "").put("source", "original").put("coverage", "not_parsed"))
            assertEquals(20, originals.getJSONArray("items").length())
            assertEquals("未解析.pdf", originals.getJSONArray("items").getJSONObject(0).getString("displayName"))
            assertTrue(store.add("等待处理的资料", "text_file"))
            val pending = store.searchPage("等待处理的资料", 0, 1).first.getJSONObject(0)
            val processing = dashboard.execute(JSONObject().put("operation", "knowledgeProcessingStatus")
                .put("id", pending.getString("id")).put("expectedText", pending.getString("text")))
            assertEquals("not_queued", processing.getString("state"))
            val staleStatusFailure = runCatching {
                dashboard.execute(JSONObject().put("operation", "knowledgeProcessingStatus")
                    .put("id", pending.getString("id")).put("expectedText", "已变更"))
            }.exceptionOrNull()
            assertTrue(staleStatusFailure is IllegalStateException)
            assertTrue(store.add("批量资料一", "text_file"))
            assertTrue(store.add("批量资料二", "text_file"))
            val firstBatch = store.searchPage("批量资料一", 0, 1).first.getJSONObject(0)
            val secondBatch = store.searchPage("批量资料二", 0, 1).first.getJSONObject(0)
            val staleBatch = runCatching { dashboard.execute(JSONObject().put("operation", "knowledgeBatchDelete").put("entries", JSONArray()
                .put(JSONObject().put("id", firstBatch.getString("id")).put("expectedText", firstBatch.getString("text")))
                .put(JSONObject().put("id", secondBatch.getString("id")).put("expectedText", "已变更")))) }.exceptionOrNull()
            assertTrue(staleBatch is IllegalStateException)
            assertEquals(1, store.searchPage("批量资料一", 0, 1).first.length())
            val deleted = dashboard.execute(JSONObject().put("operation", "knowledgeBatchDelete").put("entries", JSONArray()
                .put(JSONObject().put("id", firstBatch.getString("id")).put("expectedText", firstBatch.getString("text")))
                .put(JSONObject().put("id", secondBatch.getString("id")).put("expectedText", secondBatch.getString("text")))))
            assertEquals(2, deleted.getInt("deleted"))
            assertEquals(0, store.searchPage("批量资料", 0, 2).first.length())
            assertTrue(dashboard.execute(JSONObject().put("operation", "knowledgeBatchUndoDelete").put("ids", JSONArray().put(firstBatch.getString("id")).put(secondBatch.getString("id")))).getBoolean("ok"))
            assertEquals(2, store.searchPage("批量资料", 0, 2).first.length())
            val invalidFilterFailure = runCatching {
                dashboard.execute(JSONObject().put("operation", "knowledgeList").put("source", "unknown"))
            }.exceptionOrNull()
            assertTrue(invalidFilterFailure is IllegalArgumentException)
        } finally {
            openedPreferences.forEach(base::deleteSharedPreferences)
            directory.deleteRecursively()
        }
    }
}

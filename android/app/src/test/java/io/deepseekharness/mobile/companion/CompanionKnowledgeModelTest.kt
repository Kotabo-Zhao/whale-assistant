package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionKnowledgeModelTest {
    @Test fun excerptsDoNotCutSurrogatePairsAtEitherBoundary() {
        val text = "a".repeat(99) + "🐋" + "x".repeat(199) + "needle" + "y".repeat(794) + "🐋" + "z".repeat(30)
        val rows = JSONArray().put(JSONObject().put("text", text).put("createdAt", 1).put("source", "shared_text"))
        val result = JSONObject(CompanionKnowledgeModel.execute(true, "{\"query\":\"needle\"}") { rows })
        val excerpt = result.getJSONArray("items").getJSONObject(0).getString("excerpt")
        CompanionKnowledgeData.utf8(excerpt)
        assertTrue(excerpt.contains("needle"))
        assertEquals(999, excerpt.length)
    }
    @Test fun checksConsentAndReturnsBoundedMatchingExcerpts() {
        var loads = 0
        val rows = JSONArray()
        repeat(4) { rows.put(JSONObject().put("text", "x".repeat(900) + "关键字" + "y".repeat(300)).put("createdAt", it + 1).put("source", "shared_text")) }
        assertThrows(RuntimeFailure::class.java) { CompanionKnowledgeModel.execute(false, "{\"query\":\"关键字\"}") { loads++; rows } }
        assertEquals(0, loads)
        val result = JSONObject(CompanionKnowledgeModel.execute(true, "{\"query\":\"关键字\"}") { loads++; rows })
        assertEquals(1, loads)
        assertEquals(3, result.getJSONArray("items").length())
        assertTrue(result.getBoolean("truncated"))
        val item = result.getJSONArray("items").getJSONObject(0)
        assertTrue(item.getString("excerpt").contains("关键字"))
        assertTrue(item.getString("excerpt").length <= 1000)
        assertTrue(item.getBoolean("excerptTruncated"))
    }
    @Test fun relatedLookupIsMarkedAndShowsMatchingContext() {
        val rows = JSONArray().put(JSONObject().put("text", "x".repeat(1100) + "咖啡偏好是拿铁" + "y".repeat(1200)).put("createdAt", 1).put("source", "text_file"))
        val result = JSONObject(CompanionKnowledgeModel.execute(true, "{\"query\":\"我的咖啡偏好是什么\"}") { rows })
        assertEquals("related", result.getString("matchMode"))
        assertEquals(1, result.getJSONArray("items").length())
        assertEquals("text_file", result.getJSONArray("items").getJSONObject(0).getString("source"))
        assertTrue(result.getJSONArray("items").getJSONObject(0).getString("excerpt").contains("咖啡偏好"))
        assertTrue(result.getJSONArray("items").getJSONObject(0).getBoolean("excerptTruncated"))
        val absent = JSONObject(CompanionKnowledgeModel.execute(true, "{\"query\":\"完全无关\"}") { rows })
        assertEquals("none", absent.getString("matchMode"))
        assertEquals(0, absent.getJSONArray("items").length())
    }
    @Test fun relatedExcerptPrefersAClusterOfQueryTermsOverAnIsolatedEarlyTerm() {
        val text = "其他偏好" + "x".repeat(2000) + "咖啡偏好是拿铁" + "z".repeat(1200)
        val rows = JSONArray().put(JSONObject().put("text", text).put("createdAt", 1).put("source", "text_file"))
        val result = JSONObject(CompanionKnowledgeModel.execute(true, "{\"query\":\"我的咖啡偏好是什么\"}") { rows })
        assertEquals("related", result.getString("matchMode"))
        val excerpt = result.getJSONArray("items").getJSONObject(0).getString("excerpt")
        assertTrue(excerpt.contains("咖啡偏好是拿铁"))
        assertFalse(excerpt.contains("其他偏好"))
        assertTrue(excerpt.length <= 1000)
    }
    @Test fun invalidSearchDoesNotLoadData() {
        for (param in listOf("{}", "{\"query\":\"\"}", "{\"query\":\"x\",\"limit\":4}", "{\"query\":\"x\",\"limit\":1.5}")) {
            assertThrows(RuntimeFailure::class.java) { CompanionKnowledgeModel.execute(true, param) { fail("must not load"); JSONArray() } }
        }
    }
}

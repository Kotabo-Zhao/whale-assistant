package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionKnowledgeModelTest {
    @Test fun checksConsentAndReturnsBoundedMatchingExcerpts() {
        var loads = 0
        val rows = JSONArray()
        repeat(4) { rows.put(JSONObject().put("text", "x".repeat(900) + "关键字" + "y".repeat(300)).put("createdAt", it + 1)) }
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
        val rows = JSONArray().put(JSONObject().put("text", "x".repeat(1100) + "咖啡偏好是拿铁" + "y".repeat(1200)).put("createdAt", 1))
        val result = JSONObject(CompanionKnowledgeModel.execute(true, "{\"query\":\"我的咖啡偏好是什么\"}") { rows })
        assertEquals("related", result.getString("matchMode"))
        assertEquals(1, result.getJSONArray("items").length())
        assertTrue(result.getJSONArray("items").getJSONObject(0).getString("excerpt").contains("咖啡偏好"))
        assertTrue(result.getJSONArray("items").getJSONObject(0).getBoolean("excerptTruncated"))
        val absent = JSONObject(CompanionKnowledgeModel.execute(true, "{\"query\":\"完全无关\"}") { rows })
        assertEquals("none", absent.getString("matchMode"))
        assertEquals(0, absent.getJSONArray("items").length())
    }
    @Test fun invalidSearchDoesNotLoadData() {
        for (param in listOf("{}", "{\"query\":\"\"}", "{\"query\":\"x\",\"limit\":4}", "{\"query\":\"x\",\"limit\":1.5}")) {
            assertThrows(RuntimeFailure::class.java) { CompanionKnowledgeModel.execute(true, param) { fail("must not load"); JSONArray() } }
        }
    }
}

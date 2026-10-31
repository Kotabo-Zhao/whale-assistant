package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionKnowledgeModelTest {
    @Test fun recentUsesBoundedDatabasePageAndPreservesPagination() {
        var calls = 0
        val page: (Int, Int) -> JSONArray = { offset, count ->
            calls++
            assertEquals(3, offset); assertEquals(3, count)
            JSONArray().put(JSONObject().put("id", "four").put("text", "four").put("createdAt", 4).put("source", "shared_text"))
                .put(JSONObject().put("id", "five").put("text", "five").put("createdAt", 5).put("source", "shared_text"))
                .put(JSONObject().put("id", "six").put("text", "six").put("createdAt", 6).put("source", "shared_text"))
        }
        val request = "{\"query\":\"\",\"offset\":3,\"limit\":2}"
        assertThrows(RuntimeFailure::class.java) { CompanionKnowledgeModel.execute(false, request, recent = page) { fail("scan"); JSONArray() } }
        assertEquals(0, calls)
        val result = JSONObject(CompanionKnowledgeModel.execute(true, request, recent = page) { fail("scan"); JSONArray() })
        assertEquals(1, calls)
        assertEquals("four", result.getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals(2, result.getJSONArray("items").length())
        assertEquals(5, result.getInt("nextOffset"))
        assertEquals("recent", result.getString("matchMode"))
    }
    @Test fun searchPaginatesBeyondTenMatches() {
        val rows = JSONArray()
        repeat(15) { rows.put(JSONObject().put("id", "item-$it").put("text", "needle $it").put("source", "text_file").put("createdAt", it + 1)) }
        val seen = mutableListOf<String>()
        var offset = 0
        while (true) {
            val result = JSONObject(CompanionKnowledgeModel.execute(true, JSONObject().put("query", "needle").put("offset", offset).toString()) { rows })
            val items = result.getJSONArray("items")
            for (i in 0 until items.length()) seen.add(items.getJSONObject(i).getString("id"))
            if (result.isNull("nextOffset")) break
            offset = result.getInt("nextOffset")
        }
        assertEquals(15, seen.size)
        assertEquals(15, seen.toSet().size)
    }
    @Test fun searchCanRestrictKnowledgeToAnExplicitCreatedTimeRange() {
        val rows = JSONArray()
            .put(JSONObject().put("id", "old").put("text", "旧会议资料").put("source", "text_file").put("createdAt", 100L))
            .put(JSONObject().put("id", "current").put("text", "当前会议资料").put("source", "text_file").put("createdAt", 200L))
        val request = JSONObject().put("query", "会议").put("fromMs", 200L).put("toMs", 300L).toString()
        val result = JSONObject(CompanionKnowledgeModel.execute(true, request) { rows })
        assertEquals("current", result.getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals(200L, result.getLong("fromMs")); assertEquals(300L, result.getLong("toMs"))
    }
    @Test fun pointReadUsesLookupAndStillEnforcesConsentAndRevision() {
        val row = JSONObject().put("id", "record").put("text", "hello")
        var calls = 0
        val lookup: (String) -> JSONObject? = { id -> calls++; if (id == "record") row else null }
        val request = JSONObject().put("query", "").put("id", "record")
        assertThrows(RuntimeFailure::class.java) { CompanionKnowledgeModel.execute(false, request.toString(), lookup) { fail("scan"); JSONArray() } }
        assertEquals(0, calls)
        val result = JSONObject(CompanionKnowledgeModel.execute(true, request.toString(), lookup) { fail("scan"); JSONArray() })
        assertEquals("hello", result.getString("text"))
        request.put("revision", "stale")
        assertThrows(RuntimeFailure::class.java) { CompanionKnowledgeModel.execute(true, request.toString(), lookup) { fail("scan"); JSONArray() } }
        request.put("id", "missing").remove("revision")
        assertThrows(RuntimeFailure::class.java) { CompanionKnowledgeModel.execute(true, request.toString(), lookup) { fail("scan"); JSONArray() } }
    }
    @Test fun documentFilterPreservesChunkOrderAndPagination() {
        val document="a".repeat(64)
        val rows=JSONArray()
        for(i in 3 downTo 0) rows.put(JSONObject().put("id","part-$i").put("text","part $i").put("source","text_file").put("createdAt",1).put("documentId",document).put("chunkIndex",i).put("chunkCount",4))
        rows.put(JSONObject().put("text","unrelated").put("source","text_file").put("createdAt",2))
        val first=JSONObject(CompanionKnowledgeModel.execute(true,JSONObject().put("query","").put("documentId",document).toString()){rows})
        assertEquals("part-0",first.getJSONArray("items").getJSONObject(0).getString("id"))
        assertEquals(3,first.getInt("nextOffset"))
        val last=JSONObject(CompanionKnowledgeModel.execute(true,JSONObject().put("query","").put("documentId",document).put("offset",3).toString()){rows})
        assertEquals("part-3",last.getJSONArray("items").getJSONObject(0).getString("id"))
        assertTrue(last.isNull("nextOffset"))
    }
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
        for (param in listOf("{}", "{\"query\":\"x\",\"limit\":4}", "{\"query\":\"x\",\"limit\":1.5}")) {
            assertThrows(RuntimeFailure::class.java) { CompanionKnowledgeModel.execute(true, param) { fail("must not load"); JSONArray() } }
        }
    }
    @Test fun recentRecordsCanBeReadInChunksAndDeletedRecordsFail() {
        val text = "a".repeat(3999) + "🐋" + "tail"
        val rows = JSONArray().put(JSONObject().put("id", "record").put("text", text).put("createdAt", 1).put("source", "text_file"))
        val recent = JSONObject(CompanionKnowledgeModel.execute(true, JSONObject().put("query", "").toString()) { rows })
        assertEquals("record", recent.getJSONArray("items").getJSONObject(0).getString("id"))
        val first = JSONObject(CompanionKnowledgeModel.execute(true, JSONObject().put("query", "").put("id", "record").toString()) { rows })
        assertEquals(3999, first.getInt("nextOffset"))
        val second = JSONObject(CompanionKnowledgeModel.execute(true, JSONObject().put("query", "").put("id", "record").put("offset", first.getInt("nextOffset")).toString()) { rows })
        assertEquals(text, first.getString("text") + second.getString("text"))
        assertTrue(second.isNull("nextOffset"))
        assertThrows(RuntimeFailure::class.java) { CompanionKnowledgeModel.execute(true, JSONObject().put("query", "").put("id", "record").toString()) { JSONArray() } }
    }
}

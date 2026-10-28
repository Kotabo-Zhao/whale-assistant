package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionKnowledgeStoreTest {
    @Test fun searchAndDeleteRequireMatchingUnchangedContent() {
        val rows = JSONArray()
        assertTrue(CompanionKnowledgeData.add(rows, "会议纪要\n项目进度", 1))
        assertTrue(CompanionKnowledgeData.add(rows, "Useful link https://example.com", 2))
        assertFalse(CompanionKnowledgeData.add(rows, "会议纪要\n项目进度", 3))
        assertEquals(1, CompanionKnowledgeData.search(rows, "项目").length())
        assertEquals(1, CompanionKnowledgeData.search(rows, "USEFUL").length())
        assertEquals(2, CompanionKnowledgeData.search(rows, "").length())
        val first = rows.getJSONObject(0)
        val id = first.getString("id")
        assertNull(CompanionKnowledgeData.remove(rows, id, "changed"))
        val kept = CompanionKnowledgeData.remove(rows, id, first.getString("text"))!!
        assertEquals(1, kept.length())
        assertEquals(2, rows.length())
        assertEquals(2, CompanionKnowledgeData.parse(JSONObject().put("version", 1).put("items", rows).toString()).length())
    }
    @Test fun invalidAndOversizedDataIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CompanionKnowledgeData.add(JSONArray(), "x".repeat(8001), 1) }
        assertThrows(IllegalArgumentException::class.java) { CompanionKnowledgeData.search(JSONArray(), "x".repeat(201)) }
        assertThrows(Exception::class.java) { CompanionKnowledgeData.parse("{bad") }
        assertThrows(Exception::class.java) { CompanionKnowledgeData.parse("{\"version\":2,\"items\":[]}") }
        assertThrows(Exception::class.java) { CompanionKnowledgeData.parse("{\"version\":1,\"items\":[{}]}") }
    }
}

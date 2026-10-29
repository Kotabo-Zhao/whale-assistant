package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionUsageMemoryTest {
    @Test fun `summarizes only authorized usable observations excluding own and blocked apps`() {
        val rows = listOf(CompanionUsage.Candidate("com.example.chat", 3600000), CompanionUsage.Candidate("com.example.blocked", 3600000), CompanionUsage.Candidate("com.example.own", 3600000), CompanionUsage.Candidate("com.example.short", 1000))
        assertNull(CompanionUsageMemory.summary(CompanionUsage.Snapshot(CompanionUsage.State.NO_PERMISSION, rows), "com.example.own", emptySet()))
        val notices = JSONArray().put(JSONObject().put("package", "com.example.chat")).put(JSONObject().put("package", "com.example.chat"))
        val text = CompanionUsageMemory.summary(CompanionUsage.Snapshot(CompanionUsage.State.OK, rows), "com.example.own", setOf("com.example.blocked"), notices)!!
        assertTrue(text.contains("com.example.chat约60分钟")); assertFalse(text.contains("blocked")); assertFalse(text.contains("own")); assertFalse(text.contains("short"))
        assertTrue(text.contains("非偏好判断")); assertTrue(text.contains("留存2条通知"))
    }
    @Test fun `upsert updates only automatic record and preserves user memories`() {
        val rows = JSONArray().put(JSONObject().put("id", "user").put("text", "用户的记忆").put("source", "user_edit"))
        assertTrue(CompanionUsageMemory.upsert(rows, "第一次统计", 1))
        val id = rows.getJSONObject(1).getString("id")
        assertFalse(CompanionUsageMemory.upsert(rows, "第一次统计", 2))
        assertTrue(CompanionUsageMemory.upsert(rows, "新的统计", 3))
        assertEquals(2, rows.length()); assertEquals("用户的记忆", rows.getJSONObject(0).getString("text"))
        assertEquals(id, rows.getJSONObject(1).getString("id")); assertEquals("新的统计", rows.getJSONObject(1).getString("text"))
        assertEquals("auto_usage", CompanionMemorySearch.publicItem(rows.getJSONObject(1)).getString("source"))
    }
    @Test fun `full memory does not overwrite existing records`() {
        val rows = JSONArray(); repeat(100) { rows.put(JSONObject().put("text", "entry $it").put("source", "user_edit")) }
        assertFalse(CompanionUsageMemory.upsert(rows, "统计", 1)); assertEquals(100, rows.length())
    }
}

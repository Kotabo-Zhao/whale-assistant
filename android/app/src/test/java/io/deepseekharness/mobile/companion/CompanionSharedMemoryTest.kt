package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionSharedMemoryTest {
    @Test fun sharedFactsAreDeduplicatedSuppressedAndClearlyUnverified() {
        val rows = JSONArray()
        val event = "share:" + "a".repeat(64)
        val claims = JSONArray().put(JSONObject().put("text", "小鲸喜欢喝茉莉茶"))
            .put(JSONObject().put("text", "已经删除的内容"))
        assertEquals(1, CompanionSharedMemory.add(rows, event, claims, 1L, false) { it == "已经删除的内容" })
        assertEquals(0, CompanionSharedMemory.add(rows, event, claims, 2L, false) { it == "已经删除的内容" })
        assertEquals(event, rows.getJSONObject(0).getString("evidenceEventId"))
        assertEquals("pending", rows.getJSONObject(0).getString("status"))
        assertEquals("unverified", CompanionMemorySearch.publicItem(rows.getJSONObject(0)).getString("status"))
        rows.getJSONObject(0).put("status", "confirmed").put("confirmedAt", 10)
        assertEquals("confirmed", CompanionMemorySearch.publicItem(rows.getJSONObject(0)).getString("status"))
        assertNull(CompanionBubbleChatPolicy.proactiveMemory(JSONArray().put(JSONObject(rows.getJSONObject(0).toString()).apply { remove("confirmedAt") })))
    }
    @Test fun backupRetainsSourceAndDeletedEventBlocksReimport() {
        val event = "share:" + "c".repeat(64)
        val recordId = "123e4567-e89b-12d3-a456-426614174000"; val revision = "d".repeat(64)
        val rows = JSONArray()
        CompanionSharedMemory.add(rows, event, JSONArray().put(JSONObject().put("text", "鲸鱼喜欢茉莉茶")), 1, false, { false }, recordId, revision)
        val backup = CompanionMemoryBackup.encode(rows, false, emptySet(), emptySet())
        val imported = JSONArray(CompanionMemoryImport.plan(backup, JSONArray()).additions).getJSONObject(0)
        assertEquals("shared_understanding", imported.getString("source"))
        assertEquals(event, imported.getString("evidenceEventId"))
        assertEquals(recordId, imported.getString("sourceRecordId")); assertEquals(revision, imported.getString("sourceRevision"))
        val blocked = CompanionMemoryImport.plan(backup, JSONArray(), existingDeletedEvents = setOf(CompanionMemorySuppression.digest("event:$event")))
        assertEquals(0, JSONArray(blocked.additions).length())
    }
    @Test fun sharedMemoryCanCarryOnlyAStableKnowledgeReference() {
        val rows = JSONArray(); val event = "share:" + "b".repeat(64)
        val recordId = "123e4567-e89b-12d3-a456-426614174000"; val revision = "c".repeat(64)
        CompanionSharedMemory.add(rows, event, JSONArray().put(JSONObject().put("text", "合同待确认")), 1L, false, { false }, recordId, revision)
        val public = CompanionMemorySearch.publicItem(rows.getJSONObject(0))
        assertEquals(recordId, public.getString("sourceRecordId")); assertEquals(revision, public.getString("sourceRevision"))
        assertFalse(public.has("originalText"))
    }
}

package io.deepseekharness.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionWorkspaceStoreTest {
    @Test fun acceptsFullWorkspaceWithLongChineseTitles() {
        val rows = JSONArray()
        repeat(500) { rows.put(JSONObject().put("id", "session-$it").put("title", "鲸".repeat(200))) }
        val bytes = JSONObject().put("version", 1).put("entries", rows).toString().toByteArray()
        check(bytes.size > 150_000)
        assertEquals(500, SessionWorkspaceStore.decode(bytes).size)
    }

    @Test fun archivePayloadRoundTripsAndRejectsDuplicateOrUnsafeEntries() {
        val entry = SessionWorkspaceStore.Entry("session-abc", "项目讨论", "工作", true, true)
        val bytes = JSONObject().put("version", 1).put("entries", JSONArray().put(
            JSONObject().put("id", entry.id).put("title", entry.title).put("group", entry.group ?: JSONObject.NULL).put("pinned", entry.pinned).put("archived", entry.archived),
        )).toString().toByteArray()
        assertEquals(listOf(entry), SessionWorkspaceStore.decode(bytes))
        val duplicate = JSONObject().put("version", 1).put("entries", JSONArray()
            .put(JSONObject().put("id", entry.id)).put(JSONObject().put("id", entry.id)))
        assertThrows(IllegalArgumentException::class.java) { SessionWorkspaceStore.decode(duplicate.toString().toByteArray()) }
        val unsafe = JSONObject().put("version", 1).put("entries", JSONArray().put(
            JSONObject().put("id", "session-../escape"),
        ))
        assertThrows(IllegalArgumentException::class.java) { SessionWorkspaceStore.decode(unsafe.toString().toByteArray()) }
    }
}

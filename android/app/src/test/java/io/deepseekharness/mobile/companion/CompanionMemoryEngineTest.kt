package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CompanionMemoryEngineTest {
    @Test fun confirmedMemoryEditsAndDeletesAreBoundToReviewedRecord() {
        val rows = JSONArray()
        CompanionUserMemory.add(rows, "原记忆", "user_notification", 100L)
        CompanionUserMemory.add(rows, "另一条", "user_edit", 101L)
        val row = rows.getJSONObject(0)
        val id = row.getString("id")
        val reviewed = row.toString()
        assertThrows(Exception::class.java) { CompanionUserMemory.update(rows, id, reviewed, "另一条", 102L) }
        assertEquals(reviewed, row.toString())
        row.put("confirmedAt", 103L)
        assertFalse(CompanionUserMemory.update(rows, id, reviewed, "更新", 104L))
        assertNull(CompanionUserMemory.delete(rows, id, reviewed))
        assertTrue(CompanionUserMemory.update(rows, id, row.toString(), " 更新 ", 105L))
        assertEquals("更新", row.getString("text")); assertEquals("user_edit", row.getString("source"))
        assertEquals(105L, row.getLong("updatedAt")); assertEquals(100L, row.getLong("createdAt"))
        val kept = CompanionUserMemory.delete(rows, id, row.toString())!!
        assertEquals(1, kept.length()); assertEquals(2, rows.length())
        assertEquals(1, JSONArray(CompanionMemoryImport.plan(CompanionMemoryExport.encode(kept), JSONArray()).additions).length())
        row.put("status", "pending")
        assertFalse(CompanionUserMemory.update(rows, id, row.toString(), "不应确认", 106L))
    }
    @Test fun editedPendingMemoryIsConfirmedAtomicallyAndStaleChangesAreRejected() {
        val id = "00000000-0000-0000-0000-000000000001"
        val rows = JSONArray().put(JSONObject().put("id", id).put("text", "候选原文").put("status", "pending").put("source", "assistant_tool"))
        val before = rows.toString()
        assertFalse(CompanionMemoryApproval.approve(rows, id, 100, "已过期的文本"))
        assertFalse(CompanionMemoryApproval.approveEdited(rows, id, "已过期的文本", "修改内容", 100))
        assertEquals(before, rows.toString())
        assertTrue(CompanionMemoryApproval.approveEdited(rows, id, "候选原文", "  修改内容  ", 123))
        val row = rows.getJSONObject(0)
        assertEquals("修改内容", row.getString("text"))
        assertEquals("confirmed", row.getString("status"))
        assertEquals("user_edit", row.getString("source"))
        assertEquals(123L, row.getLong("confirmedAt"))
        assertFalse(CompanionMemoryApproval.approveEdited(rows, id, "修改内容", "再次修改", 124))
        assertEquals("修改内容", row.getString("text"))
    }
    @Test fun editedApprovalRejectsSensitiveAndDuplicateWithoutChangingPending() {
        val id = "00000000-0000-0000-0000-000000000001"
        val rows = JSONArray().put(JSONObject().put("id", id).put("text", "候选").put("status", "pending"))
            .put(JSONObject().put("id", "00000000-0000-0000-0000-000000000002").put("text", "已有记忆").put("status", "confirmed"))
        val before = rows.toString()
        for (text in listOf("", "密码 123456", "已有记忆")) {
            assertThrows(Exception::class.java) { CompanionMemoryApproval.approveEdited(rows, id, "候选", text, 123) }
        }
        assertEquals(before, rows.toString())
    }

    @Test fun userAuthoredNotificationMemoryIsConfirmedAndDoesNotCopyNotificationMetadata() {
        val rows = JSONArray()
        assertEquals(UserMemoryResult.ADDED, CompanionUserMemory.add(rows, "  我偏好提前一天提醒  ", "user_notification", 123L))
        val row = rows.getJSONObject(0)
        assertEquals("我偏好提前一天提醒", row.getString("text"))
        assertEquals("user_notification", row.getString("source"))
        assertEquals("confirmed", row.getString("status"))
        assertFalse(row.has("package"))
        assertFalse(row.has("notification"))
        val search = JSONObject(CompanionMemoryEngine({ rows }, {}).execute("{\"op\":\"search\",\"query\":\"提前一天\"}"))
        assertEquals(1, search.getJSONArray("items").length())
        val import = CompanionMemoryImport.plan(CompanionMemoryExport.encode(rows), JSONArray())
        assertEquals("user_notification", JSONArray(import.additions).getJSONObject(0).getString("source"))
    }

    @Test fun manualMemoryDoesNotApproveDuplicatePendingOrOverwriteOnInvalidInput() {
        val rows = JSONArray().put(JSONObject().put("id", "00000000-0000-0000-0000-000000000001").put("text", "已有候选").put("status", "pending"))
        val before = rows.toString()
        assertEquals(UserMemoryResult.EXISTING_PENDING, CompanionUserMemory.add(rows, "已有候选", "user_edit", 123L))
        assertEquals(before, rows.toString())
        for (value in listOf("", "密码 123456", "a".repeat(601))) {
            assertThrows(Exception::class.java) { CompanionUserMemory.add(rows, value, "user_notification", 123L) }
        }
        assertThrows(Exception::class.java) { CompanionUserMemory.add(rows, "新内容", "assistant_tool", 123L) }
        assertEquals(before, rows.toString())
    }

    @Test fun savesRecallsAcrossInstancesDeduplicatesAndDeletes() {
        val path = Files.createTempFile("companion-memory-test", ".json")
        try {
            path.toFile().writeText("[]")
            fun engine() = CompanionMemoryEngine({ JSONArray(path.toFile().readText()) }, { path.toFile().writeText(it.toString()) })
            val saved = JSONObject(engine().execute(JSONObject().put("op", "save").put("text", "我喜欢蓝色").toString()))
            val id = saved.getString("id")
            assertFalse(saved.getBoolean("alreadyExists"))
            val duplicate = JSONObject(engine().execute(JSONObject().put("op", "save").put("text", "我喜欢蓝色").toString()))
            assertTrue(duplicate.getBoolean("alreadyExists"))
            assertTrue(duplicate.getBoolean("pending"))
            assertEquals(id, duplicate.getString("id"))
            assertTrue(saved.getBoolean("pending"))
            val hidden = JSONObject(engine().execute(JSONObject().put("op", "search").put("query", "蓝色").toString()))
            assertEquals(0, hidden.getJSONArray("items").length())
            assertEquals(0, hidden.getInt("total"))
            val pending = JSONArray(path.toFile().readText())
            assertFalse(CompanionMemoryApproval.approve(pending, "missing", 123))
            assertTrue(CompanionMemoryApproval.approve(pending, id, 123))
            assertEquals(123L, pending.getJSONObject(0).getLong("confirmedAt"))
            assertFalse(CompanionMemoryApproval.approve(pending, id, 456))
            path.toFile().writeText(pending.toString())
            val results = JSONObject(engine().execute(JSONObject().put("op", "search").put("query", "蓝色").toString()))
            assertEquals(id, results.getJSONArray("items").getJSONObject(0).getString("id"))
            assertEquals("assistant_tool", results.getJSONArray("items").getJSONObject(0).getString("source"))
            assertTrue(JSONObject(engine().execute(JSONObject().put("op", "delete").put("id", id).toString())).getBoolean("removed"))
            assertEquals(0, JSONArray(path.toFile().readText()).length())
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun searchesRelatedKeywordsWithoutReturningPendingOrInventingMatches() {
        val rows = JSONArray()
            .put(JSONObject().put("id", "00000000-0000-0000-0000-000000000001").put("text", "我喜欢喝冰美式咖啡").put("status", "confirmed"))
            .put(JSONObject().put("id", "00000000-0000-0000-0000-000000000002").put("text", "喜欢喝浓缩咖啡").put("status", "pending"))
            .put(JSONObject().put("id", "00000000-0000-0000-0000-000000000003").put("text", "我喜欢蓝色").put("status", "confirmed"))
        val engine = CompanionMemoryEngine({ rows }, { })
        fun search(query: String) = JSONObject(engine.execute(JSONObject().put("op", "search").put("query", query).toString()))
        val related = search("我平时喜欢喝什么咖啡")
        assertEquals("related", related.getString("matchMode"))
        assertEquals(1, related.getJSONArray("items").length())
        assertEquals("我喜欢喝冰美式咖啡", related.getJSONArray("items").getJSONObject(0).getString("text"))
        assertEquals("exact", search("蓝色").getString("matchMode"))
        assertEquals("none", search("什么颜色").getString("matchMode"))
        assertEquals(0, search("什么颜色").getJSONArray("items").length())
        assertEquals("recent", search("").getString("matchMode"))
        assertEquals(2, search("").getJSONArray("items").length())
    }
    @Test fun exactResultsTakePriorityAndAreLimitedToTen() {
        val rows = JSONArray()
        for (index in 0 until 12) rows.put(JSONObject().put("id", "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}").put("text", "咖啡 $index").put("status", "confirmed"))
        rows.put(JSONObject().put("id", "00000000-0000-0000-0000-000000000099").put("text", "喜欢喝咖啡").put("status", "pending"))
        val result = JSONObject(CompanionMemoryEngine({ rows }, { }).execute("{\"op\":\"search\",\"query\":\"咖啡\"}"))
        assertEquals("exact", result.getString("matchMode"))
        assertEquals(12, result.getInt("total"))
        assertEquals(10, result.getJSONArray("items").length())
        assertEquals("咖啡 11", result.getJSONArray("items").getJSONObject(0).getString("text"))
    }
    @Test fun readsLegacyMemoryWithoutSource() {
        val legacy = JSONArray().put(JSONObject().put("id", "00000000-0000-0000-0000-000000000001").put("text", "旧偏好"))
        val engine = CompanionMemoryEngine({ legacy }, { })
        val result = JSONObject(engine.execute(JSONObject().put("op", "search").put("query", "旧偏好").toString()))
        assertEquals("旧偏好", result.getJSONArray("items").getJSONObject(0).getString("text"))
    }

    @Test fun rejectsSensitiveAndOversizedMemories() {
        val engine = CompanionMemoryEngine({ JSONArray() }, { })
        for (value in listOf("", "密码 abc", "api key: sk-abcdefghijklmnop", "a".repeat(601))) {
            try {
                engine.execute(JSONObject().put("op", "save").put("text", value).toString())
                fail("Expected rejection")
            } catch (error: RuntimeFailure) { assertEquals("MEMORY_INPUT_INVALID", error.code) }
        }
    }
    @Test fun exportsVersionedUtf8SnapshotWithoutModifyingItems() {
        val items = JSONArray().put(JSONObject().put("id", "00000000-0000-0000-0000-000000000001")
            .put("text", "喜欢蓝色 🐋").put("source", "user_edit"))
        val before = items.toString()
        val exported = JSONObject(String(CompanionMemoryExport.encode(items), Charsets.UTF_8))
        assertEquals(1, exported.getInt("version"))
        assertEquals("喜欢蓝色 🐋", exported.getJSONArray("items").getJSONObject(0).getString("text"))
        assertEquals("user_edit", exported.getJSONArray("items").getJSONObject(0).getString("source"))
        assertEquals(before, items.toString())
        assertEquals(0, JSONObject(String(CompanionMemoryExport.encode(JSONArray()), Charsets.UTF_8)).getJSONArray("items").length())
    }
    @Test fun importPreviewMergesWithoutOverwritingOrMutatingExistingRows() {
        val old = JSONObject().put("id", "00000000-0000-0000-0000-000000000001").put("text", "旧记忆")
        val current = JSONArray().put(old)
        val incoming = JSONArray().put(JSONObject().put("id", old.getString("id")).put("text", "不应覆盖"))
            .put(JSONObject().put("id", "00000000-0000-0000-0000-000000000002").put("text", "旧记忆"))
            .put(JSONObject().put("id", "00000000-0000-0000-0000-000000000003").put("text", "新增记忆").put("source", "user_edit").put("status", "pending").put("unexpected", "ignored"))
        val before = current.toString()
        val plan = CompanionMemoryImport.plan(CompanionMemoryExport.encode(incoming), current)
        assertEquals(2, plan.skipped)
        assertEquals(before, current.toString())
        val added = JSONArray(plan.additions)
        assertEquals(1, added.length())
        assertEquals("新增记忆", added.getJSONObject(0).getString("text"))
        assertFalse(added.getJSONObject(0).has("unexpected"))
        assertEquals("pending", added.getJSONObject(0).getString("status"))
    }
    @Test fun importRejectsSensitiveInvalidOversizedOrTooMany() {
        val id = "00000000-0000-0000-0000-000000000001"
        val invalid = JSONArray().put(JSONObject().put("id", id).put("text", "密码 123456"))
        assertThrows(Exception::class.java) { CompanionMemoryImport.plan(CompanionMemoryExport.encode(invalid), JSONArray()) }
        assertThrows(Exception::class.java) { CompanionMemoryImport.plan(ByteArray(256_001), JSONArray()) }
        assertThrows(Exception::class.java) { CompanionMemoryImport.plan("{\"version\":2,\"items\":[]}".toByteArray(), JSONArray()) }
        assertThrows(Exception::class.java) { CompanionMemoryImport.plan(byteArrayOf(0xc3.toByte(), 0x28), JSONArray()) }
    }
    @Test fun onlyNewPendingSaveProducesGenericHint() {
        val request = "{\"op\":\"save\",\"text\":\"private\"}"
        assertTrue(CompanionMemoryHint.shouldNotify(request, "{\"pending\":true,\"alreadyExists\":false}"))
        assertFalse(CompanionMemoryHint.shouldNotify(request, "{\"pending\":true,\"alreadyExists\":true}"))
        assertFalse(CompanionMemoryHint.shouldNotify(request, "{\"pending\":false,\"alreadyExists\":false}"))
        assertFalse(CompanionMemoryHint.shouldNotify("{\"op\":\"search\"}", "{\"pending\":true,\"alreadyExists\":false}"))
        assertFalse(CompanionMemoryHint.shouldNotify(request, "not json"))
    }
}

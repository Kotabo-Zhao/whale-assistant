package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class CompanionMemoryEngineTest {
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
            assertEquals(0, JSONObject(engine().execute(JSONObject().put("op", "search").put("query", "蓝色").toString())).getJSONArray("items").length())
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
}

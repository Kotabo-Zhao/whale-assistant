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
    @Test fun editPreservesIdentityAndRejectsStaleOrDuplicateValues() {
        val rows = JSONArray()
        CompanionKnowledgeData.add(rows, "原内容", 1, "text_file")
        CompanionKnowledgeData.add(rows, "另一条", 2)
        val id = rows.getJSONObject(0).getString("id")
        assertNull(CompanionKnowledgeData.edit(rows, id, "旧内容", "新内容"))
        assertNull(CompanionKnowledgeData.edit(rows, "missing", "原内容", "新内容"))
        assertThrows(IllegalArgumentException::class.java) { CompanionKnowledgeData.edit(rows, id, "原内容", "另一条") }
        assertThrows(IllegalArgumentException::class.java) { CompanionKnowledgeData.edit(rows, id, "原内容", "x".repeat(8001)) }
        val updated = CompanionKnowledgeData.edit(rows, id, "原内容", "新内容")!!
        assertEquals("原内容", rows.getJSONObject(0).getString("text"))
        assertEquals("新内容", updated.getJSONObject(0).getString("text"))
        assertEquals(id, updated.getJSONObject(0).getString("id"))
        assertEquals("text_file", updated.getJSONObject(0).getString("source"))
        assertEquals(1L, updated.getJSONObject(0).getLong("createdAt"))
        assertEquals(1, CompanionKnowledgeData.search(updated, "新内容").length())
        assertEquals("新内容", JSONArray(CompanionKnowledgeBackup.plan(CompanionKnowledgeBackup.encode(updated), JSONArray()).additions).getJSONObject(0).getString("text"))
    }
    @Test fun backupPreviewMergesWithoutOverwritingExistingContent() {
        val existing = JSONArray()
        CompanionKnowledgeData.add(existing, "已有", 1)
        val incoming = JSONArray(existing.toString())
        CompanionKnowledgeData.add(incoming, "新资料", 2)
        val bytes = CompanionKnowledgeBackup.encode(incoming)
        val plan = CompanionKnowledgeBackup.plan(bytes, existing)
        assertEquals(1, plan.skipped)
        assertEquals(1, JSONArray(plan.additions).length())
        assertEquals("新资料", JSONArray(plan.additions).getJSONObject(0).getString("text"))
        assertEquals(1, existing.length())
        val badId = JSONArray(existing.toString()).put(JSONObject().put("id", existing.getJSONObject(0).getString("id"))
            .put("text", "冲突").put("source", "shared_text").put("createdAt", 3))
        assertThrows(Exception::class.java) { CompanionKnowledgeBackup.plan(CompanionKnowledgeBackup.encode(badId), JSONArray()) }
        assertThrows(Exception::class.java) { CompanionKnowledgeBackup.plan(byteArrayOf(0xc3.toByte(), 0x28), existing) }
        assertThrows(Exception::class.java) { CompanionKnowledgeBackup.plan("{\"version\":1,\"items\":[]}".toByteArray(), existing) }
    }
    @Test fun textFilesRequireValidUtf8AndPreserveSourceThroughBackup() {
        assertEquals("你好\n世界", CompanionKnowledgeTextFile.decode("\uFEFF你好\n世界".toByteArray(Charsets.UTF_8)))
        assertThrows(Exception::class.java) { CompanionKnowledgeTextFile.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(Exception::class.java) { CompanionKnowledgeTextFile.decode("x\u0000y".toByteArray()) }
        assertThrows(Exception::class.java) { CompanionKnowledgeTextFile.decode("x".repeat(8001).toByteArray()) }
        assertThrows(Exception::class.java) { CompanionKnowledgeTextFile.decode(ByteArray(32001)) }
        val rows = JSONArray()
        assertTrue(CompanionKnowledgeData.add(rows, "文件内容", 1, "text_file"))
        val plan = CompanionKnowledgeBackup.plan(CompanionKnowledgeBackup.encode(rows), JSONArray())
        assertEquals("text_file", JSONArray(plan.additions).getJSONObject(0).getString("source"))
        assertThrows(Exception::class.java) { CompanionKnowledgeData.add(rows, "bad", 2, "other") }
    }
    @Test fun storedDataUsesStrictUtf8AndRejectsLossyWrites() {
        val rows = JSONArray()
        CompanionKnowledgeData.add(rows, "你好 🐋", 1)
        val encoded = CompanionKnowledgeData.utf8(JSONObject().put("version", 1).put("items", rows).toString())
        assertEquals("你好 🐋", CompanionKnowledgeData.parseBytes(encoded).getJSONObject(0).getString("text"))
        val prefix = "{\"version\":1,\"items\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"text\":\"".toByteArray()
        val suffix = "\",\"source\":\"shared_text\",\"createdAt\":1}]}".toByteArray()
        assertThrows(Exception::class.java) { CompanionKnowledgeData.parseBytes(prefix + byteArrayOf(0xc3.toByte(), 0x28) + suffix) }
        assertThrows(Exception::class.java) { CompanionKnowledgeData.utf8("invalid \uD800") }
        val invalid = JSONArray(rows.toString()); invalid.getJSONObject(0).put("text", "invalid \uD800")
        assertThrows(Exception::class.java) { CompanionKnowledgeBackup.encode(invalid) }
        assertThrows(Exception::class.java) { CompanionKnowledgeData.parseBytes(ByteArray(CompanionKnowledgeData.MAX_BYTES + 1)) }
        assertThrows(Exception::class.java) { CompanionKnowledgeData.parse("{\"version\":\"1\",\"items\":[]}") }
    }
    @Test fun invalidAndOversizedDataIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { CompanionKnowledgeData.add(JSONArray(), "x".repeat(8001), 1) }
        assertThrows(IllegalArgumentException::class.java) { CompanionKnowledgeData.search(JSONArray(), "x".repeat(201)) }
        assertThrows(Exception::class.java) { CompanionKnowledgeData.parse("{bad") }
        assertThrows(Exception::class.java) { CompanionKnowledgeData.parse("{\"version\":2,\"items\":[]}") }
        assertThrows(Exception::class.java) { CompanionKnowledgeData.parse("{\"version\":1,\"items\":[{}]}") }
    }
}

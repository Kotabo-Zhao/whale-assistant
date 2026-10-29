package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionNotificationMemoryTest {
    @Test fun `notifications from same app merge without overwriting user memory`() {
        val rows = JSONArray().put(JSONObject().put("id", "00000000-0000-0000-0000-000000000001").put("text", "手工记忆").put("source", "user_edit"))
        assertTrue(CompanionNotificationMemory.upsert(rows, "com.example.chat", "日程", "周一开会", 10))
        val id = rows.getJSONObject(1).getString("id")
        assertFalse(CompanionNotificationMemory.upsert(rows, "com.example.chat", "日程", "周一开会", 11))
        assertTrue(CompanionNotificationMemory.upsert(rows, "com.example.chat", "事项", "周二运动", 12))
        assertEquals(2, rows.length()); assertEquals("手工记忆", rows.getJSONObject(0).getString("text"))
        assertEquals(id, rows.getJSONObject(1).getString("id")); assertTrue(rows.getJSONObject(1).getString("text").contains("周一开会；事项：周二运动"))
        assertEquals("auto_notification_group", CompanionMemorySearch.publicItem(rows.getJSONObject(1)).getString("source"))
        val plan = CompanionMemoryImport.plan(CompanionMemoryExport.encode(rows), JSONArray())
        val restored = JSONArray(plan.additions).getJSONObject(1)
        assertEquals("com.example.chat", restored.getString("sourcePackage")); assertEquals(2, restored.getJSONArray("events").length())
    }
    @Test fun `only latest four notices retained and filtering applies`() {
        val rows = JSONArray()
        repeat(5) { assertTrue(CompanionNotificationMemory.upsert(rows, "com.example.chat", "", "消息内容$it", (it + 1).toLong())) }
        assertEquals(4, rows.getJSONObject(0).getJSONArray("events").length())
        assertFalse(rows.getJSONObject(0).getString("text").contains("消息内容0"))
        assertFalse(CompanionNotificationMemory.upsert(rows, "com.example.chat", "验证码", "123456", 10))
        assertFalse(CompanionNotificationMemory.upsert(rows, "bad", "", "消息", 10))
    }
    @Test fun `capacity does not delete existing records`() {
        val rows = JSONArray(); repeat(100) { rows.put(JSONObject().put("source", "user_edit")) }
        assertFalse(CompanionNotificationMemory.upsert(rows, "com.example.chat", "", "开会", 1))
        assertEquals(100, rows.length())
    }
}

package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class CompanionAutomaticMemoryTest {
    @Test fun `notification becomes attributed unverified memory without manual approval`() {
        val text = CompanionAutomaticMemory.fromNotification("com.example.calendar", "日程", "明天开会")!!
        assertEquals("来自 com.example.calendar 的通知（未经核实）：日程：明天开会", text)
        val rows = JSONArray()
        assertEquals(UserMemoryResult.ADDED, CompanionUserMemory.add(rows, text, "auto_notification", 123L))
        assertEquals(UserMemoryResult.EXISTING_CONFIRMED, CompanionUserMemory.add(rows, text, "auto_notification", 124L))
        assertEquals("auto_notification", CompanionMemorySearch.publicItem(rows.getJSONObject(0)).getString("source"))
        assertEquals(1, CompanionMemorySearch.find(listOf(rows.getJSONObject(0)), "明天开会").items.size)
    }
    @Test fun `reject invalid package and sensitive text and bound long excerpts`() {
        assertNull(CompanionAutomaticMemory.fromNotification("invalid", "日程", "明天开会"))
        assertNull(CompanionAutomaticMemory.fromNotification("com.example.calendar", "验证码", "123456"))
        assertNull(CompanionAutomaticMemory.fromNotification("com.example.calendar", "", ""))
        assertTrue(CompanionAutomaticMemory.fromNotification("com.example.calendar", "", "记".repeat(1000))!!.length <= 600)
    }
}

package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionNotificationModelTest {
    private fun row(pkg: String, title: String, text: String, time: Long) = JSONObject().put("package", pkg).put("title", title).put("text", text).put("time", time).put("notificationKeyHash", "private-hash")
    @Test fun disabledAndCorruptStoresFailWithoutClaimingEmpty() {
        var read = false
        val disabled = assertThrows(RuntimeFailure::class.java) { CompanionNotificationModel.execute(false, "{}", 1000, true, true, true, emptySet()) { read = true; JSONArray() } }
        assertEquals("NOTIFICATIONS_MODEL_DISABLED", disabled.code); assertFalse(read)
        val broken = assertThrows(RuntimeFailure::class.java) { CompanionNotificationModel.execute(true, "{}", 1000, true, true, true, emptySet()) { error("bad data") } }
        assertEquals("NOTIFICATIONS_UNAVAILABLE", broken.code)
    }
    @Test fun returnsOnlyBoundedAllowedMatchingRecordsAndStatus() {
        val rows = JSONArray().put(row("com.example.chat", "消息", "请回复", 10)).put(row("com.example.hidden", "消息", "不要返回", 20))
            .put(row("com.example.chat", "验证码", "123456", 30)).put(row("com.example.chat", "消息", "请回复最新", 40))
        val result = JSONObject(CompanionNotificationModel.execute(true, "{\"query\":\"回复\",\"limit\":1}", 1000, false, true, false, setOf("com.example.hidden")) { rows })
        assertFalse(result.getBoolean("captureEnabled")); assertTrue(result.getBoolean("listenerAuthorized")); assertFalse(result.getBoolean("listenerConnected"))
        assertTrue(result.getBoolean("truncated")); assertEquals(1, result.getJSONArray("items").length())
        assertEquals(2, result.getInt("matchedCount")); assertEquals(50, result.getInt("maxStoredRecords"))
        assertTrue(result.getString("notice").contains("不可信"))
        val first = result.getJSONArray("items").getJSONObject(0)
        assertEquals("请回复最新", first.getString("text")); assertFalse(first.has("notificationKeyHash"))
        val all = JSONObject(CompanionNotificationModel.execute(true, "{}", 1000, true, true, true, setOf("com.example.hidden")) { rows })
        assertEquals(2, all.getJSONArray("items").length())
        assertFalse(all.toString().contains("验证码")); assertFalse(all.toString().contains("不要返回")); assertFalse(all.toString().contains("private-hash"))
        val filtered = JSONObject(CompanionNotificationModel.execute(true, "{\"packageName\":\"com.example.other\"}", 1000, true, true, true, emptySet()) { rows })
        assertEquals(0, filtered.getJSONArray("items").length())
        for (bad in listOf("not-json", "{\"limit\":11}", "{\"packageName\":\"../bad\"}", "{\"query\":\"x\\ny\"}")) {
            assertEquals("NOTIFICATIONS_INPUT_INVALID", assertThrows(RuntimeFailure::class.java) { CompanionNotificationModel.execute(true, bad, 1000, true, true, true, emptySet()) { rows } }.code)
        }
    }
    @Test fun truncationPreservesEmojiBoundaries() {
        val title = "a".repeat(159) + "🐋"
        val text = "b".repeat(399) + "🐋"
        val result = JSONObject(CompanionNotificationModel.execute(true, "{}", 1000, false, false, false, emptySet()) {
            JSONArray().put(row("com.example.chat", title, text, 10))
        }).getJSONArray("items").getJSONObject(0)
        assertEquals("a".repeat(159), result.getString("title"))
        assertEquals("b".repeat(399), result.getString("text"))
        assertEquals("🐋", CompanionNotificationRecords.truncate("🐋x", 2))
        assertEquals("", CompanionNotificationRecords.truncate("🐋", 1))
    }
}

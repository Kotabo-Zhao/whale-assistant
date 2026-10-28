package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionReminderBridgeTest {
    private val id = "00000000-1111-2222-3333-444444444444"

    @Test fun createListAndDeleteUseExplicitTimesAndStoredIds() {
        var created: Pair<String, Long>? = null
        var removed: String? = null
        val rows = JSONArray().put(JSONObject().put("id", id).put("text", "取快递"))
        val engine = CompanionReminderBridgeEngine({ rows }, { text, at -> created = text to at }, { removed = it }, { true }, { 1_000L }, { "Asia/Shanghai" })
        val listing = JSONObject(engine.execute("""{"op":"list"}"""))
        assertEquals("Asia/Shanghai", listing.getString("timeZone"))
        assertEquals(1_000L, listing.getLong("nowMillis"))
        val createdResult = JSONObject(engine.execute("""{"op":"create","text":"取快递","atMillis":12001}"""))
        assertTrue(createdResult.getBoolean("scheduled"))
        assertTrue(createdResult.getBoolean("inexact"))
        assertEquals("取快递" to 12_001L, created)
        assertTrue(JSONObject(engine.execute("""{"op":"delete","id":"$id"}""")).getBoolean("removed"))
        assertEquals(id, removed)
        assertFalse(JSONObject(engine.execute("""{"op":"delete","id":"00000000-0000-0000-0000-000000000000"}""")).getBoolean("removed"))
    }

    @Test fun deniesDisabledNotificationsAndInvalidDataBeforeCreating() {
        var calls = 0
        val engine = CompanionReminderBridgeEngine({ JSONArray() }, { _, _ -> calls++ }, {}, { false }, { 1_000L }, { "Asia/Shanghai" })
        val cases = listOf(
            """{"op":"create","text":"会议","atMillis":12001}""" to "REMINDER_NOTIFICATION_DISABLED",
            """{"op":"create","text":"密码 1234","atMillis":12001}""" to "REMINDER_INPUT_INVALID",
            """{"op":"create","text":"会议","atMillis":1001}""" to "REMINDER_INPUT_INVALID",
            """{"op":"create","text":"会议","atMillis":12001.5}""" to "REMINDER_INPUT_INVALID",
            """{"op":"delete","id":"../bad"}""" to "REMINDER_INPUT_INVALID",
        )
        for ((input, code) in cases) {
            try { engine.execute(input); fail("Expected $code") }
            catch (error: RuntimeFailure) { assertEquals(code, error.code) }
        }
        assertEquals(0, calls)
    }
}

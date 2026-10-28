package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

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
        assertEquals("1970-01-01T08:00:01+08:00", listing.getString("nowLocal"))
        val createdResult = JSONObject(engine.execute("""{"op":"create","text":"取快递","atMillis":12001}"""))
        assertTrue(createdResult.getBoolean("scheduled"))
        assertTrue(createdResult.getBoolean("inexact"))
        assertEquals("取快递" to 12_001L, created)
        val localResult = JSONObject(engine.execute("""{"op":"create","text":"会议","localDateTime":"2026-10-30T15:00","timeZone":"Asia/Shanghai"}"""))
        assertEquals(LocalDateTime.of(2026, 10, 30, 15, 0).atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(), localResult.getLong("atMillis"))
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
            """{"op":"create","text":"会议","localDateTime":"2026-02-30T15:00","timeZone":"Asia/Shanghai"}""" to "REMINDER_INPUT_INVALID",
            """{"op":"create","text":"会议","localDateTime":"2026-10-30T15:00","timeZone":"UTC"}""" to "REMINDER_TIMEZONE_CHANGED",
            """{"op":"create","text":"会议","atMillis":12001,"localDateTime":"2026-10-30T15:00"}""" to "REMINDER_INPUT_INVALID",
            """{"op":"delete","id":"../bad"}""" to "REMINDER_INPUT_INVALID",
        )
        for ((input, code) in cases) {
            try { engine.execute(input); fail("Expected $code") }
            catch (error: RuntimeFailure) { assertEquals(code, error.code) }
        }
        assertEquals(0, calls)
    }

    @Test fun rejectsAmbiguousOrMissingDaylightSavingTimes() {
        val engine = CompanionReminderBridgeEngine({ JSONArray() }, { _, _ -> fail("must not schedule") }, {}, { true }, { 0L }, { "America/New_York" })
        for (local in listOf("2026-03-08T02:30", "2026-11-01T01:30")) {
            try { engine.execute(JSONObject().put("op", "create").put("text", "会议").put("localDateTime", local).put("timeZone", "America/New_York").toString()); fail("Expected invalid time") }
            catch (error: RuntimeFailure) { assertEquals("REMINDER_INPUT_INVALID", error.code) }
        }
    }
}

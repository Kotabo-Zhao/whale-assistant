package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompanionRecurringBridgeTest {
    private val id = "00000000-1111-2222-3333-444444444444"
    @Test fun listCreateAndDeleteRespectTimeZoneAndStoredIdentifiers() {
        var created = ""; var removed = ""
        val rows = JSONArray().put(JSONObject().put("id", id).put("text", "喝水"))
        val engine = CompanionRecurringBridgeEngine({ rows }, { t, h, m, d -> created = "$t:$h:$m:$d" }, { removed = it }, { true }, { "Asia/Shanghai" })
        assertEquals("Asia/Shanghai", JSONObject(engine.execute("""{"op":"list"}""")).getString("timeZone"))
        assertTrue(JSONObject(engine.execute("""{"op":"create","text":"喝水","hour":9,"minute":30,"weekday":0,"timeZone":"Asia/Shanghai"}""")).getBoolean("scheduled"))
        assertEquals("喝水:9:30:0", created)
        assertTrue(JSONObject(engine.execute("""{"op":"delete","id":"$id"}""")).getBoolean("removed"))
        assertEquals(id, removed)
    }
    @Test fun invalidInputChangedZoneOrDisabledNotificationsNeverCreate() {
        var count = 0
        val engine = CompanionRecurringBridgeEngine({ JSONArray() }, { _, _, _, _ -> count++ }, {}, { false }, { "Asia/Shanghai" })
        val invalid = listOf(
            """{"op":"create","text":"喝水","hour":24,"minute":0,"weekday":0,"timeZone":"Asia/Shanghai"}""",
            """{"op":"create","text":"密码 123456","hour":9,"minute":0,"weekday":0,"timeZone":"Asia/Shanghai"}""",
            """{"op":"create","text":"喝水","hour":9,"minute":0,"weekday":0,"timeZone":"UTC"}""",
            """{"op":"create","text":"喝水","hour":9,"minute":0,"weekday":0,"timeZone":"Asia/Shanghai"}""",
        )
        val codes = listOf("REMINDER_INPUT_INVALID", "REMINDER_INPUT_INVALID", "REMINDER_TIMEZONE_CHANGED", "REMINDER_NOTIFICATION_DISABLED")
        invalid.zip(codes).forEach { (request, code) ->
            try { engine.execute(request); fail("expected failure") } catch (e: RuntimeFailure) { assertEquals(code, e.code) }
        }
        assertEquals(0, count)
    }
}

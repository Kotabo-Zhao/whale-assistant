package io.deepseekharness.mobile.companion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CompanionBriefRemindersTest {
    @Test fun reportsTodayOnlyWithTruthfulStatesAndBoundedResults() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-30T04:00:00Z").toEpochMilli()
        val rows = JSONArray()
        fun row(at: String, state: String) = JSONObject().put("at", Instant.parse(at).toEpochMilli()).put("text", "提醒").put("state", state)
        rows.put(row("2026-10-30T02:00:00Z", "delivered"))
        rows.put(row("2026-10-30T03:00:00Z", "scheduled"))
        rows.put(row("2026-10-30T05:00:00Z", "scheduled"))
        rows.put(row("2026-10-30T16:00:00Z", "scheduled"))
        val result = CompanionBriefReminders.read(rows, false, now, zone)
        assertEquals(3, result.items.size)
        assertEquals("已交给系统", result.items[0].state)
        assertEquals("计划时间已过，状态待核对", result.items[1].state)
        assertEquals("已安排", result.items[2].state)
        repeat(10) { rows.put(row("2026-10-30T05:00:00Z", "scheduled")) }
        assertEquals(10, CompanionBriefReminders.read(rows, false, now, zone).items.size)
        assertTrue(CompanionBriefReminders.read(rows, false, now, zone).truncated)
        assertThrows(Exception::class.java) { CompanionBriefReminders.read(JSONArray().put(JSONObject()), false, now, zone) }
    }
    @Test fun handlesDstDayAndPendingRecurringState() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-03-08T12:00:00Z").toEpochMilli()
        val rows = JSONArray().put(JSONObject().put("nextAt", Instant.parse("2026-03-09T03:30:00Z").toEpochMilli())
            .put("text", "例行").put("state", "scheduled").put("schedulePending", true))
            .put(JSONObject().put("nextAt", Instant.parse("2026-03-09T04:00:00Z").toEpochMilli()).put("text", "明天").put("state", "scheduled"))
        val result = CompanionBriefReminders.read(rows, true, now, zone)
        assertEquals(1, result.items.size)
        assertEquals("等待恢复安排", result.items.single().state)
    }
    @Test fun includesOnlyTheSelectedNumberOfLocalCalendarDays() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = Instant.parse("2026-10-30T04:00:00Z").toEpochMilli()
        fun row(at: String) = JSONObject().put("at", Instant.parse(at).toEpochMilli()).put("text", "提醒").put("state", "scheduled")
        val rows = JSONArray().put(row("2026-10-30T05:00:00Z")).put(row("2026-10-31T05:00:00Z"))
            .put(row("2026-11-01T05:00:00Z")).put(row("2026-11-02T05:00:00Z"))
        val result = CompanionBriefReminders.read(rows, false, now, zone, 3)
        assertEquals(3, result.items.size)
        assertThrows(IllegalArgumentException::class.java) { CompanionBriefReminders.read(rows, false, now, zone, 2) }
    }
}

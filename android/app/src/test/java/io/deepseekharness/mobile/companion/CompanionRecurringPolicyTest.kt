package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class CompanionRecurringPolicyTest {
    @Test fun dailyAndWeeklyUseLocalCalendarRatherThanFixedDurations() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 10, 29, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val daily = CompanionRecurringPolicy.next(now, 9, 30, 0, zone)
        assertEquals("2026-10-30T09:30+08:00[Asia/Shanghai]", java.time.Instant.ofEpochMilli(daily).atZone(zone).toString())
        val weekly = CompanionRecurringPolicy.next(now, 11, 0, 4, zone)
        assertEquals("2026-10-29T11:00+08:00[Asia/Shanghai]", java.time.Instant.ofEpochMilli(weekly).atZone(zone).toString())
        val following = CompanionRecurringPolicy.next(weekly, 11, 0, 4, zone)
        assertEquals("2026-11-05T11:00+08:00[Asia/Shanghai]", java.time.Instant.ofEpochMilli(following).atZone(zone).toString())
    }
    @Test fun anOccurrenceCanBeClaimedOnlyOnceAndNeverBeforeItsTime() {
        val zone = ZoneId.of("Asia/Shanghai")
        val due = ZonedDateTime.of(2026, 10, 29, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val row = org.json.JSONObject().put("state", "scheduled").put("nextAt", due).put("hour", 10).put("minute", 0).put("weekday", 0)
        assertFalse(CompanionRecurringPolicy.claim(row, due - 1, zone))
        assertTrue(CompanionRecurringPolicy.claim(row, due, zone))
        assertEquals("pending", row.getString("last"))
        assertTrue(row.getLong("nextAt") > due)
        assertFalse(CompanionRecurringPolicy.claim(row, due, zone))
        row.put("state", "failed").put("nextAt", due)
        assertFalse(CompanionRecurringPolicy.claim(row, due, zone))
    }
    @Test fun interruptedSchedulingIsRecoveredWithoutReplayingAnOverdueOccurrence() {
        val zone = ZoneId.of("Asia/Shanghai")
        val now = ZonedDateTime.of(2026, 10, 29, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val row = org.json.JSONObject().put("state", "scheduled").put("nextAt", now - 1).put("hour", 9).put("minute", 0)
            .put("weekday", 0).put("schedulePending", true).put("last", "pending")
        assertTrue(CompanionRecurringPolicy.prepareRecovery(row, now, zone))
        assertEquals("missed", row.getString("last"))
        assertEquals("2026-10-30T09:00+08:00[Asia/Shanghai]", java.time.Instant.ofEpochMilli(row.getLong("nextAt")).atZone(zone).toString())
        assertTrue(row.getBoolean("schedulePending"))
        row.put("schedulePending", false)
        assertFalse(CompanionRecurringPolicy.prepareRecovery(row, now, zone))
        row.put("state", "failed").put("schedulePending", true)
        assertFalse(CompanionRecurringPolicy.prepareRecovery(row, now, zone))
        val legacy = org.json.JSONObject().put("state", "scheduled").put("nextAt", now + 100)
        assertFalse(CompanionRecurringPolicy.prepareRecovery(legacy, now, zone))
    }
    @Test fun daylightSavingGapMovesToFirstValidTimeAndInvalidRulesFail() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val next = CompanionRecurringPolicy.next(now, 2, 30, 0, zone)
        assertEquals("2026-03-08T03:30-04:00[America/New_York]", java.time.Instant.ofEpochMilli(next).atZone(zone).toString())
        assertThrows(Exception::class.java) { CompanionRecurringPolicy.next(now, 24, 0, 0, zone) }
        assertThrows(Exception::class.java) { CompanionRecurringPolicy.next(now, 12, 0, 8, zone) }
    }
}

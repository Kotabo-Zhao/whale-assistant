package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CompanionCalendarTest {
    @Test fun dayBoundsUseLocalMidnightAndRespectDaylightSaving() {
        val zone = TimeZone.getTimeZone("America/New_York")
        fun noon(month: Int, day: Int): Long = Calendar.getInstance(zone).apply {
            clear(); set(2026, month, day, 12, 0, 0)
        }.timeInMillis
        val spring = CompanionCalendar.dayRange(noon(Calendar.MARCH, 8), zone)
        val autumn = CompanionCalendar.dayRange(noon(Calendar.NOVEMBER, 1), zone)
        assertEquals(23L * 60 * 60 * 1000, spring.second - spring.first)
        assertEquals(25L * 60 * 60 * 1000, autumn.second - autumn.first)
        val china = CompanionCalendar.dayRange(noon(Calendar.JUNE, 1), TimeZone.getTimeZone("Asia/Shanghai"))
        assertEquals(24L * 60 * 60 * 1000, china.second - china.first)
    }
    @Test fun distinguishesPermissionUnavailableEmptyAndTruncated() {
        var called = false
        val denied = CompanionCalendar.snapshot(false) { called = true; emptyList() }
        assertEquals(CompanionCalendar.State.NO_PERMISSION, denied.state); assertFalse(called)
        assertEquals(CompanionCalendar.State.NO_PERMISSION, CompanionCalendar.snapshot(true) { throw SecurityException() }.state)
        assertEquals(CompanionCalendar.State.UNAVAILABLE, CompanionCalendar.snapshot(true) { null }.state)
        assertEquals(CompanionCalendar.State.UNAVAILABLE, CompanionCalendar.snapshot(true) { error("provider failure") }.state)
        val empty = CompanionCalendar.snapshot(true) { emptyList() }
        assertEquals(CompanionCalendar.State.OK, empty.state); assertTrue(empty.events.isEmpty()); assertFalse(empty.truncated)
        val events = (0..20).map { CompanionCalendar.Event("事件 $it", it.toLong(), it.toLong() + 1, false) }
        val snapshot = CompanionCalendar.snapshot(true) { events }
        assertEquals(20, snapshot.events.size); assertTrue(snapshot.truncated)
        assertEquals("事件 0", snapshot.events.first().title)
    }
    @Test fun modelRequiresSeparateConsentAndPermissionAndReportsSnapshot() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        var called = false
        val denied = org.junit.Assert.assertThrows(io.deepseekharness.mobile.runtime.RuntimeFailure::class.java) {
            CompanionCalendarModel.execute(false, "", 1000L, zone) { called = true; CompanionCalendar.Snapshot(CompanionCalendar.State.OK) }
        }
        assertEquals("CALENDAR_MODEL_DISABLED", denied.code); assertFalse(called)
        val noPermission = org.junit.Assert.assertThrows(io.deepseekharness.mobile.runtime.RuntimeFailure::class.java) {
            CompanionCalendarModel.execute(true, "", 1000L, zone) { CompanionCalendar.Snapshot(CompanionCalendar.State.NO_PERMISSION) }
        }
        assertEquals("CALENDAR_PERMISSION_REQUIRED", noPermission.code)
        val unavailable = org.junit.Assert.assertThrows(io.deepseekharness.mobile.runtime.RuntimeFailure::class.java) {
            CompanionCalendarModel.execute(true, "", 1000L, zone) { CompanionCalendar.Snapshot(CompanionCalendar.State.UNAVAILABLE) }
        }
        assertEquals("CALENDAR_UNAVAILABLE", unavailable.code)
        val result = org.json.JSONObject(CompanionCalendarModel.execute(true, "", 1000L, zone) {
            CompanionCalendar.Snapshot(CompanionCalendar.State.OK, listOf(CompanionCalendar.Event("会议", 1000L, 2000L, false)), true)
        })
        assertEquals("Asia/Shanghai", result.getString("timeZone")); assertTrue(result.getBoolean("truncated"))
        assertEquals("会议", result.getJSONArray("events").getJSONObject(0).getString("title"))
        assertThrows(io.deepseekharness.mobile.runtime.RuntimeFailure::class.java) { CompanionCalendarModel.execute(true, "unexpected", 1000L, zone) { error("must not read") } }
    }
}

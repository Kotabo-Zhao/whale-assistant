package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone

class CompanionDailyBriefTest {
    @Test fun promptsOnceOnlyInsideTheLocalTimeWindow() {
        val now = GregorianCalendar(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            clear(); set(2026, Calendar.OCTOBER, 30, 8, 0)
        }
        val key = CompanionDailyBriefPolicy.dayKey(now)
        fun due(enabled: Boolean = true, quiet: Boolean = false, ready: Boolean = true, minute: Int = 480, last: String? = null) =
            CompanionDailyBriefPolicy.due(enabled, quiet, ready, now, minute, last)
        assertTrue(due())
        assertFalse(due(enabled = false)); assertFalse(due(quiet = true)); assertFalse(due(ready = false)); assertFalse(due(last = key))
        assertFalse(due(minute = -1)); assertFalse(due(minute = 1440))
        now.set(Calendar.MINUTE, 59); now.set(Calendar.HOUR_OF_DAY, 9); assertTrue(due())
        now.set(Calendar.MINUTE, 0); now.set(Calendar.HOUR_OF_DAY, 10); assertFalse(due())
        now.set(Calendar.HOUR_OF_DAY, 7); assertFalse(due())
        now.add(Calendar.DAY_OF_YEAR, 1); now.set(Calendar.HOUR_OF_DAY, 8); assertTrue(due(last = key))
    }
    @Test fun lateWindowDoesNotSpillIntoNextDay() {
        val now = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply { clear(); set(2026, Calendar.JANUARY, 1, 23, 59) }
        assertTrue(CompanionDailyBriefPolicy.due(true, false, true, now, 23 * 60 + 30, null))
        now.add(Calendar.MINUTE, 1)
        assertFalse(CompanionDailyBriefPolicy.due(true, false, true, now, 23 * 60 + 30, null))
    }
}

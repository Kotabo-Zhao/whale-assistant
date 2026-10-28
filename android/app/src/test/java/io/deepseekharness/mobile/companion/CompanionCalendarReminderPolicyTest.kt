package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionCalendarReminderPolicyTest {
    @Test fun proposesReviewableFutureTimesWithoutGuessingAllDayEvents() {
        val now = 1_800_030L
        val events = listOf(
            CompanionCalendar.Event("会议", 4_200_000L, 4_800_000L, false),
            CompanionCalendar.Event("即将开始", 1_920_000L, 2_000_000L, false),
            CompanionCalendar.Event("太近", 1_860_000L, 2_000_000L, false),
            CompanionCalendar.Event("全天", 4_200_000L, 5_000_000L, true),
            CompanionCalendar.Event("过去", 1_000_000L, 2_000_000L, false),
        )
        val items = CompanionCalendarReminderPolicy.candidates(events, now)
        assertEquals(2, items.size)
        assertEquals(3_600_000L, items[0].suggestedAt)
        assertEquals(1_920_000L, items[1].suggestedAt)
        assertThrows(IllegalArgumentException::class.java) { CompanionCalendarReminderPolicy.candidates(events, 0) }
    }
}

package io.deepseekharness.mobile.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CompanionProactiveChatPolicyTest {
    private fun at(year: Int, month: Int, day: Int): Calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        clear(); set(year, month, day, 10, 0, 0)
    }
    @Test fun dailyAndWeeklyKeysThrottleOnlyTheirOwnLocalPeriod() {
        val monday = at(2026, Calendar.NOVEMBER, 2)
        val tuesday = at(2026, Calendar.NOVEMBER, 3)
        val nextMonday = at(2026, Calendar.NOVEMBER, 9)
        assertFalse(CompanionProactiveChatPolicy.due("daily", CompanionProactiveChatPolicy.key("daily", monday), monday))
        assertTrue(CompanionProactiveChatPolicy.due("daily", CompanionProactiveChatPolicy.key("daily", monday), tuesday))
        assertFalse(CompanionProactiveChatPolicy.due("weekly", CompanionProactiveChatPolicy.key("weekly", monday), tuesday))
        assertTrue(CompanionProactiveChatPolicy.due("weekly", CompanionProactiveChatPolicy.key("weekly", monday), nextMonday))
    }
    @Test fun legacyDailyKeyDoesNotRepeatOnTheUpgradeDay() {
        val now = at(2026, Calendar.NOVEMBER, 2)
        val migrated = CompanionProactiveChatPolicy.migrateLegacyDailyKey("2026-11-02", now)
        assertFalse(CompanionProactiveChatPolicy.due("daily", migrated, now))
    }
}

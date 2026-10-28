package io.deepseekharness.mobile.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionHintPolicyTest {
    @Test fun dueRemindersBypassRoutineCooldownButDoNotReplaceAVisibleReminder() {
        assertTrue(CompanionHintPolicy.mayShow(reminder = true, reminderVisible = false, now = 1_001, last = 1_000))
        assertFalse(CompanionHintPolicy.mayShow(reminder = false, reminderVisible = false, now = 1_001, last = 1_000))
        assertFalse(CompanionHintPolicy.mayShow(reminder = true, reminderVisible = true, now = 200_000, last = 1_000))
        assertFalse(CompanionHintPolicy.mayShow(reminder = false, reminderVisible = true, now = 200_000, last = 1_000))
        assertTrue(CompanionHintPolicy.mayShow(reminder = false, reminderVisible = false, now = 121_000, last = 1_000))
        assertFalse(CompanionHintPolicy.mayShow(reminder = false, reminderVisible = false, now = 999, last = 1_000))
    }
}

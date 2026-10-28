package io.deepseekharness.mobile.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionReminderPolicyTest {
    @Test fun futureAndMissedBoundariesAreDistinct() {
        assertFalse(CompanionReminderPolicy.isFuture(11_000, 1_000))
        assertTrue(CompanionReminderPolicy.isFuture(11_001, 1_000))
        assertTrue(CompanionReminderPolicy.isMissed(1_000, 1_000))
        assertFalse(CompanionReminderPolicy.isMissed(1_001, 1_000))
    }
}

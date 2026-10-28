package io.deepseekharness.mobile.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionQuietHoursTest {
    @Test fun overnightIncludesStartAndExcludesEnd() {
        assertFalse(CompanionQuietHours.contains(true, 22 * 60, 8 * 60, 21 * 60 + 59))
        assertTrue(CompanionQuietHours.contains(true, 22 * 60, 8 * 60, 22 * 60))
        assertTrue(CompanionQuietHours.contains(true, 22 * 60, 8 * 60, 0))
        assertTrue(CompanionQuietHours.contains(true, 22 * 60, 8 * 60, 8 * 60 - 1))
        assertFalse(CompanionQuietHours.contains(true, 22 * 60, 8 * 60, 8 * 60))
    }
    @Test fun daytimeAndDisabledAndInvalidSettings() {
        assertTrue(CompanionQuietHours.contains(true, 10, 20, 10))
        assertFalse(CompanionQuietHours.contains(true, 10, 20, 20))
        assertFalse(CompanionQuietHours.contains(false, 10, 20, 15))
        assertFalse(CompanionQuietHours.contains(true, 10, 10, 10))
        assertFalse(CompanionQuietHours.contains(true, -1, 20, 15))
        assertFalse(CompanionQuietHours.contains(true, 10, 20, 1440))
    }
}

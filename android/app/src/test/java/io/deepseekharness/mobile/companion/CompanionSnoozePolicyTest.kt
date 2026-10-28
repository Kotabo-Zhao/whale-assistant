package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionSnoozePolicyTest {
    @Test fun onlyOffersBoundedPositiveDelaysAndRejectsOverflow() {
        assertEquals(601_000L, CompanionSnoozePolicy.target(1000L, 10))
        assertEquals(1_801_000L, CompanionSnoozePolicy.target(1000L, 30))
        assertEquals(3_601_000L, CompanionSnoozePolicy.target(1000L, 60))
        assertThrows(IllegalArgumentException::class.java) { CompanionSnoozePolicy.target(1000L, 15) }
        assertThrows(IllegalArgumentException::class.java) { CompanionSnoozePolicy.target(0L, 10) }
        assertThrows(ArithmeticException::class.java) { CompanionSnoozePolicy.target(Long.MAX_VALUE, 10) }
    }
}

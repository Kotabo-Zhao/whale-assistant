package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionReminderHealthTest {
    @Test fun reportsEachBlockedLayerWithoutClaimingReadiness() {
        assertEquals(CompanionReminderHealth.State.PERMISSION_DENIED, CompanionReminderHealth.evaluate(false, false, false))
        assertEquals(CompanionReminderHealth.State.APP_DISABLED, CompanionReminderHealth.evaluate(true, false, true))
        assertEquals(CompanionReminderHealth.State.CHANNEL_DISABLED, CompanionReminderHealth.evaluate(true, true, false))
        assertEquals(CompanionReminderHealth.State.READY, CompanionReminderHealth.evaluate(true, true, true))
    }
}

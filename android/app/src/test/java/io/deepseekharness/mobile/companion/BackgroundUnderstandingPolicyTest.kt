package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class BackgroundUnderstandingPolicyTest {
    @Test fun everyAuthorizationMustRemainValid() {
        assertTrue(BackgroundUnderstandingPolicy.allowed(true, true, false, true))
        assertFalse(BackgroundUnderstandingPolicy.allowed(false, true, false, true))
        assertFalse(BackgroundUnderstandingPolicy.allowed(true, false, false, true))
        assertFalse(BackgroundUnderstandingPolicy.allowed(true, true, true, true))
        assertFalse(BackgroundUnderstandingPolicy.allowed(true, true, false, false))
    }
    @Test fun requestBudgetFailsClosedAtLimit() {
        assertTrue(BackgroundUnderstandingPolicy.canRequest(19, 20))
        assertFalse(BackgroundUnderstandingPolicy.canRequest(20, 20))
        assertFalse(BackgroundUnderstandingPolicy.canRequest(-1, 20))
        assertFalse(BackgroundUnderstandingPolicy.canRequest(0, 0))
    }
}

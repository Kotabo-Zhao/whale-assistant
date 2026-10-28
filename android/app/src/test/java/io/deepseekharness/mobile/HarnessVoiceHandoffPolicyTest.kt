package io.deepseekharness.mobile

import org.junit.Assert.*
import org.junit.Test

class HarnessVoiceHandoffPolicyTest {
    @Test fun onlySameAuthorizedVisiblePageAcceptsFreshHandoff() {
        assertTrue(HarnessVoiceHandoffPolicy.canDeliver(true, true, true, true, 0))
        assertTrue(HarnessVoiceHandoffPolicy.canDeliver(true, true, true, true, 120_000))
        assertFalse(HarnessVoiceHandoffPolicy.canDeliver(false, true, true, true, 1))
        assertFalse(HarnessVoiceHandoffPolicy.canDeliver(true, false, true, true, 1))
        assertFalse(HarnessVoiceHandoffPolicy.canDeliver(true, true, false, true, 1))
        assertFalse(HarnessVoiceHandoffPolicy.canDeliver(true, true, true, false, 1))
        assertFalse(HarnessVoiceHandoffPolicy.canDeliver(true, true, true, true, -1))
        assertFalse(HarnessVoiceHandoffPolicy.canDeliver(true, true, true, true, 120_001))
    }
}

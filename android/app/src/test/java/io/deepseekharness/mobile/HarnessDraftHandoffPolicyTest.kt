package io.deepseekharness.mobile

import org.junit.Assert.*
import org.junit.Test

class HarnessDraftHandoffPolicyTest {
    @Test fun onlySameAuthorizedVisiblePageAcceptsFreshHandoff() {
        assertTrue(HarnessDraftHandoffPolicy.canDeliver(true, true, true, true, 0))
        assertTrue(HarnessDraftHandoffPolicy.canDeliver(true, true, true, true, 120_000))
        assertFalse(HarnessDraftHandoffPolicy.canDeliver(false, true, true, true, 1))
        assertFalse(HarnessDraftHandoffPolicy.canDeliver(true, false, true, true, 1))
        assertFalse(HarnessDraftHandoffPolicy.canDeliver(true, true, false, true, 1))
        assertFalse(HarnessDraftHandoffPolicy.canDeliver(true, true, true, false, 1))
        assertFalse(HarnessDraftHandoffPolicy.canDeliver(true, true, true, true, -1))
        assertFalse(HarnessDraftHandoffPolicy.canDeliver(true, true, true, true, 120_001))
    }
}

package io.deepseekharness.mobile

import org.junit.Assert.*
import org.junit.Test

class HarnessSpeechReadRequestTest {
    @Test fun responseAndTimeoutCannotBothClaimOneRead() {
        val responseFirst = HarnessSpeechReadRequest(1)
        assertTrue(responseFirst.claim(1))
        assertFalse(responseFirst.claim(1))
        val timeoutFirst = HarnessSpeechReadRequest(2)
        assertTrue(timeoutFirst.claim(2))
        assertFalse(timeoutFirst.claim(2))
    }

    @Test fun staleCallbacksCannotAffectANewerAttempt() {
        val old = HarnessSpeechReadRequest(4)
        val current = HarnessSpeechReadRequest(5)
        assertFalse(old.claim(5))
        assertTrue(current.claim(5))
        assertFalse(old.claim(5))
    }
}

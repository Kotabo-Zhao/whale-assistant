package io.deepseekharness.mobile.companion

import io.deepseekharness.mobile.runtime.RuntimeFailure
import org.junit.Assert.*
import org.junit.Test

class ScreenTextRepetitionTest {
    @Test fun sameScreenCannotReceiveUnlimitedCopiesButChangedFieldAndExpiredAttemptCanProceed() {
        val guard=ScreenTextRepetition()
        guard.observed("text-hash","screen-a",0)
        guard.check("text-hash","screen-a",100)
        guard.observed("text-hash","screen-a",200)
        assertEquals("SCREEN_INPUT_REPEATED",(runCatching{guard.check("text-hash","screen-a",300)}.exceptionOrNull() as RuntimeFailure).code)
        guard.check("text-hash","different-field",400)
        guard.check("different-text","screen-a",400)
        guard.check("text-hash","screen-a",120201)
        guard.observed("text-hash","different-field",500)
        guard.check("text-hash","different-field",600)
    }
}

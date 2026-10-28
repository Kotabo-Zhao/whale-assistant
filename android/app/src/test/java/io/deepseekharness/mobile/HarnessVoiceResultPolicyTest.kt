package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessVoiceResultPolicyTest {
    @Test fun changedPageOffersCopyAndLongResultCanBeEdited() {
        assertEquals(HarnessVoiceResultPolicy.Decision.REVIEW, HarnessVoiceResultPolicy.decide(true, "打开日历", true))
        assertEquals(HarnessVoiceResultPolicy.Decision.COPY, HarnessVoiceResultPolicy.decide(true, "打开日历", false))
        assertEquals(HarnessVoiceResultPolicy.Decision.REVIEW, HarnessVoiceResultPolicy.decide(true, "a".repeat(2001), true))
        assertEquals(false, HarnessVoiceResultPolicy.canInsert("a".repeat(2001)))
        assertEquals(true, HarnessVoiceResultPolicy.canInsert("a".repeat(2000)))
        assertEquals(true, HarnessVoiceResultPolicy.canCopy("a".repeat(10000)))
        assertEquals(false, HarnessVoiceResultPolicy.canCopy("a".repeat(10001)))
        assertEquals(HarnessVoiceResultPolicy.Decision.INVALID, HarnessVoiceResultPolicy.decide(true, "a".repeat(10001), false))
    }
    @Test fun cancelledOrEmptyRecognitionNeverInserts() {
        assertEquals(HarnessVoiceResultPolicy.Decision.CANCELLED, HarnessVoiceResultPolicy.decide(false, "打开日历", true))
        assertEquals(HarnessVoiceResultPolicy.Decision.INVALID, HarnessVoiceResultPolicy.decide(true, "  ", true))
    }
}

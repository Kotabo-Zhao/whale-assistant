package io.deepseekharness.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class HarnessVoiceResultPolicyTest {
    @Test fun changedPageOrLongResultOffersCopyInsteadOfDroppingOrInserting() {
        assertEquals(HarnessVoiceResultPolicy.Decision.REVIEW, HarnessVoiceResultPolicy.decide(true, "打开日历", true))
        assertEquals(HarnessVoiceResultPolicy.Decision.COPY, HarnessVoiceResultPolicy.decide(true, "打开日历", false))
        assertEquals(HarnessVoiceResultPolicy.Decision.COPY, HarnessVoiceResultPolicy.decide(true, "a".repeat(2001), true))
        assertEquals(HarnessVoiceResultPolicy.Decision.INVALID, HarnessVoiceResultPolicy.decide(true, "a".repeat(10001), false))
    }
    @Test fun cancelledOrEmptyRecognitionNeverInserts() {
        assertEquals(HarnessVoiceResultPolicy.Decision.CANCELLED, HarnessVoiceResultPolicy.decide(false, "打开日历", true))
        assertEquals(HarnessVoiceResultPolicy.Decision.INVALID, HarnessVoiceResultPolicy.decide(true, "  ", true))
    }
}

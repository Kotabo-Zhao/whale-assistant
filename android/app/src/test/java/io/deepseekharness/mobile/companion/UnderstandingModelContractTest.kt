package io.deepseekharness.mobile.companion

import org.junit.Assert.assertThrows
import org.junit.Test

class UnderstandingModelContractTest {
    @Test fun acceptsEvidenceBoundClaimsAndEmptyResult() {
        UnderstandingModelContract.validate("""{"version":1,"claims":[{"text":"快递到站","evidence_ids":["event-1"]}]}""", "event-1")
        UnderstandingModelContract.validate("""{"version":1,"claims":[]}""", "event-1")
    }
    @Test fun rejectsInventedMissingOrMultipleEvidence() {
        for (ids in listOf("[]", "[\"other\"]", "[\"event-1\",\"other\"]")) {
            assertThrows(Exception::class.java) { UnderstandingModelContract.validate("""{"version":1,"claims":[{"text":"快递到站","evidence_ids":$ids}]}""", "event-1") }
        }
    }
    @Test fun rejectsBadVersionAndOversizedText() {
        assertThrows(Exception::class.java) { UnderstandingModelContract.validate("""{"version":2,"claims":[]}""", "x") }
        val text = "甲".repeat(601)
        assertThrows(Exception::class.java) { UnderstandingModelContract.validate("""{"version":1,"claims":[{"text":"$text","evidence_ids":["x"]}]}""", "x") }
    }
    @Test fun itemMustCiteTheActualEvent() {
        UnderstandingModelContract.validate("""{"version":1,"claims":[],"items":[{"title":"领取快递","evidence_ids":["e"]}]}""", "e")
        assertThrows(Exception::class.java) { UnderstandingModelContract.validate("""{"version":1,"claims":[],"items":[{"title":"领取快递","evidence_ids":["other"]}]}""", "e") }
    }
}

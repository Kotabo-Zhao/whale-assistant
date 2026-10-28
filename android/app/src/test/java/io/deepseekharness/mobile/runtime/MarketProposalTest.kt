package io.deepseekharness.mobile.runtime

import org.junit.Assert.*
import org.junit.Test

class MarketProposalTest {
    @Test fun onlyValidBoundedPackageNamesAndValidRecordsAreAccepted() {
        assertTrue(MarketProposalPolicy.validName("@example/plugin"))
        for (bad in listOf("../bad", "https://bad.example/x", "-switch", "UPPER", "a".repeat(215))) assertFalse(MarketProposalPolicy.validName(bad))
        val item = MarketProposal("00000000-0000-0000-0000-000000000001", "@example/plugin", 123L)
        assertEquals(item, MarketProposalPolicy.parse(MarketProposalPolicy.encode(item)))
        val update = MarketProposal("00000000-0000-0000-0000-000000000002", "example", 123L, "update", "1.2.3")
        assertEquals(update, MarketProposalPolicy.parse(MarketProposalPolicy.encode(update)))
        assertEquals("install", MarketProposalPolicy.parse("{\"id\":\"00000000-0000-0000-0000-000000000001\",\"packageName\":\"example\",\"createdAt\":1}").kind)
        assertFalse(MarketProposalPolicy.validVersion("1.0.0;bad"))
        assertThrows(Exception::class.java) { MarketProposalPolicy.parse("{broken") }
        assertThrows(Exception::class.java) { MarketProposalPolicy.parse("{\"id\":\"bad\",\"packageName\":\"example\",\"createdAt\":1}") }
    }
}

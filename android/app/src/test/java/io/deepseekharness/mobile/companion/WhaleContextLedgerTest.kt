package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleContextLedgerTest {

    private fun buildHistory(count: Int): List<Pair<String, String>> =
        (0 until count).map { i ->
            if (i % 2 == 0) "user" to "第${i + 1}条用户消息，请查看 report_${i + 1}.md"
            else "assistant" to "第${i + 1}条回复，已写入 output_${i + 1}.json 并完成校验。"
        }

    @Test
    fun `incremental folding preserves recent 6 verbatim messages and folds 8-message blocks`() {
        val history16 = buildHistory(16)
        val plan = WhaleContextLedger.planIncrementalWindow("s-main", history16, emptyList())
        // 16 - 6 = 10 foldable -> 1 complete block of 8 ([0..7]), remaining 8 ([8..15]) stay active
        assertEquals(1, plan.foldedCards.size)
        assertEquals(1, plan.newlyFolded.size)
        assertEquals(0, plan.foldedCards[0].startIndex)
        assertEquals(7, plan.foldedCards[0].endIndex)
        assertEquals(8, plan.activeMessages.size)
        assertTrue(plan.foldedCards[0].artifactPaths.any { it.contains("report_1.md") })
    }

    @Test
    fun `already folded blocks stay immutable when new turns arrive for KV cache safety`() {
        val history16 = buildHistory(16)
        val plan1 = WhaleContextLedger.planIncrementalWindow("s-main", history16, emptyList())
        val firstCard = plan1.foldedCards[0]

        val history24 = buildHistory(24)
        val plan2 = WhaleContextLedger.planIncrementalWindow("s-main", history24, plan1.foldedCards)
        assertEquals(2, plan2.foldedCards.size)
        assertEquals(1, plan2.newlyFolded.size)
        // First block is byte-for-byte identical (never rewritten in place)
        assertEquals(firstCard, plan2.foldedCards[0])
        assertEquals(8, plan2.foldedCards[1].startIndex)
        assertEquals(15, plan2.foldedCards[1].endIndex)
    }

    @Test
    fun `keyword search locates relevant archived block and TurnExpandGuard enforces max 1 expand per turn`() {
        val history = buildHistory(16)
        val plan = WhaleContextLedger.planIncrementalWindow("s-main", history, emptyList())
        val hits = WhaleContextLedger.searchInRecords(plan.foldedCards, "output_2.json")
        assertEquals(1, hits.size)
        assertEquals(plan.foldedCards[0].rangeId, hits[0].rangeId)

        val guard = WhaleContextLedger.TurnExpandGuard()
        assertTrue(guard.tryAcquire())
        assertFalse(guard.tryAcquire())
        assertEquals(2, guard.used())
    }
}

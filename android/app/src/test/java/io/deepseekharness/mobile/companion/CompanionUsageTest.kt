package io.deepseekharness.mobile.companion

import org.junit.Assert.*
import org.junit.Test

class CompanionUsageTest {
    @Test fun rankCombinesValidUseExcludesExistingAndDoesNotInferAttributes() {
        val rows = listOf(CompanionUsage.Candidate("com.example.app", 15 * 60000L), CompanionUsage.Candidate("com.example.app", 10 * 60000L),
            CompanionUsage.Candidate("com.example.existing", 100 * 60000L), CompanionUsage.Candidate("com.example.self", 100 * 60000L),
            CompanionUsage.Candidate("bad/path", 100 * 60000L), CompanionUsage.Candidate("com.example.small", 1000))
        val ranked = CompanionUsage.rank(rows, setOf("com.example.existing"), "com.example.self")
        assertEquals(1, ranked.size); assertEquals("com.example.app", ranked.single().packageName); assertEquals(25 * 60000L, ranked.single().foregroundMs)
        assertTrue(CompanionUsage.rank(emptyList(), emptySet(), "self").isEmpty())
        assertTrue(CompanionUsage.rank(rows, (0..5).map { "com.example.slot$it" }.toSet(), "self").isEmpty())
    }
    @Test fun summaryAggregatesMultipleIntervalsAndSaturatesOverflow() {
        val rows = listOf(CompanionUsage.Candidate("com.example.alpha", 100), CompanionUsage.Candidate("com.example.alpha", 50),
            CompanionUsage.Candidate("com.example.beta", Long.MAX_VALUE), CompanionUsage.Candidate("com.example.beta", 5),
            CompanionUsage.Candidate("invalid/path", 500), CompanionUsage.Candidate("com.example.negative", -1))
        val result = CompanionUsage.summary(rows)
        assertEquals(listOf("com.example.beta", "com.example.alpha"), result.map { it.packageName })
        assertEquals(Long.MAX_VALUE, result[0].foregroundMs)
        assertEquals(150L, result[1].foregroundMs)
        assertEquals(10, CompanionUsage.summary((1..11).map { CompanionUsage.Candidate("com.example.app$it", it.toLong()) }).size)
    }
    @Test fun hintsAreBoundedAndClockChangesDoNotBlockForever() {
        val now = 1000000000L
        assertTrue(CompanionUsageHintPolicy.due(now, 0, 0))
        assertFalse(CompanionUsageHintPolicy.due(now, now - 1, 0))
        assertFalse(CompanionUsageHintPolicy.due(now, 0, now - 1))
        assertTrue(CompanionUsageHintPolicy.due(now, now - CompanionUsageHintPolicy.CHECK_INTERVAL_MS, now - CompanionUsageHintPolicy.PROMPT_INTERVAL_MS))
        assertTrue(CompanionUsageHintPolicy.due(now, now + 1, now + 1))
    }
}

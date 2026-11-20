package io.deepseekharness.mobile.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhaleTokenMeterTest {

    @Test
    fun compactLabelFormatsSmallThousandAndMillionTokensWithCost() {
        val small = WhaleTokenMeter.Snapshot(
            day = "2026-11-20",
            promptTokens = 320,
            cacheHitTokens = 100,
            completionTokens = 180,
            costMicroYuan = 2_000,
            dailyBudgetTokens = 200_000,
        )
        assertEquals(500L, small.totalTokens)
        assertEquals("✨ 500", small.compactLabel())
        assertTrue(small.progress in 0.002f..0.003f)

        val mid = WhaleTokenMeter.Snapshot(
            day = "2026-11-20",
            promptTokens = 10_000,
            cacheHitTokens = 4_000,
            completionTokens = 2_500,
            costMicroYuan = 34_000,
            dailyBudgetTokens = 200_000,
        )
        assertEquals(12_500L, mid.totalTokens)
        assertEquals("✨ 12.5k · ¥0.03", mid.compactLabel())

        val overBudget = WhaleTokenMeter.Snapshot(
            day = "2026-11-20",
            promptTokens = 1_200_000,
            cacheHitTokens = 0,
            completionTokens = 300_000,
            costMicroYuan = 4_800_000,
            dailyBudgetTokens = 200_000,
        )
        assertEquals(1.0f, overBudget.progress, 0.0001f)
        assertEquals("✨ 1.5M · ¥4.80", overBudget.compactLabel())
    }
}
